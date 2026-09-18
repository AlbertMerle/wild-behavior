package com.wildbehavior.ai;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;

/**
 * When a mob stays on the same block for {@link #STUCK_TICKS} (~10s) while a Wild Behavior
 * overlay is trying to move it, release the overlay so vanilla AI (breed, eat, wander) can run.
 * A cooldown prevents immediate re-capture into the same stuck loop.
 */
public final class AnimalStuckRecoverySystem {
	/** 10 seconds at 20 TPS. */
	public static final long STUCK_TICKS = 200L;
	/** How long overlays stay suppressed after a stuck release. */
	public static final long COOLDOWN_TICKS = 200L;

	private static final Map<UUID, StuckState> STATES = new ConcurrentHashMap<>();

	private AnimalStuckRecoverySystem() {
	}

	public static void onEntityLoad(LivingEntity entity) {
		STATES.put(entity.getUUID(), new StuckState());
	}

	public static void onEntityUnload(LivingEntity entity) {
		STATES.remove(entity.getUUID());
	}

	/** True while overlays should stay off after a stuck release. */
	public static boolean isInCooldown(LivingEntity entity) {
		StuckState state = STATES.get(entity.getUUID());
		if (state == null) {
			return false;
		}

		return entity.level().getGameTime() < state.cooldownUntilTick;
	}

	/**
	 * Tracks position and, when stuck under an active overlay, releases Wild Behavior.
	 *
	 * @return true when the mob is in stuck cooldown (caller should skip overlays this tick)
	 */
	public static boolean tick(PathfinderMob mob) {
		long now = mob.level().getGameTime();
		StuckState state = STATES.computeIfAbsent(mob.getUUID(), id -> new StuckState());

		if (now < state.cooldownUntilTick) {
			return true;
		}

		BlockPos block = mob.blockPosition();
		if (!block.equals(state.lastBlock)) {
			state.lastBlock = block.immutable();
			state.stuckSinceTick = now;
			return false;
		}

		if (state.stuckSinceTick <= 0L) {
			state.stuckSinceTick = now;
			return false;
		}

		long stuckTicks = now - state.stuckSinceTick;
		if (stuckTicks < STUCK_TICKS) {
			return false;
		}

		if (!WildBehaviorOverlay.isTryingToControl(mob)) {
			return false;
		}

		WildBehaviorOverlay.release(mob);
		state.cooldownUntilTick = now + COOLDOWN_TICKS;
		state.stuckSinceTick = now;
		return true;
	}

	private static final class StuckState {
		private BlockPos lastBlock = BlockPos.ZERO;
		private long stuckSinceTick;
		private long cooldownUntilTick;
	}
}
