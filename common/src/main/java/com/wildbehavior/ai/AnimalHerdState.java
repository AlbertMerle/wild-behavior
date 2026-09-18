package com.wildbehavior.ai;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import com.wildbehavior.config.AnimalBehaviorConfig;

final class AnimalHerdState {
	static final int OVERRIDE_CHECK_INTERVAL_TICKS = 4;

	private static final int IDLE_PAUSE_CHECK_INTERVAL_TICKS = 200;
	private static final int IDLE_PAUSE_DURATION_TICKS = 20;
	private static final float IDLE_PAUSE_CHANCE = 0.2F;

	private UUID herdLeaderId;
	private int scanCooldown;
	private int repathCooldown;
	private int overrideCheckCooldown;
	private boolean cachedBlockedByOverride;
	/** Radians offset from directly behind the leader (-π/2 … π/2). */
	private final double followerSlotAngle;
	/** Blocks behind the leader for this follower's slot. */
	private final double followerSlotDistance;

	private long nextIdlePauseCheckTick;
	private long idlePauseEndTick;

	private long grazePhaseStartTick = -1L;
	private GrazeActivity grazeActivity = GrazeActivity.SPREADING;
	private long grazeActivityEndTick;
	@Nullable
	private Vec3 grazeTarget;

	private long wanderPhaseStartTick = -1L;
	private long wanderJoinTick;
	private boolean joinedWander;

	/** Block occupied without leaving; detects herd travel stuck loops. */
	private BlockPos stuckBlock = BlockPos.ZERO;
	/** Game time when {@link #stuckBlock} was first entered; 0 when not tracking. */
	private long stuckSinceTick;
	/** Until this game time, herd must not drive locomotion (vanilla stroll/breed may run). */
	private long locomotionYieldUntilTick;
	/** Consecutive failed herd moveTo / unpathable targets; yields after threshold. */
	private int consecutiveFailedHerdMoves;

	AnimalHerdState(UUID entityId) {
		this.herdLeaderId = entityId;
		int hash = entityId.hashCode();
		this.followerSlotAngle = ((hash & 0xFFFF) / 65536.0D - 0.5D) * Math.PI;
		this.followerSlotDistance = 3.0D + (Math.abs(hash >> 16) % 50) / 10.0D;
		int scanInterval = AnimalBehaviorConfig.herdScanIntervalTicks();
		this.scanCooldown = Math.abs(hash) % Math.max(1, scanInterval);
		this.overrideCheckCooldown = Math.abs(hash >> 4) % OVERRIDE_CHECK_INTERVAL_TICKS;
		this.nextIdlePauseCheckTick = Math.abs(hash >> 8) % IDLE_PAUSE_CHECK_INTERVAL_TICKS;
	}

	UUID getHerdLeaderId() {
		return this.herdLeaderId;
	}

	void setHerdLeaderId(UUID herdLeaderId) {
		this.herdLeaderId = herdLeaderId;
	}

	int getRepathCooldown() {
		return this.repathCooldown;
	}

	void setRepathCooldown(int repathCooldown) {
		this.repathCooldown = repathCooldown;
	}

	double getFollowerSlotAngle() {
		return this.followerSlotAngle;
	}

	double getFollowerSlotDistance() {
		return this.followerSlotDistance;
	}

	/** Returns true while herd should not issue new paths (brief idle); does not stop navigation. */
	boolean tickIdlePause(PathfinderMob mob, long gameTime) {
		if (gameTime < this.idlePauseEndTick) {
			return true;
		}

		if (gameTime < this.nextIdlePauseCheckTick) {
			return false;
		}

		this.nextIdlePauseCheckTick = gameTime + IDLE_PAUSE_CHECK_INTERVAL_TICKS;
		if (mob.getRandom().nextFloat() < IDLE_PAUSE_CHANCE) {
			this.idlePauseEndTick = gameTime + IDLE_PAUSE_DURATION_TICKS;
			return true;
		}

		return false;
	}

	boolean isInIdlePause(long gameTime) {
		return gameTime < this.idlePauseEndTick;
	}

	long getGrazePhaseStartTick() {
		return this.grazePhaseStartTick;
	}

	GrazeActivity getGrazeActivity() {
		return this.grazeActivity;
	}

	void setGrazeActivity(GrazeActivity grazeActivity) {
		this.grazeActivity = grazeActivity;
	}

	long getGrazeActivityEndTick() {
		return this.grazeActivityEndTick;
	}

	void setGrazeActivityEndTick(long grazeActivityEndTick) {
		this.grazeActivityEndTick = grazeActivityEndTick;
	}

	@Nullable
	Vec3 getGrazeTarget() {
		return this.grazeTarget;
	}

	void setGrazeTarget(@Nullable Vec3 grazeTarget) {
		this.grazeTarget = grazeTarget;
	}

