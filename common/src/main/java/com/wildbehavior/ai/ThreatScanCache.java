package com.wildbehavior.ai;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Shared threat-scan results per mob, amortized across flee, aggro, and villager panic checks.
 * Refreshed at most once per {@link AnimalAggroState#IDLE_THREAT_SCAN_INTERVAL_TICKS} window.
 */
public final class ThreatScanCache {
	public record ScanResult(
		@Nullable LivingEntity fleeThreat,
		@Nullable LivingEntity aggroTarget,
		@Nullable Vec3 scareOrigin
	) {
		static final ScanResult EMPTY = new ScanResult(null, null, null);
	}

	private record Cached(long validUntilTick, ScanResult result) {
	}

	private static final Map<UUID, Cached> CACHE = new ConcurrentHashMap<>();

	private ThreatScanCache() {
	}

	public static ScanResult get(LivingEntity mob, long gameTime) {
		Cached cached = CACHE.get(mob.getUUID());
		if (cached != null && gameTime <= cached.validUntilTick()) {
			return cached.result();
		}

		ScanResult result = new ScanResult(
			ThreatApproachDetector.findNearestFleeThreat(mob),
			ThreatApproachDetector.findNearestAggroTarget(mob),
			WeaponScareDetector.findNearestScareOrigin(mob)
		);
		long validUntil = gameTime + AnimalAggroState.IDLE_THREAT_SCAN_INTERVAL_TICKS;
		CACHE.put(mob.getUUID(), new Cached(validUntil, result));
		return result;
	}

	public static void invalidate(UUID entityId) {
		CACHE.remove(entityId);
	}
}
