package com.wildbehavior.ai;

import com.wildbehavior.config.AnimalBehaviorConfig;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

public final class ThreatApproachDetector {
	private ThreatApproachDetector() {
	}

	/** Aggressive-tagged AgeableMob babies flee instead of attacking. */
	public static boolean isAggressiveBaby(LivingEntity animal) {
		return animal instanceof AgeableMob ageable
			&& ageable.isBaby()
			&& MobBehaviorConfig.isAggroBehavior(animal.getType());
	}

	public static boolean shouldFleeFromPlayers(LivingEntity animal) {
		if (EntityPlayerTrust.bypassesPlayerFear(animal)) {
			return false;
		}

		if (MobBehaviorConfig.isEnabled(animal.getType(), MobBehavior.PLAYER_FEAR)) {
			return true;
		}

		if (MobBehaviorConfig.isAggroBehavior(animal.getType())) {
			return isAggressiveBaby(animal) || AnimalHerdSystem.isBelowConfidenceThreshold(animal);
		}

		return false;
	}

	public static boolean shouldFleeFromAggressive(LivingEntity animal) {
		return MobBehaviorConfig.isEnabled(animal.getType(), MobBehavior.PASSIVE);
	}

	public static boolean shouldAggroPlayer(LivingEntity animal) {
		if (EntityPlayerTrust.bypassesAggressive(animal)) {
			return false;
		}

		if (!MobBehaviorConfig.isAggroBehavior(animal.getType())) {
			return false;
		}

		if (isAggressiveBaby(animal)) {
			return false;
		}

		if (AnimalHerdSystem.isBelowConfidenceThreshold(animal)) {
			return false;
		}

		return true;
	}

	public static boolean shouldFleeFromPassiveFearSources(LivingEntity animal) {
		return MobBehaviorConfig.isEnabled(animal.getType(), MobBehavior.PASSIVE);
	}

	/** Aggressive babies flee from Passive-tagged mobs (not from their own species / parents). */
	public static boolean shouldFleeFromPassivePrey(LivingEntity animal) {
		return isAggressiveBaby(animal);
	}

	public static boolean shouldAggroPassivePrey(LivingEntity animal) {
		if (!MobBehaviorConfig.isAggroBehavior(animal.getType())) {
			return false;
		}

		return !isAggressiveBaby(animal);
	}

	public static boolean isPassivePrey(LivingEntity target) {
		if (!target.isAlive() || target.isSpectator()) {
			return false;
		}

		if (!MobBehaviorConfig.isModEnabled(target.getType())) {
			return false;
		}

		if (!AnimalCategories.isLivingMobType(target.getType())) {
			return false;
		}

		return MobBehaviorConfig.isEnabled(target.getType(), MobBehavior.PASSIVE);
	}

	/**
	 * Passive prey an Aggressive mob may hunt. WaterAggression mobs may hunt any passive prey in water.
	 */
	public static boolean isValidAggressivePrey(LivingEntity predator, LivingEntity prey) {
		if (!isPassivePrey(prey)) {
			return false;
		}

		if (MobBehaviorConfig.isEnabled(predator.getType(), MobBehavior.WATER_AGGRESSION)) {
			return prey.isInWater();
		}

		return !AnimalCategories.isAquaticPrey(prey);
	}

	/** Passive prey a baby aggressive flees from — never same species (parents / packmates). */
	public static boolean isPassivePreyThreat(LivingEntity animal, LivingEntity threat) {
		if (threat == animal || threat.getType() == animal.getType()) {
			return false;
		}

		return isPassivePrey(threat);
	}

	public static boolean isPassiveFearSource(LivingEntity threat) {
		if (!threat.isAlive() || threat.isSpectator()) {
			return false;
		}

		if (threat instanceof Player player) {
			return PlayerApproachDetector.isPassiveFearSource(player);
		}

		return AnimalBehaviorConfig.isPassiveFearSource(threat.getType());
	}

	public static boolean shouldFleeFromPlayer(LivingEntity animal, Player player) {
		if (MobBehaviorConfig.isEnabled(animal.getType(), MobBehavior.PLAYER_FEAR)) {
			return PlayerApproachDetector.shouldFleeFromPlayerFear(animal, player);
		}

		// Villagers use PlayerFear only; Passive controls fleeing from aggressive mobs.
		if (animal.getType() == EntityTypes.VILLAGER) {
			return false;
		}

		if (MobBehaviorConfig.isEnabled(animal.getType(), MobBehavior.PASSIVE)) {
			return PlayerApproachDetector.shouldFleeFrom(animal, player);
		}

		if (MobBehaviorConfig.isAggroBehavior(animal.getType())
			&& (isAggressiveBaby(animal) || AnimalHerdSystem.isBelowConfidenceThreshold(animal))) {
			return PlayerApproachDetector.shouldFleeFromPlayerFear(animal, player);
		}

		return false;
	}