	long getWanderPhaseStartTick() {
		return this.wanderPhaseStartTick;
	}

	long getWanderJoinTick() {
		return this.wanderJoinTick;
	}

	boolean hasJoinedWander() {
		return this.joinedWander;
	}

	void setJoinedWander(boolean joinedWander) {
		this.joinedWander = joinedWander;
	}

	void beginGrazingCycle(PathfinderMob mob, long phaseStartTick, Vec3 spreadTarget) {
		this.grazePhaseStartTick = phaseStartTick;
		this.grazeActivity = GrazeActivity.SPREADING;
		this.grazeTarget = spreadTarget;
		this.grazeActivityEndTick = 0L;
		this.joinedWander = false;
	}

	void beginGrazeRest(PathfinderMob mob, long gameTime) {
		this.grazeActivity = GrazeActivity.RESTING;
		this.grazeTarget = null;
		this.grazeActivityEndTick = gameTime + randomGrazeRestDuration(mob);
	}

	void beginGrazeMove(Vec3 target) {
		this.grazeActivity = GrazeActivity.MOVING;
		this.grazeTarget = target;
		this.grazeActivityEndTick = 0L;
	}

	void beginWanderPhase(PathfinderMob mob, long phaseStartTick, boolean isLeader) {
		this.wanderPhaseStartTick = phaseStartTick;
		this.joinedWander = isLeader;
		this.wanderJoinTick = isLeader ? phaseStartTick : phaseStartTick + randomWanderJoinDelay(mob);
	}

	boolean tickScanCooldown() {
		if (this.scanCooldown > 0) {
			this.scanCooldown--;
			return false;
		}

		this.scanCooldown = AnimalBehaviorConfig.herdScanIntervalTicks();
		return true;
	}

	void forceScan() {
		this.scanCooldown = 0;
	}

	/** Returns true on override-recheck ticks (flee/aggro/fightback/etc.). */
	boolean tickOverrideCheckCooldown() {
		if (this.overrideCheckCooldown > 0) {
			this.overrideCheckCooldown--;
			return false;
		}

		this.overrideCheckCooldown = OVERRIDE_CHECK_INTERVAL_TICKS;
		return true;
	}

	void setCachedBlockedByOverride(boolean blocked) {
		this.cachedBlockedByOverride = blocked;
	}

	boolean isCachedBlockedByOverride() {
		return this.cachedBlockedByOverride;
	}

	void tickRepathCooldown() {
		if (this.repathCooldown > 0) {
			this.repathCooldown--;
		}
	}

	BlockPos getStuckBlock() {
		return this.stuckBlock;
	}

	long getStuckSinceTick() {
		return this.stuckSinceTick;
	}

	long getStuckTicks(long gameTime) {
		if (this.stuckSinceTick <= 0L) {
			return 0L;
		}

		return Math.max(0L, gameTime - this.stuckSinceTick);
	}

	void resetStuckTracking(BlockPos block, long gameTime) {
		this.stuckBlock = block.immutable();
		this.stuckSinceTick = gameTime;
	}

	void clearStuckTracking() {
		this.stuckBlock = BlockPos.ZERO;
		this.stuckSinceTick = 0L;
	}

	boolean isLocomotionYielded(long gameTime) {
		return gameTime < this.locomotionYieldUntilTick;
	}

	void beginLocomotionYield(long gameTime, long durationTicks) {
		this.locomotionYieldUntilTick = gameTime + durationTicks;
		this.consecutiveFailedHerdMoves = 0;
	}

	int getConsecutiveFailedHerdMoves() {
		return this.consecutiveFailedHerdMoves;
	}

	void clearFailedHerdMoves() {
		this.consecutiveFailedHerdMoves = 0;
	}

	/**
	 * Records a failed herd path. Yields locomotion when the failure threshold is reached.
	 *
	 * @return true when this call started a locomotion yield
	 */
	boolean noteFailedHerdMove(long gameTime, int yieldThreshold, long yieldDurationTicks) {
		this.consecutiveFailedHerdMoves++;
		if (this.consecutiveFailedHerdMoves < yieldThreshold) {
			return false;
		}

		beginLocomotionYield(gameTime, yieldDurationTicks);
		return true;
	}

	private static int randomGrazeRestDuration(PathfinderMob mob) {
		int minTicks = AnimalBehaviorConfig.herdGrazeRestMinTicks();
		int maxTicks = AnimalBehaviorConfig.herdGrazeRestMaxTicks();
		return minTicks + mob.getRandom().nextInt(maxTicks - minTicks + 1);
	}

	private static int randomWanderJoinDelay(PathfinderMob mob) {
		return mob.getRandom().nextInt(AnimalBehaviorConfig.herdWanderJoinMaxDelayTicks() + 1);
	}
}
