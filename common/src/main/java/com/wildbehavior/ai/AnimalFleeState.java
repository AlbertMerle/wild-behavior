package com.wildbehavior.ai;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

final class AnimalFleeState {
	/** Idle threat scans share cadence with {@link AnimalAggroState#IDLE_THREAT_SCAN_INTERVAL_TICKS}. */
	static final int IDLE_THREAT_SCAN_INTERVAL_TICKS = AnimalAggroState.IDLE_THREAT_SCAN_INTERVAL_TICKS;

	/** Ticks between active flee maintenance (repath, threat resolve, stuck recovery). */
	static final int BEHAVIOR_UPDATE_INTERVAL_TICKS = 5;

	static boolean isBehaviorUpdateTick(LivingEntity entity) {
		return (entity.level().getGameTime() + (entity.getId() & 1)) % BEHAVIOR_UPDATE_INTERVAL_TICKS == 0;
	}

	private boolean fleeing;
	/** Airborne mobs descend with flight animation after a flee run ends. */
	private boolean landing;
	@Nullable
	private UUID fleeingFrom;
	@Nullable
	private Vec3 fleeFromPosition;
	@Nullable
	private BlockPos shelterTarget;
	@Nullable
	private Vec3 fleeTarget;
	private int repathCooldown;
	private long lastChainTick;
	/** Game time the current flee run started; drives the airborne take-off speed burst. */
	private long fleeStartTick;
	/** Game time when a smooth landing descent began. */
	private long landingStartTick;
	/** Game time when a player threat was last seen with line of sight. */
	private long lastSeenThreatTick;
	@Nullable
	private BlockPos batTarget;
	/** Block the mob has occupied without leaving; used to detect flee stuck loops. */
	private BlockPos stuckBlock = BlockPos.ZERO;
	/** Game time when {@link #stuckBlock} was first entered; 0 when not tracking. */
	private long stuckSinceTick;
	/** Countdown before the next idle threat scan. */
	private int idleThreatScanCooldown;
	/** Gunshot / explosion flee — priority just below food lure in {@link WildBehaviorDispatcher}. */
	private boolean weaponScareFlee;

	AnimalFleeState() {
		this.idleThreatScanCooldown = 0;
	}

	AnimalFleeState(int initialScanStagger) {
		this.idleThreatScanCooldown = Math.max(0, initialScanStagger);
	}

	boolean isFleeing() {
		return this.fleeing;
	}

	boolean isLanding() {
		return this.landing;
	}

	UUID getFleeingFrom() {
		return this.fleeingFrom;
	}

	@Nullable
	Vec3 getFleeFromPosition() {
		return this.fleeFromPosition;
	}

	int getRepathCooldown() {
		return this.repathCooldown;
	}

	long getLastChainTick() {
		return this.lastChainTick;
	}

	long getFleeStartTick() {
		return this.fleeStartTick;
	}

	void setFleeStartTick(long fleeStartTick) {
		this.fleeStartTick = fleeStartTick;
	}

	long getLandingStartTick() {
		return this.landingStartTick;
	}

	boolean hasBatTarget() {
		return this.batTarget != null;
	}

	BlockPos getBatTarget() {
		return this.batTarget;
	}

	void setFleeing(boolean fleeing) {
		this.fleeing = fleeing;
	}

	void setFleeingFrom(@Nullable UUID fleeingFrom) {
		this.fleeingFrom = fleeingFrom;
	}

	void setFleeFromPosition(@Nullable Vec3 fleeFromPosition) {
		this.fleeFromPosition = fleeFromPosition;
	}

	void setFleeTarget(Vec3 fleeTarget) {
		this.fleeTarget = fleeTarget;
	}

	void setRepathCooldown(int repathCooldown) {
		this.repathCooldown = repathCooldown;
	}

	void setLastChainTick(long lastChainTick) {
		this.lastChainTick = lastChainTick;
	}

	long getLastSeenThreatTick() {
		return this.lastSeenThreatTick;
	}

	void setLastSeenThreatTick(long lastSeenThreatTick) {
		this.lastSeenThreatTick = lastSeenThreatTick;
	}

	void setBatTarget(BlockPos batTarget) {
		this.batTarget = batTarget;
	}

	void tickRepathCooldown() {
		if (this.repathCooldown > 0) {
			this.repathCooldown--;
		}
	}

	@Nullable
	BlockPos getShelterTarget() {
		return this.shelterTarget;
	}

	void setShelterTarget(@Nullable BlockPos shelterTarget) {
		this.shelterTarget = shelterTarget;
	}

	@Nullable
	Vec3 getFleeTarget() {
		return this.fleeTarget;
	}

	/** Ends the flee run and starts a smooth airborne descent. */
	void beginLanding(long gameTime) {
		clearFlee();
		this.landing = true;
		this.landingStartTick = gameTime;
	}

	void clearLanding() {
		this.landing = false;
		this.landingStartTick = 0L;
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

	boolean isWeaponScareFlee() {
		return this.weaponScareFlee;
	}

	void setWeaponScareFlee(boolean weaponScareFlee) {
		this.weaponScareFlee = weaponScareFlee;
	}

	void clearFlee() {
		this.fleeing = false;
		this.fleeingFrom = null;
		this.fleeFromPosition = null;
		this.fleeTarget = null;
		this.shelterTarget = null;
		this.repathCooldown = 0;
		this.lastSeenThreatTick = 0L;
		this.fleeStartTick = 0L;
		this.weaponScareFlee = false;
		this.clearStuckTracking();
		this.idleThreatScanCooldown = 0;
	}

	/** Returns true when an idle threat scan may run this tick. */
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

	void clearBatTarget() {
		this.batTarget = null;
	}
}
