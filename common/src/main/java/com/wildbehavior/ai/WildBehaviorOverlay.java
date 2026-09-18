package com.wildbehavior.ai;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.Animal;

/**
 * Drops an in-progress Wild Behavior overlay so vanilla / mod goal AI owns the mob again.
 */
public final class WildBehaviorOverlay {
	private WildBehaviorOverlay() {
	}

	/**
	 * True when a Wild Behavior system currently owns or is driving this mob
	 * (movement, combat, or food-follow). Idle graze rest is excluded.
	 */
	public static boolean isTryingToControl(Mob mob) {
		if (AnimalFleeSystem.isFleeing(mob) || AnimalFleeSystem.isLanding(mob)) {
			return true;
		}

		if (AnimalAggroSystem.isAggroing(mob)) {
			return true;
		}

		if (AnimalFightBackSystem.isFightBacking(mob)) {
			return true;
		}

		if (AnimalDefenderSystem.isDefending(mob)) {
			return true;
		}

		if (WolfBoneTameSystem.isActive(mob)) {
			return true;
		}

		if (mob instanceof PathfinderMob pathfinderMob) {
			if (AnimalStayOnLandSystem.isEscapingWater(pathfinderMob)) {
				return true;
			}

			if (AnimalWaterFleeEscapeSystem.isActive(pathfinderMob)) {
				return true;
			}

			if (AnimalHerdSystem.isActivelyHerdMoving(pathfinderMob)) {
				return true;
			}
		}

		if (mob instanceof Animal animal && AnimalFoodLureSystem.isFollowingFood(animal)) {
			return true;
		}

		if (mob instanceof Bat) {
			return AnimalFleeSystem.isFleeing(mob);
		}

		return false;
	}

	/** True when any overlay session is active (including idle states that still suppress vanilla). */
	public static boolean isActive(Mob mob) {
		if (isTryingToControl(mob)) {
			return true;
		}

		if (mob instanceof Animal animal) {
			return AnimalFoodLureSystem.isFollowingFood(animal);
		}

		return false;
	}

	public static void release(Mob mob) {
		if (mob instanceof PathfinderMob pathfinderMob) {
			AnimalFleeSystem.stopFleeing(pathfinderMob);
			AnimalFoodLureSystem.release(pathfinderMob);
			AnimalHerdSystem.releaseHerd(pathfinderMob);
			AnimalStayOnLandSystem.release(pathfinderMob);
			AnimalWaterFleeEscapeSystem.release(pathfinderMob);
		} else if (mob instanceof Bat bat) {
			AnimalFleeSystem.releaseBat(bat);
		}

		WolfBoneTameSystem.release(mob);
		AnimalAggroSystem.releaseOverlay(mob);
		AnimalFightBackSystem.stopFightBack(mob);
		AnimalDefenderSystem.releaseOverlay(mob);
	}

	/**
	 * For fed/tamed mobs: clear overlays once if needed without hammering navigation every tick.
	 */
	public static void releaseIfActive(Mob mob) {
		if (isActive(mob) || isTryingToControl(mob)) {
			release(mob);
		}
	}
}
