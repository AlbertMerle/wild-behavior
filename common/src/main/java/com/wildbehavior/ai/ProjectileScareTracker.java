package com.wildbehavior.ai;

import com.wildbehavior.config.AnimalBehaviorConfig;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Remembers recent projectile firings so WeaponScare can react even when a bullet
 * (e.g. musket) is too fast for the staggered idle flee scan to see it in flight.
 */
public final class ProjectileScareTracker {
	private static final List<RecentProjectileScare> RECENT = new ArrayList<>();

	private ProjectileScareTracker() {
	}

	public static void register(Level level, Vec3 center, double radius) {
		if (level.isClientSide() || radius <= 0.0D) {
			return;
		}

		synchronized (RECENT) {
			RECENT.add(new RecentProjectileScare(level.dimension(), center, radius, level.getGameTime()));
			prune(level.getGameTime());
		}
	}

	@Nullable
	public static Vec3 findNearest(Level level, Vec3 position) {
		Vec3 nearest = null;
		double nearestDistanceSqr = Double.MAX_VALUE;
		long gameTime = level.getGameTime();
		int memoryTicks = AnimalBehaviorConfig.weaponScareExplosionMemoryTicks();

		synchronized (RECENT) {
			prune(gameTime);
			for (RecentProjectileScare scare : RECENT) {
				if (!scare.dimension().equals(level.dimension())) {
					continue;
				}

				if (gameTime - scare.tick() > memoryTicks) {
					continue;
				}

				double radiusSqr = scare.radius() * scare.radius();
				double distanceSqr = position.distanceToSqr(scare.center());
				if (distanceSqr > radiusSqr || distanceSqr >= nearestDistanceSqr) {
					continue;
				}

				nearest = scare.center();
				nearestDistanceSqr = distanceSqr;
			}
		}

		return nearest;
	}

	private static void prune(long gameTime) {
		int memoryTicks = AnimalBehaviorConfig.weaponScareExplosionMemoryTicks();
		Iterator<RecentProjectileScare> iterator = RECENT.iterator();
		while (iterator.hasNext()) {
			RecentProjectileScare scare = iterator.next();
			if (gameTime - scare.tick() > memoryTicks) {
				iterator.remove();
			}
		}
	}

	private record RecentProjectileScare(
		ResourceKey<Level> dimension,
		Vec3 center,
		double radius,
		long tick
	) {
	}
}
