package com.wildbehavior.ai;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.wildbehavior.compat.AlexBirdFlight;
import com.wildbehavior.config.AnimalBehaviorConfig;
import com.wildbehavior.compat.ModCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import com.wildbehavior.config.AnimalBehaviorConfig;

public final class AnimalFleeSystem {
	private static final double TICKS_PER_SECOND = 20.0D;
	private static final int AIRBORNE_FLEE_RISE_MIN = 8;
	private static final int AIRBORNE_FLEE_RISE_MAX = 12;
	private static final double AIRBORNE_MIN_LIFT = 0.18D;
	/** Take-off burst: airborne mobs move this much faster for the first {@link #AIRBORNE_BURST_TICKS}. */
	private static final double AIRBORNE_BURST_MULTIPLIER = 1.6D;
	private static final long AIRBORNE_BURST_TICKS = 40L;
	/** Max ticks an airborne mob may spend descending before the flying flag is cleared. */
	private static final long LANDING_TIMEOUT_TICKS = 200L;
	/** Height above ground (blocks) at which an airborne landing finishes. */
	private static final double LANDING_TOUCHDOWN_HEIGHT = 1.25D;
	/** Descent speed while landing (blocks per tick). */
	private static final double LANDING_DESCENT_SPEED = 0.10D;
	private static final double LANDING_DESCENT_NEAR_GROUND = 0.05D;
	public static final long CHAIN_COOLDOWN_TICKS = 20L;
	/** Cancel flee after this many ticks in the same block without progress. */
	private static final long FLEE_STUCK_CANCEL_TICKS = 400L;
	/** After this long in one block, pick alternate flee paths to route around obstacles. */
	private static final long FLEE_STUCK_REPATH_TICKS = 40L;
	/** How often to retry alternate flee paths while still stuck. */
	private static final long FLEE_STUCK_REPATH_INTERVAL_TICKS = 40L;

	private static final Map<UUID, AnimalFleeState> STATES = new ConcurrentHashMap<>();

	private AnimalFleeSystem() {
	}

	public static void onEntityLoad(LivingEntity entity) {
		if (AnimalCategories.receivesFleeBehavior(entity)) {
			int stagger = Math.abs(entity.getUUID().hashCode()) % AnimalFleeState.IDLE_THREAT_SCAN_INTERVAL_TICKS;
			STATES.put(entity.getUUID(), new AnimalFleeState(stagger));
		}
	}

	public static void onEntityUnload(LivingEntity entity) {
		STATES.remove(entity.getUUID());
	}

	public static void clearFleeFromPlayer(MinecraftServer server, UUID playerId) {
		for (ServerLevel level : server.getAllLevels()) {
			for (Entity entity : level.getAllEntities()) {
				if (!(entity instanceof LivingEntity livingEntity)) {
					continue;
				}

				AnimalFleeState state = STATES.get(livingEntity.getUUID());
				if (state == null || !state.isFleeing() || !playerId.equals(state.getFleeingFrom())) {
					continue;
				}

				if (livingEntity instanceof PathfinderMob pathfinderMob) {
					clearFlee(pathfinderMob);
				} else if (livingEntity instanceof Bat bat) {
					state.clearBatTarget();
					state.clearFlee();
				} else {
					state.clearFlee();
				}
			}
		}
	}

	public static boolean isFleeing(LivingEntity entity) {
		AnimalFleeState state = STATES.get(entity.getUUID());
		return state != null && state.isFleeing();
	}

	/** True while fleeing from an immediate gunshot or explosion scare event. */
	public static boolean isWeaponScareFleeing(LivingEntity entity) {
		AnimalFleeState state = STATES.get(entity.getUUID());
		return state != null && state.isWeaponScareFlee() && (state.isFleeing() || state.isLanding());
	}

	/** True while the idle threat-scan interval is counting down (dispatcher must still tick to advance it). */
	public static boolean hasIdleThreatScanCooldown(LivingEntity entity) {
		if (!AnimalCategories.receivesFleeBehavior(entity)) {
			return false;
		}

		return getState(entity).hasIdleThreatScanCooldown();
	}

	public static boolean isFleeingFromPlayer(LivingEntity entity) {
		if (!(entity.level() instanceof ServerLevel serverLevel)) {
			return false;
		}

		AnimalFleeState state = STATES.get(entity.getUUID());
		if (state == null || !state.isFleeing()) {
			return false;
		}

		UUID fleeingFrom = state.getFleeingFrom();
		if (fleeingFrom == null) {
			return false;
		}

		Entity threat = serverLevel.getEntity(fleeingFrom);
		return threat instanceof Player;
	}

	public static boolean isLanding(LivingEntity entity) {
		AnimalFleeState state = STATES.get(entity.getUUID());
		return state != null && state.isLanding();
	}

	public static void stopFleeing(PathfinderMob mob) {
		clearFlee(mob);
	}

	public static void releaseBat(Bat bat) {
		AnimalFleeState state = STATES.get(bat.getUUID());
		if (state == null) {
			return;
		}

		state.clearBatTarget();
		state.clearFlee();
	}

	public static void fleeFromAttacker(PathfinderMob mob, LivingEntity attacker) {
		if (mob.level().isClientSide() || !canApplyFlee(mob) || !attacker.isAlive()) {
			return;
		}

		AnimalFightBackSystem.stopFightBack(mob);
		AnimalFleeState state = getState(mob);
		beginFlee(mob, attacker, state);
	}

	/**
	 * True when flee logic would drive this mob on the current tick (mirrors {@link #tickPathfinderMob}).
	 */
	public static boolean wouldTakeControlThisTick(PathfinderMob mob) {
		if (!AnimalCategories.receivesFleeBehavior(mob)) {
			return false;
		}

		if (isFleeing(mob) || isLanding(mob)) {
			return true;
		}

		if (!ThreatApproachDetector.canProcessFlee(mob)) {
			return false;
		}

		if (AnimalAggroSystem.isAggroing(mob)) {
			return false;
		}

		if (AnimalFightBackSystem.isFightBacking(mob) || AnimalDefenderSystem.isDefending(mob)) {
			return false;
		}

		if (!canApplyFlee(mob)) {
			return false;
		}

		AnimalFleeState state = getState(mob);
		if (!state.isIdleThreatScanReady()) {
			return false;
		}

		ThreatScanCache.ScanResult scan = ThreatScanCache.get(mob, mob.level().getGameTime());
		return scan.fleeThreat() != null || scan.scareOrigin() != null;
	}

