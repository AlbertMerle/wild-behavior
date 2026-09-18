package com.wildbehavior.ai;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.Animal;
import com.wildbehavior.mixin.SquidAccessor;
import net.minecraft.world.entity.animal.squid.Squid;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;
import com.wildbehavior.config.AnimalBehaviorConfig;

public final class AnimalHerdSystem {
	/** Leader wander/graze/pathfinding cadence (~200 ms at 20 TPS). Followers still tick every AI step. */
	static final int HERD_LEADER_UPDATE_INTERVAL_TICKS = 4;

	private static final double TICKS_PER_SECOND = 20.0D;
	private static final double GRAZE_ARRIVAL_DISTANCE_SQR = 2.25D;
	private static final double WANDER_ARRIVAL_DISTANCE_SQR = 4.0D;
	/** Base follower clearance; scaled up for wide mobs via {@link #getLeaderClearanceRadius}. */
	private static final double LEADER_CLEARANCE_RADIUS = 1.5D;
	/** Base spacing between herd members; scaled up for wide mobs via {@link #getMemberSpacing}. */
	private static final double HERD_MEMBER_SPACING = 1.25D;
	private static final long HERD_STUCK_REPATH_TICKS = 40L;
	/** How often to retry alternate herd paths while still stuck. */
	private static final long HERD_STUCK_REPATH_INTERVAL_TICKS = 40L;
	/** After this many consecutive failed herd paths, yield locomotion to vanilla. */
	private static final int HERD_FAILED_MOVE_YIELD_THRESHOLD = 3;
	/** How long herd stops driving movement after failed-path / stuck yield (~8s). */
	private static final long HERD_LOCOMOTION_YIELD_TICKS = 160L;
	/** Stuck on same block this long → yield (3 stuck-repath windows). */
	private static final long HERD_STUCK_YIELD_TICKS =
		HERD_STUCK_REPATH_TICKS + 2L * HERD_STUCK_REPATH_INTERVAL_TICKS;

	private static final Map<UUID, AnimalHerdState> HERD_STATES = new ConcurrentHashMap<>();
	private static final Map<UUID, HerdGroupData> HERD_GROUPS = new ConcurrentHashMap<>();
	/** Cached pack size for confidence checks — avoids BFS every tick. */
	private static final Map<UUID, ConfidenceSnapshot> CONFIDENCE_SNAPSHOTS = new ConcurrentHashMap<>();

	private record ConfidenceSnapshot(int memberCount, boolean belowThreshold, long refreshedAtTick) {
	}

	private AnimalHerdSystem() {
	}

	public static void onEntityLoad(LivingEntity entity) {
		if (MobBehaviorConfig.isModEnabled(entity.getType())) {
			HERD_STATES.put(entity.getUUID(), new AnimalHerdState(entity.getUUID()));
		}
	}

	public static void onEntityUnload(LivingEntity entity) {
		AnimalHerdState state = HERD_STATES.remove(entity.getUUID());
		if (state != null && entity.getUUID().equals(state.getHerdLeaderId())) {
			HERD_GROUPS.remove(entity.getUUID());
		}

		CONFIDENCE_SNAPSHOTS.remove(entity.getUUID());
	}

	/**
	 * Cached pack-confidence check (refreshed on herd scan interval and on aggro trigger).
	 * Avoids {@link #collectHerdMembers} BFS on every flee/aggro gate tick.
	 */
	public static boolean isBelowConfidenceThreshold(LivingEntity entity) {
		ConfidenceSnapshot snapshot = CONFIDENCE_SNAPSHOTS.get(entity.getUUID());
		if (snapshot != null) {
			return snapshot.belowThreshold();
		}

		return refreshConfidenceSnapshot(entity, entity.level().getGameTime()).belowThreshold();
	}

	/** Refreshes the herd-member snapshot; called on herd scan ticks and entity load. */
	public static void refreshConfidenceSnapshot(LivingEntity entity) {
		refreshConfidenceSnapshot(entity, entity.level().getGameTime());
	}

	/**
	 * Live confidence check when Aggressive is about to start on a player.
	 * Updates the cache and returns {@code true} when the mob may aggro.
	 */
	public static boolean confirmConfidenceForAggro(LivingEntity entity) {
		return !refreshConfidenceSnapshot(entity, entity.level().getGameTime()).belowThreshold();
	}

	/**
	 * Periodic refresh for aggressive mobs that do not tick herd logic (no Herd flag).
	 * Uses the same interval as herd leader scans.
	 */
	public static void maybeRefreshConfidenceSnapshot(LivingEntity entity) {
		if (!MobBehaviorConfig.isAggroBehavior(entity.getType())) {
			return;
		}

		long now = entity.level().getGameTime();
		ConfidenceSnapshot snapshot = CONFIDENCE_SNAPSHOTS.get(entity.getUUID());
		if (snapshot != null && now - snapshot.refreshedAtTick() < AnimalBehaviorConfig.herdScanIntervalTicks()) {
			return;
		}

		refreshConfidenceSnapshot(entity, now);
	}

	private static ConfidenceSnapshot refreshConfidenceSnapshot(LivingEntity entity, long gameTime) {
		int memberCount = collectHerdMembers(entity).size();
		boolean below = memberCount < MobBehaviorConfig.getConfidence(entity.getType());
		ConfidenceSnapshot snapshot = new ConfidenceSnapshot(memberCount, below, gameTime);
		CONFIDENCE_SNAPSHOTS.put(entity.getUUID(), snapshot);
		return snapshot;
	}

	public static boolean isLoneWolf(LivingEntity entity) {
		return entity.getType() == EntityTypes.WOLF && isBelowConfidenceThreshold(entity);
	}

	public static boolean isInMultiMemberHerd(LivingEntity entity) {
		return countHerdMembers(entity) >= 2;
	}

	public static int countHerdMembers(LivingEntity entity) {
		return collectHerdMembers(entity).size();
	}

	private static boolean isBlockedByHigherPriorityBehaviors(PathfinderMob mob) {
		if (AnimalFleeSystem.isFleeing(mob)) {
			return true;
		}

		if (mob instanceof Animal animal && AnimalFoodLureSystem.suppressesWildBehaviorOverlay(animal)) {
			return true;
		}

		if (AnimalWaterFleeEscapeSystem.blocksHerd(mob)) {
			return true;
		}

		if (AnimalAggroSystem.isAggroing(mob)) {
			return true;
		}

		if (AnimalStayOnLandSystem.isEscapingWater(mob)) {
			return true;
		}

		if (AnimalFightBackSystem.isFightBacking(mob)) {
			return true;
		}

		return AnimalDefenderSystem.isDefending(mob);
	}

