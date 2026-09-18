package com.wildbehavior.mixin;

import com.wildbehavior.trust.OcelotTrustAccess;
import net.minecraft.world.entity.animal.feline.Ocelot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Ocelot.class)
public interface OcelotAccessor extends OcelotTrustAccess {
	@Invoker("isTrusting")
	@Override
	boolean animalBehavior$isTrusting();
}
