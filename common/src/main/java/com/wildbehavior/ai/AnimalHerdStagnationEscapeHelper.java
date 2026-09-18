package com.wildbehavior.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.phys.Vec3;
import com.wildbehavior.config.AnimalBehaviorConfig;
import org.jetbrains.annotations.Nullable;

/**
 * Herd-leader stagnation escape: every four minutes, leaders record their chunk. When still in the
 * same chunk as the previous check, scan nearby terrain, pick a favorable direction, and walk 12 blocks.
 * Interrupted by flee/aggro; takes priority over normal herd wandering.
 * Tamed / fed / trusting mobs are fully exempt ({@link EntityPlayerTrust#bypassesHerd}).
 */
final class AnimalHerdStagnationEscapeHelper {
	/** Four minutes at 20 TPS. */
	static final int CHUNK_CHECK_INTERVAL_TICKS = 4 * 60 * 20;
	static final int SCAN_RADIUS = 24;
	static final int WALK_DISTANCE = 12;
	private static final int DIRECTION_SAMPLES = 24;
	private static final int VERTICAL_SEARCH = 8;
	private static final double ARRIVAL_DISTANCE_SQR = 4.0D;

	private AnimalHerdStagnationEscapeHelper() {
	}

	/**
	 * Leader-only chunk check. On first check or chunk change, updates stored chunk and returns false.
	 * When the leader is still in the same chunk as the last check, begins a stagnation escape walk.
	 *
	 * @return true when a new escape walk was started this tick
	 */
	static boolean tickChunkCheck(PathfinderMob leader, HerdGroupData group, long gameTime) {
		if (EntityPlayerTrust.bypassesHerd(leader)) {
			group.cancelStagnationEscape();
			return false;
		}

		if (gameTime < group.getNextChunkCheckTick()) {
			return false;
		}

		group.setNextChunkCheckTick(gameTime + CHUNK_CHECK_INTERVAL_TICKS);
		ChunkPos current = leader.chunkPosition();
		ChunkPos previous = group.getLastCheckedChunk();
		group.setLastCheckedChunk(current);

		if (previous == null || !previous.equals(current)) {
			group.cancelStagnationEscape();
			return false;
		}

		Vec3 direction = pickEscapeDirection(leader);
		Vec3 target = pickWalkTarget(leader, direction);
		group.beginStagnationEscape(target, direction);
		return true;
	}

	/**
	 * Ticks an active stagnation escape for the herd leader.
	 */
	static void tickActiveEscape(PathfinderMob leader, AnimalHerdState state, HerdGroupData group) {
		if (EntityPlayerTrust.bypassesHerd(leader)) {
			group.cancelStagnationEscape();
			return;
		}

		Vec3 target = group.getStagnationEscapeTarget();
		if (target == null) {
			group.cancelStagnationEscape();
			return;
		}

		if (leader.position().distanceToSqr(target) <= ARRIVAL_DISTANCE_SQR
			|| (leader.getNavigation().isDone() && leader.position().distanceToSqr(target) <= ARRIVAL_DISTANCE_SQR + 9.0D)) {
			group.cancelStagnationEscape();
			return;
		}

		if (!leader.getNavigation().isInProgress()) {
			applyEscapeMovement(leader, target);
			state.setRepathCooldown(AnimalBehaviorConfig.herdRepathIntervalTicks());
		}
	}

	static void cancelForGroup(HerdGroupData group) {
		group.cancelStagnationEscape();
	}

