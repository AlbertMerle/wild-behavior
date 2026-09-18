package com.wildbehavior.ai;

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Per-wolf session for bone / raw-meat taming intercept while aggroing a player.
 */
final class WolfBoneTameState {
	enum Phase {
		IDLE,
		OFFER_HOLD,
		OFFER_APPROACH,
		STEAL_HOLD,
		STEAL_ATTACK,
		FLEE,
		ATTACK
	}

	static final int ITEM_CHECK_INTERVAL_TICKS = 5;
	static final int OFFER_HOLD_TICKS = 40;
	static final int OFFER_TOTAL_TICKS = 120;
	static final int STEAL_HOLD_TICKS = 20;
	static final int FLEE_TIMEOUT_TICKS = 60;
	static final int POST_SESSION_COOLDOWN_TICKS = 100;
	static final int GROWL_INTERVAL_TICKS = 20;

	private Phase phase = Phase.IDLE;
	@Nullable
	private UUID playerId;
	private int phaseTicks;
	private int sessionTicks;
	private int itemCheckCooldown;
	private int growlCooldown;
	private long cooldownUntilTick = Long.MIN_VALUE;
	private boolean stoleItem;

	WolfBoneTameState(int initialCheckStagger) {
		this.itemCheckCooldown = Math.max(0, initialCheckStagger);
	}

	boolean isActive() {
		return this.phase != Phase.IDLE;
	}

	Phase getPhase() {
		return this.phase;
	}

	@Nullable
	UUID getPlayerId() {
		return this.playerId;
	}

	boolean isOnCooldown(long gameTime) {
		return gameTime < this.cooldownUntilTick;
	}

	boolean tickItemCheckCooldown() {
		if (this.itemCheckCooldown > 0) {
			this.itemCheckCooldown--;
			return false;
		}

		this.itemCheckCooldown = ITEM_CHECK_INTERVAL_TICKS;
		return true;
	}

	boolean tickGrowlCooldown() {
		if (this.growlCooldown > 0) {
			this.growlCooldown--;
			return false;
		}

		this.growlCooldown = GROWL_INTERVAL_TICKS;
		return true;
	}

	void beginOffer(UUID playerId) {
		this.phase = Phase.OFFER_HOLD;
		this.playerId = playerId;
		this.phaseTicks = 0;
		this.sessionTicks = 0;
		this.growlCooldown = 0;
		this.stoleItem = false;
	}

	void beginSteal(UUID playerId) {
		this.phase = Phase.STEAL_HOLD;
		this.playerId = playerId;
		this.phaseTicks = 0;
		this.sessionTicks = 0;
		this.growlCooldown = 0;
		this.stoleItem = false;
	}

	void beginFlee(UUID playerId) {
		this.phase = Phase.FLEE;
		this.playerId = playerId;
		this.phaseTicks = 0;
		this.sessionTicks = 0;
		this.growlCooldown = 0;
	}

	void beginAttack(UUID playerId) {
		this.phase = Phase.ATTACK;
		this.playerId = playerId;
		this.phaseTicks = 0;
		this.sessionTicks = 0;
		this.growlCooldown = 0;
	}

	void advanceToOfferApproach() {
		this.phase = Phase.OFFER_APPROACH;
		this.phaseTicks = 0;
	}

	void advanceToStealAttack() {
		this.phase = Phase.STEAL_ATTACK;
		this.phaseTicks = 0;
	}

	void tickSession() {
		this.phaseTicks++;
		this.sessionTicks++;
	}

	int getPhaseTicks() {
		return this.phaseTicks;
	}

	int getSessionTicks() {
		return this.sessionTicks;
	}

	boolean hasStolenItem() {
		return this.stoleItem;
	}

	void markStolenItem() {
		this.stoleItem = true;
	}

	void clear(long gameTime) {
		this.phase = Phase.IDLE;
		this.playerId = null;
		this.phaseTicks = 0;
		this.sessionTicks = 0;
		this.growlCooldown = 0;
		this.stoleItem = false;
		this.cooldownUntilTick = gameTime + POST_SESSION_COOLDOWN_TICKS;
		this.itemCheckCooldown = ITEM_CHECK_INTERVAL_TICKS;
	}

	void clearImmediate() {
		this.phase = Phase.IDLE;
		this.playerId = null;
		this.phaseTicks = 0;
		this.sessionTicks = 0;
		this.growlCooldown = 0;
		this.stoleItem = false;
		this.cooldownUntilTick = Long.MIN_VALUE;
	}
}
