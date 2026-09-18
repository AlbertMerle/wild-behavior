package com.wildbehavior.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Herd cliff safety: per-mob edge escape (all herd members) and leader wander-direction redirects.
 */
public final class AnimalCliffAvoidanceHelper {
	static final int SCAN_INTERVAL_TICKS = 60;
	static final int IMMEDIATE_SCAN_INTERVAL_TICKS = 40;
	/** Horizontal radius (blocks) for leader wander cliff scans. */
	static final int LEADER_SCAN_RADIUS = 8;
	/** Horizontal radius (blocks) for per-member immediate cliff scans (every {@link #IMMEDIATE_SCAN_INTERVAL_TICKS}). */
	static final int IMMEDIATE_SCAN_RADIUS = 8;
	/** Minimum vertical drop (blocks) to count as a cliff edge. */
	static final int MIN_DROP_BLOCKS = 3;
	static final int INDIVIDUAL_ESCAPE_BLOCKS = 4;
	static final double AVOIDANCE_VARIANCE_DEGREES = 10.0D;
	private static final int DIRECTION_SAMPLES = 24;
	private static final int VERTICAL_SEARCH = 8;

	private AnimalCliffAvoidanceHelper() {
	}

	/**
	 * When a cliff is detected ahead of travel, updates the herd wander direction to roughly 180° away
	 * (±{@link #AVOIDANCE_VARIANCE_DEGREES}) from the cliff bearing. Leader-only during wandering.
	 *
	 * @return true when direction was changed
	 */
	static boolean maybeRedirectFromCliff(PathfinderMob mob, HerdGroupData group) {
		if (!MobBehaviorConfig.isEnabled(mob.getType(), MobBehavior.HERD)) {
			return false;
		}

		CliffThreat threat = scanForCliff(mob, group.getWanderDirection(), LEADER_SCAN_RADIUS, true);
		if (threat == null) {
			return false;
		}

		double varianceRadians = Math.toRadians(mob.getRandom().nextDouble() * AVOIDANCE_VARIANCE_DEGREES * 2.0D - AVOIDANCE_VARIANCE_DEGREES);
		double escapeYaw = threat.cliffYawRadians() + Math.PI + varianceRadians;
		Vec3 escapeDirection = new Vec3(Mth.cos((float) escapeYaw), 0.0D, Mth.sin((float) escapeYaw));
		group.setWanderDirection(escapeDirection);
		return true;
	}

	/**
	 * When this mob is near a drop-off, pathfind {@link #INDIVIDUAL_ESCAPE_BLOCKS} blocks away from the cliff.
	 * Applies to every herd member; skipped when fear/aggro/etc. already halted herd ticks.
	 */
	@Nullable
	static Vec3 pickImmediateCliffEscapeTarget(PathfinderMob mob) {
		if (!MobBehaviorConfig.isEnabled(mob.getType(), MobBehavior.HERD)) {
			return null;
		}

		CliffThreat threat = scanForCliff(mob, null, IMMEDIATE_SCAN_RADIUS, false);
		if (threat == null) {
			return null;
		}

		double escapeYaw = threat.cliffYawRadians() + Math.PI;
		double dx = Mth.cos((float) escapeYaw);
		double dz = Mth.sin((float) escapeYaw);
		Vec3 rawTarget = mob.position().add(dx * INDIVIDUAL_ESCAPE_BLOCKS, 0.0D, dz * INDIVIDUAL_ESCAPE_BLOCKS);

		Vec3 candidate;
		if (mob.isInWater()) {
			candidate = rawTarget;
		} else {
			candidate = LandRandomPos.getPosTowards(mob, INDIVIDUAL_ESCAPE_BLOCKS, 7, rawTarget);
			if (candidate == null) {
				candidate = LandRandomPos.getPos(mob, INDIVIDUAL_ESCAPE_BLOCKS, 7);
			}
		}

		if (candidate == null) {
			candidate = rawTarget;
		}

		return isUnsafeStandPosition(mob.level(), candidate) ? null : candidate;
	}

