package com.wildbehavior.client.config;

import com.wildbehavior.ai.AnimalCategories;
import com.wildbehavior.compat.ModCompat;
import com.wildbehavior.config.AnimalBehaviorConfig;
import com.wildbehavior.config.AnimalBehaviorConfig.DetectionSettings;
import com.wildbehavior.config.AnimalBehaviorConfig.FleeSettings;
import com.wildbehavior.config.AnimalBehaviorConfig.HerdSettings;
import com.wildbehavior.config.AnimalBehaviorConfig.MobBehaviorEntry;
import com.wildbehavior.config.AnimalBehaviorConfig.SpecialSettings;
import com.wildbehavior.config.AnimalBehaviorConfig.WeaponScareSettings;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.impl.builders.SubCategoryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;

/**
 * Cloth Config UI for {@code config/wild-behavior.json}.
 * Global tabs plus per-mob expandable sections (click a mob name to edit toggles/sliders).
 * Client-only; loaded reflectively from {@link WildBehaviorConfigScreens}.
 */
public final class WildBehaviorClothConfig {
	private WildBehaviorClothConfig() {
	}

	public static Screen create(Screen parent) {
		AnimalBehaviorConfig config = AnimalBehaviorConfig.get();
		if (config.specialSettings == null) {
			config.specialSettings = new SpecialSettings();
		}

		ConfigBuilder builder = ConfigBuilder.create()
			.setParentScreen(parent)
			.setTitle(Component.literal("Wild Behavior"))
			.setSavingRunnable(AnimalBehaviorConfig::saveFromGui);

		ConfigEntryBuilder entries = builder.entryBuilder();
		addDetectionCategory(builder, entries, config.detection);
		addFleeCategory(builder, entries, config.flee);
		addHerdCategory(builder, entries, config.herd);
		addWeaponScareCategory(builder, entries, config.weaponScare);
		addSpecialSettingsCategory(builder, entries, config.specialSettings);
		addMobCategories(builder, entries);

		builder.setGlobalized(true);
		builder.setGlobalizedExpanded(false);
		return builder.build();
	}

	private static void addDetectionCategory(ConfigBuilder builder, ConfigEntryBuilder entries, DetectionSettings detection) {
		ConfigCategory category = builder.getOrCreateCategory(Component.literal("Detection"));
		category.addEntry(entries.startFloatField(Component.literal("Normal Range"), detection.normalRange)
			.setDefaultValue(20.0F)
			.setMin(1.0F)
			.setMax(64.0F)
			.setSaveConsumer(value -> detection.normalRange = value)
			.setTooltip(Component.literal("How far mobs detect standing players."))
			.build());
		category.addEntry(entries.startFloatField(Component.literal("Sneak Range"), detection.sneakRange)
			.setDefaultValue(8.0F)
			.setMin(1.0F)
			.setMax(64.0F)
			.setSaveConsumer(value -> detection.sneakRange = value)
			.setTooltip(Component.literal("How far mobs detect sneaking players."))
			.build());
		category.addEntry(entries.startFloatField(Component.literal("Aggressive Alert Range"), detection.aggressiveAlertRange)
			.setDefaultValue(16.0F)
			.setMin(1.0F)
			.setMax(64.0F)
			.setSaveConsumer(value -> detection.aggressiveAlertRange = value)
			.setTooltip(Component.literal("How far Passive mobs detect Aggressive mobs."))
			.build());
	}

