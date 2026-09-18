package com.wildbehavior.ai;

import java.util.UUID;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

final class AnimalAggroState {
	/** Ticks between expensive ongoing aggro combat/chase/repath updates. */
	static final int BEHAVIOR_UPDATE_INTERVAL_TICKS = 10;

	/** Ticks between idle threat-acquisition scans. */
	static final int IDLE_THREAT_SCAN_INTERVAL_TICKS = 10;

	/** Staggered so nearby entities do not all update on the same tick. */
	static boolean isBehaviorUpdateTick(LivingEntity entity) {
		return (entity.level().getGameTime() + (entity.getId() & 1)) % BEHAVIOR_UPDATE_INTERVAL_TICKS == 0;
	}

	private boolean aggroing;
	@Nullable
	private UUID aggroTargetId;
	private long lastChainTick;
	private long lastAttackTick;
	/** Game time when the player target was last seen with line of sight. */
	private long lastSeenTargetTick;
	/** Game time until which this mob may not acquire a new Aggressive target (0 = none). */
	private long killCooldownUntilTick;
	/** Game time until which idle threat scans may not acquire a new target (0 = none). */
	private long aggroReacquireCooldownUntilTick;
	private int repathCooldown;
	/** Block occupied without leaving; detects chase stuck loops. */
	private BlockPos stuckBlock = BlockPos.ZERO;
	/** Game time when {@link #stuckBlock} was first entered; 0 when not tracking. */
	private long stuckSinceTick;
	/** Last place the player was seen; center of lost-LOS search. */
	@Nullable
	private Vec3 lastKnownTargetPos;
	/** Current investigate point while searching without LOS. */
	@Nullable
	private Vec3 searchTarget;
	/** Game time when the current look-around pause ends (0 = not looking). */
	private long lookAroundUntilTick;
	/** Length of the current look-around pause in ticks. */
	private int lookAroundDurationTicks;
	/** Yaw at the start of look-around, used for left/right head sweep. */
	private float lookAroundStartYaw;
	/** Countdown before the next idle world scan for a new target. */
	private int idleThreatScanCooldown;
	/** Game time when the current aggro target was acquired. */
	private long aggroStartedTick;
	/** True after the first post-aggro give-up roll (continue or abandon). */
	private boolean aggroGiveUpResolved;
	/** Game time of the last passive-prey proximity retarget scan while chasing prey. */
	private long lastPassivePreyRetargetTick;

	AnimalAggroState() {
		this.idleThreatScanCooldown = 0;
	}

	AnimalAggroState(int initialScanStagger) {
		this.idleThreatScanCooldown = Math.max(0, initialScanStagger);
	}

	boolean isAggroing() {
		return this.aggroing;
	}

	void setAggroing(boolean aggroing) {
		this.aggroing = aggroing;
	}

	@Nullable
	UUID getAggroTargetId() {
		return this.aggroTargetId;
	}

	void setAggroTargetId(@Nullable UUID aggroTargetId) {
		this.aggroTargetId = aggroTargetId;
	}

	long getLastChainTick() {
		return this.lastChainTick;
	}

	void setLastChainTick(long lastChainTick) {
		this.lastChainTick = lastChainTick;
	}

	long getLastAttackTick() {
		return this.lastAttackTick;
	}

	void setLastAttackTick(long lastAttackTick) {
		this.lastAttackTick = lastAttackTick;
	}

	long getLastSeenTargetTick() {
		return this.lastSeenTargetTick;
	}

	void setLastSeenTargetTick(long lastSeenTargetTick) {
		this.lastSeenTargetTick = lastSeenTargetTick;
	}

	long getKillCooldownUntilTick() {
		return this.killCooldownUntilTick;
	}

	void setKillCooldownUntilTick(long killCooldownUntilTick) {
		this.killCooldownUntilTick = killCooldownUntilTick;
	}

	boolean isOnKillCooldown(long gameTime) {
		return this.killCooldownUntilTick > 0L && gameTime < this.killCooldownUntilTick;
	}

