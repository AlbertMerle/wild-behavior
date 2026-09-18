package com.wildbehavior.ai;

import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Geometry helpers for wide mobs (elephants, bison, etc.) used by herd spacing and sidestepping.
 *
 * <p>The ledge step-up jumping that previously lived here was removed because it caused constant
 * jumping that broke herding. Only the non-jump utility methods remain.</p>
 */
public final class MobBlockStepHelper {
	/** Mob bounding-box width at/above which it is treated as a "wide" mob. */
	private static final float WIDE_MOB_WIDTH = 2.0F;
	/** Slight inflation so width probes match what players see vs narrow collision boxes. */
	private static final double HORIZONTAL_INFLATE = 0.2D;

	private MobBlockStepHelper() {
	}

	/**
	 * Picks a lateral pathfindable position to route wide mobs around trees, herd mates, and other obstacles.
	 */
	@Nullable
	public static Vec3 pickSidestepTarget(PathfinderMob mob, Vec3 origin, Vec3 direction) {
		if (direction.lengthSqr() < 1.0E-4D) {
			direction = new Vec3(1.0D, 0.0D, 0.0D);
		} else {
			direction = new Vec3(direction.x, 0.0D, direction.z).normalize();
		}

		Vec3 right = new Vec3(-direction.z, 0.0D, direction.x);
		double halfExtent = getHorizontalHalfExtent(mob);
		for (int attempt = 0; attempt < 8; attempt++) {
			boolean left = attempt % 2 == 0;
			int tier = attempt / 2 + 1;
			double lateral = (left ? 1.0D : -1.0D) * (halfExtent + 2.0D + tier * 1.5D);
			double forward = halfExtent + 1.0D + tier;
			Vec3 candidate = origin.add(right.scale(lateral)).add(direction.scale(forward));
			if (MobNavigationHelper.canPathTo(mob, candidate.x, candidate.y, candidate.z)) {
				return candidate;
			}
		}

		return null;
	}

	public static boolean isWideMob(PathfinderMob mob) {
		return mob.getBbWidth() >= WIDE_MOB_WIDTH || getHorizontalHalfExtent(mob) >= 1.5D;
	}

	public static double getHorizontalHalfExtent(PathfinderMob mob) {
		AABB box = mob.getBoundingBox();
		double fromBox = box.getXsize() * 0.5D;
		double fromWidth = mob.getBbWidth() * 0.5D;
		return Math.max(fromBox, fromWidth) + HORIZONTAL_INFLATE;
	}
}
