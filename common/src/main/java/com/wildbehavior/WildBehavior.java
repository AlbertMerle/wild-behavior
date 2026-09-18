package com.wildbehavior;

import com.wildbehavior.command.AnimalBehaviorCommands;
import com.wildbehavior.compat.ModCompat;
import com.wildbehavior.config.AnimalBehaviorConfig;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.platform.Platform;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WildBehavior {
	/** Fabric mod id and Minecraft resource namespace. */
	public static final String MOD_ID = "wild-behavior";
	/** NeoForge forbids hyphens in mod ids. */
	public static final String NEOFORGE_MOD_ID = "wild_behavior";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private WildBehavior() {
	}

	public static void init() {
		AnimalBehaviorEvents.register();
		AnimalBehaviorCommands.register();
		// NeoForge @Mod constructors run before attributes (e.g. neoforge:swim_speed) are bound.
		// Config completeness calls DefaultAttributes.hasSupplier, which would crash there.
		if (Platform.isNeoForge()) {
			LifecycleEvent.SETUP.register(WildBehavior::loadConfig);
		} else {
			loadConfig();
		}
		LifecycleEvent.SERVER_STARTING.register(server -> {
			AnimalBehaviorConfig.refreshMobEntriesFromRegistry();
			if (AnimalBehaviorConfig.ensureAlexsMobsDefaults()) {
				LOGGER.info("Applied Alex's Mobs Wild Behavior presets (PlayerFear + grizzly/crocs enabled by default; other aggressive presets off until toggled)");
			}
			ModCompat.logLoadedIntegrations();
		});
	}

	private static void loadConfig() {
		AnimalBehaviorConfig.load();
		LOGGER.info("Wild Behavior loaded — config at {}", AnimalBehaviorConfig.getConfigPath());
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
