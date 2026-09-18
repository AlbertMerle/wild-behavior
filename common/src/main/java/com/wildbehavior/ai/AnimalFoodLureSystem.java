package com.wildbehavior.ai;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import com.wildbehavior.trust.OcelotTrustAccess;
import net.minecraft.world.entity.animal.feline.Ocelot;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;
import com.wildbehavior.config.AnimalBehaviorConfig;

/**
 * When a player holds breeding food ({@link Animal#isFood}), eligible wild PlayerFear animals
 * always follow. While following, all other Wild Behavior overlays are skipped until the food
 * is put away. Fed/tamed animals skip this system entirely (vanilla TemptGoal).
 */
public final class AnimalFoodLureSystem {
	/** Vanilla walk speed modifier (1.0 = normal attribute-based walk speed). */
	private static final double FOLLOW_SPEED_MODIFIER = 1.0D;
	private static final double FOLLOW_STOP_DISTANCE_SQR = 6.25D;

	private static final Map<UUID, AnimalFoodLureState> STATES = new ConcurrentHashMap<>();

	private AnimalFoodLureSystem() {
	}

	public static void onEntityLoad(Animal animal) {
		int stagger = Math.abs(animal.getUUID().hashCode()) % AnimalFoodLureState.PLAYER_SCAN_INTERVAL_TICKS;
		STATES.put(animal.getUUID(), new AnimalFoodLureState(stagger));
	}

	public static void onEntityUnload(LivingEntity entity) {
		STATES.remove(entity.getUUID());
	}

	public static void release(PathfinderMob mob) {
		AnimalFoodLureState state = STATES.get(mob.getUUID());
		boolean wasFollowing = state != null && state.isFollowing();
		if (state != null) {
			state.clearSession();
		}

		// Only halt navigation when we were actually driving follow — avoid freezing vanilla AI.
		if (wasFollowing) {
			mob.getNavigation().stop();
		}
	}

	public static boolean isFollowingFood(LivingEntity entity) {
		AnimalFoodLureState state = STATES.get(entity.getUUID());
		return state != null && state.isFollowing();
	}

	/** True while a food-follow session is active — other Wild Behavior overlays must not run. */
	public static boolean suppressesWildBehaviorOverlay(Animal animal) {
		return isFollowingFood(animal);
	}

	public static boolean isIgnoringPlayerThreat(Animal animal, Player player) {
		AnimalFoodLureState state = STATES.get(animal.getUUID());
		return state != null && state.isFollowingPlayer(player.getUUID());
	}

	public static void tickPathfinderMob(Animal animal) {
		if (animal.level().isClientSide() || !canApply(animal)) {
			return;
		}

		if (AnimalWaterFleeEscapeSystem.blocksAllBehaviors(animal)) {
			return;
		}

		boolean tamed = EntityPlayerTrust.isTamed(animal);
		boolean playerFearEnabled = MobBehaviorConfig.isEnabled(animal.getType(), MobBehavior.PLAYER_FEAR);
		if (!playerFearEnabled && !tamed) {
			clearSession(animal);
			return;
		}

		long gameTime = animal.level().getGameTime();
		AnimalFoodLureState state = getState(animal);
		Player lurePlayer = resolveLurePlayer(animal, state, gameTime);

		if (lurePlayer == null) {
			if (!state.hasSession() || !state.withinGrace(gameTime)) {
				clearSession(animal);
			}

			return;
		}

		state.markLureTick(gameTime);

		if (tamed) {
			ensureTamedFollowSession(animal, state, lurePlayer);
		} else if (!state.hasSessionFor(lurePlayer.getUUID())) {
			if (state.hasSession()) {
				state.clearSession();
			}

			beginFollowSession(animal, state, lurePlayer.getUUID());
		}

		if (state.isFollowing()) {
			AnimalFleeSystem.stopFleeing(animal);
			tickFollow(animal, lurePlayer);
		}
	}

	private static void ensureTamedFollowSession(PathfinderMob mob, AnimalFoodLureState state, Player lurePlayer) {
		if (!state.hasSessionFor(lurePlayer.getUUID()) || !state.isFollowing()) {
			beginFollowSession(mob, state, lurePlayer.getUUID());
		}

		AnimalFleeSystem.stopFleeing(mob);
	}

	private static void beginFollowSession(PathfinderMob mob, AnimalFoodLureState state, UUID playerId) {
		state.beginSession(playerId, true);
		AnimalFleeSystem.stopFleeing(mob);
		AnimalHerdSystem.releaseHerd(mob);
	}