	public static void tickPathfinderMob(PathfinderMob mob) {
		if (mob.level().isClientSide() || !AnimalCategories.receivesFleeBehavior(mob)) {
			return;
		}

		AnimalFleeState state = getState(mob);
		if (state.isWeaponScareFlee()) {
			tickWeaponScareFleePathfinderMob(mob, state);
			return;
		}

		if (mob instanceof Animal animal && AnimalFoodLureSystem.suppressesWildBehaviorOverlay(animal)) {
			return;
		}

		if (AnimalWaterFleeEscapeSystem.blocksAllBehaviors(mob)) {
			return;
		}

		if (!ThreatApproachDetector.canProcessFlee(mob)) {
			clearFlee(mob);
			return;
		}

		if (AnimalAggroSystem.isAggroing(mob)) {
			clearFlee(mob);
			return;
		}

		if (AnimalFightBackSystem.isFightBacking(mob)) {
			return;
		}

		if (AnimalDefenderSystem.isDefending(mob)) {
			return;
		}

		if (!canApplyFlee(mob)) {
			clearFlee(mob);
			return;
		}

		if (state.isFleeing()) {
			tickActiveFlee(mob, state);
			return;
		}

		if (state.isLanding()) {
			tickLanding(mob, state);
			return;
		}

		if (!state.tickIdleThreatScanCooldown()) {
			stopAirborneFlight(mob);
			return;
		}

		ThreatScanCache.ScanResult scan = ThreatScanCache.get(mob, mob.level().getGameTime());
		LivingEntity threat = scan.fleeThreat();
		Vec3 scareOrigin = scan.scareOrigin();

		if (threat != null) {
			beginFlee(mob, threat, state);
			SpookSystem.propagateSpook(mob, threat, threat.position());

			if (threat instanceof Player) {
				state.setLastSeenThreatTick(mob.level().getGameTime());
			}

			maintainFlee(mob, threat, state);
			return;
		}

		if (scareOrigin != null) {
			state.setWeaponScareFlee(true);
			beginFleeFromPosition(mob, scareOrigin, state);
			SpookSystem.propagateSpook(mob, null, scareOrigin);
			maintainFleeFromPosition(mob, scareOrigin, state);
			return;
		}

		stopAirborneFlight(mob);
	}

	private static void tickWeaponScareFleePathfinderMob(PathfinderMob mob, AnimalFleeState state) {
		if (mob instanceof Animal animal && AnimalFoodLureSystem.suppressesWildBehaviorOverlay(animal)) {
			clearFlee(mob);
			return;
		}

		if (AnimalWaterFleeEscapeSystem.blocksAllBehaviors(mob)) {
			return;
		}

		if (!ThreatApproachDetector.canProcessFlee(mob) || !canApplyFlee(mob)) {
			clearFlee(mob);
			return;
		}

		if (state.isFleeing()) {
			tickActiveFlee(mob, state);
			return;
		}

		if (state.isLanding()) {
			tickLanding(mob, state);
			return;
		}

		state.setWeaponScareFlee(false);
	}

	private static void tickActiveFlee(PathfinderMob mob, AnimalFleeState state) {
		if (!AnimalFleeState.isBehaviorUpdateTick(mob)) {
			return;
		}

		LivingEntity threat = findThreatEntity(mob, state);
		Vec3 scareOrigin = state.getFleeFromPosition();

		if (threat != null) {
			if (threat instanceof Player) {
				state.setLastSeenThreatTick(mob.level().getGameTime());
			}

			maintainFlee(mob, threat, state);
			return;
		}

		if (scareOrigin != null) {
			maintainFleeFromPosition(mob, scareOrigin, state);
			return;
		}

		if (mob instanceof Villager villager && VillagerShelterFlee.hasReachedShelter(villager, state)) {
			mob.getNavigation().stop();
			setFleeSprinting(mob, false);
			if (!VillagerShelterFlee.shouldHoldAtShelter(villager, state, null)) {
				clearFlee(mob);
			}
			return;
		}

		if (tickFleeStuckRecovery(mob, state, resolveThreatPositionForFlee(mob, state))) {
			return;
		}

		if (shouldSwimFlee(mob) && AnimalWaterFleeEscapeSystem.shouldBeginFromFlee(mob, state)) {
			AnimalWaterFleeEscapeSystem.begin(mob, AnimalWaterFleeEscapeSystem.resolveFleeHeading(mob, state));
			clearFlee(mob);
			return;
		}

		if (shouldStopFleeingFrom(mob, state)
			|| hasReachedSafeDistance(mob, state)
			|| (!isAirborneFleeMob(mob) && mob.getNavigation().isDone())) {
			endFleeOrLand(mob, state);
		} else {
			setFleeSprinting(mob, true);
			applyAirborneFleeMovement(mob, state);
		}
	}

