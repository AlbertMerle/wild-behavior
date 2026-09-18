package com.wildbehavior.config;

import com.wildbehavior.ai.AnimalCategories;
import com.wildbehavior.ai.MobBehavior;
import com.wildbehavior.compat.ModCompat;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import dev.architectury.platform.Platform;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;

public final class AnimalBehaviorConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static AnimalBehaviorConfig instance = createDefault();
	private static Path configPath;

	public DetectionSettings detection = new DetectionSettings();
	public FleeSettings flee = new FleeSettings();
	public WeaponScareSettings weaponScare = new WeaponScareSettings();
	public HerdSettings herd = new HerdSettings();
	public SpecialSettings specialSettings = new SpecialSettings();
	public int schemaVersion = 1;
	public Map<String, MobBehaviorEntry> mobBehaviors = new LinkedHashMap<>();
	public Map<String, PlayerBehaviorEntry> playerBehaviors = new LinkedHashMap<>();

	public static void load() {
		configPath = Platform.getConfigFolder().resolve("wild-behavior.json");
		migrateLegacyConfigFile(configPath);
		if (!Files.exists(configPath)) {
			instance = createDefault();
			ensureCompleteMobEntries(instance);
			save();
			return;
		}

		try (Reader reader = Files.newBufferedReader(configPath)) {
			AnimalBehaviorConfig loaded = GSON.fromJson(reader, AnimalBehaviorConfig.class);
			instance = loaded != null ? loaded : createDefault();
			ensureDefaults(instance);
			if (ensureCompleteMobEntries(instance)) {
				save();
			}
		} catch (IOException exception) {
			instance = createDefault();
			ensureCompleteMobEntries(instance);
			save();
		}
	}

	public static void save() {
		if (configPath == null) {
			configPath = Platform.getConfigFolder().resolve("wild-behavior.json");
		}

		try {
			Files.createDirectories(configPath.getParent());
			try (Writer writer = Files.newBufferedWriter(configPath)) {
				GSON.toJson(instance, writer);
			}
		} catch (IOException exception) {
			throw new RuntimeException("Failed to save wild-behavior.json", exception);
		}
	}

	/** Renames legacy {@code animal-behavior.json} to {@code wild-behavior.json} once. */
	private static void migrateLegacyConfigFile(Path newPath) {
		if (Files.exists(newPath)) {
			return;
		}

		Path legacyPath = newPath.resolveSibling("animal-behavior.json");
		if (!Files.exists(legacyPath)) {
			return;
		}

		try {
			Files.move(legacyPath, newPath);
		} catch (IOException exception) {
			throw new RuntimeException("Failed to migrate animal-behavior.json to wild-behavior.json", exception);
		}
	}

	public static AnimalBehaviorConfig get() {
		return instance;
	}

	public static Path getConfigPath() {
		return configPath;
	}

	/** Ensures a mutable config entry exists for the type (for GUI / commands). */
	public static MobBehaviorEntry getOrCreateEntry(EntityType<?> type) {
		return get().mobBehaviors.computeIfAbsent(key(type), ignored -> createEntryForType(type));
	}

	public static void setModEnabled(EntityType<?> type, boolean enabled) {
		getOrCreateEntry(type).enabled = enabled;
	}

	/** Persist after GUI edits; keeps aggressive→passivefear invariant. */
	public static void saveFromGui() {
		get().mobBehaviors.values().forEach(AnimalBehaviorConfig::enforceAggressivePassiveFear);
		save();
	}

	/**
	 * When Alex's Mobs is loaded, write default presets for its mobs except
	 * {@link ModCompat#isAlexsMobDefaultDisabled} types.
	 * {@link ModCompat#isAlexsMobDefaultPlayerFear} types are enabled with PlayerFear only
	 * (normal AI until a player approaches; flee speed / FlightFlee still apply).
	 * {@link ModCompat#isAlexsMobDefaultEnabledAggressive} types (grizzly / crocs) start enabled
	 * with Aggressive; other {@link ModCompat#isAlexsMobDefaultAggressive} presets stay
	 * {@code enabled: false} until toggled on.
	 * Only adds entries missing from config.
	 */
	public static boolean ensureAlexsMobsDefaults() {
		if (!ModCompat.isAlexsMobsLoaded()) {
			return false;
		}

		boolean changed = false;
		for (EntityType<?> type : ModCompat.alexsMobsEntityTypes()) {
			String mobKey = key(type);
			if (get().mobBehaviors.containsKey(mobKey) || ModCompat.isAlexsMobDefaultDisabled(type)) {
				continue;
			}

			MobBehaviorEntry entry = optionalEntry().copy();
			applyAlexsMobPreset(entry, type);
			entry.enabled = ModCompat.isAlexsMobDefaultEnabled(type);
			get().mobBehaviors.put(mobKey, entry);
			changed = true;
		}

		if (changed) {
			save();
		}

		return changed;
	}

	/**
	 * Writes an explicit entry for every mob shown by commands or Mod Menu.
	 *
	 * <p>Optional mobs are stored disabled by default, except Alex's Mobs PlayerFear presets and
	 * selected aggressive presets (grizzly / crocs) which start enabled. This makes
	 * {@code wild-behavior.json} a complete client/server-editable template without silently
	 * enabling Wild Behavior for every loaded hostile or third-party mob.
	 */
	private static boolean ensureCompleteMobEntries(AnimalBehaviorConfig config) {
		boolean changed = false;
		for (EntityType<?> type : AnimalCategories.commandTargets()) {
			String mobKey = key(type);
			if (config.mobBehaviors.containsKey(mobKey)) {
				continue;
			}

			MobBehaviorEntry entry;
			if (AnimalCategories.isConfigSupported(type)) {
				entry = createEntryForType(type);
			} else if (ModCompat.isAlexsMob(type) && !ModCompat.isAlexsMobDefaultDisabled(type)) {
				entry = optionalEntry().copy();
				applyAlexsMobPreset(entry, type);
				entry.enabled = ModCompat.isAlexsMobDefaultEnabled(type);
			} else {
				entry = createEntryForType(type);
				entry.enabled = false;
			}

			config.mobBehaviors.put(mobKey, entry);
			changed = true;
		}

		return changed;
	}

	/**
	 * Fills in config entries for entity types registered after initial {@link #load()} (e.g. Alex's Mobs).
	 */
	public static void refreshMobEntriesFromRegistry() {
		if (ensureCompleteMobEntries(get())) {
			save();
		}
	}

	public static boolean isBehaviorEnabled(EntityType<?> type, MobBehavior behavior) {
		if (!isModEnabled(type)) {
			return false;
		}

		MobBehaviorEntry entry = getEntry(type);

		if (behavior == MobBehavior.STAY_ON_LAND) {
			return entry.stayonland;
		}

		if (behavior == MobBehavior.PASSIVE_FEAR) {
			return entry.passivefear;
		}

		return entry.isEnabled(behavior);
	}

	/**
	 * Ensures a config entry exists and Wild Behavior is enabled for this mob.
	 * Any non-player entity type may receive settings; runtime overlays apply when the
	 * spawned entity is living/pathfinding. Used so vanilla, Alex's, and other modded mobs
	 * are never blocked from configuration.
	 */
	public static MobBehaviorEntry ensureConfigurableEntry(EntityType<?> type) {
		if (type == null || type == EntityTypes.PLAYER) {
			throw new IllegalArgumentException("Players cannot have Wild Behavior mob settings");
		}

		MobBehaviorEntry entry = get().mobBehaviors.computeIfAbsent(key(type), ignored -> createEntryForType(type));
		if (!entry.enabled) {
			entry.enabled = true;
		}

		return entry;
	}

	public static boolean isModEnabled(EntityType<?> type) {
		MobBehaviorEntry entry = get().mobBehaviors.get(key(type));
		if (AnimalCategories.isConfigSupported(type)) {
			return entry == null || entry.enabled;
		}

		return entry != null && entry.enabled;
	}

	public static boolean toggleModEnabled(EntityType<?> type) {
		boolean currentlyEnabled = isModEnabled(type);
		MobBehaviorEntry entry = get().mobBehaviors.computeIfAbsent(key(type), ignored -> createEntryForType(type));
		entry.enabled = !currentlyEnabled;
		if (entry.enabled && AnimalCategories.isOptionalMob(type)) {
			// Alex preset mobs already have defaults saved with enabled:false; keep them on toggle-on.
			// Excluded Alex types and other optional mobs still get the aggressive-only enable preset.
			if (!(ModCompat.isAlexsMob(type) && !ModCompat.isAlexsMobDefaultDisabled(type))) {
				applyAggroPresetForType(entry, type);
			}
		}
		save();
		return entry.enabled;
	}

	public static int applyAggressiveOnlyToAllMobs() {
		int enabledCount = 0;
		int disabledCount = 0;

		for (EntityType<?> type : AnimalCategories.commandTargets()) {
			if (AnimalCategories.isAggressivePresetExcluded(type) || ModCompat.isAlexsMobDefaultDisabled(type)) {
				MobBehaviorEntry entry = get().mobBehaviors.computeIfAbsent(key(type), ignored -> createEntryForType(type));
				entry.enabled = false;
				disabledCount++;
				continue;
			}

			MobBehaviorEntry entry = get().mobBehaviors.computeIfAbsent(key(type), ignored -> createEntryForType(type));
			applyAggroPresetForType(entry, type);
			entry.enabled = true;
			enabledCount++;
		}

		save();
		return enabledCount + disabledCount;
	}

	public static void applyAggressiveOnlyPreset(EntityType<?> type) {
		MobBehaviorEntry entry = get().mobBehaviors.computeIfAbsent(key(type), ignored -> createEntryForType(type));
		applyAggroPresetForType(entry, type);
	}

	private static void applyAggroPresetForType(MobBehaviorEntry entry, EntityType<?> type) {
		if (AnimalCategories.isSeaCreature(type)) {
			applyWaterAggressionOnlyPreset(entry);
		} else {
			applyAggressiveOnlyPreset(entry);
		}
	}

	private static void applyWaterAggressionOnlyPreset(MobBehaviorEntry entry) {
		entry.herd = false;
		entry.passive = false;
		entry.playerfear = false;
		entry.aggressive = false;
		entry.waterAggression = true;
		entry.passivefear = false;
		entry.weaponscare = false;
		entry.defender = false;
		entry.fightback = false;
		entry.confidence = 1;
		entry.memory = 6;
		entry.aggressivekilltimer = DEFAULT_AGGRESSIVE_KILL_TIMER;
		entry.speed = 0.0D;
		entry.herdspeed = 0.0D;
		entry.damage = 0.0F;
		enforceAggressivePassiveFear(entry);
	}

	private static void applyAggressiveOnlyPreset(MobBehaviorEntry entry) {
		entry.herd = false;
		entry.passive = false;
		entry.playerfear = false;
		entry.aggressive = true;
		entry.passivefear = false;
		entry.weaponscare = false;
		entry.defender = false;
		entry.fightback = false;
		entry.waterAggression = false;
		entry.confidence = 1;
		entry.memory = 6;
		entry.aggressivekilltimer = DEFAULT_AGGRESSIVE_KILL_TIMER;
		entry.speed = 0.0D;
		entry.herdspeed = 0.0D;
		entry.damage = 0.0F;
		enforceAggressivePassiveFear(entry);
	}

	private static void applyPassivePlayerFearPreset(MobBehaviorEntry entry) {
		entry.herd = false;
		entry.passive = true;
		entry.playerfear = true;
		entry.aggressive = false;
		entry.passivefear = false;
		entry.weaponscare = false;
		entry.defender = false;
		entry.fightback = false;
		entry.waterAggression = false;
		entry.confidence = 0;
		entry.memory = 6;
		entry.speed = 8.0D;
		entry.herdspeed = 0.0D;
		entry.damage = 0.0F;
	}

	/** PlayerFear only — natural mod AI until a player approaches; keeps flee speed / FlightFlee. */
	private static void applyPlayerFearOnlyPreset(MobBehaviorEntry entry) {
		entry.herd = false;
		entry.passive = false;
		entry.playerfear = true;
		entry.aggressive = false;
		entry.passivefear = false;
		entry.weaponscare = false;
		entry.defender = false;
		entry.fightback = false;
		entry.waterAggression = false;
		entry.confidence = 0;
		entry.memory = 6;
		entry.speed = 8.0D;
		entry.herdspeed = 0.0D;
		entry.damage = 0.0F;
	}

	private static void applyAlexsMobPreset(MobBehaviorEntry entry, EntityType<?> type) {
		boolean playerFearOnly = ModCompat.isAlexsMobDefaultPlayerFear(type);
		if (ModCompat.isAlexsMobDefaultAggressive(type)) {
			applyAggroPresetForType(entry, type);
		} else if (playerFearOnly) {
			applyPlayerFearOnlyPreset(entry);
		}

		// Do not default Herd on any modded (Alex) mobs — user can enable per-mob later.
		entry.herd = false;

		// Fightback stacks on aggressive (and blank) presets, not on PlayerFear-only defaults.
		if (!playerFearOnly && ModCompat.isAlexsMobDefaultFightback(type)) {
			entry.fightback = true;
			entry.fightbackhelp = DEFAULT_FIGHT_BACK_HELP;
		}

		// Disable stay-on-land for aquatic Alex's Mobs by default; land mobs keep true
		entry.stayonland = !AnimalCategories.isSeaCreature(type);
	}

	public static boolean isPassiveFearSource(EntityType<?> type) {
		return getEntry(type).passivefear;
	}

	public static void setBehaviorEnabled(EntityType<?> type, MobBehavior behavior, boolean enabled) {
		MobBehaviorEntry entry = ensureConfigurableEntry(type);

		if (behavior == MobBehavior.PASSIVE_FEAR) {
			entry.passivefear = enabled;
			save();
			return;
		}

		if (behavior == MobBehavior.STAY_ON_LAND) {
			entry.stayonland = enabled;
			save();
			return;
		}

		entry.setEnabled(behavior, enabled);
		if (behavior == MobBehavior.AGGRESSIVE && enabled) {
			entry.passive = false;
			entry.playerfear = false;
			entry.waterAggression = false;
		}
		if (behavior == MobBehavior.WATER_AGGRESSION && enabled) {
			entry.passive = false;
			entry.playerfear = false;
			entry.aggressive = false;
		}
		if (behavior == MobBehavior.PASSIVE && enabled) {
			entry.aggressive = false;
			entry.waterAggression = false;
		}
		enforceAggressivePassiveFear(entry);
		save();
	}

	public static int getConfidence(EntityType<?> type) {
		MobBehaviorEntry entry = getEntry(type);
		return entry.confidence > 0 ? entry.confidence : defaultConfidence(type);
	}

	public static void setConfidence(EntityType<?> type, int confidence) {
		if (confidence < 1) {
			throw new IllegalArgumentException("Confidence must be at least 1");
		}

		MobBehaviorEntry entry = ensureConfigurableEntry(type);

		if (!entry.aggressive && !entry.waterAggression) {
			throw new IllegalArgumentException("Confidence requires Aggressive or WaterAggression");
		}

		entry.confidence = confidence;
		save();
	}

	/**
	 * Seconds a mob keeps chasing or fleeing from a player after losing line of sight.
	 * 0 = forget immediately when LOS breaks.
	 */
	public static int getMemorySeconds(EntityType<?> type) {
		return Math.max(0, getEntry(type).memory);
	}

	public static int getMemoryTicks(EntityType<?> type) {
		return getMemorySeconds(type) * 20;
	}

	public static void setMemorySeconds(EntityType<?> type, int memorySeconds) {
		if (memorySeconds < 0) {
			throw new IllegalArgumentException("Memory must be 0 or greater");
		}

		MobBehaviorEntry entry = ensureConfigurableEntry(type);
		entry.memory = memorySeconds;
		save();
	}

	public static final int DEFAULT_FLEE_DISTANCE_MIN = 48;
	public static final int DEFAULT_FLEE_DISTANCE_MAX = 72;
	public static final int DEFAULT_FLIGHT_FLEE_MIN = 12;
	public static final int DEFAULT_FLIGHT_FLEE_MAX = 24;

	/**
	 * Any non-player entity may store FlightFlee values. Prefer this for commands / Cloth.
	 * Runtime still only <em>uses</em> those distances for airborne-style flee mobs — see
	 * {@link #usesFlightFleeDistances(EntityType)}.
	 */
	public static boolean supportsFlightFlee(EntityType<?> type) {
		return type != null && type != EntityTypes.PLAYER;
	}

	/**
	 * Types that actually use FlightFlee min/max at runtime when startled
	 * (bats, bees, Alex flying birds). Other mobs use global flee distance.
	 */
	public static boolean usesFlightFleeDistances(EntityType<?> type) {
		return type == EntityTypes.BAT || type == EntityTypes.BEE || ModCompat.isAlexsFlyingBird(type);
	}

	public static int getFlightFleeMin(EntityType<?> type) {
		int min = getEntry(type).flightfleemin;
		return min > 0 ? min : DEFAULT_FLIGHT_FLEE_MIN;
	}

	public static int getFlightFleeMax(EntityType<?> type) {
		int max = getEntry(type).flightfleemax;
		int min = getFlightFleeMin(type);
		if (max < min) {
			return Math.max(min, DEFAULT_FLIGHT_FLEE_MAX);
		}

		return max > 0 ? max : Math.max(min, DEFAULT_FLIGHT_FLEE_MAX);
	}

	public static void setFlightFlee(EntityType<?> type, int min, int max) {
		if (min < 1 || max < min) {
			throw new IllegalArgumentException("FlightFlee min must be >= 1 and max must be >= min");
		}

		MobBehaviorEntry entry = ensureConfigurableEntry(type);
		entry.flightfleemin = min;
		entry.flightfleemax = max;
		save();
	}

	public static final int DEFAULT_AGGRESSIVE_KILL_TIMER = 60;
	public static final int DEFAULT_FIGHT_BACK_HELP = 2;

	/**
	 * Seconds after an Aggressive kill before the mob may acquire another attack target.
	 * 0 = no cooldown.
	 */
	public static int getAggressiveKillTimerSeconds(EntityType<?> type) {
		return Math.max(0, getEntry(type).aggressivekilltimer);
	}

	public static int getAggressiveKillTimerTicks(EntityType<?> type) {
		return getAggressiveKillTimerSeconds(type) * 20;
	}

	public static void setAggressiveKillTimerSeconds(EntityType<?> type, int seconds) {
		if (seconds < 0) {
			throw new IllegalArgumentException("AggressiveKillTimer must be 0 or greater");
		}

		MobBehaviorEntry entry = ensureConfigurableEntry(type);
		entry.aggressivekilltimer = seconds;
		save();
	}

	private static int defaultConfidence(EntityType<?> type) {
		if (type == EntityTypes.WOLF) {
			return 2;
		}

		return 1;
	}

	public static boolean isPlayerPassiveFearEnabled(UUID playerId) {
		PlayerBehaviorEntry entry = get().playerBehaviors.get(playerId.toString());
		return entry == null || entry.passivefear;
	}

	public static void setPlayerPassiveFear(UUID playerId, String name, boolean enabled) {
		PlayerBehaviorEntry entry = get().playerBehaviors.computeIfAbsent(playerId.toString(), ignored -> new PlayerBehaviorEntry());
		entry.name = name;
		entry.passivefear = enabled;
		save();
	}

	public static EnumSet<MobBehavior> getBehaviors(EntityType<?> type) {
		MobBehaviorEntry entry = getEntry(type);
		EnumSet<MobBehavior> behaviors = entry.toEnumSet();

		if (entry.stayonland) {
			behaviors.add(MobBehavior.STAY_ON_LAND);
		} else {
			behaviors.remove(MobBehavior.STAY_ON_LAND);
		}

		return behaviors;
	}

	public static void resetBehavior(EntityType<?> type) {
		MobBehaviorEntry entry;
		if (AnimalCategories.isManaged(type)) {
			entry = defaultEntry(type).copy();
		} else if (AnimalCategories.isGolem(type)) {
			entry = golemEntry().copy();
		} else if (type == EntityTypes.VILLAGER) {
			entry = villagerEntry().copy();
		} else if (AnimalCategories.isHostile(type)) {
			entry = defaultHostileEntry(type).copy();
		} else if (AnimalCategories.isOptionalMob(type)) {
			boolean wasEnabled = isModEnabled(type);
			entry = optionalEntry().copy();
			if (ModCompat.isAlexsMob(type) && !ModCompat.isAlexsMobDefaultDisabled(type)) {
				applyAlexsMobPreset(entry, type);
			}
			entry.enabled = wasEnabled;
			get().mobBehaviors.put(key(type), entry);
			save();
			return;
		} else {
			get().mobBehaviors.remove(key(type));
			save();
			return;
		}

		get().mobBehaviors.put(key(type), entry);

		save();
	}

	public static void resetAllBehaviors() {
		Map<String, PlayerBehaviorEntry> playerBehaviors = get().playerBehaviors;
		instance = createDefault();
		instance.playerBehaviors = playerBehaviors != null ? new LinkedHashMap<>(playerBehaviors) : new LinkedHashMap<>();
		instance.schemaVersion = 1;
		save();
	}

	public static float normalDetectRange() {
		return get().detection.normalRange;
	}

	public static float sneakDetectRange() {
		return get().detection.sneakRange;
	}

	public static float aggressiveAlertRange() {
		return get().detection.aggressiveAlertRange;
	}

	public static int fleeDistanceMin() {
		return get().flee.distanceMin;
	}

	public static int fleeDistanceMax() {
		return get().flee.distanceMax;
	}

	public static int fleeChainRadius() {
		return get().flee.chainRadius;
	}

	public static int fleeRepathIntervalTicks() {
		return get().flee.repathIntervalTicks;
	}

	public static double defaultFleeSpeedBps() {
		return get().flee.defaultSpeedBps;
	}

	public static double getFleeSpeedBps(EntityType<?> type) {
		MobBehaviorEntry entry = getEntry(type);
		if (entry.speed > 0.0D) {
			return entry.speed;
		}

		return defaultFleeSpeedBps();
	}

	public static void setFleeSpeedBps(EntityType<?> type, double speedBps) {
		if (speedBps <= 0.0D) {
			throw new IllegalArgumentException("Flee speed must be greater than 0 blocks per second");
		}

		MobBehaviorEntry entry = ensureConfigurableEntry(type);
		entry.speed = speedBps;
		save();
	}

	public static float getMeleeDamage(EntityType<?> type) {
		return getEntry(type).damage;
	}

	public static void setMeleeDamage(EntityType<?> type, float damage) {
		if (damage <= 0.0F) {
			throw new IllegalArgumentException("Damage must be greater than 0");
		}

		MobBehaviorEntry entry = ensureConfigurableEntry(type);
		entry.damage = damage;
		save();
	}

	public static boolean supportsMeleeDamage(EntityType<?> type) {
		return type != null && type != EntityTypes.PLAYER;
	}

	public static boolean usesVanillaHerdSpeed(EntityType<?> type) {
		return getHerdSpeedBps(type) <= 0.0D;
	}

	public static boolean supportsHerdSpeed(EntityType<?> type) {
		return type != null && type != EntityTypes.PLAYER;
	}

	public static boolean isFightBackEnabled(EntityType<?> type) {
		return getEntry(type).fightback;
	}

	public static boolean canFightBack(EntityType<?> type) {
		MobBehaviorEntry entry = getEntry(type);
		return entry.fightback && (entry.passive || entry.aggressive || entry.waterAggression);
	}

	public static void setFightBackEnabled(EntityType<?> type, boolean enabled) {
		MobBehaviorEntry entry = ensureConfigurableEntry(type);
		entry.fightback = enabled;
		save();
	}

	public static boolean supportsFightBack(EntityType<?> type) {
		return type != null && type != EntityTypes.PLAYER;
	}

	/** How many nearest same-species allies (within 20 blocks) join a FightBack retaliation. 0 = alone. */
	public static int getFightBackHelp(EntityType<?> type) {
		return Math.max(0, getEntry(type).fightbackhelp);
	}

	public static void setFightBackHelp(EntityType<?> type, int helpCount) {
		if (helpCount < 0) {
			throw new IllegalArgumentException("FightBackHelp must be 0 or greater");
		}

		MobBehaviorEntry entry = ensureConfigurableEntry(type);
		entry.fightbackhelp = helpCount;
		save();
	}

	/** Standalone water-only Aggressive behavior (not a filter on Aggressive). */
	public static boolean isWaterAggression(EntityType<?> type) {
		return isBehaviorEnabled(type, MobBehavior.WATER_AGGRESSION);
	}

	public static void setWaterAggression(EntityType<?> type, boolean enabled) {
		setBehaviorEnabled(type, MobBehavior.WATER_AGGRESSION, enabled);
	}

	public static boolean supportsFleeSpeed(EntityType<?> type) {
		return type != null && type != EntityTypes.PLAYER;
	}

	public static void setHerdSpeedBps(EntityType<?> type, double speedBps) {
		if (speedBps < 0.0D) {
			throw new IllegalArgumentException("Herd speed cannot be negative");
		}

		MobBehaviorEntry entry = ensureConfigurableEntry(type);
		entry.herdspeed = speedBps;
		save();
	}

	public static double getHerdSpeedBps(EntityType<?> type) {
		return getEntry(type).herdspeed;
	}

	public static double weaponScareExplosionRadius() {
		return get().weaponScare.explosionRadius;
	}

	public static double weaponScareGunshotRadius() {
		return get().weaponScare.gunshotRadius;
	}

	public static double weaponScareArrowRadius() {
		return get().weaponScare.arrowRadius;
	}

	public static int weaponScareExplosionMemoryTicks() {
		return get().weaponScare.explosionMemoryTicks;
	}

	public static double spookRadius() {
		return get().weaponScare.spookRadius;
	}

	public static double herdLinkRange() {
		return get().herd.linkRange;
	}

	public static int herdScanIntervalTicks() {
		return get().herd.scanIntervalTicks;
	}

	public static int herdRepathIntervalTicks() {
		return get().herd.repathIntervalTicks;
	}

	public static int herdWanderMinTicks() {
		return get().herd.wanderMinSeconds * 20;
	}

	public static int herdWanderMaxTicks() {
		return get().herd.wanderMaxSeconds * 20;
	}

	public static int herdGrazeMinTicks() {
		return get().herd.grazeMinSeconds * 20;
	}

	public static int herdGrazeMaxTicks() {
		return get().herd.grazeMaxSeconds * 20;
	}

	public static int herdGrazeRestMinTicks() {
		return get().herd.grazeRestMinSeconds * 20;
	}

	public static int herdGrazeRestMaxTicks() {
		return get().herd.grazeRestMaxSeconds * 20;
	}

	public static int herdGrazeMoveDistance() {
		return get().herd.grazeMoveDistance;
	}

	public static int herdWanderJoinMaxDelayTicks() {
		return get().herd.wanderJoinMaxDelaySeconds * 20;
	}

	public static double herdGrazeSpreadRadius() {
		return get().herd.grazeSpreadRadius;
	}

	public static int aggroChainRadius() {
		return get().flee.chainRadius;
	}

	public static boolean isOneShotOnFearEnabled() {
		SpecialSettings settings = get().specialSettings;
		return settings != null && settings.oneshotonFear;
	}

	public static void setOneShotOnFearEnabled(boolean enabled) {
		if (get().specialSettings == null) {
			get().specialSettings = new SpecialSettings();
		}

		get().specialSettings.oneshotonFear = enabled;
		save();
	}

	/** Toggles oneshotonFear; returns the new value. */
	public static boolean toggleOneShotOnFear() {
		boolean enabled = !isOneShotOnFearEnabled();
		setOneShotOnFearEnabled(enabled);
		return enabled;
	}

	public static boolean isPlayerCrawlEnabled() {
		SpecialSettings settings = get().specialSettings;
		return settings == null || settings.playerCrawl;
	}

	public static void setPlayerCrawlEnabled(boolean enabled) {
		if (get().specialSettings == null) {
			get().specialSettings = new SpecialSettings();
		}

		get().specialSettings.playerCrawl = enabled;
		save();
	}

	/** Toggles playerCrawl; returns the new value. */
	public static boolean togglePlayerCrawl() {
		boolean enabled = !isPlayerCrawlEnabled();
		setPlayerCrawlEnabled(enabled);
		return enabled;
	}

	private static AnimalBehaviorConfig createDefault() {
		AnimalBehaviorConfig config = new AnimalBehaviorConfig();
		config.detection = new DetectionSettings();
		config.flee = new FleeSettings();
		config.weaponScare = new WeaponScareSettings();
		config.herd = new HerdSettings();
		config.specialSettings = new SpecialSettings();
		config.mobBehaviors = new LinkedHashMap<>();
		for (EntityType<?> type : AnimalCategories.managedTypes()) {
			config.mobBehaviors.put(key(type), defaultEntry(type));
		}

		config.mobBehaviors.put(key(EntityTypes.VILLAGER), villagerEntry());
		config.mobBehaviors.put(key(EntityTypes.IRON_GOLEM), golemEntry());
		config.mobBehaviors.put(key(EntityTypes.SNOW_GOLEM), golemEntry());

		config.mobBehaviors.values().forEach(AnimalBehaviorConfig::enforceAggressivePassiveFear);

		for (EntityType<?> type : AnimalCategories.allTypes()) {
			MobBehaviorEntry entry = config.mobBehaviors.get(key(type));
			if (entry != null) {
				entry.enabled = true;
			}
		}

		config.schemaVersion = 38;
		return config;
	}

	private static void ensureDefaults(AnimalBehaviorConfig config) {
		if (config.detection == null) {
			config.detection = new DetectionSettings();
		} else if (config.detection.aggressiveAlertRange <= 0.0F) {
			config.detection.aggressiveAlertRange = 16.0F;
		}

		if (config.flee == null) {
			config.flee = new FleeSettings();
		} else if (config.flee.defaultSpeedBps <= 0.0D) {
			config.flee.defaultSpeedBps = 5.0D;
		}

		if (config.weaponScare == null) {
			config.weaponScare = new WeaponScareSettings();
		}

		if (config.herd == null) {
			config.herd = new HerdSettings();
		}

		if (config.specialSettings == null) {
			config.specialSettings = new SpecialSettings();
		}

		if (config.mobBehaviors == null) {
			config.mobBehaviors = new LinkedHashMap<>();
		}

		if (config.playerBehaviors == null) {
			config.playerBehaviors = new LinkedHashMap<>();
		}

		for (EntityType<?> type : AnimalCategories.managedTypes()) {
			config.mobBehaviors.putIfAbsent(key(type), defaultEntry(type));
		}

		config.mobBehaviors.putIfAbsent(key(EntityTypes.VILLAGER), villagerEntry());
		config.mobBehaviors.putIfAbsent(key(EntityTypes.IRON_GOLEM), golemEntry());
		config.mobBehaviors.putIfAbsent(key(EntityTypes.SNOW_GOLEM), golemEntry());

		config.mobBehaviors.values().forEach(AnimalBehaviorConfig::enforceAggressivePassiveFear);
		migrateSchema(config);
		ensureAlexsMobsDefaults();
	}

	private static void migrateSchema(AnimalBehaviorConfig config) {
		if (config.schemaVersion < 1) {
			for (Map.Entry<String, MobBehaviorEntry> mobEntry : config.mobBehaviors.entrySet()) {
				MobBehaviorEntry entry = mobEntry.getValue();
				if (key(EntityTypes.VILLAGER).equals(mobEntry.getKey())) {
					entry.playerfear = false;
					continue;
				}

				if (entry.passive && !entry.aggressive) {
					entry.playerfear = true;
				}
			}

			config.schemaVersion = 1;
		}

		if (config.schemaVersion < 2) {
			MobBehaviorEntry villager = config.mobBehaviors.get(key(EntityTypes.VILLAGER));
			if (villager != null) {
				villager.passivefear = false;
			}

			config.schemaVersion = 2;
		}

		if (config.schemaVersion < 3) {
			config.mobBehaviors.put(key(EntityTypes.IRON_GOLEM), golemEntry());
			config.mobBehaviors.put(key(EntityTypes.SNOW_GOLEM), golemEntry());
			config.schemaVersion = 3;
		}

		if (config.schemaVersion < 4) {
			if (config.flee.defaultSpeedBps <= 0.0D) {
				config.flee.defaultSpeedBps = 5.0D;
			}

			for (MobBehaviorEntry entry : config.mobBehaviors.values()) {
				if (entry.passive && entry.speed <= 0.0D) {
					entry.speed = 5.0D;
				}
			}

			config.schemaVersion = 4;
		}

		if (config.schemaVersion < 5) {
			config.schemaVersion = 5;
		}

		if (config.schemaVersion < 6) {
			config.schemaVersion = 6;
		}

		if (config.schemaVersion < 7) {
			config.herd.defaultSpeedBps = 0.0D;
			for (EntityType<?> type : AnimalCategories.managedTypes()) {
				if (!isHorseOrDonkey(type)) {
					continue;
				}

				MobBehaviorEntry entry = config.mobBehaviors.computeIfAbsent(key(type), ignored -> defaultEntry(type).copy());
				if (entry.herdspeed <= 0.0D) {
					entry.herdspeed = 3.0D;
				}
			}

			config.schemaVersion = 7;
		}

		if (config.schemaVersion < 8) {
			MobBehaviorEntry villager = config.mobBehaviors.computeIfAbsent(key(EntityTypes.VILLAGER), ignored -> villagerEntry().copy());
			villager.fightback = true;
			config.schemaVersion = 8;
		}

		if (config.schemaVersion < 9) {
			for (EntityType<?> type : AnimalCategories.allTypes()) {
				MobBehaviorEntry entry = config.mobBehaviors.computeIfAbsent(key(type), ignored -> createEntryForType(type));
				entry.enabled = true;
			}

			config.schemaVersion = 9;
		}

		if (config.schemaVersion < 10) {
			MobBehaviorEntry villager = config.mobBehaviors.computeIfAbsent(key(EntityTypes.VILLAGER), ignored -> villagerEntry().copy());
			villager.playerfear = false;
			config.schemaVersion = 10;
		}

		if (config.schemaVersion < 11) {
			MobBehaviorEntry villager = config.mobBehaviors.computeIfAbsent(key(EntityTypes.VILLAGER), ignored -> villagerEntry().copy());
			villager.herd = false;
			villager.passive = true;
			config.schemaVersion = 11;
			save();
		}

		if (config.schemaVersion < 12) {
			for (Map.Entry<String, MobBehaviorEntry> mobEntry : config.mobBehaviors.entrySet()) {
				MobBehaviorEntry entry = mobEntry.getValue();
				if (!entry.playerfear || (entry.speed > 0.0D && entry.speed != 5.0D)) {
					continue;
				}

				Identifier id = Identifier.tryParse(mobEntry.getKey());
				if (id == null) {
					entry.speed = 8.0D;
					continue;
				}

				EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id).map(net.minecraft.core.Holder::value).orElse(null);
				entry.speed = type != null ? defaultPlayerFearFleeSpeedBps(type) : 8.0D;
			}

			config.schemaVersion = 12;
			save();
		}

		if (config.schemaVersion < 13) {
			MobBehaviorEntry villager = config.mobBehaviors.get(key(EntityTypes.VILLAGER));
			if (villager != null && (villager.speed <= 0.0D || villager.speed == 5.0D)) {
				villager.speed = 8.0D;
			}

			for (EntityType<?> type : new EntityType<?>[] { EntityTypes.WOLF, EntityTypes.POLAR_BEAR }) {
				MobBehaviorEntry entry = config.mobBehaviors.get(key(type));
				if (entry != null && (entry.speed <= 0.0D || entry.speed == 5.0D)) {
					entry.speed = 6.0D;
				}
			}

			config.schemaVersion = 13;
			save();
		}

		if (config.schemaVersion < 14) {
			if (ModCompat.isAlexsMobsLoaded()) {
				for (EntityType<?> type : ModCompat.alexsMobsEntityTypes()) {
					if (ModCompat.isAlexsMobDefaultDisabled(type)) {
						continue;
					}

					String mobKey = key(type);
					MobBehaviorEntry entry = config.mobBehaviors.get(mobKey);
					if (ModCompat.isAlexsMobDefaultAggressive(type)) {
						if (entry == null) {
							continue;
						}

						if (entry.enabled && !entry.aggressive) {
							applyAggressiveOnlyPreset(entry);
						}
					} else if (entry != null && entry.enabled && hasAggressiveOnlyPreset(entry)) {
						MobBehaviorEntry fresh = optionalEntry().copy();
						fresh.enabled = true;
						config.mobBehaviors.put(mobKey, fresh);
					}
				}
			}

			config.schemaVersion = 14;
			save();
		}

		if (config.schemaVersion < 15) {
			if (ModCompat.isAlexsMobsLoaded()) {
				for (EntityType<?> type : ModCompat.alexsMobsEntityTypes()) {
					if (!ModCompat.isAlexsMobDefaultPlayerFear(type)) {
						continue;
					}

					MobBehaviorEntry entry = config.mobBehaviors.get(key(type));
					if (entry == null || !entry.enabled || (entry.passive && entry.playerfear)) {
						continue;
					}

					applyPassivePlayerFearPreset(entry);
				}
			}

			config.schemaVersion = 15;
			save();
		}

		if (config.schemaVersion < 16) {
			if (ModCompat.isAlexsMobsLoaded()) {
				for (EntityType<?> type : ModCompat.alexsMobsEntityTypes()) {
					if (!ModCompat.isAlexsMobDefaultHerd(type)) {
						continue;
					}

					MobBehaviorEntry entry = config.mobBehaviors.get(key(type));
					if (entry != null && entry.enabled && !entry.herd) {
						entry.herd = true;
					}
				}
			}

			config.schemaVersion = 16;
			save();
		}

		if (config.schemaVersion < 17) {
			if (ModCompat.isAlexsMobsLoaded()) {
				for (EntityType<?> type : ModCompat.alexsMobsEntityTypes()) {
					MobBehaviorEntry entry = config.mobBehaviors.get(key(type));
					if (entry == null || !entry.enabled) {
						continue;
					}

					if ("snow_leopard".equals(BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath())) {
						applyAggressiveOnlyPreset(entry);
						continue;
					}

					if (ModCompat.isAlexsMobDefaultFightback(type) && entry.passive && !entry.fightback) {
						entry.fightback = true;
					}
				}
			}

			config.schemaVersion = 17;
			save();
		}

		if (config.schemaVersion < 18) {
			if (ModCompat.isAlexsMobsLoaded()) {
				for (EntityType<?> type : ModCompat.alexsMobsEntityTypes()) {
					MobBehaviorEntry entry = config.mobBehaviors.get(key(type));
					if (entry == null || !entry.enabled) {
						continue;
					}

					if (ModCompat.isAlexsMobDefaultFightback(type) && entry.passive && !entry.fightback) {
						entry.fightback = true;
					}
				}
			}

			config.schemaVersion = 18;
			save();
		}

		if (config.schemaVersion < 19) {
			if (config.specialSettings == null) {
				config.specialSettings = new SpecialSettings();
			}

			config.schemaVersion = 19;
			save();
		}

		if (config.schemaVersion < 20) {
			for (MobBehaviorEntry entry : config.mobBehaviors.values()) {
				if (entry.aggressive) {
					entry.memory = 6;
				} else if (entry.memory <= 0) {
					entry.memory = 6;
				}
			}

			config.schemaVersion = 20;
			save();
		}

		if (config.schemaVersion < 21) {
			// fightbackhelp defaults to 0 (Gson); bump schema so existing configs rewrite with the field.
			config.schemaVersion = 21;
			save();
		}

		if (config.schemaVersion < 22) {
			for (MobBehaviorEntry entry : config.mobBehaviors.values()) {
				if (entry.flightfleemin <= 0) {
					entry.flightfleemin = DEFAULT_FLIGHT_FLEE_MIN;
				}
				if (entry.flightfleemax <= 0) {
					entry.flightfleemax = DEFAULT_FLIGHT_FLEE_MAX;
				}
				if (entry.flightfleemax < entry.flightfleemin) {
					entry.flightfleemax = entry.flightfleemin;
				}
			}

			config.schemaVersion = 22;
			save();
		}

		if (config.schemaVersion < 23) {
			for (MobBehaviorEntry entry : config.mobBehaviors.values()) {
				if (entry.aggressivekilltimer <= 0 && entry.aggressive) {
					entry.aggressivekilltimer = DEFAULT_AGGRESSIVE_KILL_TIMER;
				} else if (entry.aggressivekilltimer < 0) {
					entry.aggressivekilltimer = DEFAULT_AGGRESSIVE_KILL_TIMER;
				}
			}

			config.schemaVersion = 23;
			save();
		}

		if (config.schemaVersion < 24) {
			if (ModCompat.isAlexsMobsLoaded()) {
				for (EntityType<?> type : ModCompat.alexsMobsEntityTypes()) {
					MobBehaviorEntry entry = config.mobBehaviors.get(key(type));
					if (entry == null || !entry.enabled) {
						continue;
					}

					if (ModCompat.isAlexsMobDefaultFightback(type)
						&& !entry.fightback
						&& (entry.passive || entry.aggressive)) {
						entry.fightback = true;
					}
				}
			}

			config.schemaVersion = 24;
			save();
		}

		if (config.schemaVersion < 25) {
			for (Map.Entry<String, MobBehaviorEntry> mobEntry : config.mobBehaviors.entrySet()) {
				MobBehaviorEntry entry = mobEntry.getValue();
				Identifier id = Identifier.tryParse(mobEntry.getKey());
				if (id == null) {
					continue;
				}

				EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id).map(net.minecraft.core.Holder::value).orElse(null);
				if (type != null && entry.aggressive && AnimalCategories.isSeaCreature(type)) {
					entry.waterAggression = true;
				}
			}

			config.schemaVersion = 25;
			save();
		}

		if (config.schemaVersion < 26) {
			for (MobBehaviorEntry entry : config.mobBehaviors.values()) {
				if (entry.fightback && entry.fightbackhelp <= 0) {
					entry.fightbackhelp = DEFAULT_FIGHT_BACK_HELP;
				}
			}

			config.schemaVersion = 26;
			save();
		}

		if (config.schemaVersion < 27) {
			if (config.specialSettings == null) {
				config.specialSettings = new SpecialSettings();
			}

			config.schemaVersion = 27;
			save();
		}

		if (config.schemaVersion < 28) {
			config.schemaVersion = 28;
			save();
		}

		if (config.schemaVersion < 29) {
			config.schemaVersion = 29;
			save();
		}

		if (config.schemaVersion < 30) {
			// Bison and elephant are no longer herd-by-default; flip herd off on existing configs.
			if (ModCompat.isAlexsMobsLoaded()) {
				for (EntityType<?> type : ModCompat.alexsMobsEntityTypes()) {
					String path = BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath();
					if (!"bison".equals(path) && !"elephant".equals(path)) {
						continue;
					}

					MobBehaviorEntry entry = config.mobBehaviors.get(key(type));
					if (entry != null && entry.enabled && entry.herd) {
						entry.herd = false;
					}
				}
			}

			config.schemaVersion = 30;
			save();
		}

		if (config.schemaVersion < 31) {
			for (MobBehaviorEntry entry : config.mobBehaviors.values()) {
				entry.stayonland = true;
			}

			config.schemaVersion = 31;
			save();
		}

		if (config.schemaVersion < 32) {
			// Alex's Mobs presets stay in config, but Wild Behavior starts off until toggled on.
			if (ModCompat.isAlexsMobsLoaded()) {
				for (EntityType<?> type : ModCompat.alexsMobsEntityTypes()) {
					if (ModCompat.isAlexsMobDefaultDisabled(type)) {
						continue;
					}

					String mobKey = key(type);
					MobBehaviorEntry entry = config.mobBehaviors.get(mobKey);
					if (entry == null) {
						entry = optionalEntry().copy();
						applyAlexsMobPreset(entry, type);
						config.mobBehaviors.put(mobKey, entry);
					}
					entry.enabled = false;
				}
			} else {
				for (Map.Entry<String, MobBehaviorEntry> mobEntry : config.mobBehaviors.entrySet()) {
					Identifier id = Identifier.tryParse(mobEntry.getKey());
					if (id != null && ModCompat.ALEXSMOBS_MOD_ID.equals(id.getNamespace())) {
						mobEntry.getValue().enabled = false;
					}
				}
			}

			config.schemaVersion = 32;
			save();
		}

		if (config.schemaVersion < 33) {
			// Former passive+playerFear Alex mobs: enable with PlayerFear only (no Passive/Herd/FightBack).
			if (ModCompat.isAlexsMobsLoaded()) {
				for (EntityType<?> type : ModCompat.alexsMobsEntityTypes()) {
					if (!ModCompat.isAlexsMobDefaultPlayerFear(type)) {
						continue;
					}

					String mobKey = key(type);
					MobBehaviorEntry entry = config.mobBehaviors.get(mobKey);
					if (entry == null) {
						entry = optionalEntry().copy();
						config.mobBehaviors.put(mobKey, entry);
					}
					applyPlayerFearOnlyPreset(entry);
					entry.stayonland = !AnimalCategories.isSeaCreature(type);
					entry.enabled = true;
				}
			} else {
				for (Map.Entry<String, MobBehaviorEntry> mobEntry : config.mobBehaviors.entrySet()) {
					Identifier id = Identifier.tryParse(mobEntry.getKey());
					if (id == null || !ModCompat.ALEXSMOBS_MOD_ID.equals(id.getNamespace())) {
						continue;
					}

					MobBehaviorEntry entry = mobEntry.getValue();
					// Without the mod loaded we can only match prior playerFear-bearing presets.
					if (entry.playerfear) {
						applyPlayerFearOnlyPreset(entry);
						entry.enabled = true;
					}
				}
			}

			config.schemaVersion = 33;
			save();
		}

		if (config.schemaVersion < 34) {
			if (config.specialSettings == null) {
				config.specialSettings = new SpecialSettings();
			}
			// New toggle defaults on; existing configs pick it up on migrate.
			config.specialSettings.playerCrawl = true;
			config.schemaVersion = 34;
			save();
		}

		if (config.schemaVersion < 35) {
			// Restore enabled Aggressive for grizzly / crocs / alligator snapping turtle (+ WaterAggression).
			if (ModCompat.isAlexsMobsLoaded()) {
				for (EntityType<?> type : ModCompat.alexsMobsEntityTypes()) {
					if (!ModCompat.isAlexsMobDefaultEnabledAggressive(type)) {
						continue;
					}

					String mobKey = key(type);
					MobBehaviorEntry entry = config.mobBehaviors.get(mobKey);
					if (entry == null) {
						entry = optionalEntry().copy();
						config.mobBehaviors.put(mobKey, entry);
					}
					applyAlexsMobPreset(entry, type);
					entry.enabled = true;
				}
			} else {
				for (String path : List.of(
					"alligator_snapping_turtle",
					"caiman",
					"crocodile",
					"grizzly_bear"
				)) {
					String mobKey = ModCompat.ALEXSMOBS_MOD_ID + ":" + path;
					MobBehaviorEntry entry = config.mobBehaviors.get(mobKey);
					if (entry == null) {
						continue;
					}

					applyAggressiveOnlyPreset(entry);
					if (!"grizzly_bear".equals(path)) {
						entry.fightback = true;
						entry.fightbackhelp = DEFAULT_FIGHT_BACK_HELP;
						entry.waterAggression = true;
						entry.stayonland = false;
					}
					entry.enabled = true;
				}
			}

			config.schemaVersion = 35;
			save();
		}

		if (config.schemaVersion < 36) {
			// No Herd defaults on modded mobs — clear herd on any non-vanilla entry.
			for (Map.Entry<String, MobBehaviorEntry> mobEntry : config.mobBehaviors.entrySet()) {
				Identifier id = Identifier.tryParse(mobEntry.getKey());
				if (id == null || "minecraft".equals(id.getNamespace())) {
					continue;
				}

				mobEntry.getValue().herd = false;
			}

			config.schemaVersion = 36;
			save();
		}

		if (config.schemaVersion < 37) {
			// WaterAggression is now a standalone behavior, not an Aggressive filter.
			for (MobBehaviorEntry entry : config.mobBehaviors.values()) {
				if (entry.waterAggression) {
					entry.aggressive = false;
					if (entry.aggressivekilltimer <= 0) {
						entry.aggressivekilltimer = DEFAULT_AGGRESSIVE_KILL_TIMER;
					}
					if (entry.memory <= 0) {
						entry.memory = 6;
					}
				}
			}

			config.schemaVersion = 37;
			save();
		}

		if (config.schemaVersion < 39) {
			// Cliff avoidance follows Herd (leaders only); removed standalone cliffavoid toggle.
			config.schemaVersion = 39;
			save();
		}
	}

	private static boolean hasAggressiveOnlyPreset(MobBehaviorEntry entry) {
		return entry.aggressive
			&& !entry.herd
			&& !entry.passive
			&& !entry.playerfear
			&& !entry.weaponscare
			&& !entry.defender
			&& !entry.fightback
			&& !entry.waterAggression
			&& entry.confidence == 1;
	}

	private static double defaultPlayerFearFleeSpeedBps(EntityType<?> type) {
		if (type == EntityTypes.CAT || type == EntityTypes.OCELOT) {
			return 10.0D;
		}

		if (type == EntityTypes.HORSE || type == EntityTypes.DONKEY || type == EntityTypes.MULE
			|| type == EntityTypes.SKELETON_HORSE || type == EntityTypes.ZOMBIE_HORSE) {
			return 12.0D;
		}

		return 8.0D;
	}

	private static MobBehaviorEntry defaultEntry(EntityType<?> type) {
		MobBehaviorEntry entry = new MobBehaviorEntry();
		entry.enabled = true;
		entry.memory = 6;
		// Disable stay-on-land for aquatic mobs by default; land mobs keep the default true
		entry.stayonland = !AnimalCategories.isSeaCreature(type);
		if (type == EntityTypes.WOLF || type == EntityTypes.POLAR_BEAR) {
			entry.herd = true;
			entry.passive = false;
			entry.playerfear = false;
			entry.aggressive = true;
			entry.passivefear = true;
			entry.weaponscare = true;
			entry.confidence = type == EntityTypes.WOLF ? 2 : 1;
			entry.aggressivekilltimer = DEFAULT_AGGRESSIVE_KILL_TIMER;
			entry.speed = 6.0D;
		} else {
			entry.herd = true;
			entry.passive = true;
			entry.playerfear = true;
			entry.aggressive = false;
			entry.passivefear = true;
			entry.weaponscare = true;
			entry.speed = defaultPlayerFearFleeSpeedBps(type);
		}

		if (isHorseOrDonkey(type)) {
			entry.herdspeed = 3.0D;
		}

		enforceAggressivePassiveFear(entry);
		return entry;
	}

	private static boolean isHorseOrDonkey(EntityType<?> type) {
		return type == EntityTypes.HORSE || type == EntityTypes.DONKEY || type == EntityTypes.MULE;
	}

	private static MobBehaviorEntry defaultHostileEntry(EntityType<?> type) {
		MobBehaviorEntry entry = new MobBehaviorEntry();
		entry.enabled = true;
		entry.herd = false;
		entry.passive = false;
		entry.playerfear = false;
		entry.aggressive = true;
		entry.passivefear = true;
		entry.weaponscare = true;
		// Disable stay-on-land for aquatic hostile mobs by default; land mobs keep the default true
		entry.stayonland = !AnimalCategories.isSeaCreature(type);
		entry.confidence = 1;
		entry.memory = 6;
		entry.aggressivekilltimer = DEFAULT_AGGRESSIVE_KILL_TIMER;
		enforceAggressivePassiveFear(entry);
		return entry;
	}

	private static MobBehaviorEntry golemEntry() {
		MobBehaviorEntry entry = new MobBehaviorEntry();
		entry.enabled = true;
		entry.herd = false;
		entry.passive = false;
		entry.playerfear = false;
		entry.aggressive = false;
		entry.passivefear = false;
		entry.weaponscare = false;
		entry.defender = true;
		entry.memory = 6;
		return entry;
	}

	private static MobBehaviorEntry villagerEntry() {
		MobBehaviorEntry entry = new MobBehaviorEntry();
		entry.enabled = true;
		entry.herd = false;
		entry.passive = true;
		entry.playerfear = false;
		entry.aggressive = false;
		entry.passivefear = false;
		entry.weaponscare = true;
		entry.fightback = true;
		entry.fightbackhelp = DEFAULT_FIGHT_BACK_HELP;
		entry.memory = 6;
		entry.speed = 8.0D;
		return entry;
	}

	private static MobBehaviorEntry optionalEntry() {
		MobBehaviorEntry entry = new MobBehaviorEntry();
		entry.enabled = true;
		entry.herd = false;
		entry.passive = false;
		entry.playerfear = false;
		entry.aggressive = false;
		entry.passivefear = false;
		entry.weaponscare = false;
		entry.defender = false;
		entry.fightback = false;
		entry.confidence = 0;
		entry.memory = 6;
		entry.speed = 0.0D;
		entry.herdspeed = 0.0D;
		entry.damage = 0.0F;
		return entry;
	}

	private static MobBehaviorEntry createEntryForType(EntityType<?> type) {
		if (AnimalCategories.isGolem(type)) {
			return golemEntry().copy();
		}

		if (type == EntityTypes.VILLAGER) {
			return villagerEntry().copy();
		}

		if (AnimalCategories.isManaged(type)) {
			return defaultEntry(type).copy();
		}

		if (AnimalCategories.isHostile(type)) {
			MobBehaviorEntry entry = defaultHostileEntry(type).copy();
			if (AnimalCategories.isSeaCreature(type)) {
				entry.aggressive = false;
				entry.waterAggression = true;
			}
			return entry;
		}

		MobBehaviorEntry entry = optionalEntry().copy();
		if (ModCompat.isAlexsMob(type) && !ModCompat.isAlexsMobDefaultDisabled(type)) {
			applyAlexsMobPreset(entry, type);
			entry.enabled = ModCompat.isAlexsMobDefaultEnabled(type);
		}
		return entry;
	}

	private static void enforceAggressivePassiveFear(MobBehaviorEntry entry) {
		if (entry.aggressive || entry.waterAggression) {
			entry.passivefear = true;
		}
	}

	private static MobBehaviorEntry getEntry(EntityType<?> type) {
		MobBehaviorEntry entry = get().mobBehaviors.get(key(type));
		if (entry != null) {
			return entry;
		}

		if (AnimalCategories.isGolem(type)) {
			return golemEntry();
		}

		if (AnimalCategories.isManaged(type)) {
			return defaultEntry(type);
		}

		if (AnimalCategories.isHostile(type)) {
			return defaultHostileEntry(type);
		}

		if (AnimalCategories.isOptionalMob(type)) {
			return optionalEntry();
		}

		return villagerEntry();
	}

	private static String key(EntityType<?> type) {
		return BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
	}

	public static final class DetectionSettings {
		public float normalRange = 20.0F;
		public float sneakRange = 8.0F;
		/** How far Passive mobs detect and flee from Aggressive mobs, and chain-alert radius for aggressive threats. */
		public float aggressiveAlertRange = 16.0F;
	}

	public static final class FleeSettings {
		public int distanceMin = DEFAULT_FLEE_DISTANCE_MIN;
		public int distanceMax = DEFAULT_FLEE_DISTANCE_MAX;
		public int chainRadius = 12;
		public int repathIntervalTicks = 10;
		/** Fallback flee speed (blocks per second) when a mob entry has no speed override. */
		public double defaultSpeedBps = 5.0D;
	}

	public static final class WeaponScareSettings {
		public double explosionRadius = 30.0D;
		/** Radius flee when a WeaponMod gun fires; all animals within this range flee immediately. */
		public double gunshotRadius = 40.0D;
		public double arrowRadius = 4.0D;
		public int explosionMemoryTicks = 20;
		public double spookRadius = 35.0D;
	}

	public static final class HerdSettings {
		public double linkRange = 35.0D;
		public int scanIntervalTicks = 40;
		public int repathIntervalTicks = 20;
		public int wanderMinSeconds = 30;
		public int wanderMaxSeconds = 90;
		public int grazeMinSeconds = 120;
		public int grazeMaxSeconds = 240;
		public int grazeRestMinSeconds = 5;
		public int grazeRestMaxSeconds = 15;
		public int grazeMoveDistance = 5;
		public int wanderJoinMaxDelaySeconds = 5;
		public double grazeSpreadRadius = 12.0D;
		/** Unused legacy field; per-mob herdspeed 0 means vanilla walk speed. */
		public double defaultSpeedBps = 0.0D;
	}

	/** Small convenience toggles that tweak gameplay without per-mob config. */
	public static final class SpecialSettings {
		/**
		 * When true, arrow hits instantly kill mobs that have Passive or PlayerFear enabled.
		 * Checked globally from Passive / PlayerFear hurt handling.
		 */
		@SerializedName("oneshotonFear")
		public boolean oneshotonFear = false;
		/**
		 * When true, holding sneak + sprint (Shift + Ctrl by default) puts the player
		 * into the crawl / swimming pose on land.
		 */
		@SerializedName("playerCrawl")
		public boolean playerCrawl = true;
	}

	public static final class MobBehaviorEntry {
		/** When false, Wild Behavior ignores this mob type entirely. */
		public boolean enabled = true;
		public boolean herd = true;
		public boolean passive = true;
		@SerializedName("playerfear")
		public boolean playerfear = false;
		public boolean aggressive = false;
		@SerializedName("passivefear")
		public boolean passivefear = true;
		@SerializedName("weaponscare")
		public boolean weaponscare = true;
		public boolean defender = false;
	/**
	 * When true, herding mobs avoid water and pathfind to the nearest land if they
	 * end up in water before resuming herd behavior. Defaults true for every mob
	 * with Wild Behavior enabled.
	 */
	@SerializedName("stayonland")
	public boolean stayonland = true;
		public int confidence = 0;
		/** Flee speed in blocks per second; 0 uses {@link FleeSettings#defaultSpeedBps}. */
		public double speed = 0.0D;
		/** Herd walk speed in blocks per second; 0 uses vanilla walk speed. */
		@SerializedName("herdspeed")
		public double herdspeed = 0.0D;
		/** Retaliate against whoever damaged this mob; requires Passive or Aggressive. */
		public boolean fightback = false;
		/**
		 * When FightBack triggers, how many nearest same-species allies within 20 blocks also attack.
		 * 0 = no help. FightBack-default mobs use {@link #DEFAULT_FIGHT_BACK_HELP}.
		 */
		public int fightbackhelp = 0;
		/**
		 * Standalone Aggressive variant: only acquire targets that are in water.
		 * Ongoing chase may continue onto dry land. Defaults on for sea creatures instead of Aggressive.
		 */
		@SerializedName("waterAggression")
		public boolean waterAggression = false;
		/** Melee damage when Aggressive or Defender; 0 uses attribute/default fallback. */
		public float damage = 0.0F;
		/**
		 * Seconds to keep chasing or fleeing from a player after losing line of sight.
		 * Aggressive defaults to 6. 0 = forget immediately when LOS breaks.
		 */
		public int memory = 6;
		/**
		 * Horizontal flee distance range for birds, bats, and bees when startled.
		 * Unused by ground mobs. Defaults {@link #DEFAULT_FLIGHT_FLEE_MIN}–{@link #DEFAULT_FLIGHT_FLEE_MAX}.
		 */
		@SerializedName("flightfleemin")
		public int flightfleemin = DEFAULT_FLIGHT_FLEE_MIN;
		@SerializedName("flightfleemax")
		public int flightfleemax = DEFAULT_FLIGHT_FLEE_MAX;
		/**
		 * Seconds after an Aggressive kill before this mob may acquire another attack target.
		 * 0 = no cooldown. Default {@link #DEFAULT_AGGRESSIVE_KILL_TIMER}.
		 */
		@SerializedName("aggressivekilltimer")
		public int aggressivekilltimer = DEFAULT_AGGRESSIVE_KILL_TIMER;

		boolean isEnabled(MobBehavior behavior) {
			return switch (behavior) {
				case HERD -> this.herd;
				case PLAYER_FEAR -> this.playerfear;
				case PASSIVE -> this.passive;
				case AGGRESSIVE -> this.aggressive;
				case WATER_AGGRESSION -> this.waterAggression;
				case PASSIVE_FEAR -> this.passivefear;
				case WEAPON_SCARE -> this.weaponscare;
				case DEFENDER -> this.defender;
				case STAY_ON_LAND -> this.stayonland;
			};
		}

		void setEnabled(MobBehavior behavior, boolean enabled) {
			switch (behavior) {
				case HERD -> this.herd = enabled;
				case PLAYER_FEAR -> this.playerfear = enabled;
				case PASSIVE -> this.passive = enabled;
				case AGGRESSIVE -> this.aggressive = enabled;
				case WATER_AGGRESSION -> this.waterAggression = enabled;
				case PASSIVE_FEAR -> this.passivefear = enabled;
				case WEAPON_SCARE -> this.weaponscare = enabled;
				case DEFENDER -> this.defender = enabled;
				case STAY_ON_LAND -> this.stayonland = enabled;
			}
		}

		EnumSet<MobBehavior> toEnumSet() {
			EnumSet<MobBehavior> behaviors = EnumSet.noneOf(MobBehavior.class);
			for (MobBehavior behavior : MobBehavior.values()) {
				if (isEnabled(behavior)) {
					behaviors.add(behavior);
				}
			}

			return behaviors;
		}

		MobBehaviorEntry copy() {
			MobBehaviorEntry copy = new MobBehaviorEntry();
			copy.enabled = this.enabled;
			copy.herd = this.herd;
			copy.passive = this.passive;
			copy.playerfear = this.playerfear;
			copy.aggressive = this.aggressive;
			copy.passivefear = this.passivefear;
			copy.weaponscare = this.weaponscare;
			copy.defender = this.defender;
			copy.stayonland = this.stayonland;
			copy.confidence = this.confidence;
			copy.speed = this.speed;
			copy.herdspeed = this.herdspeed;
			copy.fightback = this.fightback;
			copy.fightbackhelp = this.fightbackhelp;
			copy.waterAggression = this.waterAggression;
			copy.damage = this.damage;
			copy.memory = this.memory;
			copy.flightfleemin = this.flightfleemin;
			copy.flightfleemax = this.flightfleemax;
			copy.aggressivekilltimer = this.aggressivekilltimer;
			return copy;
		}
	}

	public static final class PlayerBehaviorEntry {
		public String name = "";
		@SerializedName("passivefear")
		public boolean passivefear = true;
	}
}