	public static void tickPathfinderMob(PathfinderMob mob) {
		if (mob.level().isClientSide() || !MobBehaviorConfig.isModEnabled(mob.getType())) {
			return;
		}

		AnimalHerdState state = getState(mob);
		if (state.tickOverrideCheckCooldown()) {
			boolean blocked = isBlockedByHigherPriorityBehaviors(mob);
			state.setCachedBlockedByOverride(blocked);
			if (blocked) {
				UUID leaderId = state.getHerdLeaderId();
				if (leaderId != null && leaderId.equals(mob.getUUID())) {
					HerdGroupData leaderGroup = HERD_GROUPS.get(leaderId);
					if (leaderGroup != null) {
						AnimalHerdStagnationEscapeHelper.cancelForGroup(leaderGroup);
					}
				}
			}
		}

		if (state.isCachedBlockedByOverride()) {
			return;
		}

		if (!canApplyHerd(mob)) {
			// Do not stop navigation here. Tamed / in-love / fence-adjacent mobs must keep
			// vanilla pathfinding for breeding, eating, and tempt; stopping every tick freezes them.
			return;
		}
		if (state.tickScanCooldown()) {
			state.setHerdLeaderId(findHerdLeader(mob));
			refreshConfidenceSnapshot(mob);
		}

		UUID leaderId = state.getHerdLeaderId();
		if (leaderId == null) {
			leaderId = mob.getUUID();
			state.setHerdLeaderId(leaderId);
		}

		boolean isLeader = mob.getUUID().equals(leaderId);
		HerdGroupData group = getOrCreateGroup(leaderId, isLeader, mob);

		if (maybeApplyIndividualCliffEscape(mob, state, group)) {
			return;
		}

		if (isLeader) {
			long gameTime = mob.level().getGameTime();
			if (group.getPhase() == HerdPhase.WANDERING
				&& (gameTime + mob.getId()) % AnimalCliffAvoidanceHelper.SCAN_INTERVAL_TICKS == 0L
				&& AnimalCliffAvoidanceHelper.maybeRedirectFromCliff(mob, group)) {
				state.setRepathCooldown(0);
			}

			if (!EntityPlayerTrust.bypassesHerd(mob)) {
				AnimalHerdStagnationEscapeHelper.tickChunkCheck(mob, group, gameTime);
				if (group.isStagnationEscapeActive()) {
					AnimalHerdStagnationEscapeHelper.tickActiveEscape(mob, state, group);
					return;
				}
			}

			if (isHerdLeaderUpdateTick(mob)) {
				tickLeader(mob, state, group);
			}
		} else {
			LivingEntity leader = findEntity(mob.level(), leaderId);
			if (leader == null || !leader.isAlive()) {
				state.forceScan();
				if (isHerdLeaderUpdateTick(mob)) {
					tickLeader(mob, state, group);
				}
				return;
			}

			tickFollower(mob, state, group, leader);
		}
	}

	public static void tickBat(Bat bat) {
		if (bat.level().isClientSide() || !MobBehaviorConfig.isModEnabled(bat.getType())) {
			return;
		}

		AnimalHerdState state = getState(bat);
		if (state.tickOverrideCheckCooldown()) {
			state.setCachedBlockedByOverride(AnimalFleeSystem.isFleeing(bat));
		}

		if (state.isCachedBlockedByOverride()) {
			return;
		}

		if (!canApplyHerd(bat)) {
			return;
		}

		if (!MobBehaviorConfig.isEnabled(bat.getType(), MobBehavior.HERD)) {
			return;
		}

		if (state.tickScanCooldown()) {
			state.setHerdLeaderId(findHerdLeader(bat));
			refreshConfidenceSnapshot(bat);
		}

		UUID leaderId = state.getHerdLeaderId();
		if (leaderId == null) {
			leaderId = bat.getUUID();
			state.setHerdLeaderId(leaderId);
		}

		boolean isLeader = bat.getUUID().equals(leaderId);
		HerdGroupData group = getOrCreateGroup(leaderId, isLeader, bat);

		if (isLeader) {
			if (isHerdLeaderUpdateTick(bat)) {
				tickBatLeader(bat, group);
			}
		} else {
			LivingEntity leader = findEntity(bat.level(), leaderId);
			if (leader == null || !leader.isAlive()) {
				state.forceScan();
				if (isHerdLeaderUpdateTick(bat)) {
					tickBatLeader(bat, group);
				}
				return;
			}

			tickBatFollower(bat, group, leader);
		}
	}

	/** Staggered so nearby herd leaders do not all repath on the same tick. */
	static boolean isHerdLeaderUpdateTick(LivingEntity entity) {
		return (entity.level().getGameTime() + (entity.getId() & 3)) % HERD_LEADER_UPDATE_INTERVAL_TICKS == 0;
	}

	@Nullable
	public static BlockPos getBatHerdTarget(Bat bat) {
		if (bat.level().isClientSide() || AnimalFleeSystem.isFleeing(bat) || !canApplyHerd(bat)) {
			return null;
		}

		AnimalHerdState state = HERD_STATES.get(bat.getUUID());
		if (state == null) {
			return null;
		}

		HerdGroupData group = HERD_GROUPS.get(state.getHerdLeaderId());
		if (group == null || group.getPhase() != HerdPhase.WANDERING) {
			return null;
		}

		UUID leaderId = state.getHerdLeaderId();
		LivingEntity leader = leaderId.equals(bat.getUUID()) ? bat : findEntity(bat.level(), leaderId);
		if (leader == null) {
			return null;
		}

		Vec3 direction = group.getWanderDirection();
		double distance = leader instanceof Bat ? 12.0D : 16.0D;
		return BlockPos.containing(leader.getX() + direction.x * distance, leader.getY() + bat.getRandom().nextInt(4) - 1.0D, leader.getZ() + direction.z * distance);
	}

	public static boolean isBatGrazing(Bat bat) {
		AnimalHerdState state = HERD_STATES.get(bat.getUUID());
		if (state == null) {
			return false;
		}

		HerdGroupData group = HERD_GROUPS.get(state.getHerdLeaderId());
		return group != null && group.getPhase() == HerdPhase.GRAZING;
	}

	/** True when this mob's herd schedule restricts eating to grazing rest only. */
	public static boolean usesHerdEatingSchedule(PathfinderMob mob) {
		return MobBehaviorConfig.isEnabled(mob.getType(), MobBehavior.HERD) && canApplyHerd(mob);
	}

	/** True during the herd grazing rest period when vanilla grass eating is allowed. */
	public static boolean isGrazingRest(PathfinderMob mob) {
		if (!usesHerdEatingSchedule(mob)) {
			return true;
		}

		AnimalHerdState state = HERD_STATES.get(mob.getUUID());
		if (state == null) {
			return false;
		}

		HerdGroupData group = HERD_GROUPS.get(state.getHerdLeaderId());
		if (group == null) {
			return false;
		}

		return group.getPhase() == HerdPhase.GRAZING && state.getGrazeActivity() == GrazeActivity.RESTING;
	}

