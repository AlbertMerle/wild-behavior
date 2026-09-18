package com.wildbehavior.ai;

import com.wildbehavior.config.AnimalBehaviorConfig;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Scripted water escape for PlayerFear / Passive flee: after the chase ends in water the mob
 * commits forward, turns 90° twice (ending 180° from the flee heading), seeks land, then
 * paths inland before resuming herd and other lower-priority behaviors.
 */
public final class AnimalWaterFleeEscapeSystem {
	static final int COMMIT_FORWARD_BLOCKS = 8;
	static final int TURN_CROSS_BLOCKS = 20;
	static final int LEAVE_WATER_BLOCKS = 12;
	private static final int REPATH_INTERVAL_TICKS = 20;
	/** Staggered land probe while scripted swim phases run — skip to inland departure if ashore early. */
	private static final int LAND_CHECK_INTERVAL_TICKS = 5;
	private static final int LAND_SEARCH_RADIUS = 16;
	private static final int MAX_LAND_SEARCH_ATTEMPTS = 16;
	private static final double ARRIVE_DISTANCE_SQR = 2.25D;

	private static final Map<UUID, AnimalWaterFleeEscapeState> STATES = new ConcurrentHashMap<>();

	private AnimalWaterFleeEscapeSystem() {
	}

	public static void onEntityLoad(LivingEntity entity) {
		if (AnimalCategories.receivesFleeBehavior(entity)) {
			STATES.put(entity.getUUID(), new AnimalWaterFleeEscapeState());
		}
	}

	public static void onEntityUnload(LivingEntity entity) {
		STATES.remove(entity.getUUID());
	}

	public static void release(PathfinderMob mob) {
		AnimalWaterFleeEscapeState state = STATES.get(mob.getUUID());
		if (state != null) {
			state.clear();
		}
	}

	public static boolean isActive(LivingEntity entity) {
		AnimalWaterFleeEscapeState state = STATES.get(entity.getUUID());
		return state != null && state.isActive();
	}

	/** Scripted swim phases before land seek: fear, herd, aggression, and all other behaviors are suppressed. */
	public static boolean blocksAllBehaviors(LivingEntity entity) {
		if (!isActive(entity)) {
			return false;
		}

		AnimalWaterFleeEscapeState.Phase phase = getState(entity).getPhase();
		return phase == AnimalWaterFleeEscapeState.Phase.COMMIT_FORWARD
			|| phase == AnimalWaterFleeEscapeState.Phase.TURN_CROSS
			|| phase == AnimalWaterFleeEscapeState.Phase.RETURN_TURN;
	}

	/** Herd stays suppressed until the full sequence finishes (including inland departure). */
	public static boolean blocksHerd(LivingEntity entity) {
		return isActive(entity);
	}

	/**
	 * @return true when this tick fully consumed Wild Behavior (other systems should not run)
	 */
	public static boolean tickPathfinderMob(PathfinderMob mob) {
		if (mob.level().isClientSide() || !isActive(mob)) {
			return false;
		}

		AnimalWaterFleeEscapeState state = getState(mob);
		if (trySkipToLeaveWaterIfOnLand(mob, state)) {
			tickLeaveWater(mob, state);
			return blocksAllBehaviors(mob);
		}

		if (!AnimalAggroState.isBehaviorUpdateTick(mob)) {
			return blocksAllBehaviors(mob);
		}

		switch (state.getPhase()) {
			case COMMIT_FORWARD -> tickCommitForward(mob, state);
			case TURN_CROSS -> tickTurnCross(mob, state);
			case RETURN_TURN -> tickReturnTurn(mob, state);
			case SEEK_LAND -> tickSeekLand(mob, state);
			case LEAVE_WATER -> tickLeaveWater(mob, state);
		}

		return blocksAllBehaviors(mob);
	}

