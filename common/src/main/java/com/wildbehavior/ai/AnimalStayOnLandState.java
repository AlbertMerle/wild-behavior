package com.wildbehavior.ai;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * Per-mob state for the {@link MobBehavior#STAY_ON_LAND StayOnLandFix} behavior.
 * Tracks whether a herding mob is currently being forced out of water and the
 * land block it is pathfinding toward.
 */
final class AnimalStayOnLandState {
	static final int WATER_CHECK_INTERVAL_TICKS = 20;

	private boolean escaping;
	@Nullable
	private BlockPos escapeTarget;
	private int repathCooldown;
	private int waterCheckCooldown;
	private boolean cachedInWater;

	AnimalStayOnLandState() {
		this.waterCheckCooldown = 0;
	}

	boolean isEscaping() {
		return this.escaping;
	}

	@Nullable
	BlockPos getEscapeTarget() {
		return this.escapeTarget;
	}

	int getRepathCooldown() {
		return this.repathCooldown;
	}

	void setEscaping(boolean escaping) {
		this.escaping = escaping;
	}

	void setEscapeTarget(@Nullable BlockPos escapeTarget) {
		this.escapeTarget = escapeTarget == null ? null : escapeTarget.immutable();
	}

	void setRepathCooldown(int repathCooldown) {
		this.repathCooldown = repathCooldown;
	}

	void tickRepathCooldown() {
		if (this.repathCooldown > 0) {
			this.repathCooldown--;
		}
	}

	/** Returns true on water-block recheck ticks. */
	boolean tickWaterCheckCooldown() {
		if (this.waterCheckCooldown > 0) {
			this.waterCheckCooldown--;
			return false;
		}

		this.waterCheckCooldown = WATER_CHECK_INTERVAL_TICKS;
		return true;
	}

	void setCachedInWater(boolean inWater) {
		this.cachedInWater = inWater;
	}

	boolean isCachedInWater() {
		return this.cachedInWater;
	}

	void clear() {
		this.escaping = false;
		this.escapeTarget = null;
		this.repathCooldown = 0;
		this.cachedInWater = false;
	}
}