	/**
	 * True when herd overlay is driving movement (wander, graze-spread, cliff/stagnation escape).
	 * Idle graze rest, idle pause, and locomotion yield do not count.
	 */
	public static boolean isActivelyHerdMoving(PathfinderMob mob) {
		if (!MobBehaviorConfig.isEnabled(mob.getType(), MobBehavior.HERD) || !canApplyHerd(mob)) {
			return false;
		}

		if (isBlockedByHigherPriorityBehaviors(mob)) {
			return false;
		}

		AnimalHerdState state = HERD_STATES.get(mob.getUUID());
		if (state == null) {
			return false;
		}

		long gameTime = mob.level().getGameTime();
		if (state.isLocomotionYielded(gameTime)) {
			return false;
		}

		// Idle pause intentionally leaves nav alone — not "trying to move".
		if (state.isInIdlePause(gameTime)) {
			return false;
		}

		HerdGroupData group = HERD_GROUPS.get(state.getHerdLeaderId());
		if (group == null) {
			return true;
		}

		if (group.isStagnationEscapeActive()) {
			return true;
		}

		if (group.getPhase() == HerdPhase.WANDERING) {
			return true;
		}

		return group.getPhase() == HerdPhase.GRAZING
			&& state.getGrazeActivity() != GrazeActivity.RESTING;
	}

	private static void tickLeader(PathfinderMob mob, AnimalHerdState state, HerdGroupData group) {
		long gameTime = mob.level().getGameTime();

		if (group.getPhase() == HerdPhase.WANDERING) {
			syncWanderPhase(mob, state, group, true);

			if (gameTime >= group.getPhaseEndTick()) {
				group.beginGrazing(gameTime, randomDuration(mob, grazeMinTicks(), grazeMaxTicks()), mob.position());
				state.setRepathCooldown(0);
				return;
			}

			// Yielding / idle: leave navigation alone so vanilla stroll/breed can run.
			if (state.isLocomotionYielded(gameTime) || state.tickIdlePause(mob, gameTime)) {
				return;
			}

			if (maybeYieldFromStuck(mob, state, gameTime)) {
				return;
			}

			if (tickHerdStuckRecovery(mob, state)) {
				Vec3 recovery = pickStuckRecoveryWanderTarget(mob, mob.position(), group.getWanderDirection());
				group.setWanderTarget(recovery);
				if (recovery == null) {
					state.noteFailedHerdMove(gameTime, HERD_FAILED_MOVE_YIELD_THRESHOLD, HERD_LOCOMOTION_YIELD_TICKS);
					return;
				}

				tryApplyHerdMovement(mob, state, recovery);
				state.setRepathCooldown(repathIntervalTicks());
				return;
			}

			state.tickRepathCooldown();
			if (state.getRepathCooldown() > 0 && !mob.getNavigation().isDone()) {
				return;
			}

			Vec3 target = group.getWanderTarget();
			boolean needsNewTarget = state.getRepathCooldown() <= 0 || target == null;
			if (needsNewTarget) {
				target = pickWanderTarget(mob, mob.position(), group.getWanderDirection());
				group.setWanderTarget(target);
				if (target == null) {
					state.noteFailedHerdMove(gameTime, HERD_FAILED_MOVE_YIELD_THRESHOLD, HERD_LOCOMOTION_YIELD_TICKS);
					return;
				}

				tryApplyHerdMovement(mob, state, target);
				state.setRepathCooldown(repathIntervalTicks());
				return;
			}

			if (!mob.getNavigation().isDone()) {
				return;
			}

			if (mob.position().distanceToSqr(target) <= getWanderArrivalDistanceSqr(mob)) {
				state.clearFailedHerdMoves();
				return;
			}

			// Path finished early — wide mobs sidestep/repath around terrain; smaller mobs push through entities.
			if (MobBlockStepHelper.isWideMob(mob)) {
				Vec3 alternate = pickWideMobObstacleAvoidTarget(mob, mob.position(), target, group.getWanderDirection());
				tryApplyHerdMovement(mob, state, alternate != null ? alternate : target);
				state.setRepathCooldown(repathIntervalTicks());
			} else if (!tryApplyHerdMovement(mob, state, target)) {
				// Confined / blocked: do not applyDirectMovement thrash into fences.
				return;
			} else {
				state.setRepathCooldown(repathIntervalTicks());
			}
			return;
		}

		tickGrazing(mob, state, group);
		if (gameTime >= group.getPhaseEndTick()) {
			boolean keepDirection = mob.getRandom().nextBoolean();
			group.beginWandering(gameTime, randomDuration(mob, wanderMinTicks(), wanderMaxTicks()), keepDirection);
			state.beginWanderPhase(mob, group.getPhaseStartTick(), true);
			state.setRepathCooldown(0);
		}
	}

	private static void tickFollower(PathfinderMob mob, AnimalHerdState state, HerdGroupData group, LivingEntity leader) {
		long gameTime = mob.level().getGameTime();

		if (group.getPhase() == HerdPhase.GRAZING) {
			tickGrazing(mob, state, group);
			return;
		}

		syncWanderPhase(mob, state, group, false);
		if (!state.hasJoinedWander()) {
			// Wait without stopping nav — vanilla may wander until the join tick.
			if (gameTime < state.getWanderJoinTick()) {
				return;
			}

			state.setJoinedWander(true);
		}

		if (state.isLocomotionYielded(gameTime) || state.tickIdlePause(mob, gameTime)) {
			return;
		}

		if (maybeYieldFromStuck(mob, state, gameTime)) {
			return;
		}

		if (tickHerdStuckRecovery(mob, state)) {
			Vec3 recovery = pickStuckRecoveryFollowerTarget(mob, leader, group.getWanderDirection(), state);
			tryApplyHerdMovement(mob, state, recovery);
			state.setRepathCooldown(repathIntervalTicks());
			return;
		}

		state.tickRepathCooldown();
		if (state.getRepathCooldown() > 0 && !mob.getNavigation().isDone()) {
			return;
		}

		if (mob.distanceToSqr(leader) < getLeaderClearanceRadiusSqr(mob)) {
			Vec3 escapeTarget = pickLeaderClearanceTarget(mob, leader);
			if (escapeTarget == null || !MobNavigationHelper.canPathTo(mob, escapeTarget.x, escapeTarget.y, escapeTarget.z)) {
				state.noteFailedHerdMove(gameTime, HERD_FAILED_MOVE_YIELD_THRESHOLD, HERD_LOCOMOTION_YIELD_TICKS);
				return;
			}

			tryApplyHerdClearance(mob, state, escapeTarget);
			state.setRepathCooldown(repathIntervalTicks());
			return;
		}

		LivingEntity crowdedMember = findTooCloseHerdMember(mob, state.getHerdLeaderId());
		if (crowdedMember != null) {
			Vec3 escapeTarget = pickMemberClearanceTarget(mob, crowdedMember);
			if (escapeTarget == null || !MobNavigationHelper.canPathTo(mob, escapeTarget.x, escapeTarget.y, escapeTarget.z)) {
				state.noteFailedHerdMove(gameTime, HERD_FAILED_MOVE_YIELD_THRESHOLD, HERD_LOCOMOTION_YIELD_TICKS);
				return;
			}

			tryApplyHerdClearance(mob, state, escapeTarget);
			state.setRepathCooldown(repathIntervalTicks());
			return;
		}

		Vec3 target = pickFollowerTarget(mob, leader, group.getWanderDirection(), state);
		tryApplyHerdMovement(mob, state, target);
		state.setRepathCooldown(repathIntervalTicks());
	}

