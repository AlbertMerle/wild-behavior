package com.wildbehavior.compat;

import com.wildbehavior.WildBehavior;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import dev.architectury.platform.Platform;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;

public final class ModCompat {
	public static final String ALEXSMOBS_MOD_ID = "alexsmobs";

	/** Alex's Mobs that stay disabled until explicitly toggled (bosses, bosses-adjacent, ambient, etc.). */
	private static final Set<String> ALEXSMOBS_DEFAULT_DISABLED = Set.of(
		"bone_serpent",
		"comb_jelly",
		"cosmic_cod",
		"cosmaw",
		"endergrade",
		"enderiophage",
		"farseer",
		"flutter",
		"froststalker",
		"guster",
		"laviathan",
		"mimicube",
		"murmur",
		"rocky_roller",
		"skelewag",
		"skreecher",
		"soul_vulture",
		"spectre",
		"straddler",
		"stradpole",
		"underminer",
		"void_worm",
		"warped_mosco",
		"warped_toad"
	);

	/** Alex's Mobs that use the aggressive-only preset when Wild Behavior is toggled on. */
	private static final Set<String> ALEXSMOBS_DEFAULT_AGGRESSIVE = Set.of(
		"alligator_snapping_turtle",
		"anaconda",
		"caiman",
		"cave_centipede",
		"crimson_mosquito",
		"crocodile",
		"dropbear",
		"frilled_shark",
		"giant_squid",
		"gorilla",
		"grizzly_bear",
		"hammerhead_shark",
		"komodo_dragon",
		"leafcutter_ant",
		"mantis_shrimp",
		"orca",
		"rattlesnake",
		"rhinoceros",
		"tarantula_hawk",
		"tasmanian_devil",
		"tiger",
		"tusklin",
		"snow_leopard"
	);

	/**
	 * Aggressive Alex presets that start {@code enabled: true} (most aggressive presets stay off).
	 * Crocs / alligator snapping turtle default WaterAggression (standalone) via sea-creature presets.
	 */
	private static final Set<String> ALEXSMOBS_DEFAULT_ENABLED_AGGRESSIVE = Set.of(
		"alligator_snapping_turtle",
		"caiman",
		"crocodile",
		"grizzly_bear"
	);

	/** Alex's Mobs enabled by default with PlayerFear only (normal AI until a player approaches). */
	private static final Set<String> ALEXSMOBS_DEFAULT_PLAYER_FEAR = Set.of(
		"bald_eagle",
		"anteater",
		"banana_slug",
		"bison",
		"blobfish",
		"blue_jay",
		"bunfungus",
		"cachalot_whale",
		"capuchin_monkey",
		"catfish",
		"cockroach",
		"crow",
		"devils_hole_pupfish",
		"elephant",
		"emu",
		"fly",
		"flying_fish",
		"gazelle",
		"gelada_monkey",
		"hummingbird",
		"jerboa",
		"kangaroo",
		"lobster",
		"maned_wolf",
		"mimic_octopus",
		"moose",
		"mudskipper",
		"mungus",
		"platypus",
		"potoo",
		"raccoon",
		"rain_frog",
		"roadrunner",
		"seagull",
		"seal",
		"shoebill",
		"skunk",
		"sugar_glider",
		"sunbird",
		"terrapin",
		"toucan",
		"triops"
	);

	/**
	 * Formerly used for default Herd on some Alex mobs. Kept empty — do not default Herd on
	 * any modded mobs for now (players can still enable Herd per-mob via command/Cloth).
	 */
	private static final Set<String> ALEXSMOBS_DEFAULT_HERD = Set.of();

	/**
	 * Alex's Mobs that also get fightback (retaliate when damaged).
	 * Stacks on Passive or Aggressive presets (Aggressive water-ambush crocs use this to
	 * retaliate when hit from land).
	 */
	private static final Set<String> ALEXSMOBS_DEFAULT_FIGHTBACK = Set.of(
		"alligator_snapping_turtle",
		"bison",
		"caiman",
		"capuchin_monkey",
		"cachalot_whale",
		"crocodile",
		"elephant",
		"kangaroo",
		"lobster",
		"moose",
		"raccoon",
		"skunk"
	);

	/**
	 * Alex's Mobs birds that should use an airborne flee target.
	 * Flightless birds (emu, roadrunner) are excluded — they flee on the ground.
	 */
	private static final Set<String> ALEXSMOBS_FLYING_BIRDS = Set.of(
		"bald_eagle",
		"blue_jay",
		"crow",
		"hummingbird",
		"potoo",
		"seagull",
		"shoebill",
		"soul_vulture",
		"sunbird",
		"toucan"
	);

	/**
	 * Alex's Mobs aquatic / semi-aquatic creatures (WaterAggression classification).
	 * Includes crocs and aggressive sharks/orcas as well as passive fish and whales.
	 */
	private static final Set<String> ALEXSMOBS_SEA_CREATURES = Set.of(
		"alligator_snapping_turtle",
		"anaconda",
		"blobfish",
		"cachalot_whale",
		"caiman",
		"catfish",
		"comb_jelly",
		"cosmic_cod",
		"crocodile",
		"devils_hole_pupfish",
		"flying_fish",
		"frilled_shark",
		"giant_squid",
		"hammerhead_shark",
		"laviathan",
		"lobster",
		"mantis_shrimp",
		"mimic_octopus",
		"mudskipper",
		"orca",
		"platypus",
		"seal",
		"skelewag",
		"stradpole",
		"terrapin",
		"triops"
	);

