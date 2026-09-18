package com.wildbehavior.ai;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** @deprecated Use {@link WeaponScareImmediateSystem}. */
@Deprecated
public final class GunshotScareSystem {
	private GunshotScareSystem() {
	}

	public static void onGunFired(Level level, Vec3 origin, @Nullable LivingEntity shooter) {
		WeaponScareImmediateSystem.onGunFired(level, origin, shooter);
	}
}