	private static void tickBatLeader(Bat bat, HerdGroupData group) {
		long gameTime = bat.level().getGameTime();

		if (group.getPhase() == HerdPhase.GRAZING) {
			bat.setResting(true);
			if (gameTime >= group.getPhaseEndTick()) {
				bat.setResting(false);
				group.beginWandering(gameTime, randomDuration(bat, wanderMinTicks(), wanderMaxTicks()), bat.getRandom().nextBoolean());
			}

			return;
		}

		bat.setResting(false);
		if (gameTime >= group.getPhaseEndTick()) {
			group.beginGrazing(gameTime, randomDuration(bat, grazeMinTicks(), grazeMaxTicks()), bat.position());
			bat.setResting(true);
		}
	}

	private static void tickBatFollower(Bat bat, HerdGroupData group, LivingEntity leader) {
		if (group.getPhase() == HerdPhase.GRAZING) {
			bat.setResting(true);
			return;
		}

		bat.setResting(false);
	}

	private static HerdGroupData getOrCreateGroup(UUID leaderId, boolean isLeader, LivingEntity entity) {
		return HERD_GROUPS.computeIfAbsent(leaderId, ignored -> {
			HerdGroupData data = new HerdGroupData();
			long gameTime = entity.level().getGameTime();
			data.beginWandering(gameTime, randomDuration(entity, wanderMinTicks(), wanderMaxTicks()), false);
			data.setNextChunkCheckTick(gameTime + Math.abs(leaderId.hashCode()) % AnimalHerdStagnationEscapeHelper.CHUNK_CHECK_INTERVAL_TICKS);
			return data;
		});
	}

	public static List<UUID> getHerdMemberIds(LivingEntity entity) {
		return collectHerdMembers(entity);
	}

	private static List<UUID> collectHerdMembers(LivingEntity entity) {
		long gameTime = entity.level().getGameTime();
		int scanInterval = AnimalBehaviorConfig.herdScanIntervalTicks();
		AnimalHerdState herdState = HERD_STATES.get(entity.getUUID());
		UUID knownLeaderId = herdState != null ? herdState.getHerdLeaderId() : null;
		if (knownLeaderId != null) {
			HerdGroupData group = HERD_GROUPS.get(knownLeaderId);
			if (group != null && group.isMemberCacheFresh(gameTime, scanInterval)) {
				return group.getCachedMembers();
			}

			if (knownLeaderId.equals(entity.getUUID())) {
				List<UUID> members = performHerdMemberBfs(entity);
				if (group != null) {
					group.setCachedMembers(members, gameTime);
				}

				return members;
			}

			if (group != null && !group.getCachedMembers().isEmpty()) {
				return group.getCachedMembers();
			}
		}

		List<UUID> members = performHerdMemberBfs(entity);
		UUID leaderId = entity.getUUID();
		for (UUID memberId : members) {
			if (memberId.compareTo(leaderId) < 0) {
				leaderId = memberId;
			}
		}

		HerdGroupData group = HERD_GROUPS.get(leaderId);
		if (group != null) {
			group.setCachedMembers(members, gameTime);
		}

		return members;
	}

	private static List<UUID> performHerdMemberBfs(LivingEntity entity) {
		double linkRange = AnimalBehaviorConfig.herdLinkRange();
		double linkRangeSqr = linkRange * linkRange;
		EntityTypeMatcher matcher = new EntityTypeMatcher(entity.getType());
		Queue<LivingEntity> queue = new ArrayDeque<>();
		Set<UUID> visited = new HashSet<>();
		List<UUID> members = new ArrayList<>();

		queue.add(entity);
		visited.add(entity.getUUID());

		while (!queue.isEmpty()) {
			LivingEntity current = queue.poll();
			members.add(current.getUUID());

			for (LivingEntity other : current.level().getEntitiesOfClass(LivingEntity.class, current.getBoundingBox().inflate(linkRange), matcher)) {
				if (other == current || !other.isAlive() || !canHerdWith(other)) {
					continue;
				}

				if (current.distanceToSqr(other) > linkRangeSqr) {
					continue;
				}

				if (visited.add(other.getUUID())) {
					queue.add(other);
				}
			}
		}

		return members;
	}

	private static UUID findHerdLeader(LivingEntity entity) {
		List<UUID> members = collectHerdMembers(entity);
		UUID leader = entity.getUUID();
		for (UUID memberId : members) {
			if (memberId.compareTo(leader) < 0) {
				leader = memberId;
			}
		}

		return leader;
	}

	private static double grazeSpreadRadius() {
		return AnimalBehaviorConfig.herdGrazeSpreadRadius();
	}

	private static double grazeMaxDistanceSqr() {
		double radius = grazeSpreadRadius() + 4.0D;
		return radius * radius;
	}

	private static int wanderMinTicks() {
		return AnimalBehaviorConfig.herdWanderMinTicks();
	}

	private static int wanderMaxTicks() {
		return AnimalBehaviorConfig.herdWanderMaxTicks();
	}

	private static int grazeMinTicks() {
		return AnimalBehaviorConfig.herdGrazeMinTicks();
	}

	private static int grazeMaxTicks() {
		return AnimalBehaviorConfig.herdGrazeMaxTicks();
	}

	private static int repathIntervalTicks() {
		return AnimalBehaviorConfig.herdRepathIntervalTicks();
	}

	private static int grazeMoveDistance() {
		return AnimalBehaviorConfig.herdGrazeMoveDistance();
	}

	@Nullable
	private static Vec3 pickWanderTarget(PathfinderMob mob, Vec3 origin, Vec3 direction) {
		// Short hops only — pasture-scale 16–31 targets never path inside pens and freeze herds.
		int travelDistance = 6 + mob.getRandom().nextInt(5);
		Vec3 dir = direction.lengthSqr() < 1.0E-4D
			? new Vec3(1.0D, 0.0D, 0.0D)
			: new Vec3(direction.x, 0.0D, direction.z).normalize();

		for (int attempt = 0; attempt < 8; attempt++) {
			Vec3 attemptDir = attempt == 0
				? dir
				: rotateAroundY(dir, (attempt % 2 == 0 ? 1.0D : -1.0D) * Math.PI / 4.0D * ((attempt + 1) / 2));
			Vec3 preferred = origin.add(attemptDir.scale(travelDistance));
			Vec3 candidate = mob.isInWater()
				? DefaultRandomPos.getPosTowards(mob, travelDistance, 4, preferred, Math.PI / 3.0D)
				: LandRandomPos.getPosTowards(mob, travelDistance, 7, preferred);
			if (candidate == null) {
				candidate = mob.isInWater()
					? DefaultRandomPos.getPos(mob, travelDistance, 4)
					: LandRandomPos.getPos(mob, travelDistance, 7);
			}

			if (candidate != null && MobNavigationHelper.canPathTo(mob, candidate.x, candidate.y, candidate.z)) {
				return candidate;
			}
		}

		Vec3 nearby = mob.isInWater()
			? DefaultRandomPos.getPos(mob, 4, 4)
			: LandRandomPos.getPos(mob, 4, 7);
		if (nearby != null && MobNavigationHelper.canPathTo(mob, nearby.x, nearby.y, nearby.z)) {
			return nearby;
		}

		return null;
	}

