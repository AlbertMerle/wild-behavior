package com.wildbehavior.ai;

import com.wildbehavior.config.AnimalBehaviorConfig;
import java.util.EnumSet;
import java.util.List;
import net.minecraft.world.entity.EntityType;

public final class MobBehaviorConfig {
	private MobBehaviorConfig() {
	}

	public static boolean isEnabled(EntityType<?> type, MobBehavior behavior) {
		return AnimalBehaviorConfig.isBehaviorEnabled(type, behavior);
	}

	public static void setEnabled(EntityType<?> type, MobBehavior behavior, boolean enabled) {
		AnimalBehaviorConfig.setBehaviorEnabled(type, behavior, enabled);
	}

	public static EnumSet<MobBehavior> getBehaviors(EntityType<?> type) {
		return AnimalBehaviorConfig.getBehaviors(type);
	}

	public static void reset(EntityType<?> type) {
		AnimalBehaviorConfig.resetBehavior(type);
	}

	public static void resetAll() {
		AnimalBehaviorConfig.resetAllBehaviors();
	}

	public static boolean isModEnabled(EntityType<?> type) {
		return AnimalBehaviorConfig.isModEnabled(type);
	}

	public static boolean toggleMod(EntityType<?> type) {
		return AnimalBehaviorConfig.toggleModEnabled(type);
	}

	public static int applyAggressiveOnlyToAllMobs() {
		return AnimalBehaviorConfig.applyAggressiveOnlyToAllMobs();
	}

	public static boolean isOptionalMob(EntityType<?> type) {
		return AnimalCategories.isOptionalMob(type);
	}

	public static boolean isManaged(EntityType<?> type) {
		return AnimalCategories.isManaged(type);
	}

	public static boolean supportsBehavior(EntityType<?> type, MobBehavior behavior) {
		return AnimalCategories.isCommandTarget(type);
	}

	public static boolean isPlayerPassiveFearEnabled(java.util.UUID playerId) {
		return com.wildbehavior.config.AnimalBehaviorConfig.isPlayerPassiveFearEnabled(playerId);
	}

	public static void setPlayerPassiveFear(java.util.UUID playerId, String name, boolean enabled) {
		com.wildbehavior.config.AnimalBehaviorConfig.setPlayerPassiveFear(playerId, name, enabled);
	}

	public static int getConfidence(EntityType<?> type) {
		return AnimalBehaviorConfig.getConfidence(type);
	}

	public static void setConfidence(EntityType<?> type, int confidence) {
		AnimalBehaviorConfig.setConfidence(type, confidence);
	}

	public static int getMemorySeconds(EntityType<?> type) {
		return AnimalBehaviorConfig.getMemorySeconds(type);
	}

	public static void setMemorySeconds(EntityType<?> type, int memorySeconds) {
		AnimalBehaviorConfig.setMemorySeconds(type, memorySeconds);
	}

	public static boolean supportsFlightFlee(EntityType<?> type) {
		return AnimalBehaviorConfig.supportsFlightFlee(type);
	}

	/** Runtime: whether FlightFlee min/max distances are used for this type when fleeing. */
	public static boolean usesFlightFleeDistances(EntityType<?> type) {
		return AnimalBehaviorConfig.usesFlightFleeDistances(type);
	}

	public static int getFlightFleeMin(EntityType<?> type) {
		return AnimalBehaviorConfig.getFlightFleeMin(type);
	}

	public static int getFlightFleeMax(EntityType<?> type) {
		return AnimalBehaviorConfig.getFlightFleeMax(type);
	}

	public static void setFlightFlee(EntityType<?> type, int min, int max) {
		AnimalBehaviorConfig.setFlightFlee(type, min, max);
	}

	public static int getAggressiveKillTimerSeconds(EntityType<?> type) {
		return AnimalBehaviorConfig.getAggressiveKillTimerSeconds(type);
	}

	public static void setAggressiveKillTimerSeconds(EntityType<?> type, int seconds) {
		AnimalBehaviorConfig.setAggressiveKillTimerSeconds(type, seconds);
	}

	public static boolean supportsFleeSpeed(EntityType<?> type) {
		return AnimalBehaviorConfig.supportsFleeSpeed(type);
	}

	public static double getFleeSpeedBps(EntityType<?> type) {
		return AnimalBehaviorConfig.getFleeSpeedBps(type);
	}

	public static void setFleeSpeedBps(EntityType<?> type, double speedBps) {
		AnimalBehaviorConfig.setFleeSpeedBps(type, speedBps);
	}

	public static boolean supportsMeleeDamage(EntityType<?> type) {
		return AnimalBehaviorConfig.supportsMeleeDamage(type);
	}

	public static float getMeleeDamage(EntityType<?> type) {
		return AnimalBehaviorConfig.getMeleeDamage(type);
	}

	public static float getEffectiveMeleeDamage(EntityType<?> type, float defaultDamage) {
		float configured = AnimalBehaviorConfig.getMeleeDamage(type);
		return configured > 0.0F ? configured : defaultDamage;
	}

	public static void setMeleeDamage(EntityType<?> type, float damage) {
		AnimalBehaviorConfig.setMeleeDamage(type, damage);
	}

	public static boolean supportsHerdSpeed(EntityType<?> type) {
		return AnimalBehaviorConfig.supportsHerdSpeed(type);
	}

	public static boolean usesVanillaHerdSpeed(EntityType<?> type) {
		return AnimalBehaviorConfig.usesVanillaHerdSpeed(type);
	}

	public static double getHerdSpeedBps(EntityType<?> type) {
		return AnimalBehaviorConfig.getHerdSpeedBps(type);
	}

	public static void setHerdSpeedBps(EntityType<?> type, double speedBps) {
		AnimalBehaviorConfig.setHerdSpeedBps(type, speedBps);
	}

	public static boolean supportsFightBack(EntityType<?> type) {
		return AnimalBehaviorConfig.supportsFightBack(type);
	}

	public static boolean isFightBackEnabled(EntityType<?> type) {
		return AnimalBehaviorConfig.isFightBackEnabled(type);
	}

	public static boolean canFightBack(EntityType<?> type) {
		return AnimalBehaviorConfig.canFightBack(type);
	}

	public static void setFightBackEnabled(EntityType<?> type, boolean enabled) {
		AnimalBehaviorConfig.setFightBackEnabled(type, enabled);
	}

	public static int getFightBackHelp(EntityType<?> type) {
		return AnimalBehaviorConfig.getFightBackHelp(type);
	}

	public static void setFightBackHelp(EntityType<?> type, int helpCount) {
		AnimalBehaviorConfig.setFightBackHelp(type, helpCount);
	}

	public static boolean isWaterAggression(EntityType<?> type) {
		return isEnabled(type, MobBehavior.WATER_AGGRESSION);
	}

	public static void setWaterAggression(EntityType<?> type, boolean enabled) {
		setEnabled(type, MobBehavior.WATER_AGGRESSION, enabled);
	}

	/** True when this mob uses the Aggressive overlay (land or water-only). */
	public static boolean isAggroBehavior(EntityType<?> type) {
		return isEnabled(type, MobBehavior.AGGRESSIVE) || isEnabled(type, MobBehavior.WATER_AGGRESSION);
	}

	public static List<EntityType<?>> managedTypes() {
		return AnimalCategories.managedTypes();
	}
}
