package com.wildbehavior.ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

public final class AnimalFightBackSystem {
	private static final double PLAYER_SPRINT_BLOCKS_PER_TICK = 0.28D;
	private static final double CHASE_BLOCKS_PER_TICK = PLAYER_SPRINT_BLOCKS_PER_TICK * 0.93D;
	/** Ticks between fight-back combat/chase updates. */
	private static final int BEHAVIOR_UPDATE_INTERVAL_TICKS = 5;
	private static final double RETALIATION_RANGE = 40.0D;
	private static final double RETALIATION_RANGE_SQR = RETALIATION_RANGE * RETALIATION_RANGE;
	/** Fixed search radius for FightBackHelp allies. */
	private static final double FIGHTBACK_HELP_RADIUS = 20.0D;
	private static final double FIGHTBACK_HELP_RADIUS_SQR = FIGHTBACK_HELP_RADIUS * FIGHTBACK_HELP_RADIUS;
	public static final float LOW_HEALTH_FIGHTBACK_THRESHOLD = 3.0F;

	private static final Map<UUID, AnimalAggroState> STATES = new ConcurrentHashMap<>();

	private AnimalFightBackSystem() {
	}

	public static void onEntityLoad(LivingEntity entity) {
		if (MobBehaviorConfig.supportsFightBack(entity.getType())) {
			STATES.put(entity.getUUID(), new AnimalAggroState());
		}
	}

	public static void onEntityUnload(LivingEntity entity) {
		STATES.remove(entity.getUUID());
	}

	public static boolean isFightBacking(LivingEntity entity) {
		AnimalAggroState state = STATES.get(entity.getUUID());
		return state != null && state.isAggroing();
	}

	public static boolean isTooWoundedToFight(LivingEntity entity) {
		return entity.getHealth() <= LOW_HEALTH_FIGHTBACK_THRESHOLD;
	}

	public static void stopFightBack(Mob mob) {
		clearFightBack(mob);
	}

	public static void onDamaged(LivingEntity victim, DamageSource source, float amount) {
		if (victim.level().isClientSide() || amount <= 0.0F || !(victim instanceof Mob mob)) {
			return;
		}

		if (EntityPlayerTrust.bypassesFightBack(victim)) {
			return;
		}

		Entity attackerEntity = source.getEntity();
		if (!(attackerEntity instanceof LivingEntity attacker) || attacker == victim || !attacker.isAlive() || attacker.isSpectator()) {
			return;
		}

		if (attacker instanceof Player player && PlayerApproachDetector.ignoresWildBehavior(player)) {
			return;
		}

		if (MobBehaviorConfig.canFightBack(victim.getType()) && isTooWoundedToFight(mob)) {
			switchToFleeWhenTooWounded(mob, attacker);
			return;
		}

		if (!MobBehaviorConfig.canFightBack(victim.getType()) || !canApplyFightBack(mob)) {
			return;
		}

		if (mob instanceof PathfinderMob pathfinderMob && AnimalFleeSystem.isFleeing(pathfinderMob)) {
			AnimalFleeSystem.stopFleeing(pathfinderMob);
		}

		AnimalAggroState state = getState(mob);
		beginFightBack(mob, attacker, state);
		summonFightBackHelp(mob, attacker);
	}

	public static void tickMob(Mob mob) {
		if (mob.level().isClientSide() || !MobBehaviorConfig.canFightBack(mob.getType())) {
			return;
		}

		if (EntityPlayerTrust.bypassesFightBack(mob)) {
			clearFightBack(mob);
			return;
		}

		if (!canApplyFightBack(mob)) {
			clearFightBack(mob);
			return;
		}

		AnimalAggroState state = STATES.get(mob.getUUID());
		if (state == null || !state.isAggroing()) {
			return;
		}

		if (isTooWoundedToFight(mob)) {
			switchToFleeWhenTooWounded(mob, findRetaliationTarget(mob, state));
			return;
		}

		LivingEntity target = findRetaliationTarget(mob, state);
		if (target == null) {
			clearFightBack(mob);
			return;
		}

		if (isBehaviorUpdateTick(mob)) {
			performFightBackCombat(mob, target, state);
		}
	}

	private static boolean isBehaviorUpdateTick(LivingEntity entity) {
		return (entity.level().getGameTime() + (entity.getId() & 1)) % BEHAVIOR_UPDATE_INTERVAL_TICKS == 0;
	}

	private static void beginFightBack(Mob mob, LivingEntity target, AnimalAggroState state) {
		state.setAggroing(true);
		state.setAggroTargetId(target.getUUID());
		mob.setTarget(target);
	}

	/**
	 * When FightBackHelp &gt; 0, the nearest same-species allies within 20 blocks also retaliate.
	 * Allies are recruited only from the originally damaged mob (no recursive help).
	 */
	private static void summonFightBackHelp(Mob victim, LivingEntity attacker) {
		int helpCount = MobBehaviorConfig.getFightBackHelp(victim.getType());
		if (helpCount <= 0 || victim.level().isClientSide()) {
			return;
		}

		EntityType<?> species = victim.getType();
		List<Mob> candidates = new ArrayList<>();
		for (LivingEntity nearby : victim.level().getEntitiesOfClass(
			LivingEntity.class,
			victim.getBoundingBox().inflate(FIGHTBACK_HELP_RADIUS)
		)) {
			if (!(nearby instanceof Mob ally) || ally == victim || ally.getType() != species) {
				continue;
			}

			if (victim.distanceToSqr(ally) > FIGHTBACK_HELP_RADIUS_SQR) {
				continue;
			}

			if (!MobBehaviorConfig.canFightBack(ally.getType()) || !canApplyFightBack(ally) || isFightBacking(ally)) {
				continue;
			}

			candidates.add(ally);
		}

		candidates.sort(Comparator.comparingDouble(victim::distanceToSqr));

		int joined = 0;
		for (Mob ally : candidates) {
			if (joined >= helpCount) {
				break;
			}

			if (ally instanceof PathfinderMob pathfinderMob && AnimalFleeSystem.isFleeing(pathfinderMob)) {
				AnimalFleeSystem.stopFleeing(pathfinderMob);
			}

			beginFightBack(ally, attacker, getState(ally));
			joined++;
		}
	}

