package com.wildbehavior.ai;

import com.wildbehavior.config.AnimalBehaviorConfig;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public final class SpookSystem {
	private SpookSystem() {
	}

	public static void propagateSpook(LivingEntity source, @Nullable LivingEntity threat, Vec3 spookOrigin) {
		if (!canTriggerSpook(source) || !canSourceTriggerChainSpook(source, threat)) {
			return;
		}

		EntityType<?> species = source.getType();
		double radius;
		if (threat != null && ThreatApproachDetector.isPassiveMobThreat(source, threat)) {
			radius = AnimalBehaviorConfig.aggressiveAlertRange();
		} else if (threat != null && ThreatApproachDetector.isPassivePreyThreat(source, threat)) {
			radius = AnimalBehaviorConfig.normalDetectRange();
		} else if (threat instanceof Player) {
			// PlayerFear / player-threat pack flee uses Flee → Chain Radius (same numerical family as flee distance).
			radius = AnimalBehaviorConfig.fleeChainRadius();
		} else {
			radius = AnimalBehaviorConfig.spookRadius();
		}
		double radiusSqr = radius * radius;
		Set<UUID> recipients = new HashSet<>();

		collectSameSpeciesRecipients(source, species, radiusSqr, threat, recipients);

		for (UUID recipientId : recipients) {
			LivingEntity recipient = findEntity(source.level(), recipientId);
			if (recipient != null) {
				AnimalFleeSystem.forceSpookFlee(recipient, species, threat, spookOrigin);
			}
		}
	}

	public static boolean canTriggerSpook(LivingEntity entity) {
		return ThreatApproachDetector.canProcessFlee(entity) || WeaponScareDetector.reactsToWeaponScare(entity);
	}

	public static boolean canReceiveChainSpook(LivingEntity entity, @Nullable LivingEntity threat) {
		if (!AnimalCategories.receivesFleeBehavior(entity) || !canTriggerSpook(entity)) {
			return false;
		}

		if (threat instanceof Player player) {
			// Same-species allies within chain radius flee even if outside player detect range.
			return ThreatApproachDetector.canFleeFromPlayerIgnoringRange(entity, player);
		}

		if (threat != null && isVillagerPassiveFearThreat(threat)) {
			return ThreatApproachDetector.shouldFleeFromPassiveFearSources(entity);
		}

		if (threat != null && ThreatApproachDetector.isPassivePreyThreat(entity, threat)) {
			return ThreatApproachDetector.shouldFleeFromPassivePrey(entity);
		}

		if (threat != null && ThreatApproachDetector.isPassiveMobThreat(entity, threat)) {
			return ThreatApproachDetector.shouldFleeFromAggressive(entity);
		}

		return threat == null && WeaponScareDetector.reactsToWeaponScare(entity);
	}

	private static boolean canSourceTriggerChainSpook(LivingEntity source, @Nullable LivingEntity threat) {
		if (threat instanceof Player player) {
			return ThreatApproachDetector.canFleeFromPlayerIgnoringRange(source, player);
		}

		if (threat != null && isVillagerPassiveFearThreat(threat)) {
			return ThreatApproachDetector.shouldFleeFromPassiveFearSources(source);
		}

		if (threat != null && ThreatApproachDetector.isPassivePreyThreat(source, threat)) {
			return ThreatApproachDetector.shouldFleeFromPassivePrey(source);
		}

		if (threat != null && ThreatApproachDetector.isPassiveMobThreat(source, threat)) {
			return ThreatApproachDetector.shouldFleeFromAggressive(source);
		}

		return threat == null && WeaponScareDetector.reactsToWeaponScare(source);
	}

	private static void collectSameSpeciesRecipients(
		LivingEntity source,
		EntityType<?> species,
		double radiusSqr,
		@Nullable LivingEntity threat,
		Set<UUID> recipients
	) {
		double radius = Math.sqrt(radiusSqr);
		for (LivingEntity nearby : source.level().getEntitiesOfClass(LivingEntity.class, source.getBoundingBox().inflate(radius))) {
			if (!isSameSpeciesRecipient(source, nearby, species, radiusSqr, threat)) {
				continue;
			}

			recipients.add(nearby.getUUID());
		}
	}

	private static boolean isSameSpeciesRecipient(
		LivingEntity source,
		@Nullable LivingEntity candidate,
		EntityType<?> species,
		double radiusSqr,
		@Nullable LivingEntity threat
	) {
		if (candidate == null || candidate == source || candidate.getType() != species) {
			return false;
		}

		if (source.distanceToSqr(candidate) > radiusSqr) {
			return false;
		}

		return canReceiveChainSpook(candidate, threat);
	}

	private static boolean isVillagerPassiveFearThreat(LivingEntity threat) {
		return threat.getType() == EntityTypes.VILLAGER && AnimalBehaviorConfig.isPassiveFearSource(EntityTypes.VILLAGER);
	}

	@Nullable
	private static LivingEntity findEntity(net.minecraft.world.level.Level level, UUID entityId) {
		if (level instanceof ServerLevel serverLevel) {
			Entity entity = serverLevel.getEntity(entityId);
			return entity instanceof LivingEntity livingEntity ? livingEntity : null;
		}

		return null;
	}
}