	private static void tickFollow(PathfinderMob mob, Player player) {
		mob.getLookControl().setLookAt(player, 30.0F, 30.0F);

		if (mob.distanceToSqr(player) <= FOLLOW_STOP_DISTANCE_SQR) {
			mob.getNavigation().stop();
			return;
		}

		double speed = FOLLOW_SPEED_MODIFIER;
		MobNavigationHelper.setSpeedModifier(mob, speed);
		boolean pathStarted = mob.getNavigation().moveTo(player, MobNavigationHelper.clampNavigationSpeed(mob, speed));
		if (!pathStarted && !mob.getNavigation().isInProgress()) {
			mob.getMoveControl().setWantedPosition(player.getX(), player.getY(), player.getZ(), speed);
		}
	}

	@Nullable
	private static Player resolveLurePlayer(Animal animal, AnimalFoodLureState state, long gameTime) {
		Player lurePlayer = null;
		if (state.tickPlayerScanCooldown()) {
			lurePlayer = findNearestFoodPlayer(animal, false);
			state.setCachedLurePlayerId(lurePlayer != null ? lurePlayer.getUUID() : null);
		} else if (state.getCachedLurePlayerId() != null) {
			lurePlayer = findPlayerById(animal, state.getCachedLurePlayerId());
			if (lurePlayer != null && !canLureFrom(animal, lurePlayer, false)) {
				lurePlayer = null;
				state.setCachedLurePlayerId(null);
			}
		}

		if (lurePlayer != null) {
			return lurePlayer;
		}

		if (!state.hasSession() || !state.withinGrace(gameTime)) {
			return null;
		}

		UUID sessionPlayerId = state.getSessionPlayerId();
		if (sessionPlayerId == null) {
			return null;
		}

		double searchRadius = AnimalBehaviorConfig.normalDetectRange();
		for (Player player : animal.level().getEntitiesOfClass(Player.class, animal.getBoundingBox().inflate(searchRadius))) {
			if (!sessionPlayerId.equals(player.getUUID())) {
				continue;
			}

			if (canLureFrom(animal, player, false)) {
				return player;
			}
		}

		return null;
	}

	@Nullable
	private static Player findPlayerById(Animal animal, UUID playerId) {
		double searchRadius = AnimalBehaviorConfig.normalDetectRange();
		for (Player player : animal.level().getEntitiesOfClass(Player.class, animal.getBoundingBox().inflate(searchRadius))) {
			if (playerId.equals(player.getUUID())) {
				return player;
			}
		}

		return null;
	}

	@Nullable
	private static Player findNearestFoodPlayer(Animal animal, boolean requireLineOfSight) {
		double searchRadius = AnimalBehaviorConfig.normalDetectRange();
		double nearestDistanceSqr = searchRadius * searchRadius;
		Player nearest = null;

		for (Player player : animal.level().getEntitiesOfClass(Player.class, animal.getBoundingBox().inflate(searchRadius))) {
			if (!canLureFrom(animal, player, requireLineOfSight)) {
				continue;
			}

			double distanceSqr = player.distanceToSqr(animal);
			if (distanceSqr > nearestDistanceSqr) {
				continue;
			}

			nearest = player;
			nearestDistanceSqr = distanceSqr;
		}

		return nearest;
	}

	private static boolean canLureFrom(Animal animal, Player player, boolean requireLineOfSight) {
		if (!player.isAlive() || PlayerApproachDetector.ignoresWildBehavior(player)) {
			return false;
		}

		if (!player.level().dimension().equals(animal.level().dimension())) {
			return false;
		}

		if (player.isPassenger() && player.getVehicle() == animal) {
			return false;
		}

		if (animal instanceof TamableAnimal tamable && tamable.isTame() && tamable.isOwnedBy(player)) {
			return false;
		}

		if (animal instanceof Ocelot ocelot && ((OcelotTrustAccess) ocelot).animalBehavior$isTrusting()) {
			return false;
		}

		if (VillageAnimalCalm.ignoresPlayerFear(animal)) {
			return false;
		}

		if (!isHoldingBreedingFood(animal, player)) {
			return false;
		}

		return !requireLineOfSight || PlayerApproachDetector.canSeePlayer(animal, player);
	}

	public static boolean isHoldingBreedingFood(Animal animal, Player player) {
		return animal.isFood(player.getMainHandItem()) || animal.isFood(player.getOffhandItem());
	}

	private static boolean canApply(Animal animal) {
		if (!animal.isAlive() || animal.isPassenger() || animal.isInLove()) {
			return false;
		}

		return MobBehaviorConfig.isModEnabled(animal.getType());
	}

	private static AnimalFoodLureState getState(Animal animal) {
		return STATES.computeIfAbsent(animal.getUUID(), ignored -> {
			int stagger = Math.abs(animal.getUUID().hashCode()) % AnimalFoodLureState.PLAYER_SCAN_INTERVAL_TICKS;
			return new AnimalFoodLureState(stagger);
		});
	}

	private static void clearSession(Animal animal) {
		AnimalFoodLureState state = STATES.get(animal.getUUID());
		if (state != null) {
			state.clearSession();
		}
	}
}