	public static void tickBat(Bat bat) {
		if (bat.level().isClientSide() || !AnimalCategories.receivesFleeBehavior(bat)) {
			return;
		}

		if (!ThreatApproachDetector.canProcessFlee(bat)) {
			AnimalFleeState state = getState(bat);
			state.clearBatTarget();
			return;
		}

		if (!canApplyFlee(bat)) {
			AnimalFleeState state = getState(bat);
			state.clearBatTarget();
			return;
		}

		AnimalFleeState state = getState(bat);
		if (state.hasBatTarget()) {
			if (shouldStopFleeingFrom(bat, state)) {
				state.clearBatTarget();
				state.clearFlee();
				return;
			}

			if (tickBatStuckRecovery(bat, state)) {
				return;
			}

			if (bat.isResting()) {
				bat.setResting(false);
			}

			if (bat.blockPosition().closerToCenterThan(Vec3.atCenterOf(state.getBatTarget()), 4.0D)) {
				state.clearBatTarget();
			}

			return;
		}

		if (!state.tickIdleThreatScanCooldown()) {
			return;
		}

		ThreatScanCache.ScanResult scan = ThreatScanCache.get(bat, bat.level().getGameTime());
		LivingEntity threat = scan.fleeThreat();
		Vec3 scareOrigin = scan.scareOrigin();
		if (threat != null) {
			beginBatFlee(bat, threat, state);
			if (threat instanceof Player) {
				state.setLastSeenThreatTick(bat.level().getGameTime());
			}
			SpookSystem.propagateSpook(bat, threat, threat.position());
		} else if (scareOrigin != null) {
			beginBatFleeFromPosition(bat, scareOrigin, state);
			SpookSystem.propagateSpook(bat, null, scareOrigin);
		}
	}

	@Nullable
	public static net.minecraft.core.BlockPos getBatTarget(Bat bat) {
		AnimalFleeState state = STATES.get(bat.getUUID());
		return state != null && state.hasBatTarget() ? state.getBatTarget() : null;
	}

	@Nullable
	public static Vec3 getBatFleeVelocity(Bat bat, Vec3 currentMovement) {
		AnimalFleeState state = STATES.get(bat.getUUID());
		if (state == null || !state.hasBatTarget()) {
			return null;
		}

		Vec3 target = Vec3.atCenterOf(state.getBatTarget());
		Vec3 toTarget = target.subtract(bat.position());
		if (toTarget.lengthSqr() < 1.0E-4D) {
			return currentMovement;
		}

		double speed = fleeBlocksPerTick(bat.getType());
		Vec3 desired = toTarget.normalize().scale(speed);
		return currentMovement.add((desired.x - currentMovement.x) * 0.35D, (desired.y - currentMovement.y) * 0.35D, (desired.z - currentMovement.z) * 0.35D);
	}

	/** Immediate flee when a gun fires or an explosion detonates nearby. */
	public static void fleeFromWeaponScare(LivingEntity entity, Vec3 scareOrigin, @Nullable Player shooter) {
		if (!canApplyFlee(entity)) {
			return;
		}

		if (entity instanceof Animal animal && AnimalFoodLureSystem.suppressesWildBehaviorOverlay(animal)) {
			return;
		}

		if (entity instanceof Mob mob) {
			AnimalAggroSystem.releaseOverlay(mob);
			AnimalFightBackSystem.stopFightBack(mob);
		}

		AnimalFleeState state = getState(entity);
		if (entity instanceof PathfinderMob pathfinderMob) {
			if (state.isLanding()) {
				state.clearLanding();
			}

			if (shooter != null) {
				beginFlee(pathfinderMob, shooter, state);
			} else {
				beginFleeFromPosition(pathfinderMob, scareOrigin, state);
			}

			state.setWeaponScareFlee(true);
		} else if (entity instanceof Bat bat) {
			if (shooter != null) {
				beginBatFlee(bat, shooter, state);
			} else {
				beginBatFleeFromPosition(bat, scareOrigin, state);
			}

			state.setWeaponScareFlee(true);
		}
	}

	/** @deprecated Use {@link #fleeFromWeaponScare}. */
	@Deprecated
	public static void fleeFromGunshot(LivingEntity entity, Vec3 scareOrigin, @Nullable Player shooter) {
		fleeFromWeaponScare(entity, scareOrigin, shooter);
	}

	public static void forceSpookFlee(LivingEntity entity, EntityType<?> sourceSpecies, @Nullable LivingEntity threat, Vec3 spookOrigin) {
		if (entity.getType() != sourceSpecies || !canApplyFlee(entity) || !SpookSystem.canReceiveChainSpook(entity, threat)) {
			return;
		}

		if (threat != null && threat.getType() == EntityTypes.VILLAGER
			&& !hasVillagerWithinDetectRange(entity, threat)) {
			return;
		}

		AnimalFleeState state = getState(entity);
		long tick = entity.level().getGameTime();
		if (tick - state.getLastChainTick() < CHAIN_COOLDOWN_TICKS) {
			return;
		}

		state.setLastChainTick(tick);

		if (entity instanceof PathfinderMob pathfinderMob) {
			if (state.isLanding()) {
				state.clearLanding();
			}

			if (threat != null) {
				beginFlee(pathfinderMob, threat, state);
			} else {
				beginFleeFromPosition(pathfinderMob, spookOrigin, state);
			}
		} else if (entity instanceof Bat bat) {
			if (threat != null) {
				beginBatFlee(bat, threat, state);
			} else {
				beginBatFleeFromPosition(bat, spookOrigin, state);
			}
		}
	}

	private static void beginFlee(PathfinderMob mob, LivingEntity threat, AnimalFleeState state) {
		Vec3 fleePos = pickFleePosition(mob, threat.position());
		double speed = getFleeSpeed(mob);
		state.resetIdleThreatScanCooldown();
		state.clearLanding();
		state.setWeaponScareFlee(false);
		state.setFleeing(true);
		state.setFleeingFrom(threat.getUUID());
		state.setFleeFromPosition(null);
		state.setFleeTarget(fleePos);
		state.setRepathCooldown(AnimalBehaviorConfig.fleeRepathIntervalTicks());
		state.setLastSeenThreatTick(mob.level().getGameTime());
		state.setFleeStartTick(mob.level().getGameTime());
		state.resetStuckTracking(mob.blockPosition(), mob.level().getGameTime());
		setFleeSprinting(mob, true);
		MobNavigationHelper.moveTo(mob, fleePos.x, fleePos.y, fleePos.z, speed);
		applyAirborneFleeMovement(mob, state);
	}

