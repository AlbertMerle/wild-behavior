package com.wildbehavior.fabric;

import com.wildbehavior.WildBehavior;
import net.fabricmc.api.ModInitializer;

public final class WildBehaviorFabric implements ModInitializer {
	@Override
	public void onInitialize() {
		WildBehavior.init();
	}
}
