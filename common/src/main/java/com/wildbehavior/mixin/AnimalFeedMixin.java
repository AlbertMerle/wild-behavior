package com.wildbehavior.mixin;

import com.wildbehavior.ai.EntityPlayerTrust;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mob.class)
public abstract class AnimalFeedMixin {
	@Inject(method = "usePlayerItem", at = @At("RETURN"))
	private void animalBehavior$onFed(Player player, InteractionHand hand, ItemStack stack, CallbackInfo ci) {
		Mob self = (Mob) (Object) this;
		if (self instanceof Animal animal && !animal.level().isClientSide() && EntityPlayerTrust.shouldTrackFeeding(animal)) {
			EntityPlayerTrust.markFedByPlayer(animal);
		}
	}
}