	private static void beginFleeFromPosition(PathfinderMob mob, Vec3 scareOrigin, AnimalFleeState state) {
		Vec3 fleePos = pickFleePosition(mob, scareOrigin);
		double speed = getFleeSpeed(mob);
		state.resetIdleThreatScanCooldown();
		state.clearLanding();
		state.setFleeing(true);
		state.setFleeingFrom(null);
		state.setFleeFromPosition(scareOrigin);
		state.setFleeTarget(fleePos);
		state.setRepathCooldown(AnimalBehaviorConfig.fleeRepathIntervalTicks());
		state.setFleeStartTick(mob.level().getGameTime());
		state.resetStuckTracking(mob.blockPosition(), mob.level().getGameTime());
		setFleeSprinting(mob, true);
		MobNavigationHelper.moveTo(mob, fleePos.x, fleePos.y, fleePos.z, speed);
		applyAirborneFleeMovement(mob, state);
	}

	private static void maintainFlee(PathfinderMob mob, LivingEntity threat, AnimalFleeState state) {
		if (mob instanceof Villager villager && VillagerShelterFlee.shouldHoldAtShelter(villager, state, threat)) {
			mob.getNavigation().stop();
			setFleeSprinting(mob, false);
			return;
		}

		if (tickFleeStuckRecovery(mob, state, threat.position())) {
			return;
		}

		// Handle fleeing in water - animals should swim away
		if (shouldSwimFlee(mob)) {
			if (AnimalWaterFleeEscapeSystem.shouldBeginFromFlee(mob, state)) {
				AnimalWaterFleeEscapeSystem.begin(mob, AnimalWaterFleeEscapeSystem.resolveFleeHeading(mob, state));
				clearFlee(mob);
				return;
			}

			applySwimmingFleeMovement(mob, threat.position(), state);
			return;
		}

		setFleeSprinting(mob, true);
		MobNavigationHelper.setSpeedModifier(mob, getFleeSpeed(mob));
		applyAirborneFleeMovement(mob, state);

		state.tickRepathCooldown();
		if (state.getRepathCooldown() > 0 && (isAirborneFleeMob(mob) || !mob.getNavigation().isDone())) {
			return;
		}

		Vec3 fleePos = pickFleePosition(mob, threat.position());
		state.setFleeTarget(fleePos);
		state.setRepathCooldown(AnimalBehaviorConfig.fleeRepathIntervalTicks());
		MobNavigationHelper.moveTo(mob, fleePos.x, fleePos.y, fleePos.z, getFleeSpeed(mob));
	}

	private static void maintainFleeFromPosition(PathfinderMob mob, Vec3 scareOrigin, AnimalFleeState state) {
		if (mob instanceof Villager villager && VillagerShelterFlee.shouldHoldAtShelter(villager, state, null)) {
			mob.getNavigation().stop();
			setFleeSprinting(mob, false);
			return;
		}

		if (tickFleeStuckRecovery(mob, state, scareOrigin)) {
			return;
		}

		// Handle fleeing in water - animals should swim away
		if (shouldSwimFlee(mob)) {
			if (AnimalWaterFleeEscapeSystem.shouldBeginFromFlee(mob, state)) {
				AnimalWaterFleeEscapeSystem.begin(mob, AnimalWaterFleeEscapeSystem.resolveFleeHeading(mob, state));
				clearFlee(mob);
				return;
			}

			applySwimmingFleeMovement(mob, scareOrigin, state);
			return;
		}

		setFleeSprinting(mob, true);
		MobNavigationHelper.setSpeedModifier(mob, getFleeSpeed(mob));
		applyAirborneFleeMovement(mob, state);

		state.tickRepathCooldown();
		if (state.getRepathCooldown() > 0 && (isAirborneFleeMob(mob) || !mob.getNavigation().isDone())) {
			return;
		}

		Vec3 fleePos = pickFleePosition(mob, scareOrigin);
		state.setFleeTarget(fleePos);
		state.setRepathCooldown(AnimalBehaviorConfig.fleeRepathIntervalTicks());
		MobNavigationHelper.moveTo(mob, fleePos.x, fleePos.y, fleePos.z, getFleeSpeed(mob));
	}

	private static void beginBatFlee(Bat bat, LivingEntity threat, AnimalFleeState state) {
		Vec3 away = new Vec3(bat.getX() - threat.getX(), 0.0D, bat.getZ() - threat.getZ());
		if (away.lengthSqr() < 1.0E-4D) {
			away = new Vec3(bat.getRandom().nextDouble() - 0.5D, 0.0D, bat.getRandom().nextDouble() - 0.5D);
		}

		state.setFleeing(true);
		state.setFleeingFrom(threat.getUUID());
		state.setFleeFromPosition(null);
		state.setBatTarget(pickBatTarget(bat, away.normalize(), threat.getY()));
		state.resetStuckTracking(bat.blockPosition(), bat.level().getGameTime());
		state.setLastChainTick(bat.level().getGameTime());
		state.setLastSeenThreatTick(bat.level().getGameTime());
		if (bat.isResting()) {
			bat.setResting(false);
		}
	}

	private static void beginBatFleeFromPosition(Bat bat, Vec3 scareOrigin, AnimalFleeState state) {
		Vec3 away = new Vec3(bat.getX() - scareOrigin.x, 0.0D, bat.getZ() - scareOrigin.z);
		if (away.lengthSqr() < 1.0E-4D) {
			away = new Vec3(bat.getRandom().nextDouble() - 0.5D, 0.0D, bat.getRandom().nextDouble() - 0.5D);
		}

		state.setFleeing(true);
		state.setFleeingFrom(null);
		state.setFleeFromPosition(scareOrigin);
		state.setBatTarget(pickBatTarget(bat, away.normalize(), scareOrigin.y));
		state.resetStuckTracking(bat.blockPosition(), bat.level().getGameTime());
		state.setLastChainTick(bat.level().getGameTime());
		if (bat.isResting()) {
			bat.setResting(false);
		}
	}

	private static void clearFlee(PathfinderMob mob) {
		AnimalFleeState state = STATES.get(mob.getUUID());
		if (state == null) {
			return;
		}

		if (!state.isFleeing() && !state.isLanding()) {
			return;
		}

		state.clearFlee();
		state.clearLanding();
		setFleeSprinting(mob, false);
		stopAirborneFlight(mob);
	}