	long getAggroReacquireCooldownUntilTick() {
		return this.aggroReacquireCooldownUntilTick;
	}

	void setAggroReacquireCooldownUntilTick(long aggroReacquireCooldownUntilTick) {
		this.aggroReacquireCooldownUntilTick = aggroReacquireCooldownUntilTick;
	}

	boolean isOnAggroReacquireCooldown(long gameTime) {
		return this.aggroReacquireCooldownUntilTick > 0L && gameTime < this.aggroReacquireCooldownUntilTick;
	}

	int getRepathCooldown() {
		return this.repathCooldown;
	}

	void setRepathCooldown(int repathCooldown) {
		this.repathCooldown = repathCooldown;
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

	@Nullable
	Vec3 getLastKnownTargetPos() {
		return this.lastKnownTargetPos;
	}

	void setLastKnownTargetPos(@Nullable Vec3 lastKnownTargetPos) {
		this.lastKnownTargetPos = lastKnownTargetPos;
	}

	@Nullable
	Vec3 getSearchTarget() {
		return this.searchTarget;
	}

	void setSearchTarget(@Nullable Vec3 searchTarget) {
		this.searchTarget = searchTarget;
	}

	long getLookAroundUntilTick() {
		return this.lookAroundUntilTick;
	}

	int getLookAroundDurationTicks() {
		return this.lookAroundDurationTicks;
	}

	float getLookAroundStartYaw() {
		return this.lookAroundStartYaw;
	}

	boolean isLookingAround(long gameTime) {
		return this.lookAroundUntilTick > gameTime;
	}

	void beginLookAround(long gameTime, int durationTicks, float startYaw) {
		this.lookAroundDurationTicks = Math.max(1, durationTicks);
		this.lookAroundUntilTick = gameTime + this.lookAroundDurationTicks;
		this.lookAroundStartYaw = startYaw;
	}

	void clearSearch() {
		this.searchTarget = null;
		this.lookAroundUntilTick = 0L;
		this.lookAroundDurationTicks = 0;
		this.lookAroundStartYaw = 0.0F;
	}

	long getAggroStartedTick() {
		return this.aggroStartedTick;
	}

	void setAggroStartedTick(long aggroStartedTick) {
		this.aggroStartedTick = aggroStartedTick;
	}

	boolean isAggroGiveUpResolved() {
		return this.aggroGiveUpResolved;
	}

	void setAggroGiveUpResolved(boolean aggroGiveUpResolved) {
		this.aggroGiveUpResolved = aggroGiveUpResolved;
	}

	long getLastPassivePreyRetargetTick() {
		return this.lastPassivePreyRetargetTick;
	}

	void setLastPassivePreyRetargetTick(long lastPassivePreyRetargetTick) {
		this.lastPassivePreyRetargetTick = lastPassivePreyRetargetTick;
	}

	void clearAggro() {
		this.aggroing = false;
		this.aggroTargetId = null;
		this.lastAttackTick = 0L;
		this.lastSeenTargetTick = 0L;
		this.repathCooldown = 0;
		this.lastKnownTargetPos = null;
		this.aggroStartedTick = 0L;
		this.aggroGiveUpResolved = false;
		this.lastPassivePreyRetargetTick = 0L;
		this.clearSearch();
		this.clearStuckTracking();
		this.idleThreatScanCooldown = 0;
	}

	/** Returns true when an idle acquisition scan may run this tick. */
	boolean tickIdleThreatScanCooldown() {
		if (this.idleThreatScanCooldown > 0) {
			this.idleThreatScanCooldown--;
			return false;
		}

		this.idleThreatScanCooldown = IDLE_THREAT_SCAN_INTERVAL_TICKS;
		return true;
	}

	boolean isIdleThreatScanReady() {
		return this.idleThreatScanCooldown <= 0;
	}

	boolean hasIdleThreatScanCooldown() {
		return this.idleThreatScanCooldown > 0;
	}

	void resetIdleThreatScanCooldown() {
		this.idleThreatScanCooldown = 0;
	}
}
