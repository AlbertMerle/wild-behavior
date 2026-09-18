package com.wildbehavior.mixin;

import com.wildbehavior.ai.AnimalAggroSystem;
import com.wildbehavior.ai.AnimalFightBackSystem;
import com.wildbehavior.ai.FearArrowOneShot;
import com.wildbehavior.ai.NearbyPlayerAiGate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityHurtMixin {
	@ModifyVariable(method = "hurtServer", at = @At("HEAD"), argsOnly = true)
	private float wildBehavior$oneshotOnFearArrow(float amount, ServerLevel level, DamageSource damageSource) {
		return FearArrowOneShot.adjustArrowDamage((LivingEntity) (Object) this, damageSource, amount);
	}

	@Inject(method = "hurtServer", at = @At("RETURN"))
	private void animalBehavior$onHurt(ServerLevel level, DamageSource damageSource, float amount, CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValue()) {
			return;
		}

		LivingEntity victim = (LivingEntity) (Object) this;
		if (NearbyPlayerAiGate.isNearPlayer(victim)) {
			AnimalFightBackSystem.onDamaged(victim, damageSource, amount);
		}

		if (!victim.isAlive()) {
			Entity attacker = damageSource.getEntity();
			if (attacker instanceof Mob mob) {
				AnimalAggroSystem.notifyKill(mob, victim);
			}
		}
	}
}