	private static void addFleeCategory(ConfigBuilder builder, ConfigEntryBuilder entries, FleeSettings flee) {
		ConfigCategory category = builder.getOrCreateCategory(Component.literal("Flee"));
		category.addEntry(entries.startIntSlider(Component.literal("Distance Min"), flee.distanceMin, 4, 128)
			.setDefaultValue(AnimalBehaviorConfig.DEFAULT_FLEE_DISTANCE_MIN)
			.setSaveConsumer(value -> flee.distanceMin = value)
			.setTooltip(Component.literal("Minimum blocks to run when fleeing (PlayerFear, Passive, WeaponScare)."))
			.build());
		category.addEntry(entries.startIntSlider(Component.literal("Distance Max"), flee.distanceMax, 4, 128)
			.setDefaultValue(AnimalBehaviorConfig.DEFAULT_FLEE_DISTANCE_MAX)
			.setSaveConsumer(value -> flee.distanceMax = value)
			.setTooltip(Component.literal("Maximum blocks to run when fleeing (PlayerFear, Passive, WeaponScare)."))
			.build());
		category.addEntry(entries.startIntSlider(Component.literal("Chain Radius"), flee.chainRadius, 1, 64)
			.setDefaultValue(12)
			.setSaveConsumer(value -> flee.chainRadius = value)
			.setTooltip(Component.literal("How far PlayerFear pack-flee spreads to same-species allies."))
			.build());
		category.addEntry(entries.startIntSlider(Component.literal("Repath Interval (ticks)"), flee.repathIntervalTicks, 1, 100)
			.setDefaultValue(10)
			.setSaveConsumer(value -> flee.repathIntervalTicks = value)
			.build());
		category.addEntry(entries.startIntSlider(Component.literal("Default Speed (bps ×10)"), tenths(flee.defaultSpeedBps), 10, 200)
			.setDefaultValue(50)
			.setTextGetter(value -> Component.literal(String.format("%.1f bps", value / 10.0D)))
			.setSaveConsumer(value -> flee.defaultSpeedBps = value / 10.0D)
			.setTooltip(Component.literal("Fallback flee speed when a mob has no speed override."))
			.build());
	}

	private static void addHerdCategory(ConfigBuilder builder, ConfigEntryBuilder entries, HerdSettings herd) {
		ConfigCategory category = builder.getOrCreateCategory(Component.literal("Herd"));
		category.addEntry(entries.startDoubleField(Component.literal("Link Range"), herd.linkRange)
			.setDefaultValue(35.0D)
			.setMin(1.0D)
			.setMax(128.0D)
			.setSaveConsumer(value -> herd.linkRange = value)
			.build());
		category.addEntry(entries.startIntSlider(Component.literal("Scan Interval (ticks)"), herd.scanIntervalTicks, 1, 200)
			.setDefaultValue(40)
			.setSaveConsumer(value -> herd.scanIntervalTicks = value)
			.build());
		category.addEntry(entries.startIntSlider(Component.literal("Repath Interval (ticks)"), herd.repathIntervalTicks, 1, 200)
			.setDefaultValue(20)
			.setSaveConsumer(value -> herd.repathIntervalTicks = value)
			.build());
		category.addEntry(entries.startIntSlider(Component.literal("Wander Min (seconds)"), herd.wanderMinSeconds, 1, 600)
			.setDefaultValue(30)
			.setSaveConsumer(value -> herd.wanderMinSeconds = value)
			.build());
		category.addEntry(entries.startIntSlider(Component.literal("Wander Max (seconds)"), herd.wanderMaxSeconds, 1, 600)
			.setDefaultValue(90)
			.setSaveConsumer(value -> herd.wanderMaxSeconds = value)
			.build());
		category.addEntry(entries.startIntSlider(Component.literal("Graze Min (seconds)"), herd.grazeMinSeconds, 1, 1200)
			.setDefaultValue(120)
			.setSaveConsumer(value -> herd.grazeMinSeconds = value)
			.build());
		category.addEntry(entries.startIntSlider(Component.literal("Graze Max (seconds)"), herd.grazeMaxSeconds, 1, 1200)
			.setDefaultValue(240)
			.setSaveConsumer(value -> herd.grazeMaxSeconds = value)
			.build());
		category.addEntry(entries.startIntSlider(Component.literal("Graze Rest Min (seconds)"), herd.grazeRestMinSeconds, 1, 120)
			.setDefaultValue(5)
			.setSaveConsumer(value -> herd.grazeRestMinSeconds = value)
			.build());
		category.addEntry(entries.startIntSlider(Component.literal("Graze Rest Max (seconds)"), herd.grazeRestMaxSeconds, 1, 120)
			.setDefaultValue(15)
			.setSaveConsumer(value -> herd.grazeRestMaxSeconds = value)
			.build());
		category.addEntry(entries.startIntSlider(Component.literal("Graze Move Distance"), herd.grazeMoveDistance, 1, 32)
			.setDefaultValue(5)
			.setSaveConsumer(value -> herd.grazeMoveDistance = value)
			.build());
		category.addEntry(entries.startIntSlider(Component.literal("Wander Join Max Delay (seconds)"), herd.wanderJoinMaxDelaySeconds, 0, 60)
			.setDefaultValue(5)
			.setSaveConsumer(value -> herd.wanderJoinMaxDelaySeconds = value)
			.build());
		category.addEntry(entries.startDoubleField(Component.literal("Graze Spread Radius"), herd.grazeSpreadRadius)
			.setDefaultValue(12.0D)
			.setMin(1.0D)
			.setMax(64.0D)
			.setSaveConsumer(value -> herd.grazeSpreadRadius = value)
			.build());
	}

