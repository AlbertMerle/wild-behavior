package com.wildbehavior.ai;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.animal.equine.AbstractHorse;

/**
 * Gates vanilla grass eating ({@link net.minecraft.world.entity.ai.goal.EatBlockGoal},
 * {@link AbstractHorse#canEatGrass()}) so mobs do not eat while fleeing, aggressive,
 * or herd walking — only during herd grazing rest.
 */
public final class AnimalEatingGate {
	private AnimalEatingGate() {
	}

	public static boolean shouldAllowGrassEating(Mob mob) {
		if (mob.level().isClientSide() || !MobBehaviorConfig.isModEnabled(mob.getType())) {
			return true;
		}

		if (EntityPlayerTrust.usesVanillaEating(mob) || !NearbyPlayerAiGate.isNearPlayer(mob)) {
			return true;
		}

		if (AnimalStuckRecoverySystem.isInCooldown(mob)) {
			return true;
		}

		if (AnimalFleeSystem.isFleeing(mob)
			|| AnimalFoodLureSystem.isFollowingFood(mob)
			|| AnimalAggroSystem.isAggroing(mob)
			|| AnimalFightBackSystem.isFightBacking(mob)
			|| AnimalDefenderSystem.isDefending(mob)
			|| AnimalStayOnLandSystem.isEscapingWater(mob)
			|| AnimalWaterFleeEscapeSystem.isActive(mob)) {
			return false;
		}

		if (mob instanceof PathfinderMob pathfinderMob && AnimalHerdSystem.usesHerdEatingSchedule(pathfinderMob)) {
			return AnimalHerdSystem.isGrazingRest(pathfinderMob);
		}

		return true;
	}

	/** Stops in-progress horse eating when Wild Behavior suppresses grazing. */
	public static void enforceEatingSuppression(Mob mob) {
		if (shouldAllowGrassEating(mob)) {
			return;
		}

		if (mob instanceof AbstractHorse horse && horse.isEating()) {
			horse.setEating(false);
		}
	}
}
