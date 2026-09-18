package com.wildbehavior.compat.mixin;

import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.TamableAnimal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.UUID;

/**
 * Alex's Mobs 26.2: {@code EntityRhinoceros.considersEntityAsAlly} NPEs when a tamable
 * entity has no owner reference (calls {@code getOwnerReference().getUUID()} unchecked).
 */
@Mixin(targets = "com.github.alexthe666.alexsmobs.entity.EntityRhinoceros", remap = false)
public abstract class AlexRhinocerosAllianceFixMixin {
	private static final UUID UNTRUSTED_OWNER_PLACEHOLDER = new UUID(0L, 0L);

	@Redirect(
		method = "considersEntityAsAlly",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/entity/TamableAnimal;getOwnerReference()Lnet/minecraft/world/entity/EntityReference;"
		),
		require = 1
	)
	private EntityReference<?> wildBehavior$nullSafeOwnerReference(TamableAnimal tamable) {
		EntityReference<?> ownerReference = tamable.getOwnerReference();
		if (ownerReference != null) {
			return ownerReference;
		}
		// Stand-in so getUUID() does not NPE; trusts() will not match this sentinel.
		return EntityReference.of(UNTRUSTED_OWNER_PLACEHOLDER);
	}
}