	private static void performFightBackCombat(Mob mob, LivingEntity target, AnimalAggroState state) {
		mob.setTarget(target);
		mob.getLookControl().setLookAt(target, 30.0F, 30.0F);

		if (AnimalMeleeCombat.tryMeleeAttack(mob, target, state, AnimalMeleeCombat.DEFAULT_AGGRESSIVE_DAMAGE)) {
			return;
		}

		if (mob instanceof PathfinderMob pathfinderMob) {
			boolean villager = mob instanceof Villager;
			double chaseSpeed = villager ? getConfiguredFleeSpeed(pathfinderMob) : getChaseSpeed(pathfinderMob);
			MobNavigationHelper.moveTo(pathfinderMob, target, chaseSpeed);
			MobNavigationHelper.setSpeedModifier(pathfinderMob, chaseSpeed);
			if (!villager) {
				mob.setSprinting(true);
			}
		}
	}

	@Nullable
	private static LivingEntity findRetaliationTarget(Mob mob, AnimalAggroState state) {
		UUID targetId = state.getAggroTargetId();
		if (targetId == null) {
			return null;
		}

		LivingEntity target = findEntity(mob.level(), targetId);
		if (target == null || !target.isAlive() || target.isSpectator()) {
			return null;
		}

		if (target instanceof Player player && PlayerApproachDetector.ignoresWildBehavior(player)) {
			return null;
		}

		if (mob.distanceToSqr(target) > RETALIATION_RANGE_SQR) {
			return null;
		}

		return target;
	}

	private static double getChaseSpeed(PathfinderMob mob) {
		double baseSpeed = getAttributeOrDefault(mob, Attributes.MOVEMENT_SPEED, 0.2D);
		return Math.max(1.0D, CHASE_BLOCKS_PER_TICK / baseSpeed);
	}

	private static double getConfiguredFleeSpeed(PathfinderMob mob) {
		double baseSpeed = getAttributeOrDefault(mob, Attributes.MOVEMENT_SPEED, 0.2D);
		double blocksPerTick = MobBehaviorConfig.getFleeSpeedBps(mob.getType()) / 20.0D;
		return Math.max(0.01D, blocksPerTick / baseSpeed);
	}

	private static double getAttributeOrDefault(LivingEntity entity, Holder<Attribute> attribute, double fallback) {
		if (!entity.getAttributes().hasAttribute(attribute)) {
			return fallback;
		}

		return entity.getAttributeValue(attribute);
	}

	private static void switchToFleeWhenTooWounded(Mob mob, @Nullable LivingEntity fleeFrom) {
		stopFightBack(mob);
		if (fleeFrom != null && fleeFrom.isAlive() && mob instanceof PathfinderMob pathfinderMob) {
			AnimalFleeSystem.fleeFromAttacker(pathfinderMob, fleeFrom);
		}
	}

	private static void clearFightBack(Mob mob) {
		AnimalAggroState state = STATES.get(mob.getUUID());
		if (state == null || !state.isAggroing()) {
			return;
		}

		if (mob.getTarget() != null && isFightBackTarget(mob.getTarget(), state)) {
			mob.setTarget(null);
		}

		mob.setSprinting(false);
		if (mob instanceof PathfinderMob pathfinderMob) {
			pathfinderMob.getNavigation().stop();
		}

		state.clearAggro();
	}

	private static boolean isFightBackTarget(LivingEntity target, AnimalAggroState state) {
		UUID targetId = state.getAggroTargetId();
		return targetId != null && targetId.equals(target.getUUID());
	}

	private static boolean canApplyFightBack(Mob mob) {
		if (!mob.isAlive() || mob.isPassenger()) {
			return false;
		}

		if (isTooWoundedToFight(mob)) {
			return false;
		}

		if (mob instanceof AgeableMob ageable && ageable.isBaby()) {
			return false;
		}

		if (mob instanceof TamableAnimal tamable && tamable.isTame()) {
			return false;
		}

		if (mob instanceof Animal animal && animal.isInLove()) {
			return false;
		}

		if (mob instanceof AbstractHorse horse && horse.isVehicle()) {
			return false;
		}

		return true;
	}

	@Nullable
	private static LivingEntity findEntity(net.minecraft.world.level.Level level, UUID entityId) {
		if (level instanceof ServerLevel serverLevel) {
			Entity entity = serverLevel.getEntity(entityId);
			return entity instanceof LivingEntity livingEntity ? livingEntity : null;
		}

		return null;
	}

	private static AnimalAggroState getState(LivingEntity entity) {
		return STATES.computeIfAbsent(entity.getUUID(), ignored -> new AnimalAggroState());
	}
}
