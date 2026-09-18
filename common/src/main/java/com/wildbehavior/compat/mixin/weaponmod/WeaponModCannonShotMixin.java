package com.wildbehavior.compat.mixin.weaponmod;

import com.wildbehavior.ai.WeaponScareImmediateSystem;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Balkon's WeaponMod placed cannons call {@code EntityCannon.fireCannon} when fired.
 */
@Mixin(targets = "ckathode.weaponmod.entity.EntityCannon", remap = false)
public abstract class WeaponModCannonShotMixin {
	@Inject(method = "fireCannon", at = @At("HEAD"))
	private void wildBehavior$onCannonFired(CallbackInfo ci) {
		Entity self = (Entity) (Object) this;
		if (self.level().isClientSide()) {
			return;
		}

		LivingEntity shooter = null;
		if (!self.getPassengers().isEmpty() && self.getPassengers().getFirst() instanceof LivingEntity passenger) {
			shooter = passenger;
		}

		WeaponScareImmediateSystem.onGunFired(self.level(), self.position(), shooter);
	}
}
