package com.wildbehavior.mixin;

import com.wildbehavior.ai.AnimalFleeSystem;
import com.wildbehavior.ai.AnimalHerdSystem;
import com.wildbehavior.ai.NearbyPlayerAiGate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Bat.class)
public abstract class BatFleeMixin {
	@Shadow
	@Nullable
	private BlockPos targetPosition;

	@Inject(method = "customServerAiStep", at = @At("HEAD"))
	private void animalBehavior$tickAndApplyBatFlee(ServerLevel serverLevel, CallbackInfo ci) {
		Bat self = (Bat) (Object) this;
		if (!NearbyPlayerAiGate.allowOverlayTick(self)) {
			return;
		}

		AnimalFleeSystem.tickBat(self);
		AnimalHerdSystem.tickBat(self);

		BlockPos fleeTarget = AnimalFleeSystem.getBatTarget(self);
		if (fleeTarget != null) {
			this.targetPosition = fleeTarget;
			return;
		}

		if (AnimalHerdSystem.isBatGrazing(self)) {
			this.targetPosition = null;
			return;
		}

		BlockPos herdTarget = AnimalHerdSystem.getBatHerdTarget(self);
		if (herdTarget != null) {
			this.targetPosition = herdTarget;
		}
	}

	@Inject(method = "tick", at = @At("TAIL"))
	private void animalBehavior$boostBatFleeSpeed(CallbackInfo ci) {
		Bat self = (Bat) (Object) this;
		if (self.level().isClientSide() || self.isResting() || !NearbyPlayerAiGate.isNearPlayer(self)) {
			return;
		}

		Vec3 adjusted = AnimalFleeSystem.getBatFleeVelocity(self, self.getDeltaMovement());
		if (adjusted != null) {
			self.setDeltaMovement(adjusted);
		}
	}
}
