package com.wildbehavior.mixin;

import com.wildbehavior.AnimalBehaviorEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityUnloadMixin {
	@Inject(method = "setRemoved", at = @At("HEAD"))
	private void wildBehavior$onRemoved(Entity.RemovalReason reason, CallbackInfo ci) {
		Entity self = (Entity) (Object) this;
		if (self instanceof LivingEntity livingEntity) {
			AnimalBehaviorEvents.onEntityUnload(livingEntity);
		}
	}
}