	public static void begin(PathfinderMob mob, float fleeHeading) {
		int turnSign = mob.getRandom().nextBoolean() ? 1 : -1;
		AnimalWaterFleeEscapeState state = getState(mob);
		state.begin(fleeHeading, turnSign, mob.getX(), mob.getZ());
		mob.setYRot(fleeHeading);
		mob.setYBodyRot(fleeHeading);
		mob.setYHeadRot(fleeHeading);
		issueMoveAlongHeading(mob, fleeHeading, COMMIT_FORWARD_BLOCKS);
	}

	private static void tickCommitForward(PathfinderMob mob, AnimalWaterFleeEscapeState state) {
		float heading = state.getFleeHeading();
		if (reachedTravelDistance(state, mob, heading, COMMIT_FORWARD_BLOCKS)) {
			advanceToTurnCross(mob, state);
			return;
		}

		maintainHeadingMove(mob, state, heading, COMMIT_FORWARD_BLOCKS);
	}

	private static void tickTurnCross(PathfinderMob mob, AnimalWaterFleeEscapeState state) {
		float heading = crossHeading(state);
		if (reachedTravelDistance(state, mob, heading, TURN_CROSS_BLOCKS)) {
			advanceToReturnTurn(mob, state);
			return;
		}

		maintainHeadingMove(mob, state, heading, TURN_CROSS_BLOCKS);
	}

	private static void tickReturnTurn(PathfinderMob mob, AnimalWaterFleeEscapeState state) {
		float heading = reverseHeading(state);
		applyHeading(mob, heading);
		if (reachedTravelDistance(state, mob, heading, 2.0D)) {
			advanceToSeekLand(mob, state);
			return;
		}

		maintainHeadingMove(mob, state, heading, 2);
	}

	private static void tickSeekLand(PathfinderMob mob, AnimalWaterFleeEscapeState state) {
		if (!isInWaterBlock(mob)) {
			advanceToLeaveWater(mob, state);
			return;
		}

		float heading = reverseHeading(state);
		applyHeading(mob, heading);
		state.tickRepathCooldown();

		BlockPos land = state.getLandTarget();
		boolean needRepath = land == null
			|| mob.getNavigation().isDone()
			|| state.getRepathCooldown() <= 0;

		if (needRepath) {
			land = findNearestLand(mob);
			if (land != null) {
				state.setLandTarget(land);
				state.setRepathCooldown(REPATH_INTERVAL_TICKS);
				MobNavigationHelper.moveTo(mob, land.getX() + 0.5D, land.getY(), land.getZ() + 0.5D, fleeSpeed(mob));
			} else {
				state.setLandTarget(null);
				state.setRepathCooldown(REPATH_INTERVAL_TICKS);
				issueMoveAlongHeading(mob, heading, TURN_CROSS_BLOCKS);
			}
		} else if (land != null && !mob.getNavigation().isInProgress()) {
			MobNavigationHelper.moveTo(mob, land.getX() + 0.5D, land.getY(), land.getZ() + 0.5D, fleeSpeed(mob));
		}
	}

	private static void tickLeaveWater(PathfinderMob mob, AnimalWaterFleeEscapeState state) {
		if (isInWaterBlock(mob)) {
			advanceToSeekLand(mob, state);
			return;
		}

		state.tickRepathCooldown();
		BlockPos inland = state.getInlandTarget();
		boolean needRepath = inland == null
			|| mob.getNavigation().isDone()
			|| state.getRepathCooldown() <= 0;

		if (needRepath) {
			inland = pickInlandDeparture(mob, LEAVE_WATER_BLOCKS);
			if (inland == null) {
				finish(mob, state);
				return;
			}

			state.setInlandTarget(inland);
			state.setRepathCooldown(REPATH_INTERVAL_TICKS);
			MobNavigationHelper.moveTo(mob, inland.getX() + 0.5D, inland.getY(), inland.getZ() + 0.5D, fleeSpeed(mob));
			return;
		}

		if (inland != null && mob.position().distanceToSqr(Vec3.atCenterOf(inland)) <= ARRIVE_DISTANCE_SQR) {
			finish(mob, state);
			return;
		}

		if (inland != null && !mob.getNavigation().isInProgress()) {
			MobNavigationHelper.moveTo(mob, inland.getX() + 0.5D, inland.getY(), inland.getZ() + 0.5D, fleeSpeed(mob));
		}
	}

