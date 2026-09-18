package com.wildbehavior.mixin;

import com.wildbehavior.ai.EquineBreedingHelper;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractHorse.class)
public abstract class AbstractHorseTamedMixin {
	@Inject(method = "isTamed", at = @At("HEAD"), cancellable = true)
	private void wildBehavior$trustTagCountsAsTamed(CallbackInfoReturnable<Boolean> cir) {
		AbstractHorse self = (AbstractHorse) (Object) this;
		if (EquineBreedingHelper.isManagedBreedableEquine(self) && EquineBreedingHelper.hasWildBehaviorTrust(self)) {
			cir.setReturnValue(true);
		}
	}
}