	/** Ends a flee run; airborne mobs still aloft enter a smooth landing descent. */
	private static void endFleeOrLand(PathfinderMob mob, AnimalFleeState state) {
		if (shouldSmoothLand(mob)) {
			setFleeSprinting(mob, false);
			mob.getNavigation().stop();
			state.beginLanding(mob.level().getGameTime());
			if (ModCompat.isAlexsFlyingBird(mob.getType())) {
				AlexBirdFlight.setFlying(mob, true);
			}

			return;
		}

		clearFlee(mob);
	}

	private static boolean shouldSmoothLand(PathfinderMob mob) {
		if (!isAirborneFleeMob(mob) || mob.onGround()) {
			return false;
		}

		return heightAboveGround(mob) > LANDING_TOUCHDOWN_HEIGHT;
	}

	private static void tickLanding(PathfinderMob mob, AnimalFleeState state) {
		long elapsed = mob.level().getGameTime() - state.getLandingStartTick();
		if (elapsed < 0L || elapsed > LANDING_TIMEOUT_TICKS || mob.onGround() || heightAboveGround(mob) <= LANDING_TOUCHDOWN_HEIGHT) {
			clearFlee(mob);
			return;
		}

		if (ModCompat.isAlexsFlyingBird(mob.getType())) {
			AlexBirdFlight.setFlying(mob, true);
		}

		double groundY = findGroundY(mob);
		double height = mob.getY() - groundY;
		double descent = height < 4.0D ? LANDING_DESCENT_NEAR_GROUND : LANDING_DESCENT_SPEED;

		Vec3 current = mob.getDeltaMovement();
		Vec3 desired = new Vec3(current.x * 0.7D, -descent, current.z * 0.7D);
		mob.setDeltaMovement(
			current.x + (desired.x - current.x) * 0.3D,
			current.y + (desired.y - current.y) * 0.25D,
			current.z + (desired.z - current.z) * 0.3D
		);
		mob.getMoveControl().setWantedPosition(mob.getX(), groundY, mob.getZ(), Math.max(0.4D, getFleeSpeed(mob) * 0.35D));
	}

	private static double heightAboveGround(PathfinderMob mob) {
		return Math.max(0.0D, mob.getY() - findGroundY(mob));
	}

	private static double findGroundY(PathfinderMob mob) {
		Level level = mob.level();
		BlockPos.MutableBlockPos pos = BlockPos.containing(mob.getX(), mob.getY(), mob.getZ()).mutable();
		int minY = level.getMinY();
		while (pos.getY() > minY) {
			BlockPos below = pos.below();
			BlockState blockState = level.getBlockState(below);
			VoxelShape shape = blockState.getCollisionShape(level, below);
			if (!shape.isEmpty()) {
				return below.getY() + shape.max(net.minecraft.core.Direction.Axis.Y);
			}

			pos.move(0, -1, 0);
		}

		return minY;
	}

	private static boolean shouldStopFleeingFrom(LivingEntity mob, AnimalFleeState state) {
		UUID fleeingFrom = state.getFleeingFrom();
		if (fleeingFrom == null) {
			return false;
		}

		if (!(mob.level() instanceof ServerLevel serverLevel)) {
			return false;
		}

		Entity entity = serverLevel.getEntity(fleeingFrom);
		if (!(entity instanceof Player player)) {
			return false;
		}

		// Fed / tamed / tempting: cancel immediately. Out of detect range must NOT cancel —
		// PlayerFear needs to finish the fleeDistanceMin–Max run (detect range is often smaller).
		if (PlayerApproachDetector.shouldIgnorePlayerThreat(mob, player)) {
			return true;
		}

		long now = mob.level().getGameTime();
		if (PlayerApproachDetector.canSeePlayer(mob, player)
			&& ThreatApproachDetector.shouldFleeFromPlayer(mob, player)) {
			state.setLastSeenThreatTick(now);
			return false;
		}

		// Lost LOS (or left detect range): keep running until Memory expires, then forget.
		return PlayerApproachDetector.hasForgottenPlayer(mob, state.getLastSeenThreatTick(), now);
	}

	private static boolean hasVillagerWithinDetectRange(LivingEntity animal, LivingEntity villager) {
		float detectRange = AnimalBehaviorConfig.normalDetectRange();
		return villager.distanceToSqr(animal) <= detectRange * detectRange;
	}

	private static boolean hasReachedSafeDistance(PathfinderMob mob, AnimalFleeState state) {
		if (mob instanceof Villager villager && VillagerShelterFlee.hasReachedShelter(villager, state)) {
			return true;
		}

		int fleeDistanceMin = resolveFleeDistanceMin(mob.getType());
		double safeDistanceSqr = (double) fleeDistanceMin * fleeDistanceMin;

		Vec3 fleeFromPosition = state.getFleeFromPosition();
		if (fleeFromPosition != null) {
			return mob.position().distanceToSqr(fleeFromPosition) >= safeDistanceSqr;
		}

		if (!(mob.level() instanceof ServerLevel serverLevel)) {
			return false;
		}

		UUID fleeingFrom = state.getFleeingFrom();
		if (fleeingFrom == null) {
			return true;
		}

		Entity threat = serverLevel.getEntity(fleeingFrom);
		if (threat == null || !threat.isAlive()) {
			return true;
		}

		return mob.distanceToSqr(threat) >= safeDistanceSqr;
	}

	private static boolean canApplyFlee(LivingEntity entity) {
		if (!entity.isAlive() || entity.isPassenger()) {
			return false;
		}

		if (entity instanceof Animal animal && animal.isInLove()) {
			return false;
		}

		if (entity instanceof AbstractHorse horse && horse.isVehicle()) {
			return false;
		}

		return true;
	}

	private static double getFleeSpeed(PathfinderMob mob) {
		double baseSpeed = mob.getAttributeValue(Attributes.MOVEMENT_SPEED);
		if (baseSpeed <= 0.0D) {
			baseSpeed = 0.2D;
		}

		return Math.max(0.01D, fleeBlocksPerTick(mob.getType()) / baseSpeed);
	}