	@Nullable
	private static Vec3 pickPathfindableNearby(PathfinderMob mob, Vec3 origin, Vec3 preferred, Vec3 direction) {
		if (MobNavigationHelper.canPathTo(mob, preferred.x, preferred.y, preferred.z)) {
			return preferred;
		}

		for (int attempt = 0; attempt < 6; attempt++) {
			double angleOffset = (attempt % 2 == 0 ? 1.0D : -1.0D) * Math.PI / 4.0D * (attempt / 2 + 1);
			Vec3 offsetDir = rotateAroundY(direction, angleOffset);
			int distance = 8 + mob.getRandom().nextInt(12);
			Vec3 alternate = origin.add(offsetDir.scale(distance));
			Vec3 adjusted = mob.isInWater()
				? DefaultRandomPos.getPosTowards(mob, distance, 4, alternate, Math.PI / 4.0D)
				: DefaultRandomPos.getPosTowards(mob, distance, 7, alternate, Math.PI / 4.0D);
			if (adjusted != null && MobNavigationHelper.canPathTo(mob, adjusted.x, adjusted.y, adjusted.z)) {
				return adjusted;
			}
		}

		return null;
	}

	private static void tickGrazing(PathfinderMob mob, AnimalHerdState state, HerdGroupData group) {
		long gameTime = mob.level().getGameTime();
		syncGrazingPhase(mob, state, group);

		if (state.isLocomotionYielded(gameTime)) {
			return;
		}

		switch (state.getGrazeActivity()) {
			case SPREADING -> tickGrazingMovement(mob, state, () -> state.beginGrazeRest(mob, gameTime));
			case RESTING -> {
				// Do not stop navigation every tick — that freezes vanilla stroll/breed during rest.
				if (gameTime >= state.getGrazeActivityEndTick()) {
					Vec3 moveTarget = pickLocalGrazeMoveTarget(mob, group);
					state.beginGrazeMove(moveTarget);
					tryApplyHerdMovement(mob, state, moveTarget);
				}
			}
			case MOVING -> tickGrazingMovement(mob, state, () -> state.beginGrazeRest(mob, gameTime));
		}
	}

	private static void tickGrazingMovement(PathfinderMob mob, AnimalHerdState state, Runnable onArrival) {
		long gameTime = mob.level().getGameTime();
		if (state.tickIdlePause(mob, gameTime)) {
			return;
		}

		Vec3 target = state.getGrazeTarget();
		if (target == null) {
			onArrival.run();
			return;
		}

		if (maybeYieldFromStuck(mob, state, gameTime)) {
			return;
		}

		if (tickHerdStuckRecovery(mob, state)) {
			Vec3 recovery = pickStuckRecoveryNearby(mob, mob.position(), target);
			state.setGrazeTarget(recovery);
			tryApplyHerdMovement(mob, state, recovery);
			return;
		}

		if (mob.getNavigation().isDone() && mob.position().distanceToSqr(target) <= GRAZE_ARRIVAL_DISTANCE_SQR) {
			state.clearFailedHerdMoves();
			onArrival.run();
			return;
		}

		if (!mob.getNavigation().isInProgress()) {
			if (!tryApplyHerdMovement(mob, state, target)
				&& mob.position().distanceToSqr(target) <= GRAZE_ARRIVAL_DISTANCE_SQR * 4.0D) {
				// Close enough / unpathable — treat as arrived so rest can start.
				state.clearFailedHerdMoves();
				onArrival.run();
			}
		}
	}

	private static void syncGrazingPhase(PathfinderMob mob, AnimalHerdState state, HerdGroupData group) {
		if (state.getGrazePhaseStartTick() == group.getPhaseStartTick()) {
			return;
		}

		state.beginGrazingCycle(mob, group.getPhaseStartTick(), pickSpreadTarget(mob, group));
		tryApplyHerdMovement(mob, state, state.getGrazeTarget());
	}

	private static void syncWanderPhase(PathfinderMob mob, AnimalHerdState state, HerdGroupData group, boolean isLeader) {
		if (state.getWanderPhaseStartTick() == group.getPhaseStartTick()) {
			return;
		}

		state.beginWanderPhase(mob, group.getPhaseStartTick(), isLeader);
	}

	private static Vec3 pickSpreadTarget(PathfinderMob mob, HerdGroupData group) {
		Vec3 center = group.getGrazingCenter();
		int hash = mob.getUUID().hashCode();
		double baseAngle = (hash & 0xFFFF) / 65536.0D * Math.PI * 2.0D;
		double angle = baseAngle + (mob.getRandom().nextDouble() - 0.5D) * Math.PI / 2.0D;
		double minDistance = 2.0D + getMemberSpacing(mob);
		double maxDistance = Math.max(minDistance + 1.0D, grazeSpreadRadius());
		double distance = minDistance + mob.getRandom().nextDouble() * (maxDistance - minDistance);
		Vec3 preferredPoint = center.add(Math.cos(angle) * distance, 0.0D, Math.sin(angle) * distance);
		return findValidGrazingPosition(mob, preferredPoint, 8);
	}

	private static Vec3 pickLocalGrazeMoveTarget(PathfinderMob mob, HerdGroupData group) {
		Vec3 center = group.getGrazingCenter();
		double angle = mob.getRandom().nextDouble() * Math.PI * 2.0D;
		Vec3 direction = new Vec3(Math.cos(angle), 0.0D, Math.sin(angle));
		Vec3 preferredPoint = mob.position().add(direction.scale(grazeMoveDistance()));

		if (preferredPoint.distanceToSqr(center) > grazeMaxDistanceSqr()) {
			Vec3 towardCenter = center.subtract(mob.position());
			if (towardCenter.lengthSqr() > 1.0E-4D) {
				preferredPoint = mob.position().add(towardCenter.normalize().scale(grazeMoveDistance()));
			}
		}

		return findValidGrazingPosition(mob, preferredPoint, grazeMoveDistance() + 2);
	}

	private static Vec3 findValidGrazingPosition(PathfinderMob mob, Vec3 preferredPoint, int searchRadius) {
		Vec3 candidate = pickVanillaGrazingPosition(mob, preferredPoint, searchRadius);
		if (candidate == null) {
			candidate = preferredPoint;
		}

		if (MobBlockStepHelper.isWideMob(mob)) {
			Vec3 pathable = pickPathfindableNearby(mob, mob.position(), candidate, candidate.subtract(mob.position()));
			if (pathable != null) {
				return pathable;
			}
		}

		return candidate;
	}

	/**
	 * Vanilla-style grazing movement: pathfind toward a preferred point while favoring higher ground.
	 */
	@Nullable
	private static Vec3 pickVanillaGrazingPosition(PathfinderMob mob, Vec3 preferredPoint, int searchRadius) {
		if (mob.isInWater()) {
			return DefaultRandomPos.getPosTowards(mob, searchRadius, 4, preferredPoint, Math.PI / 3.0D);
		}

		for (int attempt = 0; attempt < 6; attempt++) {
			Vec3 candidate = attempt == 0
				? LandRandomPos.getPosTowards(mob, searchRadius, 7, preferredPoint)
				: LandRandomPos.getPos(mob, searchRadius, 7);
			if (candidate == null) {
				continue;
			}

			if (!AnimalCliffAvoidanceHelper.isUnsafeStandPosition(mob.level(), candidate)) {
				return candidate;
			}
		}

		return LandRandomPos.getPosTowards(mob, searchRadius, 7, preferredPoint);
	}

