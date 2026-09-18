package com.wildbehavior.compat;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.entity.Mob;

/**
 * Reflective access to the {@code setFlying(boolean)} / {@code isFlying()} flight flag on Alex's Mobs birds.
 *
 * <p>Alex's Mobs stays a soft dependency, so this never links against its classes. The flag drives the
 * bird's flight animation, its no-gravity handling, and its swap to Alex's flight move controller and
 * navigator, so Wild Behavior must set it while steering a bird through the air.
 */
public final class AlexBirdFlight {
	private static final Map<Class<?>, Optional<Method>> SET_FLYING = new ConcurrentHashMap<>();
	private static final Map<Class<?>, Optional<Method>> IS_FLYING = new ConcurrentHashMap<>();

	private AlexBirdFlight() {
	}

	/** Sets the bird's flight flag. Returns false when the entity has no such flag (for example a sunbird). */
	public static boolean setFlying(Mob mob, boolean flying) {
		Optional<Method> method = SET_FLYING.computeIfAbsent(
			mob.getClass(),
			type -> findMethod(type, "setFlying", boolean.class)
		);
		if (method.isEmpty()) {
			return false;
		}

		try {
			method.get().invoke(mob, flying);
			return true;
		} catch (ReflectiveOperationException | RuntimeException exception) {
			SET_FLYING.put(mob.getClass(), Optional.empty());
			return false;
		}
	}

	public static boolean isFlying(Mob mob) {
		Optional<Method> method = IS_FLYING.computeIfAbsent(
			mob.getClass(),
			type -> findMethod(type, "isFlying")
		);
		if (method.isEmpty()) {
			return false;
		}

		try {
			return method.get().invoke(mob) instanceof Boolean flying && flying;
		} catch (ReflectiveOperationException | RuntimeException exception) {
			IS_FLYING.put(mob.getClass(), Optional.empty());
			return false;
		}
	}

	private static Optional<Method> findMethod(Class<?> type, String name, Class<?>... parameters) {
		try {
			return Optional.of(type.getMethod(name, parameters));
		} catch (NoSuchMethodException | RuntimeException exception) {
			return Optional.empty();
		}
	}
}
