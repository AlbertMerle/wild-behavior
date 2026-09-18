package com.wildbehavior.ai;

import com.wildbehavior.trust.OcelotTrustAccess;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.feline.Ocelot;
import net.minecraft.world.entity.animal.equine.AbstractHorse;

/**
 * Per-entity trust: fed, tamed, or trusting mobs use vanilla AI only (no Wild Behavior overlays).
 * Breeding food and grass eating use vanilla goals; players are safe.
 */
public final class EntityPlayerTrust {
	public static final String TAMED_TAG = "wild_behavior.tamed";
	public static final String FED_BY_PLAYER_TAG = "wild_behavior.fed_by_player";
	private static final String LEGACY_FED_BY_PLAYER_TAG = "animal_behavior.fed_by_player";

	private EntityPlayerTrust() {
	}

	public static void markFedByPlayer(LivingEntity entity) {
		markTamed(entity);
		if (entity instanceof Mob mob && !entity.level().isClientSide()) {
			WildBehaviorOverlay.release(mob);
		}
	}

	/** Marks this mob as tamed — all Wild Behavior overlays stop; vanilla AI only. */
	public static void markTamed(LivingEntity entity) {
		entity.addTag(TAMED_TAG);
		entity.addTag(FED_BY_PLAYER_TAG);
		entity.removeTag(LEGACY_FED_BY_PLAYER_TAG);
		EquineBreedingHelper.syncVanillaTame(entity, true);
	}

	public static void setTamed(LivingEntity entity, boolean tamed) {
		if (tamed) {
			markTamed(entity);
			if (entity instanceof Mob mob && !entity.level().isClientSide()) {
				WildBehaviorOverlay.release(mob);
			}
			return;
		}

		entity.removeTag(TAMED_TAG);
		entity.removeTag(FED_BY_PLAYER_TAG);
		entity.removeTag(LEGACY_FED_BY_PLAYER_TAG);
		EquineBreedingHelper.syncVanillaTame(entity, false);
	}

	/**
	 * Fed / tamed / trusting mobs skip every Wild Behavior overlay and use vanilla AI
	 * (tempt, breed, eat, wander). Players are treated as safe.
	 */
	public static boolean isTamed(LivingEntity entity) {
		if (entity.entityTags().contains(TAMED_TAG)) {
			return true;
		}

		if (wasFedByPlayer(entity)) {
			return true;
		}

		if (entity instanceof TamableAnimal tamable && tamable.isTame()) {
			return true;
		}

		if (entity instanceof AbstractHorse horse && horse.isTamed()) {
			return true;
		}

		return entity instanceof Ocelot ocelot && ((OcelotTrustAccess) ocelot).animalBehavior$isTrusting();
	}

	public static boolean wasFedByPlayer(LivingEntity entity) {
		if (entity.entityTags().contains(FED_BY_PLAYER_TAG)) {
			return true;
		}

		if (entity.entityTags().contains(LEGACY_FED_BY_PLAYER_TAG)) {
			entity.addTag(FED_BY_PLAYER_TAG);
			entity.removeTag(LEGACY_FED_BY_PLAYER_TAG);
			return true;
		}

		return false;
	}

	/**
	 * Whether feeding / breeding this mob should grant the permanent fed / tamed state.
	 * Includes managed vanilla animals and enabled optional/Alex mobs.
	 */
	public static boolean shouldTrackFeeding(LivingEntity entity) {
		if (AnimalCategories.isManaged(entity)) {
			return true;
		}

		return AnimalCategories.isOptionalMob(entity.getType())
			&& MobBehaviorConfig.isModEnabled(entity.getType());
	}

	/** Fed/tamed mobs skip the entire Wild Behavior dispatcher overlay stack. */
	public static boolean bypassesAllWildBehavior(LivingEntity entity) {
		return isTamed(entity);
	}

	/** Tamed mobs do not flee from players (PlayerFear bypass). */
	public static boolean bypassesPlayerFear(LivingEntity entity) {
		return isTamed(entity);
	}

	/** Tamed mobs do not herd-wander with Wild Behavior. */
	public static boolean bypassesHerd(LivingEntity entity) {
		return isTamed(entity);
	}

	/** Tamed mobs do not Aggressive-attack players. */
	public static boolean bypassesAggressive(LivingEntity entity) {
		return isTamed(entity);
	}

	/** Tamed mobs skip Wild Behavior fight-back overlay (vanilla retaliation when applicable). */
	public static boolean bypassesFightBack(LivingEntity entity) {
		return isTamed(entity);
	}

	/** Tamed mobs use vanilla grass eating. */
	public static boolean usesVanillaEating(LivingEntity entity) {
		return isTamed(entity);
	}
}