	private static boolean maybeApplyIndividualCliffEscape(PathfinderMob mob, AnimalHerdState state, HerdGroupData group) {
		long gameTime = mob.level().getGameTime();
		if ((gameTime + mob.getId()) % AnimalCliffAvoidanceHelper.IMMEDIATE_SCAN_INTERVAL_TICKS != 0L) {
			return false;
		}

		if (group.getPhase() == HerdPhase.GRAZING && state.getGrazeActivity() == GrazeActivity.RESTING) {
			return false;
		}

		Vec3 escapeTarget = AnimalCliffAvoidanceHelper.pickImmediateCliffEscapeTarget(mob);
		if (escapeTarget == null) {
			return false;
		}

		if (!MobNavigationHelper.canPathTo(mob, escapeTarget.x, escapeTarget.y, escapeTarget.z)) {
			return false;
		}

		tryApplyHerdMovement(mob, state, escapeTarget);
		state.setRepathCooldown(repathIntervalTicks());
		return true;
	}

	private static Vec3 pickFollowerTarget(PathfinderMob mob, LivingEntity leader, Vec3 direction, AnimalHerdState state) {
		Vec3 behind = new Vec3(-direction.x, 0.0D, -direction.z);
		if (behind.lengthSqr() < 1.0E-4D) {
			behind = new Vec3(0.0D, 0.0D, 1.0D);
		} else {
			behind = behind.normalize();
		}

		double slotDistance = getFollowerSlotDistance(mob, state);
		Vec3 offset = rotateAroundY(behind, state.getFollowerSlotAngle()).scale(slotDistance);
		Vec3 target = leader.position().add(offset);
		target = enforceLeaderClearance(target, leader, mob);
		return enforceMemberClearance(mob, target, state.getHerdLeaderId());
	}

	private static Vec3 enforceLeaderClearance(Vec3 target, LivingEntity leader, PathfinderMob follower) {
		Vec3 leaderPos = leader.position();
		Vec3 horizontalOffset = new Vec3(target.x - leaderPos.x, 0.0D, target.z - leaderPos.z);
		double clearanceSqr = getLeaderClearanceRadiusSqr(follower);
		if (horizontalOffset.lengthSqr() >= clearanceSqr) {
			return target;
		}

		Vec3 pushDirection;
		if (horizontalOffset.lengthSqr() < 1.0E-4D) {
			Vec3 look = leader.getLookAngle();
			pushDirection = new Vec3(-look.x, 0.0D, -look.z);
			if (pushDirection.lengthSqr() < 1.0E-4D) {
				pushDirection = new Vec3(1.0D, 0.0D, 0.0D);
			} else {
				pushDirection = pushDirection.normalize();
			}
		} else {
			pushDirection = horizontalOffset.normalize();
		}

		double clearance = getLeaderClearanceRadius(follower);
		Vec3 cleared = leaderPos.add(pushDirection.scale(clearance));
		return new Vec3(cleared.x, target.y, cleared.z);
	}

	private static Vec3 pickLeaderClearanceTarget(PathfinderMob mob, LivingEntity leader) {
		Vec3 away = mob.position().subtract(leader.position());
		away = new Vec3(away.x, 0.0D, away.z);
		if (away.lengthSqr() < 1.0E-4D) {
			away = new Vec3(1.0D, 0.0D, 0.0D);
		} else {
			away = away.normalize();
		}

		return leader.position().add(away.scale(getLeaderClearanceRadius(mob) + getMemberSpacing(mob) * 0.5D));
	}

	@Nullable
	private static LivingEntity findTooCloseHerdMember(PathfinderMob mob, UUID leaderId) {
		double searchRange = getMemberSpacing(mob) * 2.0D;
		double spacingSqr = getMemberSpacingSqr(mob);
		for (LivingEntity other : mob.level().getEntitiesOfClass(LivingEntity.class, mob.getBoundingBox().inflate(searchRange), candidate -> candidate != mob && candidate.getType() == mob.getType() && canHerdWith(candidate))) {
			AnimalHerdState otherState = HERD_STATES.get(other.getUUID());
			if (otherState == null || !leaderId.equals(otherState.getHerdLeaderId())) {
				continue;
			}

			if (horizontalDistanceSqr(mob, other) < spacingSqr) {
				return other;
			}
		}

		return null;
	}

	private static Vec3 pickMemberClearanceTarget(PathfinderMob mob, LivingEntity crowdedMember) {
		Vec3 away = mob.position().subtract(crowdedMember.position());
		away = new Vec3(away.x, 0.0D, away.z);
		if (away.lengthSqr() < 1.0E-4D) {
			away = new Vec3(1.0D, 0.0D, 0.0D);
		} else {
			away = away.normalize();
		}

		Vec3 cleared = crowdedMember.position().add(away.scale(getMemberSpacing(mob) + 0.25D));
		return new Vec3(cleared.x, mob.getY(), cleared.z);
	}

	private static Vec3 enforceMemberClearance(PathfinderMob mob, Vec3 target, UUID leaderId) {
		Vec3 adjusted = target;
		double searchRange = getMemberSpacing(mob) * 2.0D;
		double spacingSqr = getMemberSpacingSqr(mob);
		for (LivingEntity other : mob.level().getEntitiesOfClass(LivingEntity.class, mob.getBoundingBox().inflate(searchRange), candidate -> candidate != mob && candidate.getType() == mob.getType() && canHerdWith(candidate))) {
			AnimalHerdState otherState = HERD_STATES.get(other.getUUID());
			if (otherState == null || !leaderId.equals(otherState.getHerdLeaderId())) {
				continue;
			}

			Vec3 horizontalOffset = new Vec3(adjusted.x - other.getX(), 0.0D, adjusted.z - other.getZ());
			if (horizontalOffset.lengthSqr() >= spacingSqr) {
				continue;
			}

			Vec3 pushDirection;
			if (horizontalOffset.lengthSqr() < 1.0E-4D) {
				pushDirection = mob.position().subtract(other.position());
				pushDirection = new Vec3(pushDirection.x, 0.0D, pushDirection.z);
				if (pushDirection.lengthSqr() < 1.0E-4D) {
					pushDirection = new Vec3(1.0D, 0.0D, 0.0D);
				} else {
					pushDirection = pushDirection.normalize();
				}
			} else {
				pushDirection = horizontalOffset.normalize();
			}

			Vec3 cleared = other.position().add(pushDirection.scale(getMemberSpacing(mob)));
			adjusted = new Vec3(cleared.x, adjusted.y, cleared.z);
		}

		return adjusted;
	}

	private static Vec3 rotateAroundY(Vec3 vec, double angle) {
		double cos = Math.cos(angle);
		double sin = Math.sin(angle);
		return new Vec3(vec.x * cos - vec.z * sin, vec.y, vec.x * sin + vec.z * cos);
	}

	private static double horizontalDistanceSqr(LivingEntity a, LivingEntity b) {
		double dx = a.getX() - b.getX();
		double dz = a.getZ() - b.getZ();
		return dx * dx + dz * dz;
	}

