package com.wildbehavior.ai;

public final class MobMovementScaling {
	public static final double FLEE_MULTIPLIER = 0.4D;
	public static final double HERD_MULTIPLIER = 0.6D;

	private MobMovementScaling() {
	}

	public static double scaleFlee(double speed) {
		return speed * FLEE_MULTIPLIER;
	}

	public static double scaleHerd(double speed) {
		return speed * HERD_MULTIPLIER;
	}
}
