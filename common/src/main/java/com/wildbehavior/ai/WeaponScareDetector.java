package com.wildbehavior.ai;

import com.wildbehavior.config.AnimalBehaviorConfig;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public final class WeaponScareDetector {
	private static final double MIN_FLYING_PROJECTILE_SPEED_SQR = 0.01D;
	/** Musket/flintlock shoot around 5; full-draw bows are lower. */
	private static final double LOUD_PROJECTILE_SPEED = 4.0D;

	private WeaponScareDetector() {
	}

	/** WeaponScare toggle — explosions and flying arrows (not gunshot radius). */
	public static boolean isEnabled(LivingEntity entity) {
		return MobBehaviorConfig.isEnabled(entity.getType(), MobBehavior.WEAPON_SCARE);
	}

	/**
	 * Passive / PlayerFear / WeaponScare mobs react to loud gunshots via radius memory.
	 * Fed/tamed mobs skip Wild Behavior entirely ({@link EntityPlayerTrust#bypassesAllWildBehavior}).
	 */
	public static boolean reactsToWeaponScare(LivingEntity entity) {
		if (EntityPlayerTrust.bypassesAllWildBehavior(entity)) {
			return false;
		}

		EntityType<?> type = entity.getType();
		return MobBehaviorConfig.isEnabled(type, MobBehavior.WEAPON_SCARE)
			|| MobBehaviorConfig.isEnabled(type, MobBehavior.PLAYER_FEAR)
			|| MobBehaviorConfig.isEnabled(type, MobBehavior.PASSIVE);
	}

	@Nullable
	public static Vec3 findNearestScareOrigin(LivingEntity entity) {
		if (!reactsToWeaponScare(entity)) {
			return null;
		}

		Vec3 nearest = null;
		double nearestDistanceSqr = Double.MAX_VALUE;

		if (isEnabled(entity)) {
			Vec3 explosion = ExplosionTracker.findNearestExplosion(
				entity.level(),
				entity.position(),
				AnimalBehaviorConfig.weaponScareExplosionRadius()
			);
			if (explosion != null) {
				nearest = explosion;
				nearestDistanceSqr = entity.position().distanceToSqr(explosion);
			}

			double arrowRadius = AnimalBehaviorConfig.weaponScareArrowRadius();
			Vec3 projectile = findNearestFlyingProjectile(entity, arrowRadius);
			if (projectile != null) {
				double projectileDistanceSqr = entity.position().distanceToSqr(projectile);
				if (projectileDistanceSqr < nearestDistanceSqr) {
					nearest = projectile;
					nearestDistanceSqr = projectileDistanceSqr;
				}
			}
		}

		Vec3 recentShot = ProjectileScareTracker.findNearest(entity.level(), entity.position());
		if (recentShot != null) {
			double shotDistanceSqr = entity.position().distanceToSqr(recentShot);
			if (shotDistanceSqr < nearestDistanceSqr) {
				nearest = recentShot;
			}
		}

		return nearest;
	}

	/**
	 * Records a scare origin when a weapon-like projectile enters the world.
	 * Covers vanilla arrows/tridents and Balkon's WeaponMod bullets/shells/bolts
	 * (all extend {@link AbstractArrow}), plus fireballs and similar.
	 * Fast shots (musket/flintlock velocity) use gunshotRadius (radius flee, not flying-entity
	 * detection); slower arrows use arrowRadius.
	 */
	public static void onProjectileAdded(Projectile projectile) {
		if (projectile.level().isClientSide() || !isWeaponScareProjectile(projectile)) {
			return;
		}

		if (isCreativePlayerProjectile(projectile.getOwner())) {
			return;
		}

		double speedSqr = projectile.getDeltaMovement().lengthSqr();
		if (speedSqr < MIN_FLYING_PROJECTILE_SPEED_SQR) {
			return;
		}

		double scareRadius = Math.sqrt(speedSqr) >= LOUD_PROJECTILE_SPEED
			? AnimalBehaviorConfig.weaponScareGunshotRadius()
			: AnimalBehaviorConfig.weaponScareArrowRadius();
		ProjectileScareTracker.register(projectile.level(), projectile.position(), scareRadius);
	}

	@Nullable
	private static Vec3 findNearestFlyingProjectile(LivingEntity entity, double radius) {
		double radiusSqr = radius * radius;
		Vec3 nearest = null;
		double nearestDistanceSqr = Double.MAX_VALUE;

		for (Projectile projectile : entity.level().getEntitiesOfClass(
			Projectile.class,
			entity.getBoundingBox().inflate(radius),
			WeaponScareDetector::isWeaponScareProjectile
		)) {
			if (!projectile.isAlive() || projectile.isRemoved()) {
				continue;
			}

			if (projectile.getDeltaMovement().lengthSqr() < MIN_FLYING_PROJECTILE_SPEED_SQR) {
				continue;
			}

			if (isCreativePlayerProjectile(projectile.getOwner())) {
				continue;
			}

			double distanceSqr = entity.distanceToSqr(projectile);
			if (distanceSqr > radiusSqr || distanceSqr >= nearestDistanceSqr) {
				continue;
			}

			nearest = projectile.position();
			nearestDistanceSqr = distanceSqr;
		}

		return nearest;
	}

	/**
	 * Arrows/bolts/bullets/shells ({@link AbstractArrow}, including WeaponMod) and
	 * damaging hurled projectiles (fireballs, etc.). Excludes fishing bobbers and
	 * soft throwables (snowballs, eggs, ender pearls, potions).
	 */
	private static boolean isWeaponScareProjectile(Projectile projectile) {
		return projectile instanceof AbstractArrow
			|| projectile instanceof AbstractHurtingProjectile;
	}

	private static boolean isCreativePlayerProjectile(@Nullable net.minecraft.world.entity.Entity owner) {
		return owner instanceof Player player && PlayerApproachDetector.ignoresWildBehavior(player);
	}
}
