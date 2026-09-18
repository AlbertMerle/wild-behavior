package com.wildbehavior.ai;

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

final class AnimalFoodLureState {
	static final int SESSION_GRACE_TICKS = 40;
	/** Ticks between full player food scans; follow sessions persist between scans. */
	static final int PLAYER_SCAN_INTERVAL_TICKS = 10;

	@Nullable
	private UUID sessionPlayerId;
	private boolean decidedToFollow;
	private long lastLureTick = Long.MIN_VALUE;
	private int playerScanCooldown;
	@Nullable
	private UUID cachedLurePlayerId;

	AnimalFoodLureState(int initialScanStagger) {
		this.playerScanCooldown = Math.max(0, initialScanStagger);
	}

	void beginSession(UUID playerId, boolean follow) {
		this.sessionPlayerId = playerId;
		this.decidedToFollow = follow;
	}

	void clearSession() {
		this.sessionPlayerId = null;
		this.decidedToFollow = false;
		this.lastLureTick = Long.MIN_VALUE;
		this.cachedLurePlayerId = null;
	}

	void markLureTick(long gameTime) {
		this.lastLureTick = gameTime;
	}

	boolean hasSession() {
		return this.sessionPlayerId != null;
	}

	boolean hasSessionFor(UUID playerId) {
		return playerId.equals(this.sessionPlayerId);
	}

	@Nullable
	UUID getSessionPlayerId() {
		return this.sessionPlayerId;
	}

	boolean withinGrace(long gameTime) {
		return this.lastLureTick != Long.MIN_VALUE && gameTime - this.lastLureTick <= SESSION_GRACE_TICKS;
	}

	boolean isFollowing() {
		return this.sessionPlayerId != null && this.decidedToFollow;
	}

	boolean isFollowingPlayer(UUID playerId) {
		return isFollowing() && playerId.equals(this.sessionPlayerId);
	}

	boolean tickPlayerScanCooldown() {
		if (this.playerScanCooldown > 0) {
			this.playerScanCooldown--;
			return false;
		}

		this.playerScanCooldown = PLAYER_SCAN_INTERVAL_TICKS;
		return true;
	}

	@Nullable
	UUID getCachedLurePlayerId() {
		return this.cachedLurePlayerId;
	}

	void setCachedLurePlayerId(@Nullable UUID playerId) {
		this.cachedLurePlayerId = playerId;
	}
}