	private static void setFleeSprinting(PathfinderMob mob, boolean fleeing) {
		mob.setSprinting(fleeing && !(mob instanceof Villager));
	}

	private static double fleeBlocksPerTick(EntityType<?> type) {
		return AnimalBehaviorConfig.getFleeSpeedBps(type) / TICKS_PER_SECOND;
	}

	@Nullable
	private static Vec3 pickFleePosition(PathfinderMob mob, Vec3 threatPosition) {
		if (mob instanceof Villager villager) {
			return VillagerShelterFlee.pickShelterPosition(villager, threatPosition, getState(villager));
		}

		int fleeDistanceMin = resolveFleeDistanceMin(mob.getType());
		int fleeDistanceMax = resolveFleeDistanceMax(mob.getType());
		int fleeDistance = fleeDistanceMin + mob.getRandom().nextInt(fleeDistanceMax - fleeDistanceMin + 1);
		if (isAirborneFleeMob(mob)) {
			return pickAirborneFleePosition(mob, threatPosition, fleeDistance);
		}

		Vec3 fleePos = DefaultRandomPos.getPosAway(mob, fleeDistance, 7, threatPosition);
		if (fleePos != null) {
			return fleePos;
		}

		Vec3 away = mob.position().subtract(threatPosition);
		if (away.lengthSqr() < 1.0E-4D) {
			away = new Vec3(mob.getRandom().nextDouble() - 0.5D, 0.0D, mob.getRandom().nextDouble() - 0.5D);
		}

		return mob.position().add(away.normalize().scale(fleeDistance));
	}

	private static net.minecraft.core.BlockPos pickBatTarget(Bat bat, Vec3 awayDirection, double scareY) {
		int fleeDistanceMin = resolveFleeDistanceMin(bat.getType());
		int fleeDistanceMax = resolveFleeDistanceMax(bat.getType());
		int fleeDistance = fleeDistanceMin + bat.getRandom().nextInt(fleeDistanceMax - fleeDistanceMin + 1);
		int rise = AIRBORNE_FLEE_RISE_MIN + bat.getRandom().nextInt(AIRBORNE_FLEE_RISE_MAX - AIRBORNE_FLEE_RISE_MIN + 1);
		return net.minecraft.core.BlockPos.containing(
			bat.getX() + awayDirection.x * fleeDistance,
			Math.max(bat.getY(), scareY + rise),
			bat.getZ() + awayDirection.z * fleeDistance
		);
	}

	private static int resolveFleeDistanceMin(EntityType<?> type) {
		if (AnimalBehaviorConfig.usesFlightFleeDistances(type)) {
			return AnimalBehaviorConfig.getFlightFleeMin(type);
		}

		return AnimalBehaviorConfig.fleeDistanceMin();
	}

	private static int resolveFleeDistanceMax(EntityType<?> type) {
		if (AnimalBehaviorConfig.usesFlightFleeDistances(type)) {
			return AnimalBehaviorConfig.getFlightFleeMax(type);
		}

		return AnimalBehaviorConfig.fleeDistanceMax();
	}

	private static boolean isAirborneFleeMob(PathfinderMob mob) {
		return mob.getType() == EntityTypes.BEE || ModCompat.isAlexsFlyingBird(mob.getType());
	}

	private static Vec3 pickAirborneFleePosition(PathfinderMob mob, Vec3 threatPosition, int fleeDistance) {
		Vec3 away = new Vec3(mob.getX() - threatPosition.x, 0.0D, mob.getZ() - threatPosition.z);
		if (away.lengthSqr() < 1.0E-4D) {
			away = new Vec3(mob.getRandom().nextDouble() - 0.5D, 0.0D, mob.getRandom().nextDouble() - 0.5D);
		}

		int rise = AIRBORNE_FLEE_RISE_MIN + mob.getRandom().nextInt(AIRBORNE_FLEE_RISE_MAX - AIRBORNE_FLEE_RISE_MIN + 1);
		Vec3 horizontalTarget = mob.position().add(away.normalize().scale(fleeDistance));
		return new Vec3(horizontalTarget.x, Math.max(mob.getY(), threatPosition.y + rise), horizontalTarget.z);
	}

	private static void applyAirborneFleeMovement(PathfinderMob mob, AnimalFleeState state) {
		if (!isAirborneFleeMob(mob)) {
			return;
		}

		Vec3 target = state.getFleeTarget();
		if (target == null) {
			return;
		}

		// Alex birds need their own flight flag to play the flying animation and use their flight controller.
		if (ModCompat.isAlexsFlyingBird(mob.getType())) {
			AlexBirdFlight.setFlying(mob, true);
		}

		double burst = airborneBurstMultiplier(mob, state);
		double speedModifier = getFleeSpeed(mob) * burst;
		mob.getMoveControl().setWantedPosition(target.x, target.y, target.z, speedModifier);

		Vec3 toTarget = target.subtract(mob.position());
		if (toTarget.lengthSqr() < 1.0E-4D) {
			return;
		}

		Vec3 current = mob.getDeltaMovement();
		Vec3 desired = toTarget.normalize().scale(fleeBlocksPerTick(mob.getType()) * burst);
		if (target.y - mob.getY() > 1.0D) {
			desired = new Vec3(desired.x, Math.max(desired.y, AIRBORNE_MIN_LIFT * burst), desired.z);
		}

		mob.setDeltaMovement(
			current.x + (desired.x - current.x) * 0.35D,
			current.y + (desired.y - current.y) * 0.35D,
			current.z + (desired.z - current.z) * 0.35D
		);
	}

	private static double airborneBurstMultiplier(PathfinderMob mob, AnimalFleeState state) {
		long elapsed = mob.level().getGameTime() - state.getFleeStartTick();
		return elapsed >= 0L && elapsed < AIRBORNE_BURST_TICKS ? AIRBORNE_BURST_MULTIPLIER : 1.0D;
	}

