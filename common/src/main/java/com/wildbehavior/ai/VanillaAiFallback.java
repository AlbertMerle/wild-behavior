package com.wildbehavior.ai;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.phys.AABB;

/**
 * Forces vanilla goal AI (wander / breed / eat / tempt) when Wild Behavior overlays would
 * freeze animals in pens or packed clusters.
 *
 * <p>Release navigation at most once when entering fallback — never every tick.
 */
public final class VanillaAiFallback {
	/** Same-type animals within this range count toward packing. */
	private static final double PACKED_RANGE = 4.0D;
	/** Self + this many others → treat as penned-together (skip WB overlays). */
	private static final int PACKED_TOTAL = 3;
	private static final int PACKED_CHECK_INTERVAL_TICKS = 10;

	private static final Set<UUID> ACTIVE = ConcurrentHashMap.newKeySet();
	private static final Map<UUID, PackedCache> PACKED_CACHE = new ConcurrentHashMap<>();

	private VanillaAiFallback() {
	}

	/**
	 * @return true when overlays must not run this tick (caller should return immediately)
	 */
	public static boolean applyIfNeeded(PathfinderMob mob) {
		boolean useVanilla = HerdFenceProximity.isNearFenceOrGate(mob) || isPackedWithHerdmates(mob);
		UUID id = mob.getUUID();

		if (!useVanilla) {
			ACTIVE.remove(id);
			return false;
		}

		// Entering fallback: clear overlays once. Staying: leave navigation alone.
		if (ACTIVE.add(id)) {
			WildBehaviorOverlay.release(mob);
		}

		return true;
	}

	public static void clear(LivingEntity entity) {
		UUID id = entity.getUUID();
		ACTIVE.remove(id);
		PACKED_CACHE.remove(id);
	}

	/**
	 * True when enough same-type herdmates are crammed nearby that herd clearance / long
	 * wander targets cannot work (typical fenced pen).
	 */
	public static boolean isPackedWithHerdmates(LivingEntity entity) {
		if (!MobBehaviorConfig.isEnabled(entity.getType(), MobBehavior.HERD)) {
			return false;
		}

		long now = entity.level().getGameTime();
		UUID id = entity.getUUID();
		PackedCache cached = PACKED_CACHE.get(id);
		if (cached != null && now - cached.checkedAtTick < PACKED_CHECK_INTERVAL_TICKS) {
			return cached.packed;
		}

		boolean packed = scanPacked(entity);
		PACKED_CACHE.put(id, new PackedCache(now, packed));
		return packed;
	}

	private static boolean scanPacked(LivingEntity entity) {
		AABB box = entity.getBoundingBox().inflate(PACKED_RANGE);
		int count = 0;
		for (LivingEntity other : entity.level().getEntitiesOfClass(LivingEntity.class, box)) {
			if (other.getType() != entity.getType() || !other.isAlive()) {
				continue;
			}

			if (other.distanceToSqr(entity) > PACKED_RANGE * PACKED_RANGE) {
				continue;
			}

			count++;
			if (count >= PACKED_TOTAL) {
				return true;
			}
		}

		return false;
	}

	private record PackedCache(long checkedAtTick, boolean packed) {
	}
}
