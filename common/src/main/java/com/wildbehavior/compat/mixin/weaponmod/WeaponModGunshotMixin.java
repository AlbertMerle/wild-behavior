package com.wildbehavior.compat.mixin.weaponmod;

import com.wildbehavior.ai.WeaponScareImmediateSystem;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Balkon's WeaponMod: flintlock, musket, mortar, blunderbuss, crossbow, blowgun, etc.
 * {@code RangedComponent.fire} is abstract (enum body implementations) — mixin targets
 * the concrete {@code postShootingEffects} hook instead.
 */
@Mixin(targets = "ckathode.weaponmod.item.RangedComponent", remap = false)
public abstract class WeaponModGunshotMixin {
	@Inject(method = "postShootingEffects", at = @At("HEAD"))
	private void wildBehavior$onGunFired(ItemStack stack, LivingEntity shooter, Level level, CallbackInfo ci) {
		if (!level.isClientSide() && shooter != null) {
			WeaponScareImmediateSystem.onGunFired(level, shooter.position(), shooter);
		}
	}
}
