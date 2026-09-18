package com.wildbehavior.ai;

import java.util.Set;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.equine.AbstractHorse;

/**
 * Llamas, trader llamas, and camels only breed when vanilla {@link AbstractHorse#isTamed()}
 * is true. Wild Behavior trust tags alone are not enough unless synced or bridged here.
 */
public final class EquineBreedingHelper {
	private static final Set<EntityType<?>> BREEDABLE_EQUINES = Set.of(
		EntityTypes.LLAMA,
		EntityTypes.TRADER_LLAMA,
		EntityTypes.CAMEL);

	private EquineBreedingHelper() {
	}

	public static boolean isManagedBreedableEquine(LivingEntity entity) {
		return entity instanceof AbstractHorse horse && isManagedBreedableEquine(horse);
	}

	public static boolean isManagedBreedableEquine(AbstractHorse horse) {
		return BREEDABLE_EQUINES.contains(horse.getType());
	}

	public static boolean hasWildBehaviorTrust(LivingEntity entity) {
		return entity.entityTags().contains(EntityPlayerTrust.TAMED_TAG)
			|| EntityPlayerTrust.wasFedByPlayer(entity);
	}

	public static void syncVanillaTame(LivingEntity entity, boolean tamed) {
		if (!(entity instanceof AbstractHorse horse) || !isManagedBreedableEquine(horse)) {
			return;
		}

		horse.setTamed(tamed);
	}
}
