package com.wildbehavior.mixin;

import com.wildbehavior.ai.WeaponScareImmediateSystem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tracks explosions and immediately scares nearby mobs via {@link WeaponScareImmediateSystem}.
 * Inject at construction (not only {@code explode()}) so WeaponMod mortar/cannon
 * {@code AdvancedExplosion} is caught — that class extends {@link ServerExplosion} but
 * detonates via custom methods and never calls {@code explode()}.
 */
@Mixin(ServerExplosion.class)
public abstract class ExplosionScareMixin {
	@Inject(method = "<init>", at = @At("RETURN"))
	private void wildBehavior$trackExplosionOnCreate(
		ServerLevel level,
		Entity source,
		DamageSource damageSource,
		ExplosionDamageCalculator damageCalculator,
		Vec3 center,
		float radius,
		boolean fire,
		Explosion.BlockInteraction blockInteraction,
		CallbackInfo ci
	) {
		WeaponScareImmediateSystem.onExplosion(level, center);
	}
}