	private static void advanceToTurnCross(PathfinderMob mob, AnimalWaterFleeEscapeState state) {
		float heading = crossHeading(state);
		state.setPhase(AnimalWaterFleeEscapeState.Phase.TURN_CROSS);
		state.setPhaseStart(mob.getX(), mob.getZ());
		applyHeading(mob, heading);
		issueMoveAlongHeading(mob, heading, TURN_CROSS_BLOCKS);
	}

	private static void advanceToReturnTurn(PathfinderMob mob, AnimalWaterFleeEscapeState state) {
		float heading = reverseHeading(state);
		state.setPhase(AnimalWaterFleeEscapeState.Phase.RETURN_TURN);
		state.setPhaseStart(mob.getX(), mob.getZ());
		applyHeading(mob, heading);
		issueMoveAlongHeading(mob, heading, 2);
	}

	private static void advanceToSeekLand(PathfinderMob mob, AnimalWaterFleeEscapeState state) {
		state.setPhase(AnimalWaterFleeEscapeState.Phase.SEEK_LAND);
		state.setLandTarget(null);
		state.setRepathCooldown(0);
	}

	private static void advanceToLeaveWater(PathfinderMob mob, AnimalWaterFleeEscapeState state) {
		state.setPhase(AnimalWaterFleeEscapeState.Phase.LEAVE_WATER);
		state.setInlandTarget(null);
		state.setRepathCooldown(0);
		mob.getNavigation().stop();
	}

	/**
	 * Every {@link #LAND_CHECK_INTERVAL_TICKS} while the escape is active, detect reaching land
	 * during scripted swim phases (or seek-land) and jump straight to the inland departure leg.
	 */
	private static boolean trySkipToLeaveWaterIfOnLand(PathfinderMob mob, AnimalWaterFleeEscapeState state) {
		if (state.getPhase() == AnimalWaterFleeEscapeState.Phase.LEAVE_WATER) {
			return false;
		}

		if (!isLandCheckTick(mob)) {
			return false;
		}

		if (isInWaterBlock(mob)) {
			return false;
		}

		advanceToLeaveWater(mob, state);
		return true;
	}

	private static boolean isLandCheckTick(PathfinderMob mob) {
		return (mob.level().getGameTime() + mob.getId()) % LAND_CHECK_INTERVAL_TICKS == 0;
	}

	private static void finish(PathfinderMob mob, AnimalWaterFleeEscapeState state) {
		mob.getNavigation().stop();
		mob.setSprinting(false);
		state.clear();
	}

	private static void maintainHeadingMove(PathfinderMob mob, AnimalWaterFleeEscapeState state, float heading, int distance) {
		applyHeading(mob, heading);
		state.tickRepathCooldown();
		if (state.getRepathCooldown() <= 0 && (mob.getNavigation().isDone() || !mob.getNavigation().isInProgress())) {
			state.setRepathCooldown(REPATH_INTERVAL_TICKS);
			issueMoveAlongHeading(mob, heading, distance);
		}
	}

	private static void issueMoveAlongHeading(PathfinderMob mob, float headingDegrees, int distance) {
		mob.setSprinting(true);
		Vec3 dir = headingToDirection(headingDegrees);
		Vec3 target = mob.position().add(dir.scale(distance));
		MobNavigationHelper.moveTo(mob, target.x, target.y, target.z, fleeSpeed(mob));
	}

	private static void applyHeading(PathfinderMob mob, float heading) {
		mob.setYRot(heading);
		mob.setYBodyRot(heading);
		mob.setYHeadRot(heading);
	}