	/** Alex birds keep their flight flag only while fleeing or landing; clearing it lets them touch down. */
	private static void stopAirborneFlight(PathfinderMob mob) {
		if (preservesVanillaIdleFlight(mob)) {
			return;
		}

		if (ModCompat.isAlexsFlyingBird(mob.getType()) && AlexBirdFlight.isFlying(mob)) {
			AlexBirdFlight.setFlying(mob, false);
		}
	}

	/** Alex birds manage their own flight while idle; do not clear Alex's flight flag. */
	private static boolean preservesVanillaIdleFlight(PathfinderMob mob) {
		return ModCompat.isAlexsFlyingBird(mob.getType())
			&& !MobBehaviorConfig.isEnabled(mob.getType(), MobBehavior.HERD)
			&& !isFleeing(mob)
			&& !isLanding(mob);
	}

	/** Returns true if the mob should use swimming flee behavior (in water but not a sea creature). */
	private static boolean shouldSwimFlee(PathfinderMob mob) {
		// Only apply swimming flee to non-aquatic mobs that are actually in water
		return mob.isInWater() && !AnimalCategories.isSeaCreature(mob.getType());
	}

	/**
	 * Applies swimming movement to flee from a threat while in water.
	 * Uses normal navigation to let vanilla swimming physics handle the movement naturally.
	 */
	private static void applySwimmingFleeMovement(PathfinderMob mob, Vec3 threatPosition, AnimalFleeState state) {
		// Check if we need to repath (similar to land flee behavior)
		state.tickRepathCooldown();
		if (state.getRepathCooldown() > 0 && !mob.getNavigation().isDone()) {
			// Keep current path, just update speed
			MobNavigationHelper.setSpeedModifier(mob, getFleeSpeed(mob));
			return;
		}

		// Pick a flee position away from the threat - use water-aware positioning
		Vec3 fleePos = pickWaterFleePosition(mob, threatPosition);
		state.setFleeTarget(fleePos);
		state.setRepathCooldown(AnimalBehaviorConfig.fleeRepathIntervalTicks());

		// Use normal navigation - vanilla will handle swimming naturally
		// Don't set swimming flag manually - let vanilla detect it from being in water
		MobNavigationHelper.moveTo(mob, fleePos.x, fleePos.y, fleePos.z, getFleeSpeed(mob));
	}

	/**
	 * Picks a flee position for a mob that's in water.
	 * Uses shallower vertical search to stay near water surface.
	 */
	@Nullable
	private static Vec3 pickWaterFleePosition(PathfinderMob mob, Vec3 threatPosition) {
		int fleeDistanceMin = resolveFleeDistanceMin(mob.getType());
		int fleeDistanceMax = resolveFleeDistanceMax(mob.getType());
		int fleeDistance = fleeDistanceMin + mob.getRandom().nextInt(fleeDistanceMax - fleeDistanceMin + 1);

		// Try to find a position away from threat with shallower vertical range (stay near surface)
		Vec3 fleePos = DefaultRandomPos.getPosAway(mob, fleeDistance, 4, threatPosition);
		if (fleePos != null) {
			return fleePos;
		}

		// Fallback: calculate position directly away from threat
		Vec3 away = mob.position().subtract(threatPosition);
		if (away.lengthSqr() < 1.0E-4D) {
			away = new Vec3(mob.getRandom().nextDouble() - 0.5D, 0.0D, mob.getRandom().nextDouble() - 0.5D);
		}

		return mob.position().add(away.normalize().scale(fleeDistance));
	}

	private static AnimalFleeState getState(LivingEntity entity) {
		return STATES.computeIfAbsent(entity.getUUID(), id -> {
			int stagger = Math.abs(id.hashCode()) % AnimalFleeState.IDLE_THREAT_SCAN_INTERVAL_TICKS;
			return new AnimalFleeState(stagger);
		});
	}

	/**
	 * @return true when flee was canceled because the mob stayed in one block too long
	 */
	private static boolean tickFleeStuckRecovery(PathfinderMob mob, AnimalFleeState state, @Nullable Vec3 threatPosition) {
		if (mob instanceof Villager villager && VillagerShelterFlee.hasReachedShelter(villager, state)) {
			return false;
		}

		BlockPos current = mob.blockPosition();
		long now = mob.level().getGameTime();
		if (!current.equals(state.getStuckBlock())) {
			state.resetStuckTracking(current, now);
			return false;
		}

		long stuckTicks = state.getStuckTicks(now);
		if (stuckTicks >= FLEE_STUCK_CANCEL_TICKS) {
			clearFlee(mob);
			return true;
		}

		if (shouldRetryStuckFleePath(stuckTicks)) {
			repathStuckFlee(mob, state, threatPosition);
		}

		return false;
	}

	private static boolean tickBatStuckRecovery(Bat bat, AnimalFleeState state) {
		BlockPos current = bat.blockPosition();
		long now = bat.level().getGameTime();
		if (!current.equals(state.getStuckBlock())) {
			state.resetStuckTracking(current, now);
			return false;
		}

		long stuckTicks = state.getStuckTicks(now);
		if (stuckTicks >= FLEE_STUCK_CANCEL_TICKS) {
			state.clearBatTarget();
			state.clearFlee();
			return true;
		}

		if (shouldRetryStuckFleePath(stuckTicks)) {
			Vec3 away = new Vec3(bat.getRandom().nextDouble() - 0.5D, 0.0D, bat.getRandom().nextDouble() - 0.5D);
			double scareY = state.getFleeFromPosition() != null ? state.getFleeFromPosition().y : bat.getY();
			state.setBatTarget(pickBatTarget(bat, away.normalize(), scareY));
		}

		return false;
	}

	private static boolean shouldRetryStuckFleePath(long stuckTicks) {
		if (stuckTicks < FLEE_STUCK_REPATH_TICKS) {
			return false;
		}

		return stuckTicks == FLEE_STUCK_REPATH_TICKS
			|| (stuckTicks - FLEE_STUCK_REPATH_TICKS) % FLEE_STUCK_REPATH_INTERVAL_TICKS == 0L;
	}

