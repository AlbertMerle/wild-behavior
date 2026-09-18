package com.wildbehavior.ai;

import com.wildbehavior.config.AnimalBehaviorConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.core.Holder;
import net.minecraft.world.Difficulty;

public final class AnimalMeleeCombat {
	public static final float DEFAULT_AGGRESSIVE_DAMAGE = 2.0F;
	public static final float DEFAULT_DEFENDER_DAMAGE = 3.0F;
	private static final int DEFAULT_ATTACK_COOLDOWN_TICKS = 15;

	private AnimalMeleeCombat() {
	}

	/** Returns true when the mob is in melee range (including while waiting on attack cooldown). */
	public static boolean tryMeleeAttack(Mob mob, LivingEntity target, AnimalAggroState state, float defaultDamage) {
		if (!mob.isWithinMeleeAttackRange(target) || !mob.hasLineOfSight(target)) {
			return false;
		}

		if (mob instanceof PathfinderMob pathfinderMob) {
			pathfinderMob.getNavigation().stop();
		}

		long tick = mob.level().getGameTime();
		if (tick - state.getLastAttackTick() < getAttackCooldownTicks(mob)) {
			return true;
		}

		float damage = getMeleeDamage(mob, defaultDamage);
		mob.swing(InteractionHand.MAIN_HAND);
		mob.jumpFromGround();
		if (dealMeleeDamage(mob, target, damage)) {
			DamageSource source = mob.damageSources().mobAttack(mob);
			target.knockback(0.4D, mob.getX() - target.getX(), mob.getZ() - target.getZ(), source, damage);
		}

		state.setLastAttackTick(tick);
		return true;
	}

	public static float getMeleeDamage(Mob mob, float defaultDamage) {
		float configured = AnimalBehaviorConfig.getMeleeDamage(mob.getType());
		if (configured > 0.0F) {
			return configured;
		}

		return (float) getAttributeOrDefault(mob, Attributes.ATTACK_DAMAGE, defaultDamage);
	}

	private static boolean dealMeleeDamage(Mob mob, LivingEntity target, float damage) {
		if (damage <= 0.0F) {
			return false;
		}

		DamageSource source = mob.damageSources().mobAttack(mob);
		if (target instanceof Player player && wouldPeacefulBlockMobAttack(player, source)) {
			// mob_attack from non-player mobs is zeroed on Peaceful for players only; other mobs still take damage.
			source = mob.damageSources().generic();
		}

		float finalDamage = damage;
		if (mob.level() instanceof ServerLevel serverLevel) {
			finalDamage = EnchantmentHelper.modifyDamage(serverLevel, mob.getWeaponItem(), target, source, damage);
		}

		target.hurt(source, finalDamage);
		return true;
	}

	private static boolean wouldPeacefulBlockMobAttack(Player player, DamageSource source) {
		return source.scalesWithDifficulty() && player.level().getDifficulty() == Difficulty.PEACEFUL;
	}

	public static int getAttackCooldownTicks(Mob mob) {
		if (!mob.getAttributes().hasAttribute(Attributes.ATTACK_SPEED)) {
			return DEFAULT_ATTACK_COOLDOWN_TICKS;
		}

		double attackSpeed = getAttributeOrDefault(mob, Attributes.ATTACK_SPEED, 0.0D);
		if (attackSpeed <= 0.0D) {
			return DEFAULT_ATTACK_COOLDOWN_TICKS;
		}

		return Math.max(5, (int) Math.ceil(20.0D / attackSpeed));
	}

	private static double getAttributeOrDefault(LivingEntity entity, Holder<Attribute> attribute, double fallback) {
		if (!entity.getAttributes().hasAttribute(attribute)) {
			return fallback;
		}

		return entity.getAttributeValue(attribute);
	}
}