	private static void applyMovement(PathfinderMob mob, Vec3 target) {
		mob.setSprinting(false);

		if (mob instanceof Squid squid) {
			Vec3 delta = target.subtract(mob.position());
			if (delta.lengthSqr() < 1.0E-4D) {
				((SquidAccessor) squid).setMovementVector(Vec3.ZERO);
				return;
			}

			Vec3 direction = delta.normalize();
			((SquidAccessor) squid).setMovementVector(new Vec3((float) direction.x * 0.2F, (float) direction.y * 0.1F, (float) direction.z * 0.2F));
			return;
		}

		double speed = getWalkSpeed(mob);
		MobNavigationHelper.setSpeedModifier(mob, speed);
		MobNavigationHelper.moveTo(mob, target.x, target.y, target.z, speed);
	}

	/**
	 * Pathfind toward a herd target. On unpathable / failed start, count failures and
	 * eventually yield locomotion so vanilla AI can run. Never thrash with every-tick stop.
	 *
	 * @return true when a path was accepted (or already close enough)
	 */
	private static boolean tryApplyHerdMovement(PathfinderMob mob, AnimalHerdState state, @Nullable Vec3 target) {
		long gameTime = mob.level().getGameTime();
		if (target == null) {
			state.noteFailedHerdMove(gameTime, HERD_FAILED_MOVE_YIELD_THRESHOLD, HERD_LOCOMOTION_YIELD_TICKS);
			return false;
		}

		if (mob instanceof Squid) {
			applyMovement(mob, target);
			state.clearFailedHerdMoves();
			return true;
		}

		double distSqr = mob.position().distanceToSqr(target);
		if (distSqr <= GRAZE_ARRIVAL_DISTANCE_SQR) {
			state.clearFailedHerdMoves();
			return true;
		}

		if (!MobNavigationHelper.canPathTo(mob, target.x, target.y, target.z)) {
			state.noteFailedHerdMove(gameTime, HERD_FAILED_MOVE_YIELD_THRESHOLD, HERD_LOCOMOTION_YIELD_TICKS);
			return false;
		}

		applyMovement(mob, target);
		if (!mob.getNavigation().isInProgress()) {
			state.noteFailedHerdMove(gameTime, HERD_FAILED_MOVE_YIELD_THRESHOLD, HERD_LOCOMOTION_YIELD_TICKS);
			return false;
		}

		state.clearFailedHerdMoves();
		return true;
	}

	/** Clearance only when the escape point is pathable — never shove forever into a crowded pen. */
	private static void tryApplyHerdClearance(PathfinderMob mob, AnimalHerdState state, @Nullable Vec3 target) {
		long gameTime = mob.level().getGameTime();
		if (target == null) {
			state.noteFailedHerdMove(gameTime, HERD_FAILED_MOVE_YIELD_THRESHOLD, HERD_LOCOMOTION_YIELD_TICKS);
			return;
		}

		if (MobBlockStepHelper.isWideMob(mob)) {
			tryApplyHerdMovement(mob, state, target);
			return;
		}

		if (!MobNavigationHelper.canPathTo(mob, target.x, target.y, target.z)) {
			state.noteFailedHerdMove(gameTime, HERD_FAILED_MOVE_YIELD_THRESHOLD, HERD_LOCOMOTION_YIELD_TICKS);
			return;
		}

		applyMovement(mob, target);
		if (!mob.getNavigation().isInProgress()) {
			state.noteFailedHerdMove(gameTime, HERD_FAILED_MOVE_YIELD_THRESHOLD, HERD_LOCOMOTION_YIELD_TICKS);
			return;
		}

		state.clearFailedHerdMoves();
	}

	private static boolean maybeYieldFromStuck(PathfinderMob mob, AnimalHerdState state, long gameTime) {
		if (state.getStuckTicks(gameTime) < HERD_STUCK_YIELD_TICKS) {
			return false;
		}

		if (!mob.blockPosition().equals(state.getStuckBlock())) {
			return false;
		}

		state.beginLocomotionYield(gameTime, HERD_LOCOMOTION_YIELD_TICKS);
		state.clearStuckTracking();
		return true;
	}

	private static double getLeaderClearanceRadius(PathfinderMob mob) {
		if (MobBlockStepHelper.isWideMob(mob)) {
			return Math.max(3.0D, MobBlockStepHelper.getHorizontalHalfExtent(mob) + 1.5D);
		}

		return Math.max(LEADER_CLEARANCE_RADIUS, MobBlockStepHelper.getHorizontalHalfExtent(mob) + 0.5D);
	}

	private static double getLeaderClearanceRadiusSqr(PathfinderMob mob) {
		double radius = getLeaderClearanceRadius(mob);
		return radius * radius;
	}

	private static double getMemberSpacing(PathfinderMob mob) {
		if (MobBlockStepHelper.isWideMob(mob)) {
			// Stable 3–6 block center spacing per mob so wide herds leave room for hitboxes.
			int hash = mob.getUUID().hashCode();
			return 3.0D + (Math.abs(hash >> 8) % 31) / 10.0D;
		}

		return HERD_MEMBER_SPACING;
	}

	private static double getFollowerSlotDistance(PathfinderMob mob, AnimalHerdState state) {
		double slotDistance = state.getFollowerSlotDistance();
		if (MobBlockStepHelper.isWideMob(mob)) {
			return Math.max(slotDistance, getMemberSpacing(mob) + 1.0D);
		}

		return slotDistance;
	}

	private static double getMemberSpacingSqr(PathfinderMob mob) {
		double spacing = getMemberSpacing(mob);
		return spacing * spacing;
	}

	private static double getWanderArrivalDistanceSqr(PathfinderMob mob) {
		double radius = Math.max(2.0D, MobBlockStepHelper.getHorizontalHalfExtent(mob));
		return radius * radius;
	}

	/** @return true when a forced repath was issued due to standing still too long */
	private static boolean tickHerdStuckRecovery(PathfinderMob mob, AnimalHerdState state) {
		BlockPos current = mob.blockPosition();
		long now = mob.level().getGameTime();
		if (!current.equals(state.getStuckBlock())) {
			state.resetStuckTracking(current, now);
			return false;
		}

		long stuckTicks = state.getStuckTicks(now);
		if (stuckTicks < HERD_STUCK_REPATH_TICKS) {
			return false;
		}

		return stuckTicks == HERD_STUCK_REPATH_TICKS
			|| (stuckTicks - HERD_STUCK_REPATH_TICKS) % HERD_STUCK_REPATH_INTERVAL_TICKS == 0L;
	}

	@Nullable
	private static Vec3 pickStuckRecoveryWanderTarget(PathfinderMob mob, Vec3 origin, Vec3 direction) {
		if (MobBlockStepHelper.isWideMob(mob)) {
			Vec3 sidestep = MobBlockStepHelper.pickSidestepTarget(mob, origin, direction);
			if (sidestep != null) {
				return sidestep;
			}
		}

		for (int attempt = 0; attempt < 8; attempt++) {
			double angleOffset = (attempt % 2 == 0 ? 1.0D : -1.0D) * Math.PI / 3.0D * (attempt / 2 + 1);
			Vec3 offsetDir = rotateAroundY(direction, angleOffset);
			Vec3 candidate = pickWanderTarget(mob, origin, offsetDir);
			if (candidate != null && MobNavigationHelper.canPathTo(mob, candidate.x, candidate.y, candidate.z)) {
				return candidate;
			}
		}

		return pickWanderTarget(mob, origin, rotateAroundY(direction, mob.getRandom().nextDouble() * Math.PI * 2.0D));
	}

