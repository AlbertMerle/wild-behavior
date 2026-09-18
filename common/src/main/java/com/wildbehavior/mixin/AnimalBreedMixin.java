package com.wildbehavior.mixin;

import com.wildbehavior.ai.EntityPlayerTrust;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Animal.class)
public abstract class AnimalBreedMixin {
	@Inject(method = "finalizeSpawnChildFromBreeding", at = @At("RETURN"))
	private static void animalBehavior$onBreedChild(ServerLevel level, Animal parent, AgeableMob child, CallbackInfo ci) {
		if (child instanceof LivingEntity living && EntityPlayerTrust.shouldTrackFeeding(living)) {
			EntityPlayerTrust.markFedByPlayer(living);
		}
	}
}
