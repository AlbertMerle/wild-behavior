package com.wildbehavior.client.config;

import dev.architectury.platform.Platform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Entry point for Mod Menu / NeoForge config UI. Avoids hard-linking Cloth Config classes so the mod
 * still loads when Cloth Config is not installed.
 * Client-only: referenced from Fabric Mod Menu / NeoForge {@code Dist.CLIENT} entrypoints.
 */
public final class WildBehaviorConfigScreens {
	private WildBehaviorConfigScreens() {
	}

	public static Screen create(Screen parent) {
		if (!Platform.isModLoaded("cloth-config") && !Platform.isModLoaded("cloth_config")) {
			return new ConfirmScreen(
				confirmed -> Minecraft.getInstance().setScreenAndShow(parent),
				Component.literal("Wild Behavior Config"),
				Component.literal("Install Cloth Config to edit settings in-game."),
				Component.literal("Back"),
				Component.literal("Back")
			);
		}

		try {
			Class<?> cloth = Class.forName("com.wildbehavior.client.config.WildBehaviorClothConfig");
			return (Screen) cloth.getMethod("create", Screen.class).invoke(null, parent);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException("Failed to open Wild Behavior Cloth Config screen", exception);
		}
	}
}
