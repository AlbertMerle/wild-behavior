package com.wildbehavior.mixin;

import net.minecraft.world.entity.animal.squid.Squid;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Squid.class)
public interface SquidAccessor {
	@Accessor("movementVector")
	void setMovementVector(Vec3 movementVector);
}
