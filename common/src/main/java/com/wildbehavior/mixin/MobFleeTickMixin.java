package com.wildbehavior.mixin;

import com.wildbehavior.ai.AnimalEatingGate;
import com.wildbehavior.ai.MobBehaviorConfig;
import com.wildbehavior.ai.NearbyPlayerAiGate;
import com.wildbehavior.ai.WildBehaviorDispatcher;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mob.class)
public abstract class MobFleeTickMixin {
	@Inject(
		method = "serverAiStep",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/navigation/PathNavigation;tick()V", shift = At.Shift.BEFORE)
	)
	private void animalBehavior$tickFleeBeforeNavigation(CallbackInfo ci) {
		Mob self = (Mob) (Object) this;
		if (self.level().isClientSide() || !(self instanceof PathfinderMob pathfinderMob)) {
			return;
		}

		if (!MobBehaviorConfig.isModEnabled(self.getType())) {
			return;
		}

		if (!NearbyPlayerAiGate.allowOverlayTick(self)) {
			return;
		}

		AnimalEatingGate.enforceEatingSuppression(self);
		WildBehaviorDispatcher.tickPathfinderMob(pathfinderMob);
	}
}
