package com.wildbehavior.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.entity.animal.fish.AbstractFish;

public final class AnimalCategories {
	private static final Set<EntityType<?>> MANAGED_TYPES = Set.of(
		EntityTypes.BAT,
		EntityTypes.BEE,
		EntityTypes.CAMEL,
		EntityTypes.CAT,
		EntityTypes.CHICKEN,
		EntityTypes.COD,
		EntityTypes.COW,
		EntityTypes.DOLPHIN,
		EntityTypes.DONKEY,
		EntityTypes.FOX,
		EntityTypes.GLOW_SQUID,
		EntityTypes.GOAT,
		EntityTypes.HORSE,
		EntityTypes.LLAMA,
		EntityTypes.MOOSHROOM,
		EntityTypes.MULE,
		EntityTypes.OCELOT,
		EntityTypes.PIG,
		EntityTypes.POLAR_BEAR,
		EntityTypes.PUFFERFISH,
		EntityTypes.RABBIT,
		EntityTypes.SALMON,
		EntityTypes.SHEEP,
		EntityTypes.SKELETON_HORSE,
		EntityTypes.SNIFFER,
		EntityTypes.SQUID,
		EntityTypes.TRADER_LLAMA,
		EntityTypes.TROPICAL_FISH,
		EntityTypes.TURTLE,
		EntityTypes.WOLF,
		EntityTypes.ZOMBIE_HORSE
	);

	private static final Set<EntityType<?>> GOLEM_TYPES = Set.of(
		EntityTypes.IRON_GOLEM,
		EntityTypes.SNOW_GOLEM
	);

	private static List<EntityType<?>> cachedAllTypes;

	private static final Set<EntityType<?>> AGGRESSIVE_PRESET_EXCLUDED = Set.of(
		EntityTypes.WARDEN,
		EntityTypes.ENDER_DRAGON,
		EntityTypes.ENDERMAN
	);

	/** Vanilla aquatic / amphibious mobs for WaterAggression defaults and classification. */
	private static final Set<EntityType<?>> VANILLA_SEA_CREATURES = Set.of(
		EntityTypes.AXOLOTL,
		EntityTypes.COD,
		EntityTypes.DOLPHIN,
		EntityTypes.DROWNED,
		EntityTypes.ELDER_GUARDIAN,
		EntityTypes.GLOW_SQUID,
		EntityTypes.GUARDIAN,
		EntityTypes.PUFFERFISH,
		EntityTypes.SALMON,
		EntityTypes.SQUID,
		EntityTypes.TADPOLE,
		EntityTypes.TROPICAL_FISH,
		EntityTypes.TURTLE
	);

	private AnimalCategories() {
	}

	/** True for vanilla aquatic mobs and Alex's Mobs sea creatures (incl. crocs). */
	public static boolean isSeaCreature(EntityType<?> type) {
		return VANILLA_SEA_CREATURES.contains(type) || com.wildbehavior.compat.ModCompat.isAlexsSeaCreature(type);
	}

	/**
	 * Fish, squid, and other aquatic prey that land Aggressive mobs should not hunt unless they
	 * have WaterAggression. Includes {@link AbstractFish} subclasses not listed in the sea set.
	 */
	public static boolean isAquaticPrey(LivingEntity entity) {
		return isSeaCreature(entity.getType()) || entity instanceof AbstractFish;
	}

	public static boolean isAquaticPrey(EntityType<?> type) {
		return isSeaCreature(type);
	}

	public static boolean isManaged(EntityType<?> type) {
		return MANAGED_TYPES.contains(type);
	}

	public static boolean isHostile(EntityType<?> type) {
		return type.getCategory() == MobCategory.MONSTER;
	}

	public static boolean isManaged(LivingEntity entity) {
		return isManaged(entity.getType()) || entity instanceof AbstractFish;
	}

	public static boolean isOptionalMob(EntityType<?> type) {
		return !isConfigSupported(type);
	}