	private static Vec3 pickStuckRecoveryFollowerTarget(PathfinderMob mob, LivingEntity leader, Vec3 direction, AnimalHerdState state) {
		if (MobBlockStepHelper.isWideMob(mob)) {
			Vec3 sidestep = MobBlockStepHelper.pickSidestepTarget(mob, mob.position(), direction);
			if (sidestep != null) {
				return enforceMemberClearance(mob, sidestep, state.getHerdLeaderId());
			}
		}

		for (int attempt = 0; attempt < 6; attempt++) {
			double angleOffset = state.getFollowerSlotAngle() + (attempt % 2 == 0 ? 1.0D : -1.0D) * Math.PI / 6.0D * (attempt / 2 + 1);
			Vec3 behind = new Vec3(-direction.x, 0.0D, -direction.z);
			if (behind.lengthSqr() < 1.0E-4D) {
				behind = new Vec3(0.0D, 0.0D, 1.0D);
			} else {
				behind = behind.normalize();
			}

			Vec3 offset = rotateAroundY(behind, angleOffset).scale(getFollowerSlotDistance(mob, state));
			Vec3 candidate = leader.position().add(offset);
			candidate = enforceLeaderClearance(candidate, leader, mob);
			candidate = enforceMemberClearance(mob, candidate, state.getHerdLeaderId());
			if (MobNavigationHelper.canPathTo(mob, candidate.x, candidate.y, candidate.z)) {
				return candidate;
			}
		}

		return pickFollowerTarget(mob, leader, direction, state);
	}

	private static Vec3 pickStuckRecoveryNearby(PathfinderMob mob, Vec3 origin, Vec3 failedTarget) {
		Vec3 toward = failedTarget.subtract(origin);
		if (toward.lengthSqr() < 1.0E-4D) {
			toward = new Vec3(1.0D, 0.0D, 0.0D);
		} else {
			toward = new Vec3(toward.x, 0.0D, toward.z).normalize();
		}

		if (MobBlockStepHelper.isWideMob(mob)) {
			Vec3 sidestep = MobBlockStepHelper.pickSidestepTarget(mob, origin, toward);
			if (sidestep != null) {
				return sidestep;
			}
		}

		for (int attempt = 0; attempt < 6; attempt++) {
			double angleOffset = (attempt % 2 == 0 ? 1.0D : -1.0D) * Math.PI / 4.0D * (attempt / 2 + 1);
			Vec3 offsetDir = rotateAroundY(toward, angleOffset);
			int distance = 4 + mob.getRandom().nextInt(6);
			Vec3 candidate = origin.add(offsetDir.scale(distance));
			Vec3 adjusted = mob.isInWater()
				? DefaultRandomPos.getPosTowards(mob, distance, 4, candidate, Math.PI / 4.0D)
				: DefaultRandomPos.getPosTowards(mob, distance, 7, candidate, Math.PI / 4.0D);
			if (adjusted != null && MobNavigationHelper.canPathTo(mob, adjusted.x, adjusted.y, adjusted.z)) {
				return adjusted;
			}
		}

		return failedTarget;
	}

	@Nullable
	private static Vec3 pickWideMobObstacleAvoidTarget(PathfinderMob mob, Vec3 origin, Vec3 target, Vec3 direction) {
		Vec3 sidestep = MobBlockStepHelper.pickSidestepTarget(mob, origin, direction);
		if (sidestep != null) {
			return sidestep;
		}

		return pickPathfindableNearby(mob, origin, target, direction);
	}

	public static void releaseHerd(PathfinderMob mob) {
		stopHerdMovement(mob);
	}

	private static void stopHerdMovement(PathfinderMob mob) {
		if (mob instanceof Squid squid) {
			((SquidAccessor) squid).setMovementVector(Vec3.ZERO);
		}

		mob.getNavigation().stop();
	}

	private static double getWalkSpeed(PathfinderMob mob) {
		if (AnimalBehaviorConfig.usesVanillaHerdSpeed(mob.getType())) {
			return 1.0D;
		}

		double baseSpeed = mob.getAttributeValue(Attributes.MOVEMENT_SPEED);
		if (baseSpeed <= 0.0D) {
			baseSpeed = 0.2D;
		}

		double blocksPerTick = AnimalBehaviorConfig.getHerdSpeedBps(mob.getType()) / TICKS_PER_SECOND;
		return Math.max(1.0D, blocksPerTick / baseSpeed);
	}

	private static int randomDuration(LivingEntity entity, int minTicks, int maxTicks) {
		return minTicks + entity.getRandom().nextInt(maxTicks - minTicks + 1);
	}

	private static boolean canApplyHerd(LivingEntity entity) {
		if (!entity.isAlive() || entity.isPassenger()) {
			return false;
		}

		if (EntityPlayerTrust.bypassesHerd(entity)) {
			return false;
		}

		if (entity instanceof Animal animal && animal.isInLove()) {
			return false;
		}

		if (entity instanceof AbstractHorse horse && horse.isVehicle()) {
			return false;
		}

		if (HerdFenceProximity.isNearFenceOrGate(entity)) {
			return false;
		}

		if (entity instanceof PathfinderMob && VanillaAiFallback.isPackedWithHerdmates(entity)) {
			return false;
		}

		return true;
	}

	private static boolean canHerdWith(LivingEntity entity) {
		return MobBehaviorConfig.isModEnabled(entity.getType())
			&& MobBehaviorConfig.isEnabled(entity.getType(), MobBehavior.HERD)
			&& canApplyHerd(entity)
			&& !AnimalFleeSystem.isFleeing(entity)
			&& !AnimalWaterFleeEscapeSystem.blocksHerd(entity)
			&& !AnimalAggroSystem.isAggroing(entity)
			&& !AnimalStayOnLandSystem.isEscapingWater(entity);
	}

	@Nullable
	private static LivingEntity findEntity(net.minecraft.world.level.Level level, UUID entityId) {
		if (level instanceof ServerLevel serverLevel) {
			Entity entity = serverLevel.getEntity(entityId);
			return entity instanceof LivingEntity livingEntity ? livingEntity : null;
		}

		return null;
	}

	private static AnimalHerdState getState(LivingEntity entity) {
		return HERD_STATES.computeIfAbsent(entity.getUUID(), id -> new AnimalHerdState(id));
	}

	private static final class EntityTypeMatcher implements java.util.function.Predicate<LivingEntity> {
		private final net.minecraft.world.entity.EntityType<?> type;

		private EntityTypeMatcher(net.minecraft.world.entity.EntityType<?> type) {
			this.type = type;
		}

		@Override
		public boolean test(LivingEntity entity) {
			return entity.getType() == this.type;
		}
	}
}