	private static void repathStuckFlee(PathfinderMob mob, AnimalFleeState state, @Nullable Vec3 threatPosition) {
		Vec3 fleePos = threatPosition != null
			? pickStuckRecoveryFleePosition(mob, threatPosition)
			: pickStuckRecoveryFleePositionWithoutThreat(mob);
		if (fleePos == null) {
			return;
		}

		state.setFleeTarget(fleePos);
		state.setRepathCooldown(AnimalBehaviorConfig.fleeRepathIntervalTicks());
		setFleeSprinting(mob, true);
		MobNavigationHelper.moveTo(mob, fleePos.x, fleePos.y, fleePos.z, getFleeSpeed(mob));
		applyAirborneFleeMovement(mob, state);
	}

	@Nullable
	private static Vec3 resolveThreatPositionForFlee(PathfinderMob mob, AnimalFleeState state) {
		Vec3 fleeFromPosition = state.getFleeFromPosition();
		if (fleeFromPosition != null) {
			return fleeFromPosition;
		}

		LivingEntity threat = findThreatEntity(mob, state);
		return threat != null ? threat.position() : null;
	}

	@Nullable
	private static Vec3 pickStuckRecoveryFleePosition(PathfinderMob mob, Vec3 threatPosition) {
		if (mob instanceof Villager villager) {
			return VillagerShelterFlee.pickShelterPosition(villager, threatPosition, getState(villager));
		}

		int fleeDistanceMin = resolveFleeDistanceMin(mob.getType());
		int fleeDistanceMax = resolveFleeDistanceMax(mob.getType());
		int fleeDistance = fleeDistanceMin + mob.getRandom().nextInt(fleeDistanceMax - fleeDistanceMin + 1);
		if (isAirborneFleeMob(mob)) {
			return pickAirborneStuckRecoveryPosition(mob, threatPosition, fleeDistance);
		}

		Vec3 away = mob.position().subtract(threatPosition);
		if (away.lengthSqr() < 1.0E-4D) {
			away = new Vec3(mob.getRandom().nextDouble() - 0.5D, 0.0D, mob.getRandom().nextDouble() - 0.5D);
		}

		Vec3 ideal = mob.position().add(away.normalize().scale(fleeDistance));
		Vec3 pos = DefaultRandomPos.getPosTowards(mob, fleeDistance, 7, ideal, Math.PI / 2.0D);
		if (pos != null) {
			return pos;
		}

		pos = DefaultRandomPos.getPosTowards(mob, fleeDistance, 7, ideal, Math.PI);
		if (pos != null) {
			return pos;
		}

		pos = DefaultRandomPos.getPos(mob, fleeDistance, 7);
		if (pos != null) {
			return pos;
		}

		double angle = mob.getRandom().nextDouble() * Math.PI * 2.0D;
		return mob.position().add(Math.cos(angle) * fleeDistance, 0.0D, Math.sin(angle) * fleeDistance);
	}

	@Nullable
	private static Vec3 pickStuckRecoveryFleePositionWithoutThreat(PathfinderMob mob) {
		int fleeDistanceMin = resolveFleeDistanceMin(mob.getType());
		int fleeDistanceMax = resolveFleeDistanceMax(mob.getType());
		int fleeDistance = fleeDistanceMin + mob.getRandom().nextInt(fleeDistanceMax - fleeDistanceMin + 1);
		if (isAirborneFleeMob(mob)) {
			double angle = mob.getRandom().nextDouble() * Math.PI * 2.0D;
			Vec3 away = new Vec3(Math.cos(angle), 0.0D, Math.sin(angle));
			int rise = AIRBORNE_FLEE_RISE_MIN + mob.getRandom().nextInt(AIRBORNE_FLEE_RISE_MAX - AIRBORNE_FLEE_RISE_MIN + 1);
			Vec3 horizontalTarget = mob.position().add(away.scale(fleeDistance));
			return new Vec3(horizontalTarget.x, Math.max(mob.getY(), mob.getY() + rise), horizontalTarget.z);
		}

		Vec3 pos = DefaultRandomPos.getPos(mob, fleeDistance, 7);
		if (pos != null) {
			return pos;
		}

		double angle = mob.getRandom().nextDouble() * Math.PI * 2.0D;
		return mob.position().add(Math.cos(angle) * fleeDistance, 0.0D, Math.sin(angle) * fleeDistance);
	}

	private static Vec3 pickAirborneStuckRecoveryPosition(PathfinderMob mob, Vec3 threatPosition, int fleeDistance) {
		Vec3 away = new Vec3(mob.getX() - threatPosition.x, 0.0D, mob.getZ() - threatPosition.z);
		if (away.lengthSqr() < 1.0E-4D) {
			away = new Vec3(mob.getRandom().nextDouble() - 0.5D, 0.0D, mob.getRandom().nextDouble() - 0.5D);
		}

		double angle = (mob.getRandom().nextDouble() - 0.5D) * Math.PI;
		Vec3 rotated = rotateY(away.normalize(), angle);
		int rise = AIRBORNE_FLEE_RISE_MIN + mob.getRandom().nextInt(AIRBORNE_FLEE_RISE_MAX - AIRBORNE_FLEE_RISE_MIN + 1);
		Vec3 horizontalTarget = mob.position().add(rotated.scale(fleeDistance));
		return new Vec3(horizontalTarget.x, Math.max(mob.getY(), threatPosition.y + rise), horizontalTarget.z);
	}

	private static Vec3 rotateY(Vec3 vector, double angle) {
		double cos = Math.cos(angle);
		double sin = Math.sin(angle);
		return new Vec3(vector.x * cos - vector.z * sin, vector.y, vector.x * sin + vector.z * cos);
	}

	@Nullable
	private static LivingEntity findThreatEntity(PathfinderMob mob, AnimalFleeState state) {
		UUID fleeingFrom = state.getFleeingFrom();
		if (fleeingFrom == null || !(mob.level() instanceof ServerLevel serverLevel)) {
			return null;
		}

		Entity entity = serverLevel.getEntity(fleeingFrom);
		return entity instanceof LivingEntity livingEntity ? livingEntity : null;
	}
}
