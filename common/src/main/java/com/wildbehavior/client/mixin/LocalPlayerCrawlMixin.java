package com.wildbehavior.client.mixin;

import com.wildbehavior.config.AnimalBehaviorConfig;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Pose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client prediction for voluntary crawl (sneak + sprint). Server authoritative pose
 * still comes from {@code PlayerCrawlMixin} / {@code PlayerCrawlSystem}.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerCrawlMixin {
	@Inject(method = "aiStep", at = @At("TAIL"))
	private void wildBehavior$predictPlayerCrawl(CallbackInfo ci) {
		LocalPlayer player = (LocalPlayer) (Object) this;
		if (!AnimalBehaviorConfig.isPlayerCrawlEnabled()) {
			return;
		}

		if (player.isSpectator()
			|| player.isPassenger()
			|| player.isSleeping()
			|| player.isFallFlying()
			|| player.getAbilities().flying) {
			return;
		}

		if (player.input.keyPresses.shift() && player.input.keyPresses.sprint()) {
			player.setSwimming(true);
			player.setPose(Pose.SWIMMING);
		}
	}
}