	private static float crossHeading(AnimalWaterFleeEscapeState state) {
		return Mth.wrapDegrees(state.getFleeHeading() + 90.0F * state.getTurnSign());
	}

	private static float reverseHeading(AnimalWaterFleeEscapeState state) {
		return Mth.wrapDegrees(state.getFleeHeading() + 180.0F * state.getTurnSign());
	}

	private static boolean reachedTravelDistance(AnimalWaterFleeEscapeState state, PathfinderMob mob, float heading, double distance) {
		Vec3 dir = headingToDirection(heading);
		double dx = mob.getX() - state.getPhaseStartX();
		double dz = mob.getZ() - state.getPhaseStartZ();
		double traveled = dx * dir.x + dz * dir.z;
		return traveled >= distance - 0.5D;
	}

	private static Vec3 headingToDirection(float headingDegrees) {
		float radians = headingDegrees * (float) (Math.PI / 180.0D);
		return new Vec3(-Mth.sin(radians), 0.0D, Mth.cos(radians));
	}

	private static double fleeSpeed(PathfinderMob mob) {
		double baseSpeed = mob.getAttributeValue(Attributes.MOVEMENT_SPEED);
		if (baseSpeed <= 0.0D) {
			baseSpeed = 0.2D;
		}

		double blocksPerTick = AnimalBehaviorConfig.getFleeSpeedBps(mob.getType()) / 20.0D;
		return Math.max(0.01D, blocksPerTick / baseSpeed);
	}

	private static boolean isInWaterBlock(PathfinderMob mob) {
		if (mob.isInWater()) {
			return true;
		}

		Level level = mob.level();
		BlockPos feet = mob.blockPosition();
		BlockPos eyes = BlockPos.containing(mob.getEyePosition());
		return isWater(level, feet) || isWater(level, eyes);
	}

	private static boolean isWater(Level level, BlockPos pos) {
		var fluid = level.getFluidState(pos);
		return fluid.is(Fluids.WATER) || fluid.is(Fluids.FLOWING_WATER);
	}

	private static boolean isStandableLand(Level level, BlockPos pos) {
		if (!level.getFluidState(pos).isEmpty()) {
			return false;
		}

		BlockState feet = level.getBlockState(pos);
		if (!feet.getCollisionShape(level, pos).isEmpty()) {
			return false;
		}

		BlockState below = level.getBlockState(pos.below());
		return below.isFaceSturdy(level, pos.below(), Direction.UP);
	}

