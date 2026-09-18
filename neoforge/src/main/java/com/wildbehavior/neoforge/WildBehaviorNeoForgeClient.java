package com.wildbehavior.neoforge;

import com.wildbehavior.WildBehavior;
import com.wildbehavior.client.config.WildBehaviorConfigScreens;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

@Mod(value = WildBehavior.NEOFORGE_MOD_ID, dist = Dist.CLIENT)
public final class WildBehaviorNeoForgeClient {
	public WildBehaviorNeoForgeClient(ModContainer container) {
		container.registerExtensionPoint(
			IConfigScreenFactory.class,
			(minecraft, parent) -> WildBehaviorConfigScreens.create(parent)
		);
	}
}
