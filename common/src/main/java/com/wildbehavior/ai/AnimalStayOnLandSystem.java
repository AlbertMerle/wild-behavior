package com.wildbehavior.ai;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import org.jetbrains.annotations.Nullable;

/**
 * StayOnLandFix behavior: herding mobs avoid water at all costs. If a herding
 * mob ends up in water, it is forced to pathfind to the nearest land before
 * herd behavior resumes. Ticked just above {@link AnimalHerdSystem} so it only
 * interferes with herding — fleeing / aggressive mobs may still enter water.
 */
public final class AnimalStayOnLandSystem {
	private static final int MAX_SEARCH_RADIUS = 12;
	private static final int MAX_PATH_ATTEMPTS = 12;
	private static final int REPATH_INTERVAL_TICKS = 20;
	private static final int SEARCH_RETRY_INTERVAL_TICKS = 30;
	private static final double ESCAPE_SPEED_MODIFIER = 1.2D;

	private static final Map<UUID, AnimalStayOnLandState> STATES = new ConcurrentHashMap<>();

	private AnimalStayOnLandSystem() {
	}

	public static void onEntityLoad(LivingEntity entity) {
		if (MobBehaviorConfig.isEnabled(entity.getType(), MobBehavior.STAY_ON_LAND)) {
			STATES.put(entity.getUUID(), new AnimalStayOnLandState());
		}
	}

	public static void onEntityUnload(LivingEntity entity) {
		STATES.remove(entity.getUUID());
	}

	/** True while this mob is actively being forced out of water (suppresses herding). */
	public static boolean isEscapingWater(LivingEntity entity) {
		AnimalStayOnLandState state = STATES.get(entity.getUUID());
		return state != null && state.isEscaping();
	}

	public static void tickPathfinderMob(PathfinderMob mob) {
		if (mob.level().isClientSide() || !MobBehaviorConfig.isModEnabled(mob.getType())) {
			return;
		}
		if (!MobBehaviorConfig.isEnabled(mob.getType(), MobBehavior.STAY_ON_LAND)) {
			return;
		}
		if (isAquaticMob(mob)) {
			return;
		}
		// Stand aside while a higher-priority behavior drives the mob so fleeing /
		// aggressive mobs may enter water freely.
		if (AnimalFleeSystem.isFleeing(mob)
			|| AnimalWaterFleeEscapeSystem.isActive(mob)
			|| AnimalAggroSystem.isAggroing(mob)
			|| AnimalFightBackSystem.isFightBacking(mob)
			|| AnimalDefenderSystem.isDefending(mob)) {
			clear(mob);
			return;
		}
		if (!canApply(mob)) {
			return;
		}

		AnimalStayOnLandState state = getState(mob);

		if (state.isEscaping()) {
			if (state.tickWaterCheckCooldown()) {
				if (!isInWaterBlock(mob)) {
					state.clear();
					return;
				}

				state.setCachedInWater(true);
			} else if (!state.isCachedInWater()) {
				return;
			}

			state.setEscaping(true);
			state.tickRepathCooldown();

			BlockPos target = state.getEscapeTarget();
			boolean needRepath = target == null
				|| mob.getNavigation().isDone()
				|| state.getRepathCooldown() <= 0;

			if (needRepath) {
				BlockPos land = findNearestLand(mob);
				if (land != null) {
					state.setEscapeTarget(land);
					state.setRepathCooldown(REPATH_INTERVAL_TICKS);
					moveToLand(mob, land);
				} else {
					state.setEscapeTarget(null);
					state.setRepathCooldown(SEARCH_RETRY_INTERVAL_TICKS);
				}
				return;
			}

			if (target != null && !mob.getNavigation().isInProgress()) {
				moveToLand(mob, target);
			}

			return;
		}

		if (!state.tickWaterCheckCooldown()) {
			return;
		}

		boolean inWater = isInWaterBlock(mob);
		state.setCachedInWater(inWater);
		if (!inWater) {
			return;
		}

		// In water: force pathfind to the nearest land before herding resumes.
		state.setEscaping(true);
		state.tickRepathCooldown();

		BlockPos land = findNearestLand(mob);
		if (land != null) {
			state.setEscapeTarget(land);
			state.setRepathCooldown(REPATH_INTERVAL_TICKS);
			moveToLand(mob, land);
		} else {
			state.setEscapeTarget(null);
			state.setRepathCooldown(SEARCH_RETRY_INTERVAL_TICKS);
		}
	}

	private static boolean isAquaticMob(LivingEntity mob) {
		return AnimalCategories.isSeaCreature(mob.getType()) || mob.canBreatheUnderwater();
	}

	private static boolean canApply(PathfinderMob mob) {
		if (!mob.isAlive() || mob.isPassenger()) {
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
		for (int radius = 1; radius <= MAX_SEARCH_RADIUS; radius++) {
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
						continue;
					}
					BlockPos base = origin.offset(dx, 0, dz);
					for (int dy = -1; dy <= 1; dy++) {
						BlockPos candidate = base.offset(0, dy, 0);
						if (!isStandableLand(level, candidate)) {
							continue;
						}
						if (++attempts > MAX_PATH_ATTEMPTS) {
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

	private static void moveToLand(PathfinderMob mob, BlockPos land) {
		MobNavigationHelper.moveTo(mob, land.getX() + 0.5D, land.getY(), land.getZ() + 0.5D, ESCAPE_SPEED_MODIFIER);
	}

	public static void release(PathfinderMob mob) {
		clear(mob);
	}

	private static void clear(LivingEntity mob) {
		AnimalStayOnLandState state = STATES.get(mob.getUUID());
		if (state != null && state.isEscaping()) {
			state.clear();
		}
	}

	private static AnimalStayOnLandState getState(LivingEntity entity) {
		return STATES.computeIfAbsent(entity.getUUID(), id -> new AnimalStayOnLandState());
	}
}