	public static boolean receivesFleeBehavior(LivingEntity entity) {
		if (!MobBehaviorConfig.isModEnabled(entity.getType())) {
			return false;
		}

		EntityType<?> type = entity.getType();
		if (MobBehaviorConfig.isEnabled(type, MobBehavior.PLAYER_FEAR)
			|| MobBehaviorConfig.isEnabled(type, MobBehavior.PASSIVE)
			|| MobBehaviorConfig.isEnabled(type, MobBehavior.WEAPON_SCARE)) {
			return true;
		}

		if (!MobBehaviorConfig.isAggroBehavior(type)) {
			return false;
		}

		// Aggressive babies always flee; adults only when pack confidence is too low.
		return ThreatApproachDetector.isAggressiveBaby(entity)
			|| AnimalHerdSystem.isBelowConfidenceThreshold(entity);
	}

	public static boolean isGolem(EntityType<?> type) {
		return GOLEM_TYPES.contains(type);
	}

	public static boolean isGolem(LivingEntity entity) {
		return isGolem(entity.getType());
	}

	public static boolean isConfigSupported(EntityType<?> type) {
		return isManaged(type) || isGolem(type) || type == EntityTypes.VILLAGER;
	}

	/**
	 * True for any non-player living entity type (vanilla, Alex's Mobs, or other mods).
	 * Used by runtime AI and complete JSON generation.
	 * <p>
	 * In 26.2 {@link EntityType#getBaseClass()} always returns {@code Entity.class}, so living
	 * types are detected via {@link DefaultAttributes#hasSupplier(EntityType)} plus a category
	 * fallback for loaders/mods that register living entities without attribute suppliers yet.
	 * Never use {@code getBaseClass()} for this check.
	 */
	public static boolean isLivingMobType(EntityType<?> type) {
		if (type == null || type == EntityTypes.PLAYER) {
			return false;
		}

		if (DefaultAttributes.hasSupplier(type)) {
			return true;
		}

		MobCategory category = type.getCategory();
		return category != MobCategory.MISC;
	}

	/**
	 * Commands and Cloth may configure <em>any</em> non-player entity type. Wild Behavior
	 * stores settings and overlays original AI when the entity is living/pathfinding and enabled;
	 * non-living types are accepted for config but are runtime no-ops.
	 */
	public static boolean isCommandTarget(EntityType<?> type) {
		return type != null && type != EntityTypes.PLAYER;
	}

	public static boolean isAnimal(LivingEntity entity) {
		return isManaged(entity);
	}

	public static List<EntityType<?>> managedTypes() {
		return allTypes();
	}

	public static List<EntityType<?>> allTypes() {
		if (cachedAllTypes == null) {
			List<EntityType<?>> types = new ArrayList<>(MANAGED_TYPES);
			types.add(EntityTypes.VILLAGER);
			types.add(EntityTypes.IRON_GOLEM);
			types.add(EntityTypes.SNOW_GOLEM);
			types.sort(Comparator.comparing(type -> BuiltInRegistries.ENTITY_TYPE.getKey(type).toString()));
			cachedAllTypes = Collections.unmodifiableList(types);
		}

		return cachedAllTypes;
	}

	/**
	 * Living mob types for suggestions, Cloth Config, and complete JSON.
	 * Rebuilt each call so modded types registered after Wild Behavior initializes are included.
	 * Explicit commands still accept any non-player entity via {@link #isCommandTarget}.
	 */
	public static List<EntityType<?>> commandTargets() {
		List<EntityType<?>> types = new ArrayList<>();
		for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
			if (isLivingMobType(type)) {
				types.add(type);
			}
		}

		types.sort(Comparator.comparing(type -> BuiltInRegistries.ENTITY_TYPE.getKey(type).toString()));
		return Collections.unmodifiableList(types);
	}

	public static boolean isAggressivePresetExcluded(EntityType<?> type) {
		return AGGRESSIVE_PRESET_EXCLUDED.contains(type);
	}
}
