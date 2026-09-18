package com.wildbehavior.ai;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * Per-mob scripted water escape after PlayerFear / Passive flee chase ends in water.
 */
final class AnimalWaterFleeEscapeState {
	enum Phase {
		/** Keep moving forward along the flee heading for {@link AnimalWaterFleeEscapeSystem#COMMIT_FORWARD_BLOCKS}. */
		COMMIT_FORWARD,
		/** Turn 90° and travel {@link AnimalWaterFleeEscapeSystem#TURN_CROSS_BLOCKS}. */
		TURN_CROSS,
		/** Turn another 90° (180° from flee heading) before seeking land. */
		RETURN_TURN,
		/** Swim toward the nearest land. Fear / aggression may interrupt from here. */
		SEEK_LAND,
		/** On land: pathfind {@link AnimalWaterFleeEscapeSystem#LEAVE_WATER_BLOCKS} away from water. */
		LEAVE_WATER
	}

	private boolean active;
	private Phase phase = Phase.COMMIT_FORWARD;
	/** Horizontal flee heading in degrees when the chase ended. */
	private float fleeHeading;
	/** +1 = right, -1 = left for the two 90° turns. */
	private int turnSign = 1;
	private double phaseStartX;
	private double phaseStartZ;
	@Nullable
	private BlockPos landTarget;
	@Nullable
	private BlockPos inlandTarget;
	private int repathCooldown;

	boolean isActive() {
		return this.active;
	}

	Phase getPhase() {
		return this.phase;
	}

	void begin(float fleeHeading, int turnSign, double x, double z) {
		this.active = true;
		this.phase = Phase.COMMIT_FORWARD;
		this.fleeHeading = fleeHeading;
		this.turnSign = turnSign;
		this.phaseStartX = x;
		this.phaseStartZ = z;
		this.landTarget = null;
		this.inlandTarget = null;
		this.repathCooldown = 0;
	}

	float getFleeHeading() {
		return this.fleeHeading;
	}

	int getTurnSign() {
		return this.turnSign;
	}

	double getPhaseStartX() {
		return this.phaseStartX;
	}

	double getPhaseStartZ() {
		return this.phaseStartZ;
	}

	void setPhaseStart(double x, double z) {
		this.phaseStartX = x;
		this.phaseStartZ = z;
	}

	void setPhase(Phase phase) {
		this.phase = phase;
	}

	@Nullable
	BlockPos getLandTarget() {
		return this.landTarget;
	}

	void setLandTarget(@Nullable BlockPos landTarget) {
		this.landTarget = landTarget;
	}

	@Nullable
	BlockPos getInlandTarget() {
		return this.inlandTarget;
	}

	void setInlandTarget(@Nullable BlockPos inlandTarget) {
		this.inlandTarget = inlandTarget;
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

	void clear() {
		this.active = false;
		this.phase = Phase.COMMIT_FORWARD;
		this.landTarget = null;
		this.inlandTarget = null;
		this.repathCooldown = 0;
	}
}