	private static void addWeaponScareCategory(ConfigBuilder builder, ConfigEntryBuilder entries, WeaponScareSettings weaponScare) {
		ConfigCategory category = builder.getOrCreateCategory(Component.literal("Weapon Scare"));
		category.addEntry(entries.startDoubleField(Component.literal("Explosion Radius"), weaponScare.explosionRadius)
			.setDefaultValue(30.0D)
			.setMin(1.0D)
			.setMax(128.0D)
			.setSaveConsumer(value -> weaponScare.explosionRadius = value)
			.build());
		category.addEntry(entries.startDoubleField(Component.literal("Gunshot Radius"), weaponScare.gunshotRadius)
			.setDefaultValue(48.0D)
			.setMin(1.0D)
			.setMax(128.0D)
			.setTooltip(Component.literal("Radius flee when a WeaponMod gun fires (flintlock, musket, mortar, cannon, etc.). All animals within this range flee immediately."))
			.setSaveConsumer(value -> weaponScare.gunshotRadius = value)
			.build());
		category.addEntry(entries.startDoubleField(Component.literal("Arrow Radius"), weaponScare.arrowRadius)
			.setDefaultValue(4.0D)
			.setMin(0.5D)
			.setMax(64.0D)
			.setSaveConsumer(value -> weaponScare.arrowRadius = value)
			.build());
		category.addEntry(entries.startIntSlider(Component.literal("Explosion Memory (ticks)"), weaponScare.explosionMemoryTicks, 1, 200)
			.setDefaultValue(20)
			.setSaveConsumer(value -> weaponScare.explosionMemoryTicks = value)
			.build());
		category.addEntry(entries.startDoubleField(Component.literal("Spook Radius"), weaponScare.spookRadius)
			.setDefaultValue(35.0D)
			.setMin(1.0D)
			.setMax(128.0D)
			.setSaveConsumer(value -> weaponScare.spookRadius = value)
			.build());
	}

	private static void addSpecialSettingsCategory(ConfigBuilder builder, ConfigEntryBuilder entries, SpecialSettings specialSettings) {
		ConfigCategory category = builder.getOrCreateCategory(Component.literal("Special Settings"));
		category.addEntry(entries.startBooleanToggle(Component.literal("oneshotonFear"), specialSettings.oneshotonFear)
			.setDefaultValue(false)
			.setSaveConsumer(value -> specialSettings.oneshotonFear = value)
			.setTooltip(Component.literal("Arrows instantly kill mobs that have Passive or PlayerFear enabled."))
			.build());
		category.addEntry(entries.startBooleanToggle(Component.literal("Player Crawl"), specialSettings.playerCrawl)
			.setDefaultValue(true)
			.setSaveConsumer(value -> specialSettings.playerCrawl = value)
			.setTooltip(Component.literal("Hold sneak + sprint (Shift + Ctrl by default) to crawl on the floor."))
			.build());
	}

	private static void addMobCategories(ConfigBuilder builder, ConfigEntryBuilder entries) {
		List<EntityType<?>> vanilla = new ArrayList<>();
		List<EntityType<?>> alex = new ArrayList<>();
		List<EntityType<?>> other = new ArrayList<>();

		for (EntityType<?> type : AnimalCategories.commandTargets()) {
			Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
			if (ModCompat.isAlexsMob(type)) {
				alex.add(type);
			} else if ("minecraft".equals(id.getNamespace())) {
				vanilla.add(type);
			} else {
				other.add(type);
			}
		}

		Comparator<EntityType<?>> byId = Comparator.comparing(type -> BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());
		vanilla.sort(byId);
		alex.sort(byId);
		other.sort(byId);

		ConfigCategory vanillaCategory = builder.getOrCreateCategory(Component.literal("Vanilla Mobs"));
		vanillaCategory.addEntry(entries.startTextDescription(
			Component.literal("Click a mob name to expand. Toggle behaviors or drag sliders, then Done to save.")
		).build());
		for (EntityType<?> type : vanilla) {
			vanillaCategory.addEntry(buildMobSubCategory(entries, type).build());
		}

		if (ModCompat.isAlexsMobsLoaded()) {
			ConfigCategory alexCategory = builder.getOrCreateCategory(Component.literal("Alex's Mobs"));
		alexCategory.addEntry(entries.startTextDescription(
			Component.literal("Any setting applies on top of original AI when Enabled. PlayerFear animals and grizzly/crocs start on; other Aggressive presets stay off until enabled.")
		).build());
			for (EntityType<?> type : alex) {
				alexCategory.addEntry(buildMobSubCategory(entries, type).build());
			}
		}

		if (!other.isEmpty()) {
			ConfigCategory otherCategory = builder.getOrCreateCategory(Component.literal("Other Mobs"));
		otherCategory.addEntry(entries.startTextDescription(
			Component.literal("Other modded living mobs currently loaded. Any setting applies on top of original AI when Enabled.")
		).build());
			for (EntityType<?> type : other) {
				otherCategory.addEntry(buildMobSubCategory(entries, type).build());
			}
		}
	}