	private ModCompat() {
	}

	public static boolean isAlexsMobsLoaded() {
		return Platform.isModLoaded(ALEXSMOBS_MOD_ID);
	}

	public static boolean isAlexsMob(EntityType<?> type) {
		return ALEXSMOBS_MOD_ID.equals(BuiltInRegistries.ENTITY_TYPE.getKey(type).getNamespace());
	}

	public static boolean isAlexsFlyingBird(EntityType<?> type) {
		return isAlexsMob(type) && ALEXSMOBS_FLYING_BIRDS.contains(BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath());
	}

	/** True for Alex aquatic / semi-aquatic entity types (registry path match). */
	public static boolean isAlexsSeaCreature(EntityType<?> type) {
		return isAlexsMob(type)
			&& ALEXSMOBS_SEA_CREATURES.contains(BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath());
	}

	public static boolean isAlexsMobDefaultDisabled(EntityType<?> type) {
		if (!isAlexsMob(type)) {
			return false;
		}

		return ALEXSMOBS_DEFAULT_DISABLED.contains(BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath());
	}

	public static boolean isAlexsMobDefaultAggressive(EntityType<?> type) {
		if (!isAlexsMob(type)) {
			return false;
		}

		return ALEXSMOBS_DEFAULT_AGGRESSIVE.contains(BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath());
	}

	/** Aggressive Alex presets that start enabled (grizzly + crocs / snapping turtle). */
	public static boolean isAlexsMobDefaultEnabledAggressive(EntityType<?> type) {
		if (!isAlexsMob(type)) {
			return false;
		}

		return ALEXSMOBS_DEFAULT_ENABLED_AGGRESSIVE.contains(BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath());
	}

	/** True when this Alex type should start with Wild Behavior enabled (PlayerFear set or enabled-aggressive set). */
	public static boolean isAlexsMobDefaultEnabled(EntityType<?> type) {
		return isAlexsMobDefaultPlayerFear(type) || isAlexsMobDefaultEnabledAggressive(type);
	}

	public static boolean isAlexsMobDefaultPlayerFear(EntityType<?> type) {
		if (!isAlexsMob(type)) {
			return false;
		}

		return ALEXSMOBS_DEFAULT_PLAYER_FEAR.contains(BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath());
	}

	/** @deprecated use {@link #isAlexsMobDefaultPlayerFear} */
	@Deprecated
	public static boolean isAlexsMobDefaultPassivePlayerFear(EntityType<?> type) {
		return isAlexsMobDefaultPlayerFear(type);
	}

	/** Always false for now — Herd is not defaulted on any Alex/modded mob. */
	public static boolean isAlexsMobDefaultHerd(EntityType<?> type) {
		if (!isAlexsMob(type)) {
			return false;
		}

		return ALEXSMOBS_DEFAULT_HERD.contains(BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath());
	}

	public static boolean isAlexsMobDefaultFightback(EntityType<?> type) {
		if (!isAlexsMob(type)) {
			return false;
		}

		return ALEXSMOBS_DEFAULT_FIGHTBACK.contains(BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath());
	}

	public static List<EntityType<?>> alexsMobsEntityTypes() {
		List<EntityType<?>> types = new ArrayList<>();
		if (!isAlexsMobsLoaded()) {
			return types;
		}

		for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
			if (isAlexsMob(type)) {
				types.add(type);
			}
		}

		types.sort(Comparator.comparing(type -> BuiltInRegistries.ENTITY_TYPE.getKey(type).toString()));
		return types;
	}

	public static void logLoadedIntegrations() {
		if (isAlexsMobsLoaded()) {
			int count = alexsMobsEntityTypes().size();
			int presetCount = (int) alexsMobsEntityTypes().stream().filter(type -> !isAlexsMobDefaultDisabled(type)).count();
			int aggressiveByDefault = (int) alexsMobsEntityTypes().stream().filter(ModCompat::isAlexsMobDefaultAggressive).count();
			int aggressiveEnabledByDefault = (int) alexsMobsEntityTypes().stream().filter(ModCompat::isAlexsMobDefaultEnabledAggressive).count();
			int playerFearByDefault = (int) alexsMobsEntityTypes().stream().filter(ModCompat::isAlexsMobDefaultPlayerFear).count();
			WildBehavior.LOGGER.info(
				"Alex's Mobs detected ({} entity types, {} with presets, {} aggressive ({} enabled-by-default), {} playerFear enabled-by-default). Excluded from presets: {}. Herd is not defaulted on modded mobs.",
				count,
				presetCount,
				aggressiveByDefault,
				aggressiveEnabledByDefault,
				playerFearByDefault,
				ALEXSMOBS_DEFAULT_DISABLED.size()
			);
		}
	}
}
