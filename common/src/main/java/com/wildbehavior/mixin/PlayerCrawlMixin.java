package com.wildbehavior.mixin;

import com.wildbehavior.ai.PlayerCrawlSystem;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Re-applies the swimming flag before pose selection so sneak + sprint becomes crawl.
 * Vanilla {@code updateSwimming} clears the flag on land earlier in the tick.
 */
@Mixin(Player.class)
public abstract class PlayerCrawlMixin {
	@Inject(method = "updatePlayerPose", at = @At("HEAD"))
	private void wildBehavior$applyPlayerCrawl(CallbackInfo ci) {
		PlayerCrawlSystem.beforeUpdatePlayerPose((Player) (Object) this);
	}
}