	@Nullable
	private static BlockPos findNearestLand(PathfinderMob mob) {
		Level level = mob.level();
		BlockPos origin = mob.blockPosition();
		int attempts = 0;
		for (int radius = 1; radius <= LAND_SEARCH_RADIUS; radius++) {
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
						continue;
					}

					BlockPos base = origin.offset(dx, 0, dz);
					for (int dy = -2; dy <= 2; dy++) {
						BlockPos candidate = base.offset(0, dy, 0);
						if (!isStandableLand(level, candidate)) {
							continue;
						}

						if (++attempts > MAX_LAND_SEARCH_ATTEMPTS) {
							return null;
						}

						if (MobNavigationHelper.canPathTo(mob, candidate.getX() + 0.5D, candidate.getY(), candidate.getZ() + 0.5D)) {
							return candidate;
						}
					}
				}
			}
		}

		return null;
	}

	@Nullable
	private static BlockPos pickInlandDeparture(PathfinderMob mob, int distance) {
		Level level = mob.level();
		BlockPos origin = mob.blockPosition();
		float baseHeading = mob.getYRot();

		for (int attempt = 0; attempt < 16; attempt++) {
			float heading = baseHeading + (attempt * 22.5F);
			Vec3 dir = headingToDirection(heading);
			BlockPos candidate = BlockPos.containing(
				origin.getX() + dir.x * distance,
				origin.getY(),
				origin.getZ() + dir.z * distance
			);

			for (int dy = -2; dy <= 3; dy++) {
				BlockPos adjusted = candidate.offset(0, dy, 0);
				if (!isStandableLand(level, adjusted) || isNearWater(level, adjusted, 2)) {
					continue;
				}

				if (MobNavigationHelper.canPathTo(mob, adjusted.getX() + 0.5D, adjusted.getY(), adjusted.getZ() + 0.5D)) {
					return adjusted;
				}
			}
		}

		return null;
	}

	private static boolean isNearWater(Level level, BlockPos pos, int radius) {
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -radius; dz <= radius; dz++) {
					if (isWater(level, pos.offset(dx, dy, dz))) {
						return true;
					}
				}
			}
		}

		return false;
	}

	private static AnimalWaterFleeEscapeState getState(LivingEntity entity) {
		return STATES.computeIfAbsent(entity.getUUID(), id -> new AnimalWaterFleeEscapeState());
	}

	/** True when the mob is in water, fleeing from a living threat, and the chase has ended. */
	static boolean shouldBeginFromFlee(PathfinderMob mob, AnimalFleeState fleeState) {
		if (!mob.isInWater() || AnimalCategories.isSeaCreature(mob.getType())) {
			return false;
		}

		if (fleeState.getFleeFromPosition() != null || fleeState.getFleeingFrom() == null) {
			return false;
		}

		if (!MobBehaviorConfig.isEnabled(mob.getType(), MobBehavior.PLAYER_FEAR)
			&& !MobBehaviorConfig.isEnabled(mob.getType(), MobBehavior.PASSIVE)) {
			return false;
		}

		return !isStillBeingChased(mob, fleeState);
	}

	static float resolveFleeHeading(PathfinderMob mob, AnimalFleeState fleeState) {
		Vec3 threatPos = resolveThreatPosition(mob, fleeState);
		if (threatPos != null) {
			Vec3 away = mob.position().subtract(threatPos);
			if (away.horizontalDistanceSqr() > 1.0E-4D) {
				return (float) Math.toDegrees(Math.atan2(-away.x, away.z));
			}
		}

		return mob.getYRot();
	}

	private static boolean isStillBeingChased(PathfinderMob mob, AnimalFleeState fleeState) {
		UUID fleeingFrom = fleeState.getFleeingFrom();
		if (fleeingFrom == null || !(mob.level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
			return false;
		}

		net.minecraft.world.entity.Entity entity = serverLevel.getEntity(fleeingFrom);
		if (!(entity instanceof LivingEntity threat) || !threat.isAlive()) {
			return false;
		}

		if (threat instanceof Player player) {
			if (PlayerApproachDetector.shouldIgnorePlayerThreat(mob, player)) {
				return false;
			}

			long now = mob.level().getGameTime();
			if (PlayerApproachDetector.canSeePlayer(mob, player)
				&& ThreatApproachDetector.shouldFleeFromPlayer(mob, player)) {
				return true;
			}

			return !PlayerApproachDetector.hasForgottenPlayer(mob, fleeState.getLastSeenThreatTick(), now);
		}

		float detectRange = AnimalBehaviorConfig.normalDetectRange();
		if (mob.distanceToSqr(threat) > detectRange * detectRange) {
			return false;
		}

		if (MobBehaviorConfig.isEnabled(mob.getType(), MobBehavior.PASSIVE)
			&& MobBehaviorConfig.isAggroBehavior(threat.getType())) {
			return true;
		}

		return ThreatApproachDetector.isPassivePreyThreat(mob, threat);
	}

	@Nullable
	private static Vec3 resolveThreatPosition(PathfinderMob mob, AnimalFleeState fleeState) {
		UUID fleeingFrom = fleeState.getFleeingFrom();
		if (fleeingFrom == null || !(mob.level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
			return null;
		}

		net.minecraft.world.entity.Entity entity = serverLevel.getEntity(fleeingFrom);
		return entity != null ? entity.position() : null;
	}
}
