package com.wildbehavior.ai;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PathfinderMob;

/**
 * Navigation helpers for modded mobs that use Citadel's {@code AdvancedPathNavigate}
 * (Alex's Mobs and other Citadel-based mods). Citadel rejects speed modifiers above
 * {@value #CITADEL_MAX_SPEED}, which would silently discard flee/herd/aggro paths.
 */
public final class MobNavigationHelper {
	private static final double CITADEL_MIN_SPEED = 0.1D;
	private static final double CITADEL_MAX_SPEED = 2.0D;

	private MobNavigationHelper() {
	}

	public static boolean usesCitadelPathfinding(PathfinderMob mob) {
		String navigationClass = mob.getNavigation().getClass().getName();
		return navigationClass.contains("citadel") || navigationClass.contains("AdvancedPathNavigate");
	}

	public static double clampNavigationSpeed(PathfinderMob mob, double speed) {
		if (usesCitadelPathfinding(mob)) {
			return Math.clamp(speed, CITADEL_MIN_SPEED, CITADEL_MAX_SPEED);
		}

		return speed;
	}

	public static void setSpeedModifier(PathfinderMob mob, double speed) {
		mob.getNavigation().setSpeedModifier(clampNavigationSpeed(mob, speed));
	}

	public static void moveTo(PathfinderMob mob, double x, double y, double z, double speed) {
		mob.getNavigation().moveTo(x, y, z, clampNavigationSpeed(mob, speed));
	}

	public static void moveTo(PathfinderMob mob, Entity target, double speed) {
		mob.getNavigation().moveTo(target, clampNavigationSpeed(mob, speed));
	}

	/** Whether the mob's pathfinder can reach the given block position. */
	public static boolean canPathTo(PathfinderMob mob, double x, double y, double z) {
		return mob.getNavigation().createPath(x, y, z, 0) != null;
	}
}
