package com.wildbehavior.mixin;

import com.wildbehavior.ai.AnimalCategories;
import com.wildbehavior.ai.AnimalFightBackSystem;
import com.wildbehavior.ai.AnimalFleeSystem;
import com.wildbehavior.ai.MobBehaviorConfig;
import com.wildbehavior.ai.NearbyPlayerAiGate;
import com.wildbehavior.ai.VillagerFleeSupport;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Villager.class)
public abstract class VillagerFleeMixin {
	@Inject(method = "customServerAiStep", at = @At("TAIL"))
	private void animalBehavior$tickFleeAfterBrain(ServerLevel serverLevel, CallbackInfo ci) {
		Villager self = (Villager) (Object) this;
		if (self.level().isClientSide() || !MobBehaviorConfig.isModEnabled(self.getType())) {
			return;
		}

		if (!NearbyPlayerAiGate.allowOverlayTick(self)) {
			return;
		}

		if (AnimalFightBackSystem.isFightBacking(self)) {
			return;
		}

		if (!AnimalCategories.receivesFleeBehavior(self)) {
			return;
		}

		if (VillagerFleeSupport.usesModPassiveFlee(self) && VillagerFleeSupport.hasModFleeThreat(self)) {
			VillagerFleeSupport.suppressVanillaPanic(self);
		}

		AnimalFleeSystem.tickPathfinderMob(self);
	}
}
