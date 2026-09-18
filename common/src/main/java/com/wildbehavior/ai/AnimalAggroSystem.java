package com.wildbehavior.ai;

import com.wildbehavior.config.AnimalBehaviorConfig;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.monster.CrossbowAttackMob;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public final class AnimalAggroSystem {
	public static final long CHAIN_COOLDOWN_TICKS = 20L;
	private static final double TICKS_PER_SECOND = 20.0D;
	/** Seconds after acquiring a target before a one-time give-up roll. */
	private static final int AGGRO_GIVE_UP_TIMER_SECONDS = 20;
	private static final int AGGRO_GIVE_UP_TIMER_TICKS = AGGRO_GIVE_UP_TIMER_SECONDS * 20;
	/** Chance to abandon the current target when the give-up timer elapses. */
	private static final float AGGRO_GIVE_UP_CHANCE = 0.5F;
	/** Seconds after dropping aggro before idle scans may acquire a new target. */
	private static final int AGGRO_REACQUIRE_COOLDOWN_SECONDS = 20;
	private static final int AGGRO_REACQUIRE_COOLDOWN_TICKS = AGGRO_REACQUIRE_COOLDOWN_SECONDS * 20;
	/** Ticks between passive-prey retarget scans while already chasing prey. */
	private static final int PASSIVE_PREY_RETARGET_INTERVAL_TICKS = 60;
	/** After this long in one block, force a new chase path to route around obstacles. */
	private static final long CHASE_STUCK_REPATH_TICKS = 40L;
	/** How often to retry alternate chase paths while still stuck. */
	private static final long CHASE_STUCK_REPATH_INTERVAL_TICKS = 40L;
	/** Horizontal radius around last-seen position to investigate after losing LOS. */
	private static final int LOST_SIGHT_SEARCH_RADIUS = 8;
	/** Look-around pause after reaching a search point (1–2 seconds). */
	private static final int LOOK_AROUND_MIN_TICKS = 20;
	private static final int LOOK_AROUND_MAX_TICKS = 40;
	private static final double SEARCH_ARRIVE_DISTANCE_SQR = 2.25D;
	private static final Map<UUID, AnimalAggroState> STATES = new ConcurrentHashMap<>();

	private AnimalAggroSystem() {
	}

	public static void releaseOverlay(Mob mob) {
		clearModAggro(mob);
	}

	public static void onEntityLoad(LivingEntity entity) {
		if (MobBehaviorConfig.isModEnabled(entity.getType())) {
			int stagger = Math.abs(entity.getUUID().hashCode()) % AnimalAggroState.IDLE_THREAT_SCAN_INTERVAL_TICKS;
			STATES.put(entity.getUUID(), new AnimalAggroState(stagger));
			if (MobBehaviorConfig.isAggroBehavior(entity.getType())) {
				AnimalHerdSystem.refreshConfidenceSnapshot(entity);
			}
		}
	}

	public static void onEntityUnload(LivingEntity entity) {
		STATES.remove(entity.getUUID());
	}

	public static boolean isAggroing(LivingEntity entity) {
		AnimalAggroState state = STATES.get(entity.getUUID());
		return state != null && state.isAggroing();
	}

	/** True while the idle threat-scan interval is counting down (dispatcher must still tick to advance it). */
	public static boolean hasIdleThreatScanCooldown(LivingEntity entity) {
		if (!MobBehaviorConfig.isAggroBehavior(entity.getType())) {
			return false;
		}

		return getState(entity).hasIdleThreatScanCooldown();
	}

	/**
	 * True when aggro logic would drive this mob on the current tick (mirrors {@link #tickMob}).
	 * True when aggro would begin or continue this tick (e.g. WaterAggression may block new
	 * acquisition when the target is not in water).
	 */
	public static boolean wouldTakeControlThisTick(Mob mob) {
		if (!MobBehaviorConfig.isModEnabled(mob.getType())
			|| !MobBehaviorConfig.isAggroBehavior(mob.getType())) {
			return false;
		}

		if (!canApplyAggro(mob)) {
			return false;
		}

		if (AnimalFleeSystem.isFleeing(mob)
			|| AnimalDefenderSystem.isDefending(mob)
			|| AnimalFightBackSystem.isFightBacking(mob)) {
			return false;
		}

		if (mob instanceof PathfinderMob pathfinderMob && AnimalWaterFleeEscapeSystem.blocksAllBehaviors(pathfinderMob)) {
			return false;
		}

		AnimalAggroState state = STATES.get(mob.getUUID());
		if (state != null && state.isOnKillCooldown(mob.level().getGameTime())) {
			return false;
		}

		if (state != null && state.isAggroing()) {
			LivingEntity current = resolveCurrentAggroTarget(mob, state);
			if (current != null && current.isAlive() && !(current instanceof Player)) {
				return true;
			}

			if (current instanceof Player player && PlayerApproachDetector.canContinueAggroOnPlayer(mob, player)) {
				long now = mob.level().getGameTime();
				if (PlayerApproachDetector.canAggroSeePlayer(mob, player)
					|| !PlayerApproachDetector.hasForgottenPlayer(mob, state.getLastSeenTargetTick(), now)) {
					return true;
				}
			}
		}

		AnimalAggroState scanState = state != null ? state : getState(mob);
		if (scanState.isOnAggroReacquireCooldown(mob.level().getGameTime())) {
			return false;
		}

		if (!scanState.isIdleThreatScanReady()) {
			return false;
		}

		LivingEntity target = ThreatScanCache.get(mob, mob.level().getGameTime()).aggroTarget();
		if (target == null) {
			return false;
		}

		if (target instanceof Player && !ThreatApproachDetector.shouldAggroPlayer(mob)) {
			return false;
		}

		boolean alreadyOnThisTarget = state != null && state.isAggroing() && target.getUUID().equals(state.getAggroTargetId());
		if (alreadyOnThisTarget) {
			return true;
		}

		return canAcquireNewAggro(mob, target);
	}

	public static void tickMob(Mob mob) {
		if (mob.level().isClientSide() || !MobBehaviorConfig.isModEnabled(mob.getType())) {
			return;
		}

		if (!MobBehaviorConfig.isAggroBehavior(mob.getType())) {
			clearModAggro(mob);
			return;
		}

		if (!canApplyAggro(mob)) {
			clearModAggro(mob);
			return;
		}

		if (AnimalFleeSystem.isFleeing(mob)) {
			clearModAggro(mob);
			return;
		}

		if (mob instanceof PathfinderMob pathfinderMob && AnimalWaterFleeEscapeSystem.blocksAllBehaviors(pathfinderMob)) {
			clearModAggro(mob);
			return;
		}

		if (AnimalDefenderSystem.isDefending(mob)) {
			clearModAggro(mob);
			return;
		}

		// FightBack (e.g. water-ambush crocs hit from shore) owns combat until retaliation ends.
		if (AnimalFightBackSystem.isFightBacking(mob)) {
			return;
		}

		AnimalAggroState state = getState(mob);
		if (state.isOnKillCooldown(mob.level().getGameTime())) {
			clearModAggro(mob);
			return;
		}

		if (!MobBehaviorConfig.isEnabled(mob.getType(), MobBehavior.HERD)) {
			AnimalHerdSystem.maybeRefreshConfidenceSnapshot(mob);
		}

		// Ongoing chase — continue without re-scanning the world every tick.
		if (state.isAggroing()) {
			if (tickOngoingAggro(mob, state)) {
				return;
			}
		}

		if (state.isOnAggroReacquireCooldown(mob.level().getGameTime())) {
			return;
		}

		if (!state.tickIdleThreatScanCooldown()) {
			return;
		}

		LivingEntity target = ThreatScanCache.get(mob, mob.level().getGameTime()).aggroTarget();

		if (target != null) {
			boolean alreadyOnThisTarget = state.isAggroing() && target.getUUID().equals(state.getAggroTargetId());
			if (!alreadyOnThisTarget) {
				// WaterAggression: only start/switch when the target is in water.
				// Ongoing chase may continue onto dry land.
				if (!canAcquireNewAggro(mob, target)) {
					return;
				}

				beginAggro(mob, target, state);
				if (state.isAggroing() && target instanceof Player player) {
					propagateChainAggro(mob, player);
				}
			}

			if (!state.isAggroing()) {
				return;
			}

			if (target instanceof Player player) {
				state.setLastSeenTargetTick(mob.level().getGameTime());
				state.setLastKnownTargetPos(player.position());
				state.clearSearch();
			}

			performAggroCombat(mob, target, state);
		}
	}

	/**
	 * Drives an already-acquired chase without a world threat scan.
	 *
	 * @return true when aggro remains active after this tick
	 */
	private static boolean tickOngoingAggro(Mob mob, AnimalAggroState state) {
		if (rollAggroGiveUp(mob, state)) {
			clearModAggro(mob);
			return false;
		}

		LivingEntity current = resolveCurrentAggroTarget(mob, state);
		if (current != null && current.isAlive()) {
			if (current instanceof Player player && PlayerApproachDetector.canContinueAggroOnPlayer(mob, player)) {
				long now = mob.level().getGameTime();
				if (state.getSearchTarget() != null || state.isLookingAround(now)) {
					performLostSightSearch(mob, player, state);
					return true;
				}

				if (!AnimalAggroState.isBehaviorUpdateTick(mob)) {
					return true;
				}

				if (PlayerApproachDetector.canAggroSeePlayer(mob, player)) {
					state.setLastSeenTargetTick(now);
					state.setLastKnownTargetPos(player.position());
					state.clearSearch();
					performAggroCombat(mob, player, state);
				} else if (!PlayerApproachDetector.hasForgottenPlayer(mob, state.getLastSeenTargetTick(), now)) {
					performLostSightSearch(mob, player, state);
				} else {
					clearModAggro(mob);
					return false;
				}

				return true;
			}

			if (!(current instanceof Player)) {
				maybeRetargetCloserPassivePrey(mob, state, current);
				current = resolveCurrentAggroTarget(mob, state);
				if (current != null && current.isAlive() && !(current instanceof Player)) {
					if (AnimalAggroState.isBehaviorUpdateTick(mob)) {
						performAggroCombat(mob, current, state);
					}

					return true;
				}
			}
		}

		if (!AnimalAggroState.isBehaviorUpdateTick(mob)) {
			return state.isAggroing();
		}

		continueExistingAggro(mob, state);
		return state.isAggroing();
	}

	/** Continues an already-acquired chase, or clears aggro when the target is gone / forgotten. */
	private static void continueExistingAggro(Mob mob, AnimalAggroState state) {
		LivingEntity current = resolveCurrentAggroTarget(mob, state);
		if (current instanceof Player player && PlayerApproachDetector.canContinueAggroOnPlayer(mob, player)) {
			long now = mob.level().getGameTime();
			if (PlayerApproachDetector.canAggroSeePlayer(mob, player)) {
				state.setLastSeenTargetTick(now);
				state.setLastKnownTargetPos(player.position());
				state.clearSearch();
				performAggroCombat(mob, player, state);
				return;
			}

			if (!PlayerApproachDetector.hasForgottenPlayer(mob, state.getLastSeenTargetTick(), now)) {
				performLostSightSearch(mob, player, state);
				return;
			}
		}

		clearModAggro(mob);
	}

	/**
	 * After losing player LOS: investigate an 8-block radius around the last known position,
	 * pausing 1–2 seconds to look around at each stop, until Memory expires.
	 */
	private static void performLostSightSearch(Mob mob, Player player, AnimalAggroState state) {
		long now = mob.level().getGameTime();
		if (state.getLastKnownTargetPos() == null) {
			state.setLastKnownTargetPos(player.position());
		}

		if (PlayerApproachDetector.canAggroSeePlayer(mob, player)) {
			state.setLastSeenTargetTick(now);
			state.setLastKnownTargetPos(player.position());
			state.clearSearch();
			performAggroCombat(mob, player, state);
			return;
		}

		mob.setSprinting(false);
		// Drop combat target so vanilla ranged goals do not fire blind shots while searching.
		if (mob.getTarget() == player) {
			mob.setTarget(null);
		}

		if (state.isLookingAround(now)) {
			if (mob instanceof PathfinderMob pathfinderMob) {
				pathfinderMob.getNavigation().stop();
			}

			tickLookAround(mob, state, now);
			return;
		}

		if (!(mob instanceof PathfinderMob pathfinderMob)) {
			beginLookAroundPause(mob, state, now);
			return;
		}

		Vec3 searchTarget = state.getSearchTarget();
		if (searchTarget != null && hasArrivedAtSearchTarget(pathfinderMob, searchTarget)) {
			state.setSearchTarget(null);
			beginLookAroundPause(mob, state, now);
			pathfinderMob.getNavigation().stop();
			return;
		}

		if (searchTarget == null) {
			searchTarget = pickSearchPoint(pathfinderMob, state);
			state.setSearchTarget(searchTarget);
			state.setRepathCooldown(0);
		}

		if (searchTarget == null) {
			beginLookAroundPause(mob, state, now);
			return;
		}

		maintainSearchMove(pathfinderMob, searchTarget, state);
	}

	private static void beginLookAroundPause(Mob mob, AnimalAggroState state, long now) {
		int span = LOOK_AROUND_MAX_TICKS - LOOK_AROUND_MIN_TICKS + 1;
		int duration = LOOK_AROUND_MIN_TICKS + mob.getRandom().nextInt(span);
		state.beginLookAround(now, duration, mob.getYRot());
	}

	private static void tickLookAround(Mob mob, AnimalAggroState state, long now) {
		int duration = state.getLookAroundDurationTicks();
		long remaining = state.getLookAroundUntilTick() - now;
		long elapsed = Math.max(0L, duration - remaining);
		float progress = duration <= 0 ? 1.0F : (float) elapsed / (float) duration;
		// One left-right sweep over the pause.
		float yawOffset = (float) Math.sin(progress * Math.PI * 2.0D) * 75.0F;
		float yaw = state.getLookAroundStartYaw() + yawOffset;
		double rad = Math.toRadians(yaw);
		double lookDist = 6.0D;
		mob.getLookControl().setLookAt(
			mob.getX() - Math.sin(rad) * lookDist,
			mob.getEyeY(),
			mob.getZ() + Math.cos(rad) * lookDist,
			60.0F,
			30.0F);
	}

	private static boolean hasArrivedAtSearchTarget(PathfinderMob mob, Vec3 searchTarget) {
		if (mob.position().distanceToSqr(searchTarget) <= SEARCH_ARRIVE_DISTANCE_SQR) {
			return true;
		}

		return mob.getNavigation().isDone() && mob.position().distanceToSqr(searchTarget) <= 9.0D;
	}

	@Nullable
	private static Vec3 pickSearchPoint(PathfinderMob mob, AnimalAggroState state) {
		Vec3 center = state.getLastKnownTargetPos();
		if (center == null) {
			center = mob.position();
		}

		// First leg: close on the last known spot if still away from it.
		if (mob.position().distanceToSqr(center) > SEARCH_ARRIVE_DISTANCE_SQR) {
			Vec3 towardLastKnown = DefaultRandomPos.getPosTowards(
				mob, LOST_SIGHT_SEARCH_RADIUS, 7, center, Math.PI / 2.0D);
			if (towardLastKnown != null) {
				return towardLastKnown;
			}
		}

		double angle = mob.getRandom().nextDouble() * Math.PI * 2.0D;
		double distance = 2.0D + mob.getRandom().nextDouble() * (LOST_SIGHT_SEARCH_RADIUS - 2.0D);
		Vec3 preferred = center.add(Math.cos(angle) * distance, 0.0D, Math.sin(angle) * distance);
		Vec3 candidate = DefaultRandomPos.getPosTowards(
			mob, LOST_SIGHT_SEARCH_RADIUS, 7, preferred, Math.PI / 2.0D);
		if (candidate != null) {
			return candidate;
		}

		candidate = DefaultRandomPos.getPos(mob, LOST_SIGHT_SEARCH_RADIUS, 7);
		if (candidate != null && candidate.distanceToSqr(center)
			<= (double) LOST_SIGHT_SEARCH_RADIUS * LOST_SIGHT_SEARCH_RADIUS) {
			return candidate;
		}

		return preferred;
	}

	private static void maintainSearchMove(PathfinderMob mob, Vec3 searchTarget, AnimalAggroState state) {
		double searchSpeed = getSearchSpeed(mob);
		MobNavigationHelper.setSpeedModifier(mob, searchSpeed);
		mob.getLookControl().setLookAt(searchTarget.x, searchTarget.y + 1.0D, searchTarget.z, 30.0F, 30.0F);

		state.tickRepathCooldown();
		if (state.getRepathCooldown() > 0 && !mob.getNavigation().isDone()) {
			return;
		}

		MobNavigationHelper.moveTo(mob, searchTarget.x, searchTarget.y, searchTarget.z, searchSpeed);
		state.setRepathCooldown(AnimalBehaviorConfig.fleeRepathIntervalTicks());
	}

	private static double getSearchSpeed(PathfinderMob mob) {
		return Math.max(1.0D, getChaseSpeed(mob) * 0.65D);
	}

	@Nullable
	private static LivingEntity resolveCurrentAggroTarget(Mob mob, AnimalAggroState state) {
		UUID targetId = state.getAggroTargetId();
		if (targetId == null) {
			return null;
		}

		LivingEntity current = mob.getTarget();
		if (current != null && current.isAlive() && targetId.equals(current.getUUID())) {
			return current;
		}

		if (!(mob.level() instanceof ServerLevel serverLevel)) {
			return null;
		}

		Entity entity = serverLevel.getEntity(targetId);
		return entity instanceof LivingEntity living && living.isAlive() ? living : null;
	}

	public static void forceChainAggro(LivingEntity entity, Player player) {
		if (!(entity instanceof Mob mob) || !canApplyAggro(mob)) {
			return;
		}

		if (PlayerApproachDetector.ignoresWildBehavior(player)) {
			return;
		}

		if (!ThreatApproachDetector.shouldAggroPlayer(mob)) {
			return;
		}

		AnimalAggroState state = getState(mob);
		boolean alreadyOnThisTarget = state.isAggroing() && player.getUUID().equals(state.getAggroTargetId());
		if (!alreadyOnThisTarget && !canAcquireNewAggro(mob, player, true)) {
			return;
		}

		long tick = mob.level().getGameTime();
		if (tick - state.getLastChainTick() < CHAIN_COOLDOWN_TICKS) {
			return;
		}

		state.setLastChainTick(tick);
		beginAggro(mob, player, state);
		if (state.isAggroing()) {
			performAggroCombat(mob, player, state);
		}
	}

	/**
	 * Whether this mob may start (or switch to) a new aggro target right now.
	 * Blocked during AggressiveKillTimer cooldown.
	 * WaterAggression: target must be in water. Aggressive: aquatic prey is ignored.
	 */
	private static boolean canAcquireNewAggro(Mob mob, LivingEntity target) {
		return canAcquireNewAggro(mob, target, false);
	}

	private static boolean canAcquireNewAggro(Mob mob, LivingEntity target, boolean ignoreReacquireCooldown) {
		AnimalAggroState state = STATES.get(mob.getUUID());
		long gameTime = mob.level().getGameTime();
		if (state != null && state.isOnKillCooldown(gameTime)) {
			return false;
		}

		if (!ignoreReacquireCooldown && state != null && state.isOnAggroReacquireCooldown(gameTime)) {
			return false;
		}

		if (MobBehaviorConfig.isEnabled(mob.getType(), MobBehavior.WATER_AGGRESSION)) {
			return target.isInWater();
		}

		return !AnimalCategories.isAquaticPrey(target);
	}

	/**
	 * Called when an Aggressive mob's lethal hit kills a living entity.
	 * Starts the AggressiveKillTimer cooldown before another hunt can begin.
	 */
	public static void notifyKill(Mob killer, LivingEntity victim) {
		if (killer.level().isClientSide() || victim == killer) {
			return;
		}

		if (!MobBehaviorConfig.isModEnabled(killer.getType())
			|| !MobBehaviorConfig.isAggroBehavior(killer.getType())) {
			return;
		}

		AnimalAggroState state = getState(killer);
		boolean wasAggroTarget = state.isAggroing() && victim.getUUID().equals(state.getAggroTargetId());
		boolean wasMobTarget = killer.getTarget() == victim;
		if (!wasAggroTarget && !wasMobTarget) {
			return;
		}

		startKillCooldown(killer, state);
		clearModAggro(killer);
	}

	private static void startKillCooldown(Mob killer, AnimalAggroState state) {
		int ticks = AnimalBehaviorConfig.getAggressiveKillTimerTicks(killer.getType());
		if (ticks <= 0) {
			state.setKillCooldownUntilTick(0L);
			return;
		}

		state.setKillCooldownUntilTick(killer.level().getGameTime() + ticks);
	}

	/**
	 * After {@link #AGGRO_GIVE_UP_TIMER_SECONDS} on one target, roll once: {@link #AGGRO_GIVE_UP_CHANCE}
	 * to drop aggro; otherwise keep pursuing until normal end conditions.
	 */
	private static boolean rollAggroGiveUp(Mob mob, AnimalAggroState state) {
		if (state.isAggroGiveUpResolved()) {
			return false;
		}

		long elapsed = mob.level().getGameTime() - state.getAggroStartedTick();
		if (elapsed < AGGRO_GIVE_UP_TIMER_TICKS) {
			return false;
		}

		state.setAggroGiveUpResolved(true);
		return mob.getRandom().nextFloat() < AGGRO_GIVE_UP_CHANCE;
	}

	private static void beginAggro(Mob mob, LivingEntity target, AnimalAggroState state) {
		if (target instanceof Player && !AnimalHerdSystem.confirmConfidenceForAggro(mob)) {
			return;
		}

		long now = mob.level().getGameTime();
		state.resetIdleThreatScanCooldown();
		state.setAggroing(true);
		state.setAggroTargetId(target.getUUID());
		state.setAggroStartedTick(now);
		state.setAggroGiveUpResolved(false);
		state.setLastPassivePreyRetargetTick(now);
		// Refresh on LOS when visible; chain alerts without LOS still start the Memory timer.
		state.setLastSeenTargetTick(now);
		state.setLastKnownTargetPos(target.position());
		state.clearSearch();
		state.setRepathCooldown(0);
		state.resetStuckTracking(mob.blockPosition(), mob.level().getGameTime());
		mob.setTarget(target);
	}

	/**
	 * While chasing passive prey, periodically pick a closer valid target (Aggressive / WaterAggression).
	 */
	private static void maybeRetargetCloserPassivePrey(Mob mob, AnimalAggroState state, LivingEntity current) {
		if (!ThreatApproachDetector.shouldAggroPassivePrey(mob)) {
			return;
		}

		long now = mob.level().getGameTime();
		if (now - state.getLastPassivePreyRetargetTick() < PASSIVE_PREY_RETARGET_INTERVAL_TICKS) {
			return;
		}

		state.setLastPassivePreyRetargetTick(now);
		LivingEntity nearestPrey = ThreatApproachDetector.findNearestPassivePrey(mob);
		if (nearestPrey == null || nearestPrey.getUUID().equals(current.getUUID())) {
			return;
		}

		if (nearestPrey.distanceToSqr(mob) >= current.distanceToSqr(mob)) {
			return;
		}

		if (!canAcquireNewAggro(mob, nearestPrey)) {
			return;
		}

		beginAggro(mob, nearestPrey, state);
	}

	private static void performAggroCombat(Mob mob, LivingEntity target, AnimalAggroState state) {
		if (mob instanceof Wolf && WolfBoneTameSystem.isActive(mob)) {
			return;
		}

		mob.setTarget(target);
		mob.getLookControl().setLookAt(target, 30.0F, 30.0F);

		// Skeletons / pillagers / other ranged hostiles: pick the target, then leave shooting,
		// strafing, and walking to vanilla combat goals (RangedBowAttackGoal, etc.).
		if (shouldDeferToVanillaRangedCombat(mob)) {
			mob.setSprinting(false);
			return;
		}

		if (AnimalMeleeCombat.tryMeleeAttack(mob, target, state, AnimalMeleeCombat.DEFAULT_AGGRESSIVE_DAMAGE)) {
			return;
		}

		if (mob instanceof PathfinderMob pathfinderMob) {
			maintainAggroChase(pathfinderMob, target, state);
		}
	}

	/**
	 * Path toward the target via vanilla navigation so mobs can jump up and drop down blocks.
	 * Repaths only when the current path finishes or the repath interval elapses — calling
	 * {@code moveTo} every tick resets paths before vertical steps can execute.
	 */
	private static void maintainAggroChase(PathfinderMob mob, LivingEntity target, AnimalAggroState state) {
		double chaseSpeed = getChaseSpeed(mob);
		mob.setSprinting(true);
		MobNavigationHelper.setSpeedModifier(mob, chaseSpeed);

		if (tickChaseStuckRecovery(mob, state)) {
			MobNavigationHelper.moveTo(mob, target, chaseSpeed);
			state.setRepathCooldown(AnimalBehaviorConfig.fleeRepathIntervalTicks());
			return;
		}

		state.tickRepathCooldown();
		if (state.getRepathCooldown() > 0 && !mob.getNavigation().isDone()) {
			return;
		}

		MobNavigationHelper.moveTo(mob, target, chaseSpeed);
		state.setRepathCooldown(AnimalBehaviorConfig.fleeRepathIntervalTicks());
	}

	/** @return true when a forced repath was issued due to standing still too long */
	private static boolean tickChaseStuckRecovery(PathfinderMob mob, AnimalAggroState state) {
		BlockPos current = mob.blockPosition();
		long now = mob.level().getGameTime();
		if (!current.equals(state.getStuckBlock())) {
			state.resetStuckTracking(current, now);
			return false;
		}

		long stuckTicks = state.getStuckTicks(now);
		if (stuckTicks < CHASE_STUCK_REPATH_TICKS) {
			return false;
		}

		if (stuckTicks == CHASE_STUCK_REPATH_TICKS
			|| (stuckTicks - CHASE_STUCK_REPATH_TICKS) % CHASE_STUCK_REPATH_INTERVAL_TICKS == 0L) {
			state.setRepathCooldown(0);
			return true;
		}

		return false;
	}

	/**
	 * True for mobs that already have vanilla ranged (or weapon-switched melee) combat goals.
	 * Wild Behavior still chooses the aggro target; it must not replace bow fire with punches
	 * or overwrite navigation every tick (that cancels skeleton strafe/walk).
	 */
	private static boolean shouldDeferToVanillaRangedCombat(Mob mob) {
		return mob instanceof RangedAttackMob || mob instanceof CrossbowAttackMob;
	}

	private static double getChaseSpeed(PathfinderMob mob) {
		double baseSpeed = getAttributeOrDefault(mob, Attributes.MOVEMENT_SPEED, 0.2D);
		double blocksPerTick = MobBehaviorConfig.getFleeSpeedBps(mob.getType()) / TICKS_PER_SECOND;
		return Math.max(1.0D, blocksPerTick / baseSpeed);
	}

	private static double getAttributeOrDefault(LivingEntity entity, Holder<Attribute> attribute, double fallback) {
		if (!entity.getAttributes().hasAttribute(attribute)) {
			return fallback;
		}

		return entity.getAttributeValue(attribute);
	}

	private static void propagateChainAggro(LivingEntity source, Player player) {
		int chainRadius = AnimalBehaviorConfig.aggroChainRadius();
		source.level()
			.getEntitiesOfClass(LivingEntity.class, source.getBoundingBox().inflate(chainRadius), entity -> entity != source && MobBehaviorConfig.isModEnabled(entity.getType()))
			.forEach(entity -> {
				if (MobBehaviorConfig.isAggroBehavior(entity.getType())) {
					forceChainAggro(entity, player);
				}
			});
	}

	private static void clearModAggro(Mob mob) {
		AnimalAggroState state = STATES.get(mob.getUUID());
		if (state == null || !state.isAggroing()) {
			return;
		}

		if (mob.getTarget() != null && isModAggroTarget(mob.getTarget(), state)) {
			mob.setTarget(null);
		}

		mob.setSprinting(false);
		if (mob instanceof PathfinderMob pathfinderMob) {
			pathfinderMob.getNavigation().stop();
		}

		state.setAggroReacquireCooldownUntilTick(mob.level().getGameTime() + AGGRO_REACQUIRE_COOLDOWN_TICKS);
		state.clearAggro();
	}

	private static boolean isModAggroTarget(LivingEntity target, AnimalAggroState state) {
		UUID targetId = state.getAggroTargetId();
		return targetId != null && targetId.equals(target.getUUID());
	}

	private static boolean canApplyAggro(Mob mob) {
		if (!mob.isAlive() || mob.isPassenger()) {
			return false;
		}

		if (ThreatApproachDetector.isAggressiveBaby(mob)) {
			return false;
		}

		if (mob instanceof TamableAnimal tamable && tamable.isTame()) {
			return false;
		}

		if (mob instanceof Animal animal && animal.isInLove()) {
			return false;
		}

		if (mob instanceof AbstractHorse horse && horse.isVehicle()) {
			return false;
		}

		return true;
	}

	private static AnimalAggroState getState(LivingEntity entity) {
		return STATES.computeIfAbsent(entity.getUUID(), id -> {
			int stagger = Math.abs(id.hashCode()) % AnimalAggroState.IDLE_THREAT_SCAN_INTERVAL_TICKS;
			return new AnimalAggroState(stagger);
		});
	}
}
