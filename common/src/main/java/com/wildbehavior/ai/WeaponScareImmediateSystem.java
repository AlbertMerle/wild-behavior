package com.wildbehavior.ai;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import com.wildbehavior.config.AnimalBehaviorConfig;

/**
 * Immediate radius flee for loud weapon events — gunshots (WeaponMod mixin / projectile
 * memory) and explosions ({@link ExplosionScareMixin}). Does not wait for idle threat scans.
 */
public final class WeaponScareImmediateSystem {
	private WeaponScareImmediateSystem() {
	}

	public static void onGunFired(Level level, Vec3 origin, @Nullable LivingEntity shooter) {
		if (level.isClientSide()) {
			return;
		}

		if (shooter instanceof Player player && PlayerApproachDetector.ignoresWildBehavior(player)) {
			return;
		}

		Player fleeThreat = shooter instanceof Player player ? player : null;
		scareInRadius(level, origin, AnimalBehaviorConfig.weaponScareGunshotRadius(), fleeThreat);
	}

	public static void onExplosion(Level level, Vec3 center) {
		if (level.isClientSide()) {
			return;
		}

		ExplosionTracker.register(level, center);
		scareInRadius(level, center, AnimalBehaviorConfig.weaponScareExplosionRadius(), null);
	}

	private static void scareInRadius(Level level, Vec3 origin, double radius, @Nullable Player shooter) {
		double radiusSqr = radius * radius;
		AABB searchBox = AABB.ofSize(origin, radius * 2.0D, radius * 2.0D, radius * 2.0D);

		for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, searchBox)) {
			if (!shouldScareImmediately(entity)) {
				continue;
			}

			if (entity.position().distanceToSqr(origin) > radiusSqr) {
				continue;
			}

			AnimalFleeSystem.fleeFromWeaponScare(entity, origin, shooter);
			SpookSystem.propagateSpook(entity, shooter, origin);
		}
	}

	private static boolean shouldScareImmediately(LivingEntity entity) {
		if (!entity.isAlive() || entity.isPassenger()) {
			return false;
		}

		if (!MobBehaviorConfig.isModEnabled(entity.getType())) {
			return false;
		}

		if (!(entity instanceof Animal) && !(entity instanceof Bat)) {
			return false;
		}

		if (!(entity instanceof PathfinderMob) && !(entity instanceof Bat)) {
			return false;
		}

		if (!WeaponScareDetector.reactsToWeaponScare(entity)) {
			return false;
		}

		if (EntityPlayerTrust.bypassesAllWildBehavior(entity)) {
			return false;
		}

		if (HerdFenceProximity.isNearFenceOrGate(entity)
			|| VanillaAiFallback.isPackedWithHerdmates(entity)) {
			return false;
		}

		if (!ThreatApproachDetector.canProcessFlee(entity)) {
			return false;
		}

		return !(entity instanceof Animal animal && AnimalFoodLureSystem.suppressesWildBehaviorOverlay(animal));
	}
}
