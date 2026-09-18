package com.wildbehavior.ai;

import java.util.List;
import java.util.UUID;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

final class HerdGroupData {
	private static final List<UUID> EMPTY_MEMBERS = List.of();

	private HerdPhase phase = HerdPhase.WANDERING;
	private Vec3 wanderDirection = randomHorizontalDirection();
	@Nullable
	private Vec3 wanderTarget;
	private Vec3 grazingCenter = Vec3.ZERO;
	private long phaseEndTick;
	private long phaseStartTick;
	private List<UUID> cachedMembers = EMPTY_MEMBERS;
	private long memberScanTick = Long.MIN_VALUE;

	@Nullable
	private ChunkPos lastCheckedChunk;
	private long nextChunkCheckTick;
	private boolean stagnationEscapeActive;
	@Nullable
	private Vec3 stagnationEscapeTarget;

	void beginWandering(long gameTime, int durationTicks, boolean keepDirection) {
		if (!keepDirection) {
			this.wanderDirection = randomHorizontalDirection();
		} else if (this.wanderDirection.lengthSqr() < 1.0E-4D) {
			this.wanderDirection = randomHorizontalDirection();
		}

		this.wanderTarget = null;
		this.phase = HerdPhase.WANDERING;
		this.phaseStartTick = gameTime;
		this.phaseEndTick = gameTime + durationTicks;
	}

	void beginGrazing(long gameTime, int durationTicks, Vec3 center) {
		this.grazingCenter = center;
		this.wanderTarget = null;
		this.phase = HerdPhase.GRAZING;
		this.phaseStartTick = gameTime;
		this.phaseEndTick = gameTime + durationTicks;
	}

	HerdPhase getPhase() {
		return this.phase;
	}

	Vec3 getWanderDirection() {
		return this.wanderDirection;
	}

	void setWanderDirection(Vec3 direction) {
		if (direction.lengthSqr() < 1.0E-4D) {
			this.wanderDirection = randomHorizontalDirection();
			return;
		}

		this.wanderDirection = new Vec3(direction.x, 0.0D, direction.z).normalize();
		this.wanderTarget = null;
	}

	@Nullable
	Vec3 getWanderTarget() {
		return this.wanderTarget;
	}

	void setWanderTarget(@Nullable Vec3 wanderTarget) {
		this.wanderTarget = wanderTarget;
	}

	long getPhaseEndTick() {
		return this.phaseEndTick;
	}

	long getPhaseStartTick() {
		return this.phaseStartTick;
	}

	Vec3 getGrazingCenter() {
		return this.grazingCenter;
	}

	List<UUID> getCachedMembers() {
		return this.cachedMembers;
	}

	long getMemberScanTick() {
		return this.memberScanTick;
	}

	boolean isMemberCacheFresh(long gameTime, int scanIntervalTicks) {
		return this.memberScanTick != Long.MIN_VALUE
			&& gameTime - this.memberScanTick < scanIntervalTicks;
	}

	void setCachedMembers(List<UUID> members, long gameTime) {
		this.cachedMembers = List.copyOf(members);
		this.memberScanTick = gameTime;
	}

	void clearMemberCache() {
		this.cachedMembers = EMPTY_MEMBERS;
		this.memberScanTick = Long.MIN_VALUE;
	}

	@Nullable
	ChunkPos getLastCheckedChunk() {
		return this.lastCheckedChunk;
	}

	void setLastCheckedChunk(ChunkPos chunk) {
		this.lastCheckedChunk = chunk;
	}

	long getNextChunkCheckTick() {
		return this.nextChunkCheckTick;
	}

	void setNextChunkCheckTick(long nextChunkCheckTick) {
		this.nextChunkCheckTick = nextChunkCheckTick;
	}

	boolean isStagnationEscapeActive() {
		return this.stagnationEscapeActive;
	}

	@Nullable
	Vec3 getStagnationEscapeTarget() {
		return this.stagnationEscapeTarget;
	}

	void beginStagnationEscape(Vec3 target, Vec3 direction) {
		this.stagnationEscapeActive = true;
		this.stagnationEscapeTarget = target;
		this.wanderDirection = new Vec3(direction.x, 0.0D, direction.z).normalize();
		this.wanderTarget = target;
	}

	void cancelStagnationEscape() {
		this.stagnationEscapeActive = false;
		this.stagnationEscapeTarget = null;
	}

	private static Vec3 randomHorizontalDirection() {
		double angle = Math.random() * Math.PI * 2.0D;
		return new Vec3(Math.cos(angle), 0.0D, Math.sin(angle));
	}
}