	/**
	 * Whether this animal flees from the player ignoring detect range (for mid-flee / chain spook).
	 * Still respects PlayerFear / PassiveFear / trust rules.
	 */
	public static boolean canFleeFromPlayerIgnoringRange(LivingEntity animal, Player player) {
		if (PlayerApproachDetector.shouldIgnorePlayerThreat(animal, player)) {
			return false;
		}

		if (MobBehaviorConfig.isEnabled(animal.getType(), MobBehavior.PLAYER_FEAR)) {
			return true;
		}

		if (animal.getType() == EntityTypes.VILLAGER) {
			return false;
		}

		if (MobBehaviorConfig.isEnabled(animal.getType(), MobBehavior.PASSIVE)
			&& PlayerApproachDetector.isPassiveFearSource(player)) {
			return true;
		}

		return MobBehaviorConfig.isAggroBehavior(animal.getType())
			&& (isAggressiveBaby(animal) || AnimalHerdSystem.isBelowConfidenceThreshold(animal));
	}

	@Nullable
	public static Player findNearestPlayerFleeThreat(LivingEntity animal) {
		if (!shouldFleeFromPlayers(animal)) {
			return null;
		}

		return PlayerApproachDetector.findNearestPlayerFearPlayer(animal, AnimalBehaviorConfig.normalDetectRange());
	}

	@Nullable
	public static LivingEntity findNearestFleeThreat(LivingEntity animal) {
		if (!shouldFleeFromPlayers(animal)
			&& !shouldFleeFromAggressive(animal)
			&& !shouldFleeFromPassiveFearSources(animal)
			&& !shouldFleeFromPassivePrey(animal)) {
			return null;
		}

		LivingEntity nearest = null;
		double nearestDistanceSqr = Double.MAX_VALUE;
		float playerSearchRadius = AnimalBehaviorConfig.normalDetectRange();
		float aggressiveSearchRadius = AnimalBehaviorConfig.aggressiveAlertRange();
		float searchRadius = Math.max(playerSearchRadius, aggressiveSearchRadius);
		double playerRadiusSqr = playerSearchRadius * playerSearchRadius;
		double aggressiveRadiusSqr = aggressiveSearchRadius * aggressiveSearchRadius;

		for (LivingEntity threat : animal.level().getEntitiesOfClass(LivingEntity.class, animal.getBoundingBox().inflate(searchRadius))) {
			if (threat == animal) {
				continue;
			}

			double distanceSqr = threat.distanceToSqr(animal);
			double maxRadiusSqr;

			if (threat instanceof Player player) {
				// Passive / PlayerFear / low-confidence Aggressive / aggressive babies: must see the player to start fleeing.
				if (!shouldFleeFromPlayer(animal, player) || !PlayerApproachDetector.canSeePlayer(animal, player)) {
					continue;
				}

				maxRadiusSqr = playerRadiusSqr;
			} else if (shouldFleeFromPassivePrey(animal) && isPassivePreyThreat(animal, threat)) {
				// Aggressive babies flee from Passive mobs; same-species (parents) already filtered out.
				maxRadiusSqr = playerRadiusSqr;
			} else if (shouldFleeFromAggressive(animal)) {
				if (!isPassiveMobThreat(animal, threat)) {
					continue;
				}

				maxRadiusSqr = aggressiveRadiusSqr;
			} else if (shouldFleeFromPassiveFearSources(animal)) {
				if (!isPassiveFearSource(threat)) {
					continue;
				}

				maxRadiusSqr = playerRadiusSqr;
			} else {
				continue;
			}

			if (distanceSqr > maxRadiusSqr || distanceSqr >= nearestDistanceSqr) {
				continue;
			}

			nearest = threat;
			nearestDistanceSqr = distanceSqr;
		}

		return nearest;
	}

	@Nullable
	public static LivingEntity findNearestDefenderTarget(LivingEntity defender) {
		if (!MobBehaviorConfig.isEnabled(defender.getType(), MobBehavior.DEFENDER)) {
			return null;
		}

		LivingEntity nearest = null;
		double nearestDistanceSqr = Double.MAX_VALUE;
		float searchRadius = AnimalBehaviorConfig.normalDetectRange();
		double searchRadiusSqr = searchRadius * searchRadius;

		for (LivingEntity threat : defender.level().getEntitiesOfClass(LivingEntity.class, defender.getBoundingBox().inflate(searchRadius))) {
			if (threat == defender || !isAggressiveThreat(threat)) {
				continue;
			}

			double distanceSqr = threat.distanceToSqr(defender);
			if (distanceSqr > searchRadiusSqr || distanceSqr >= nearestDistanceSqr) {
				continue;
			}

			nearest = threat;
			nearestDistanceSqr = distanceSqr;
		}

		return nearest;
	}

