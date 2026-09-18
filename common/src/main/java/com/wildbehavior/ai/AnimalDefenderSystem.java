package com.wildbehavior.ai;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.jetbrains.annotations.Nullable;

public final class AnimalDefenderSystem {
	private static final double PLAYER_SPRINT_BLOCKS_PER_TICK = 0.28D;

	private static final Map<UUID, AnimalAggroState> STATES = new ConcurrentHashMap<>();

	private AnimalDefenderSystem() {
	}

	public static void onEntityLoad(LivingEntity entity) {
		if (MobBehaviorConfig.isEnabled(entity.getType(), MobBehavior.DEFENDER)) {
			int stagger = Math.abs(entity.getUUID().hashCode()) % AnimalAggroState.IDLE_THREAT_SCAN_INTERVAL_TICKS;
			STATES.put(entity.getUUID(), new AnimalAggroState(stagger));
		}
	}

	public static void onEntityUnload(LivingEntity entity) {
		STATES.remove(entity.getUUID());
	}

	public static void releaseOverlay(Mob mob) {
		clearModDefend(mob);
	}

	public static boolean isDefending(LivingEntity entity) {
		AnimalAggroState state = STATES.get(entity.getUUID());
		return state != null && state.isAggroing();
	}

	public static void tickMob(Mob mob) {
		if (mob.level().isClientSide() || !MobBehaviorConfig.isEnabled(mob.getType(), MobBehavior.DEFENDER)) {
			return;
		}

		if (!mob.isAlive() || mob.isPassenger()) {
			clearModDefend(mob);
			return;
		}

		if (AnimalFleeSystem.isFleeing(mob) || AnimalAggroSystem.isAggroing(mob)) {
			clearModDefend(mob);
			return;
		}

		AnimalAggroState state = getState(mob);

		if (state.isAggroing()) {
			LivingEntity current = findDefendTarget(mob, state);
			if (current != null) {
				if (AnimalAggroState.isBehaviorUpdateTick(mob)) {
					performDefendCombat(mob, current, state);
				}

				return;
			}

			clearModDefend(mob);
		}

		if (!state.tickIdleThreatScanCooldown()) {
			return;
		}

		LivingEntity target = ThreatApproachDetector.findNearestDefenderTarget(mob);

		if (target != null) {
			beginDefend(mob, target, state);
			performDefendCombat(mob, target, state);
		}
	}

	@Nullable
	private static LivingEntity findDefendTarget(Mob mob, AnimalAggroState state) {
		UUID targetId = state.getAggroTargetId();
		if (targetId == null) {
			return null;
		}

		LivingEntity target = findEntity(mob.level(), targetId);
		if (target == null || !target.isAlive() || !ThreatApproachDetector.isAggressiveThreat(target)) {
			return null;
		}

		return target;
	}

	@Nullable
	private static LivingEntity findEntity(net.minecraft.world.level.Level level, UUID entityId) {
		if (level instanceof ServerLevel serverLevel) {
			Entity entity = serverLevel.getEntity(entityId);
			return entity instanceof LivingEntity livingEntity ? livingEntity : null;
		}

		return null;
	}

	private static void beginDefend(Mob mob, LivingEntity target, AnimalAggroState state) {
		state.resetIdleThreatScanCooldown();
		state.setAggroing(true);
		state.setAggroTargetId(target.getUUID());
		mob.setTarget(target);
	}

	private static void performDefendCombat(Mob mob, LivingEntity target, AnimalAggroState state) {
		mob.setTarget(target);
		mob.getLookControl().setLookAt(target, 30.0F, 30.0F);

		if (AnimalMeleeCombat.tryMeleeAttack(mob, target, state, AnimalMeleeCombat.DEFAULT_DEFENDER_DAMAGE)) {
			return;
		}

		if (mob instanceof PathfinderMob pathfinderMob) {
			double chaseSpeed = getChaseSpeed(pathfinderMob);
			MobNavigationHelper.moveTo(pathfinderMob, target, chaseSpeed);
			MobNavigationHelper.setSpeedModifier(pathfinderMob, chaseSpeed);
			mob.setSprinting(true);
		}
	}

	private static double getChaseSpeed(PathfinderMob mob) {
		double baseSpeed = getAttributeOrDefault(mob, Attributes.MOVEMENT_SPEED, 0.2D);
		return Math.max(1.0D, PLAYER_SPRINT_BLOCKS_PER_TICK / baseSpeed);
	}

	private static double getAttributeOrDefault(LivingEntity entity, Holder<Attribute> attribute, double fallback) {
		if (!entity.getAttributes().hasAttribute(attribute)) {
			return fallback;
		}

		return entity.getAttributeValue(attribute);
	}

	private static void clearModDefend(Mob mob) {
		AnimalAggroState state = STATES.get(mob.getUUID());
		if (state == null || !state.isAggroing()) {
			return;
		}

		if (mob.getTarget() != null && isModDefendTarget(mob.getTarget(), state)) {
			mob.setTarget(null);
		}

		mob.setSprinting(false);
		if (mob instanceof PathfinderMob pathfinderMob) {
			pathfinderMob.getNavigation().stop();
		}

		state.clearAggro();
	}

	private static boolean isModDefendTarget(LivingEntity target, AnimalAggroState state) {
		UUID targetId = state.getAggroTargetId();
		return targetId != null && targetId.equals(target.getUUID());
	}

	private static AnimalAggroState getState(LivingEntity entity) {
		return STATES.computeIfAbsent(entity.getUUID(), id -> {
			int stagger = Math.abs(id.hashCode()) % AnimalAggroState.IDLE_THREAT_SCAN_INTERVAL_TICKS;
			return new AnimalAggroState(stagger);
		});
	}
}
