package com.wildbehavior.mixin;

import com.wildbehavior.ai.EntityPlayerTrust;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractHorse.class)
public abstract class AbstractHorseFeedMixin {
	@Inject(method = "handleEating", at = @At("RETURN"))
	private void animalBehavior$onFed(Player player, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
		if (Boolean.TRUE.equals(cir.getReturnValue())) {
			AbstractHorse self = (AbstractHorse) (Object) this;
			if (!self.level().isClientSide() && EntityPlayerTrust.shouldTrackFeeding(self)) {
				EntityPlayerTrust.markFedByPlayer(self);
			}
		}
	}
}