	private static Vec3 pickEscapeDirection(PathfinderMob mob) {
		Level level = mob.level();
		BlockPos origin = mob.blockPosition();
		int originGroundY = findStandableGroundY(level, origin);
		if (originGroundY == Integer.MIN_VALUE) {
			return randomHorizontalDirection(mob);
		}

		double bestScore = Double.NEGATIVE_INFINITY;
		double bestYaw = mob.getRandom().nextDouble() * Math.PI * 2.0D;

		for (int sample = 0; sample < DIRECTION_SAMPLES; sample++) {
			double yaw = sample * (Math.PI * 2.0D / DIRECTION_SAMPLES);
			double score = scoreDirection(level, origin.getX(), origin.getZ(), originGroundY, yaw);
			if (score > bestScore) {
				bestScore = score;
				bestYaw = yaw;
			}
		}

		return new Vec3(Mth.cos((float) bestYaw), 0.0D, Mth.sin((float) bestYaw));
	}

	private static double scoreDirection(Level level, int originX, int originZ, int originGroundY, double yaw) {
		double dx = Mth.cos((float) yaw);
		double dz = Mth.sin((float) yaw);
		double score = 0.0D;

		for (int distance = 1; distance <= SCAN_RADIUS; distance++) {
			int x = originX + Mth.floor(dx * distance);
			int z = originZ + Mth.floor(dz * distance);
			BlockPos column = new BlockPos(x, originGroundY + 2, z);

			for (int dy = 2; dy >= -2; dy--) {
				BlockState state = level.getBlockState(column.offset(0, dy, 0));
				if (isAvoidBlock(state)) {
					score -= 1.0D;
				}

				if (isPositiveBlock(state)) {
					score += 1.0D;
				}
			}

			int groundY = findStandableGroundY(level, column);
			if (groundY != Integer.MIN_VALUE && groundY > originGroundY) {
				score += groundY - originGroundY;
			}
		}

		return score;
	}

	private static Vec3 pickWalkTarget(PathfinderMob mob, Vec3 direction) {
		Vec3 rawTarget = mob.position().add(direction.scale(WALK_DISTANCE));
		Vec3 candidate;
		if (mob.isInWater()) {
			candidate = rawTarget;
		} else {
			candidate = LandRandomPos.getPosTowards(mob, WALK_DISTANCE, 7, rawTarget);
			if (candidate == null) {
				candidate = LandRandomPos.getPos(mob, WALK_DISTANCE, 7);
			}
		}

		return candidate != null ? candidate : rawTarget;
	}

	private static void applyEscapeMovement(PathfinderMob mob, Vec3 target) {
		mob.setSprinting(false);
		double speed = getWalkSpeed(mob);
		MobNavigationHelper.setSpeedModifier(mob, speed);
		MobNavigationHelper.moveTo(mob, target.x, target.y, target.z, speed);
	}

	private static double getWalkSpeed(PathfinderMob mob) {
		if (AnimalBehaviorConfig.usesVanillaHerdSpeed(mob.getType())) {
			return 1.0D;
		}

		double baseSpeed = mob.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
		if (baseSpeed <= 0.0D) {
			baseSpeed = 0.2D;
		}

		double blocksPerTick = AnimalBehaviorConfig.getHerdSpeedBps(mob.getType()) / 20.0D;
		return Math.max(1.0D, blocksPerTick / baseSpeed);
	}

	private static boolean isAvoidBlock(BlockState state) {
		return state.is(BlockTags.FENCES)
			|| state.is(BlockTags.FENCE_GATES)
			|| state.is(BlockTags.PLANKS)
			|| state.is(BlockTags.DOORS)
			|| state.is(Blocks.CRAFTING_TABLE)
			|| state.is(Blocks.FURNACE)
			|| state.is(Blocks.BLAST_FURNACE)
			|| state.is(Blocks.SMOKER);
	}

	private static boolean isPositiveBlock(BlockState state) {
		return state.is(BlockTags.FLOWERS)
			|| state.is(Blocks.TALL_GRASS)
			|| state.is(Blocks.SHORT_GRASS);
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

	private static Vec3 randomHorizontalDirection(PathfinderMob mob) {
		double angle = mob.getRandom().nextDouble() * Math.PI * 2.0D;
		return new Vec3(Mth.cos((float) angle), 0.0D, Mth.sin((float) angle));
	}
}