	@Nullable
	public static LivingEntity findNearestDefender(LivingEntity animal) {
		if (!shouldAggroPassivePrey(animal)) {
			return null;
		}

		LivingEntity nearest = null;
		double nearestDistanceSqr = Double.MAX_VALUE;
		float searchRadius = AnimalBehaviorConfig.normalDetectRange();
		double searchRadiusSqr = searchRadius * searchRadius;

		for (LivingEntity defender : animal.level().getEntitiesOfClass(LivingEntity.class, animal.getBoundingBox().inflate(searchRadius))) {
			if (defender == animal || !MobBehaviorConfig.isEnabled(defender.getType(), MobBehavior.DEFENDER)) {
				continue;
			}

			double distanceSqr = defender.distanceToSqr(animal);
			if (distanceSqr > searchRadiusSqr || distanceSqr >= nearestDistanceSqr) {
				continue;
			}

			nearest = defender;
			nearestDistanceSqr = distanceSqr;
		}

		return nearest;
	}

	public static boolean isDefender(LivingEntity entity) {
		return MobBehaviorConfig.isEnabled(entity.getType(), MobBehavior.DEFENDER);
	}

	@Nullable
	public static LivingEntity findNearestPassivePrey(LivingEntity animal) {
		if (!shouldAggroPassivePrey(animal)) {
			return null;
		}

		LivingEntity nearest = null;
		double nearestDistanceSqr = Double.MAX_VALUE;
		float searchRadius = AnimalBehaviorConfig.normalDetectRange();
		double threatRadiusSqr = searchRadius * searchRadius;

		for (LivingEntity prey : animal.level().getEntitiesOfClass(LivingEntity.class, animal.getBoundingBox().inflate(searchRadius))) {
			if (prey == animal || !isValidAggressivePrey(animal, prey)) {
				continue;
			}

			double distanceSqr = prey.distanceToSqr(animal);
			if (distanceSqr > threatRadiusSqr || distanceSqr >= nearestDistanceSqr) {
				continue;
			}

			nearest = prey;
			nearestDistanceSqr = distanceSqr;
		}

		return nearest;
	}

	public static boolean isAggressiveThreat(LivingEntity threat) {
		if (!threat.isAlive() || threat.isSpectator()) {
			return false;
		}

		return MobBehaviorConfig.isAggroBehavior(threat.getType());
	}

	static boolean isPassiveMobThreat(LivingEntity animal, LivingEntity threat) {
		if (isAggressiveThreat(threat)) {
			return true;
		}

		if (animal.getType() == EntityTypes.VILLAGER && shouldFleeFromAggressive(animal)) {
			return threat instanceof Enemy;
		}

		return false;
	}

	@Nullable
	public static LivingEntity findNearestAggroTarget(LivingEntity animal) {
		if (findNearestPlayerFleeThreat(animal) != null) {
			return null;
		}

		LivingEntity nearest = null;
		double nearestDistanceSqr = Double.MAX_VALUE;
		float searchRadius = AnimalBehaviorConfig.normalDetectRange();

		if (shouldAggroPlayer(animal)) {
			Player player = PlayerApproachDetector.findNearestAggroPlayer(animal, searchRadius);
			if (player != null) {
				double distanceSqr = player.distanceToSqr(animal);
				if (distanceSqr < nearestDistanceSqr) {
					nearest = player;
					nearestDistanceSqr = distanceSqr;
				}
			}
		}

		LivingEntity defender = findNearestDefender(animal);
		if (defender != null) {
			double distanceSqr = defender.distanceToSqr(animal);
			if (distanceSqr < nearestDistanceSqr) {
				nearest = defender;
				nearestDistanceSqr = distanceSqr;
			}
		}

		LivingEntity prey = findNearestPassivePrey(animal);
		if (prey != null) {
			double distanceSqr = prey.distanceToSqr(animal);
			if (distanceSqr < nearestDistanceSqr) {
				nearest = prey;
			}
		}

		return nearest;
	}

	@Nullable
	public static Player findNearestAggroPlayer(LivingEntity animal) {
		if (!shouldAggroPlayer(animal)) {
			return null;
		}

		return PlayerApproachDetector.findNearestAggroPlayer(animal, AnimalBehaviorConfig.normalDetectRange());
	}

	public static boolean canProcessFlee(LivingEntity animal) {
		return shouldFleeFromPlayers(animal)
			|| shouldFleeFromAggressive(animal)
			|| shouldFleeFromPassiveFearSources(animal)
			|| shouldFleeFromPassivePrey(animal)
			|| WeaponScareDetector.reactsToWeaponScare(animal);
	}
}
