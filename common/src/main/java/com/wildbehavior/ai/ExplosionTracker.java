package com.wildbehavior.ai;

import com.wildbehavior.config.AnimalBehaviorConfig;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public final class ExplosionTracker {
	private static final List<RecentExplosion> RECENT = new ArrayList<>();

	private ExplosionTracker() {
	}

	public static void register(Level level, Vec3 center) {
		if (level.isClientSide()) {
			return;
		}

		synchronized (RECENT) {
			RECENT.add(new RecentExplosion(level.dimension(), center, level.getGameTime()));
			prune(level.getGameTime());
		}
	}

	public static Vec3 findNearestExplosion(Level level, Vec3 position, double radius) {
		double radiusSqr = radius * radius;
		Vec3 nearest = null;
		double nearestDistanceSqr = Double.MAX_VALUE;
		long gameTime = level.getGameTime();
		int memoryTicks = AnimalBehaviorConfig.weaponScareExplosionMemoryTicks();

		synchronized (RECENT) {
			prune(gameTime);
			for (RecentExplosion explosion : RECENT) {
				if (!explosion.dimension().equals(level.dimension())) {
					continue;
				}

				if (gameTime - explosion.tick() > memoryTicks) {
					continue;
				}

				double distanceSqr = position.distanceToSqr(explosion.center());
				if (distanceSqr > radiusSqr || distanceSqr >= nearestDistanceSqr) {
					continue;
				}

				nearest = explosion.center();
				nearestDistanceSqr = distanceSqr;
			}
		}

		return nearest;
	}

	private static void prune(long gameTime) {
		int memoryTicks = AnimalBehaviorConfig.weaponScareExplosionMemoryTicks();
		Iterator<RecentExplosion> iterator = RECENT.iterator();
		while (iterator.hasNext()) {
			RecentExplosion explosion = iterator.next();
			if (gameTime - explosion.tick() > memoryTicks) {
				iterator.remove();
			}
		}
	}

	private record RecentExplosion(ResourceKey<Level> dimension, Vec3 center, long tick) {
	}
}
