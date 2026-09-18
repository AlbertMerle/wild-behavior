package com.wildbehavior.compat;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

/**
 * Applies Alex's Mobs compatibility mixins only when that mod is loaded.
 * Uses reflection so this plugin works on Fabric and NeoForge before Architectury is fully up.
 */
public final class WildBehaviorMixinPlugin implements IMixinConfigPlugin {
	private static final String ALEXSMOBS_MIXIN_PREFIX = "com.wildbehavior.compat.mixin.";
	private static final String WEAPONMOD_MIXIN_PREFIX = "com.wildbehavior.compat.mixin.weaponmod.";

	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (mixinClassName.startsWith(WEAPONMOD_MIXIN_PREFIX)) {
			return isModLoaded("weaponmod");
		}

		if (mixinClassName.startsWith(ALEXSMOBS_MIXIN_PREFIX)) {
			return isModLoaded("alexsmobs");
		}

		return true;
	}

	private static boolean isModLoaded(String modId) {
		try {
			Class<?> platform = Class.forName("dev.architectury.platform.Platform");
			Method method = platform.getMethod("isModLoaded", String.class);
			return (Boolean) method.invoke(null, modId);
		} catch (Throwable ignored) {
		}
		try {
			Class<?> loader = Class.forName("net.fabricmc.loader.api.FabricLoader");
			Object instance = loader.getMethod("getInstance").invoke(null);
			return (Boolean) loader.getMethod("isModLoaded", String.class).invoke(instance, modId);
		} catch (Throwable ignored) {
		}
		try {
			Class<?> modList = Class.forName("net.neoforged.fml.ModList");
			Object instance = modList.getMethod("get").invoke(null);
			if (instance == null) {
				return false;
			}
			return (Boolean) modList.getMethod("isLoaded", String.class).invoke(instance, modId);
		} catch (Throwable ignored) {
		}
		return false;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
