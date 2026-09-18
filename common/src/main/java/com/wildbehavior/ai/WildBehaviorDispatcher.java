package com.wildbehavior.ai;

import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import org.jetbrains.annotations.Nullable;

/**
 * Central Wild Behavior tick order. Resolves navigation priority so higher behaviors
 * run before lower ones and idle systems are skipped when a higher priority owns the mob.
 *
 * <p>Priority (high → low): water escape (blocking) → food lure → active wolf bone tame →
 * weapon scare flee → fight-back → aggro → (new) wolf bone tame → flee → late water escape →
 * stay-on-land → herd.
 *
 * <p>Fed / tamed mobs and vanilla-tamed wolves skip all Wild Behavior overlays and use vanilla AI.
 * Penned / packed animals ({@link VanillaAiFallback}) also use vanilla AI.
 * Mobs stuck on the same block for ~10s while an overlay tries to move them get released with a cooldown.
 */
public final class WildBehaviorDispatcher {
	private WildBehaviorDispatcher() {
	}

	public static void tickPathfinderMob(PathfinderMob mob) {
		if (EntityPlayerTrust.bypassesAllWildBehavior(mob)) {
			WildBehaviorOverlay.releaseIfActive(mob);
			return;
		}

		// Penned / packed animals: release overlays once, then pure vanilla wander/breed/eat.
		if (VanillaAiFallback.applyIfNeeded(mob)) {
			return;
		}

		if (AnimalStuckRecoverySystem.tick(mob)) {
			return;
		}

		if (AnimalWaterFleeEscapeSystem.isActive(mob)
			&& AnimalWaterFleeEscapeSystem.blocksAllBehaviors(mob)) {
			AnimalWaterFleeEscapeSystem.tickPathfinderMob(mob);
			return;
		}

		if (AnimalCategories.isGolem(mob.getType())) {
			AnimalDefenderSystem.tickMob(mob);
			return;
		}

		if (mob.getType() == EntityTypes.VILLAGER) {
			tickVillager(mob);
			return;
		}

		tickAnimal(mob);
	}

	private static void tickVillager(PathfinderMob mob) {
		AnimalFightBackSystem.tickMob(mob);
		if (AnimalFightBackSystem.isFightBacking(mob)) {
			return;
		}

		if (MobBehaviorConfig.isEnabled(mob.getType(), MobBehavior.HERD)) {
			tickHerdStack(mob);
		}
	}

	private static void tickAnimal(PathfinderMob mob) {
		@Nullable Animal animal = mob instanceof Animal a ? a : null;

		if (animal != null) {
			AnimalFoodLureSystem.tickPathfinderMob(animal);
			if (AnimalFoodLureSystem.suppressesWildBehaviorOverlay(animal)) {
				if (AnimalWaterFleeEscapeSystem.isActive(mob)) {
					AnimalWaterFleeEscapeSystem.release(mob);
				}

				return;
			}
		}

		if (mob instanceof Wolf wolf && WolfBoneTameSystem.isActive(wolf)) {
			WolfBoneTameSystem.tick(wolf);
			if (AnimalWaterFleeEscapeSystem.isActive(mob)) {
				AnimalWaterFleeEscapeSystem.release(mob);
			}

			return;
		}

		if (AnimalFleeSystem.isWeaponScareFleeing(mob)) {
			AnimalFleeSystem.tickPathfinderMob(mob);
			return;
		}

		if (MobBehaviorConfig.isAggroBehavior(mob.getType())) {
			if (!AnimalFleeSystem.isFleeing(mob) && !AnimalFleeSystem.isLanding(mob)) {
				if (AnimalAggroSystem.isAggroing(mob)
					|| AnimalAggroSystem.hasIdleThreatScanCooldown(mob)
					|| AnimalAggroSystem.wouldTakeControlThisTick(mob)) {
					AnimalAggroSystem.tickMob(mob);
				}
			}
		}

		if (mob instanceof Wolf wolf && WolfBoneTameSystem.tick(wolf)) {
			if (AnimalWaterFleeEscapeSystem.isActive(mob)) {
				AnimalWaterFleeEscapeSystem.release(mob);
			}

			return;
		}

		AnimalFightBackSystem.tickMob(mob);
		if (AnimalFightBackSystem.isFightBacking(mob)) {
			return;
		}

		if (AnimalCategories.receivesFleeBehavior(mob)) {
			if (AnimalFleeSystem.isFleeing(mob)
				|| AnimalFleeSystem.isLanding(mob)
				|| AnimalFleeSystem.hasIdleThreatScanCooldown(mob)
				|| AnimalFleeSystem.wouldTakeControlThisTick(mob)) {
				AnimalFleeSystem.tickPathfinderMob(mob);
			}
		}

		if (tickLateWaterEscape(mob, animal)) {
			return;
		}

		if (MobBehaviorConfig.isEnabled(mob.getType(), MobBehavior.HERD)) {
			tickHerdStack(mob);
		}
	}

	private static void tickHerdStack(PathfinderMob mob) {
		if (AnimalWaterFleeEscapeSystem.blocksHerd(mob)) {
			return;
		}

		if (MobBehaviorConfig.isEnabled(mob.getType(), MobBehavior.STAY_ON_LAND)) {
			AnimalStayOnLandSystem.tickPathfinderMob(mob);
		}

		AnimalHerdSystem.tickPathfinderMob(mob);
	}

	/**
	 * @return true when late-phase water escape consumed this tick (herd must not run)
	 */
	private static boolean tickLateWaterEscape(PathfinderMob mob, @Nullable Animal animal) {
		if (!AnimalWaterFleeEscapeSystem.isActive(mob)
			|| AnimalWaterFleeEscapeSystem.blocksAllBehaviors(mob)) {
			return false;
		}

		boolean interrupted = AnimalAggroSystem.isAggroing(mob)
			|| AnimalFleeSystem.isFleeing(mob)
			|| AnimalFightBackSystem.isFightBacking(mob)
			|| (animal != null && AnimalFoodLureSystem.isFollowingFood(animal));
		if (interrupted) {
			AnimalWaterFleeEscapeSystem.release(mob);
			return false;
		}

		AnimalWaterFleeEscapeSystem.tickPathfinderMob(mob);
		return true;
	}
}
