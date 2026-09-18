package com.wildbehavior.mixin;

import com.wildbehavior.ai.AnimalEatingGate;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.EatBlockGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EatBlockGoal.class)
public abstract class EatBlockGoalMixin {
	@Shadow
	private Mob mob;

	@Inject(method = "canUse", at = @At("HEAD"), cancellable = true)
	private void animalBehavior$blockEatWhenSuppressed(CallbackInfoReturnable<Boolean> cir) {
		if (!AnimalEatingGate.shouldAllowGrassEating(this.mob)) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "canContinueToUse", at = @At("HEAD"), cancellable = true)
	private void animalBehavior$blockEatContinueWhenSuppressed(CallbackInfoReturnable<Boolean> cir) {
		if (!AnimalEatingGate.shouldAllowGrassEating(this.mob)) {
			cir.setReturnValue(false);
		}
	}
}
