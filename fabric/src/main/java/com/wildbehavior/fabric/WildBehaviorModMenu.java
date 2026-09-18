package com.wildbehavior.fabric;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import com.wildbehavior.client.config.WildBehaviorConfigScreens;

public final class WildBehaviorModMenu implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return WildBehaviorConfigScreens::create;
	}
}
