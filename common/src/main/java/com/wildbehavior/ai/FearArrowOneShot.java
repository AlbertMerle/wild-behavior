package com.wildbehavior.ai;

import com.wildbehavior.config.AnimalBehaviorConfig;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;

/**
 * Special setting {@code oneshotonFear}: when enabled, any arrow that hits a mob with
 * {@link MobBehavior#PASSIVE} or {@link MobBehavior#PLAYER_FEAR} deals lethal damage.
 */
public final class FearArrowOneShot {
	private FearArrowOneShot() {
	}

	/**
	 * If oneshotonFear is on and this victim has Passive or PlayerFear, boost arrow damage to kill.
	 * Called from Passive / PlayerFear-aware hurt handling.
	 */
	public static float adjustArrowDamage(LivingEntity victim, DamageSource source, float amount) {
		if (!AnimalBehaviorConfig.isOneShotOnFearEnabled()) {
			return amount;
		}

		if (!(victim instanceof Mob) || !isArrowDamage(source)) {
			return amount;
		}

		if (!appliesToFearBehaviors(victim)) {
			return amount;
		}

		float lethal = victim.getHealth() + victim.getAbsorptionAmount() + 1.0F;
		return Math.max(amount, lethal);
	}

	/** True when Passive or PlayerFear is enabled for this mob (the settings this special option attaches to). */
	public static boolean appliesToFearBehaviors(LivingEntity victim) {
		return MobBehaviorConfig.isEnabled(victim.getType(), MobBehavior.PASSIVE)
			|| MobBehaviorConfig.isEnabled(victim.getType(), MobBehavior.PLAYER_FEAR);
	}

	private static boolean isArrowDamage(DamageSource source) {
		if (source.is(DamageTypes.ARROW)) {
			return true;
		}

		Entity direct = source.getDirectEntity();
		return direct instanceof AbstractArrow && !(direct instanceof ThrownTrident);
	}
}