	private static SubCategoryBuilder buildMobSubCategory(ConfigEntryBuilder entries, EntityType<?> type) {
		String mobId = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
		MobBehaviorEntry entry = AnimalBehaviorConfig.getOrCreateEntry(type);
		SubCategoryBuilder sub = entries.startSubCategory(Component.literal(mobId));
		sub.setExpanded(false);
		sub.setTooltip(Component.literal("Wild Behavior settings for " + mobId));

		sub.add(entries.startBooleanToggle(Component.literal("Enabled"), entry.enabled)
			.setDefaultValue(true)
			.setSaveConsumer(value -> entry.enabled = value)
			.setTooltip(Component.literal("When false, Wild Behavior ignores this mob entirely."))
			.build());

		sub.add(entries.startBooleanToggle(Component.literal("Herd"), entry.herd)
			.setDefaultValue(false)
			.setSaveConsumer(value -> entry.herd = value)
			.build());
		sub.add(entries.startBooleanToggle(Component.literal("Passive"), entry.passive)
			.setDefaultValue(false)
			.setSaveConsumer(value -> entry.passive = value)
			.build());
		sub.add(entries.startBooleanToggle(Component.literal("Player Fear"), entry.playerfear)
			.setDefaultValue(false)
			.setSaveConsumer(value -> entry.playerfear = value)
			.build());
		sub.add(entries.startBooleanToggle(Component.literal("Aggressive"), entry.aggressive)
			.setDefaultValue(false)
			.setSaveConsumer(value -> {
				entry.aggressive = value;
				if (value) {
					entry.waterAggression = false;
				}
			})
			.build());
		sub.add(entries.startBooleanToggle(Component.literal("Water Aggression"), entry.waterAggression)
			.setDefaultValue(false)
			.setSaveConsumer(value -> {
				entry.waterAggression = value;
				if (value) {
					entry.aggressive = false;
				}
			})
			.setTooltip(Component.literal("Standalone Aggressive variant: only acquire targets in water. Chase may continue ashore. Sea creatures default to this instead of Aggressive."))
			.build());
		sub.add(entries.startBooleanToggle(Component.literal("Passive Fear"), entry.passivefear)
			.setDefaultValue(false)
			.setSaveConsumer(value -> entry.passivefear = value)
			.setTooltip(Component.literal("Passive mobs flee from this entity type."))
			.build());
		sub.add(entries.startBooleanToggle(Component.literal("Weapon Scare"), entry.weaponscare)
			.setDefaultValue(false)
			.setSaveConsumer(value -> entry.weaponscare = value)
			.build());
		sub.add(entries.startBooleanToggle(Component.literal("Defender"), entry.defender)
			.setDefaultValue(false)
			.setSaveConsumer(value -> entry.defender = value)
			.build());

		sub.add(entries.startBooleanToggle(Component.literal("Fight Back"), entry.fightback)
			.setDefaultValue(false)
			.setSaveConsumer(value -> entry.fightback = value)
			.setTooltip(Component.literal("Requires Passive, Aggressive, or Water Aggression. Damaged individuals retaliate."))
			.build());

		sub.add(entries.startIntSlider(Component.literal("Fight Back Help"), Math.max(0, entry.fightbackhelp), 0, 16)
			.setDefaultValue(entry.fightback ? AnimalBehaviorConfig.DEFAULT_FIGHT_BACK_HELP : 0)
			.setTextGetter(value -> value <= 0
				? Component.literal("None")
				: Component.literal(Integer.toString(value)))
			.setSaveConsumer(value -> entry.fightbackhelp = value)
			.setTooltip(Component.literal("Nearest same-species allies within 20 blocks that join FightBack. Default 2 when FightBack is on."))
			.build());

		sub.add(entries.startIntSlider(Component.literal("Confidence"), Math.max(0, entry.confidence), 0, 64)
			.setDefaultValue(0)
			.setTextGetter(value -> value <= 0
				? Component.literal("Default")
				: Component.literal(Integer.toString(value)))
			.setSaveConsumer(value -> entry.confidence = value)
			.setTooltip(Component.literal("Pack size needed before Aggressive or Water Aggression mobs attack. 0 = type default."))
			.build());

		sub.add(entries.startIntSlider(Component.literal("Memory"), Math.max(0, entry.memory), 0, 60)
			.setDefaultValue(6)
			.setTextGetter(value -> Component.literal(value + "s"))
			.setSaveConsumer(value -> entry.memory = value)
			.setTooltip(Component.literal("Seconds to keep chasing or fleeing from a player after losing line of sight. 0 = forget immediately."))
			.build());

		sub.add(entries.startIntSlider(Component.literal("Aggressive Kill Timer"), Math.max(0, entry.aggressivekilltimer), 0, 300)
			.setDefaultValue(AnimalBehaviorConfig.DEFAULT_AGGRESSIVE_KILL_TIMER)
			.setTextGetter(value -> value <= 0
				? Component.literal("None")
				: Component.literal(value + "s"))
			.setSaveConsumer(value -> entry.aggressivekilltimer = value)
			.setTooltip(Component.literal("Seconds after an Aggressive kill before this mob may attack again. 0 = no cooldown."))
			.build());

		sub.add(entries.startIntSlider(Component.literal("Flight Flee Min"), Math.max(1, entry.flightfleemin), 1, 64)
			.setDefaultValue(AnimalBehaviorConfig.DEFAULT_FLIGHT_FLEE_MIN)
			.setTextGetter(value -> Component.literal(value + " blocks"))
			.setSaveConsumer(value -> entry.flightfleemin = value)
			.setTooltip(Component.literal("Used at runtime by bats, bees, and Alex flying birds when startled."))
			.build());
		sub.add(entries.startIntSlider(Component.literal("Flight Flee Max"), Math.max(1, entry.flightfleemax), 1, 64)
			.setDefaultValue(AnimalBehaviorConfig.DEFAULT_FLIGHT_FLEE_MAX)
			.setTextGetter(value -> Component.literal(value + " blocks"))
			.setSaveConsumer(value -> {
				entry.flightfleemax = Math.max(value, entry.flightfleemin);
			})
			.setTooltip(Component.literal("Used at runtime by bats, bees, and Alex flying birds when startled."))
			.build());

		sub.add(entries.startIntSlider(Component.literal("Flee Speed"), tenths(entry.speed), 0, 200)
			.setDefaultValue(0)
			.setTextGetter(value -> value <= 0
				? Component.literal("Default")
				: Component.literal(String.format("%.1f bps", value / 10.0D)))
			.setSaveConsumer(value -> entry.speed = value / 10.0D)
			.setTooltip(Component.literal("Flee speed in blocks/second. 0 uses the global default."))
			.build());

		sub.add(entries.startIntSlider(Component.literal("Herd Speed"), tenths(entry.herdspeed), 0, 200)
			.setDefaultValue(0)
			.setTextGetter(value -> value <= 0
				? Component.literal("Vanilla walk")
				: Component.literal(String.format("%.1f bps", value / 10.0D)))
			.setSaveConsumer(value -> entry.herdspeed = value / 10.0D)
			.setTooltip(Component.literal("Herd walk speed. 0 uses vanilla walk speed."))
			.build());

		sub.add(entries.startIntSlider(Component.literal("Melee Damage"), damageToSlider(entry.damage), 0, 100)
			.setDefaultValue(0)
			.setTextGetter(value -> value <= 0
				? Component.literal("Default")
				: Component.literal(String.format("%.1f", value / 2.0F)))
			.setSaveConsumer(value -> entry.damage = sliderToDamage(value))
			.setTooltip(Component.literal("Melee damage for Aggressive/Defender. 0 uses attribute default."))
			.build());

		return sub;
	}

	private static int tenths(double value) {
		if (value <= 0.0D) {
			return 0;
		}

		return (int) Math.round(value * 10.0D);
	}

	private static int damageToSlider(float damage) {
		if (damage <= 0.0F) {
			return 0;
		}

		return Math.max(1, Math.round(damage * 2.0F));
	}

	private static float sliderToDamage(int slider) {
		if (slider <= 0) {
			return 0.0F;
		}

		return slider / 2.0F;
	}
}
