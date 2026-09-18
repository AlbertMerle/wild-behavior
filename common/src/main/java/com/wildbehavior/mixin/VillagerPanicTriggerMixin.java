package com.wildbehavior.mixin;

import com.wildbehavior.ai.VillagerFleeSupport;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.VillagerPanicTrigger;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(VillagerPanicTrigger.class)
public abstract class VillagerPanicTriggerMixin {
	@Inject(method = "start", at = @At("HEAD"), cancellable = true)
	private void animalBehavior$skipWhenModFlee(ServerLevel level, Villager villager, long gameTime, CallbackInfo ci) {
		if (VillagerFleeSupport.usesModPassiveFlee(villager) && VillagerFleeSupport.hasModFleeThreat(villager)) {
			ci.cancel();
		}
	}

	@Inject(method = "canStillUse", at = @At("HEAD"), cancellable = true)
	private void animalBehavior$stopWhenModFlee(ServerLevel level, Villager villager, long gameTime, CallbackInfoReturnable<Boolean> cir) {
		if (VillagerFleeSupport.usesModPassiveFlee(villager) && VillagerFleeSupport.hasModFleeThreat(villager)) {
			cir.setReturnValue(false);
		}
	}
}
