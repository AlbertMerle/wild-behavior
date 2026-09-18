package com.wildbehavior.mixin;

import com.wildbehavior.ai.AnimalEatingGate;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractHorse.class)
public abstract class AbstractHorseEatMixin {
	@Inject(method = "canEatGrass", at = @At("HEAD"), cancellable = true)
	private void animalBehavior$blockHorseEatWhenSuppressed(CallbackInfoReturnable<Boolean> cir) {
		AbstractHorse self = (AbstractHorse) (Object) this;
		if (!AnimalEatingGate.shouldAllowGrassEating(self)) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "setEating", at = @At("HEAD"), cancellable = true)
	private void animalBehavior$blockHorseStartEatingWhenSuppressed(boolean eating, CallbackInfo ci) {
		if (!eating) {
			return;
		}

		AbstractHorse self = (AbstractHorse) (Object) this;
		if (!AnimalEatingGate.shouldAllowGrassEating(self)) {
			ci.cancel();
		}
	}

	@Inject(method = "aiStep", at = @At("TAIL"))
	private void animalBehavior$enforceHorseEatingSuppression(CallbackInfo ci) {
		AnimalEatingGate.enforceEatingSuppression((AbstractHorse) (Object) this);
	}
}
