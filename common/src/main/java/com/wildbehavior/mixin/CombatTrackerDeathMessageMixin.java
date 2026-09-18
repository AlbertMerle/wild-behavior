package com.wildbehavior.mixin;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.world.damagesource.CombatEntry;
import net.minecraft.world.damagesource.CombatTracker;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CombatTracker.class)
public abstract class CombatTrackerDeathMessageMixin {
	/** Distinct wolves that damaged the player during this combat — more than this triggers the pack message. */
	private static final int PACK_DEATH_THRESHOLD = 2;

	@Shadow
	@Final
	private List<CombatEntry> entries;

	@Shadow
	@Final
	private LivingEntity mob;

	@Inject(method = "getDeathMessage", at = @At("RETURN"), cancellable = true)
	private void wildBehavior$wolfPackDeathMessage(CallbackInfoReturnable<Component> cir) {
		if (!(this.mob instanceof Player) || this.entries.isEmpty()) {
			return;
		}

		CombatEntry killingBlow = this.entries.get(this.entries.size() - 1);
		Entity killer = killingBlow.source().getEntity();
		if (killer == null || killer.getType() != EntityTypes.WOLF) {
			return;
		}

		Set<UUID> wolves = new HashSet<>();
		for (CombatEntry entry : this.entries) {
			Entity attacker = entry.source().getEntity();
			if (attacker != null && attacker.getType() == EntityTypes.WOLF) {
				wolves.add(attacker.getUUID());
			}
		}

		if (wolves.size() > PACK_DEATH_THRESHOLD) {
			cir.setReturnValue(Component.translatable("death.attack.wildbehavior.wolf_pack", this.mob.getDisplayName()));
		}
	}
}