	static boolean isUnsafeStandPosition(Level level, Vec3 position) {
		BlockPos feet = BlockPos.containing(position.x, position.y, position.z);
		int groundY = findStandableGroundY(level, feet);
		if (groundY == Integer.MIN_VALUE) {
			return true;
		}

		return scanDropFrom(level, feet.getX(), feet.getZ(), groundY, IMMEDIATE_SCAN_RADIUS, Vec3.ZERO, false) != null;
	}

	@Nullable
	private static CliffThreat scanForCliff(PathfinderMob mob, @Nullable Vec3 wanderDirection, int scanRadius, boolean biasTravelDirection) {
		Level level = mob.level();
		BlockPos origin = mob.blockPosition();
		int originGroundY = findStandableGroundY(level, origin);
		if (originGroundY == Integer.MIN_VALUE) {
			return null;
		}

		Vec3 travelDirection = Vec3.ZERO;
		if (biasTravelDirection && wanderDirection != null) {
			travelDirection = wanderDirection;
			if (travelDirection.lengthSqr() < 1.0E-4D) {
				travelDirection = new Vec3(1.0D, 0.0D, 0.0D);
			} else {
				travelDirection = new Vec3(travelDirection.x, 0.0D, travelDirection.z).normalize();
			}
		}

		return scanDropFrom(level, origin.getX(), origin.getZ(), originGroundY, scanRadius, travelDirection, biasTravelDirection);
	}

	@Nullable
	private static CliffThreat scanDropFrom(Level level, int originX, int originZ, int originGroundY, int scanRadius, Vec3 travelDirection, boolean biasTravelDirection) {
		double bestWeight = 0.0D;
		double bestYaw = 0.0D;

		for (int sample = 0; sample < DIRECTION_SAMPLES; sample++) {
			double cliffYaw = sample * (Math.PI * 2.0D / DIRECTION_SAMPLES);
			double dx = Mth.cos((float) cliffYaw);
			double dz = Mth.sin((float) cliffYaw);

			int previousGroundY = originGroundY;
			for (int distance = 1; distance <= scanRadius; distance++) {
				int x = originX + Mth.floor(dx * distance);
				int z = originZ + Mth.floor(dz * distance);
				int groundY = findStandableGroundY(level, new BlockPos(x, originGroundY + 2, z));
				int drop;
				if (groundY == Integer.MIN_VALUE) {
					drop = measureDropToGround(level, x, z, previousGroundY);
				} else {
					drop = previousGroundY - groundY;
				}

				if (drop >= MIN_DROP_BLOCKS) {
					double weight = (scanRadius - distance + 1) * (double) drop;
					if (biasTravelDirection) {
						Vec3 cliffDirection = new Vec3(dx, 0.0D, dz);
						weight *= 0.5D + 0.5D * Math.max(0.0D, cliffDirection.dot(travelDirection));
					}

					if (weight > bestWeight) {
						bestWeight = weight;
						bestYaw = cliffYaw;
					}
					break;
				}

				if (groundY == Integer.MIN_VALUE) {
					break;
				}

				previousGroundY = groundY;
			}
		}

		return bestWeight > 0.0D ? new CliffThreat(bestYaw, bestWeight) : null;
	}

	private static int measureDropToGround(Level level, int x, int z, int fromY) {
		for (int y = fromY - 1; y >= fromY - MIN_DROP_BLOCKS - 2; y--) {
			if (isStandableLand(level, new BlockPos(x, y, z))) {
				return fromY - y;
			}
		}

		return MIN_DROP_BLOCKS;
	}

	private static int findStandableGroundY(Level level, BlockPos around) {
		int baseY = around.getY();
		for (int dy = 2; dy >= -VERTICAL_SEARCH; dy--) {
			BlockPos pos = around.offset(0, dy, 0);
			if (isStandableLand(level, pos)) {
				return pos.getY();
			}
		}

		return Integer.MIN_VALUE;
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

	private record CliffThreat(double cliffYawRadians, double weight) {
	}
}
