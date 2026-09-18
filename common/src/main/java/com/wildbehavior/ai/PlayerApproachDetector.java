package com.wildbehavior.ai;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import com.wildbehavior.trust.OcelotTrustAccess;
import net.minecraft.world.entity.animal.feline.Ocelot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import com.wildbehavior.config.AnimalBehaviorConfig;

public final class PlayerApproachDetector {
	private static final TagKey<Block> SAPLINGS = TagKey.create(Registries.BLOCK, Identifier.withDefaultNamespace("saplings"));

	public static float normalDetectRange() {
		return AnimalBehaviorConfig.normalDetectRange();
	}

	public static float sneakDetectRange() {
		return AnimalBehaviorConfig.sneakDetectRange();
	}

	public static final float NORMAL_DETECT_BLOCKS = 20.0F;
	public static final float CALM_DETECT_BLOCKS = 8.0F;
	public static final float TALL_COVER_DETECT_BLOCKS = 3.0F;
	public static final float SHORT_COVER_DETECT_BLOCKS = 6.0F;
	/** Max PlayerFear / Aggressive detect distance while the player is crawling in the open. */
	public static final float CRAWL_DETECT_BLOCKS = 4.0F;

	private static final int COVER_SEARCH_RADIUS = 1;
	private static final double SUDDEN_MOVE_DISTANCE_SQR = 0.010000000000000002;
	private static final float SUDDEN_TURN_DEGREES = 5.0F;
	private static final Map<UUID, CalmTracker> CALM_TRACKERS = new HashMap<>();

	private PlayerApproachDetector() {
	}

	/**
	 * Creative and spectator players do not trigger Wild Behavior AI (matches vanilla passive mob ignore).
	 */
	public static boolean ignoresWildBehavior(Player player) {
		return player.isSpectator() || player.isCreative();
	}

	public static boolean isPassiveFearSource(Player player) {
		if (ignoresWildBehavior(player)) {
			return false;
		}

		return AnimalBehaviorConfig.isPlayerPassiveFearEnabled(player.getUUID());
	}

	public static boolean shouldFleeFrom(LivingEntity animal, Player player) {
		if (!isPassiveFearSource(player)) {
			return false;
		}

		return isWithinPlayerFleeRange(animal, player);
	}

	public static boolean shouldFleeFromPlayerFear(LivingEntity animal, Player player) {
		return isWithinPlayerFleeRange(animal, player);
	}

	/**
	 * Trust / ownership checks that permanently cancel fleeing from this player.
	 * Does not consider detect range — used mid-flee so animals can finish fleeDistanceMin–Max.
	 */
	public static boolean shouldIgnorePlayerThreat(LivingEntity animal, Player player) {
		if (!player.isAlive() || ignoresWildBehavior(player)) {
			return true;
		}

		if (!player.level().dimension().equals(animal.level().dimension())) {
			return true;
		}

		if (player.isPassenger() && player.getVehicle() == animal) {
			return true;
		}

		if (EntityPlayerTrust.isTamed(animal)) {
			return true;
		}

		if (animal instanceof TamableAnimal tamable && tamable.isTame() && tamable.isOwnedBy(player)) {
			return true;
		}

		if (animal instanceof Ocelot ocelot && ((OcelotTrustAccess) ocelot).animalBehavior$isTrusting()) {
			return true;
		}

		if (VillageAnimalCalm.ignoresPlayerFear(animal)) {
			return true;
		}

		return animal instanceof Animal mobAnimal && AnimalFoodLureSystem.isIgnoringPlayerThreat(mobAnimal, player);
	}

	private static boolean isWithinPlayerFleeRange(LivingEntity animal, Player player) {
		if (shouldIgnorePlayerThreat(animal, player)) {
			return false;
		}

		return isWithinPlayerDetectRange(animal, player);
	}

	public static Player findNearestStartlingPlayer(LivingEntity animal, double searchRadius) {
		return findNearestMatchingPlayer(animal, searchRadius, PlayerApproachDetector::shouldFleeFrom);
	}

	public static Player findNearestPlayerFearPlayer(LivingEntity animal, double searchRadius) {
		return findNearestMatchingPlayer(animal, searchRadius, PlayerApproachDetector::shouldFleeFromPlayerFear);
	}

	@FunctionalInterface
	private interface PlayerFleePredicate {
		boolean test(LivingEntity animal, Player player);
	}

	private static Player findNearestMatchingPlayer(LivingEntity animal, double searchRadius, PlayerFleePredicate predicate) {
		Player nearest = null;
		double nearestDistanceSqr = searchRadius * searchRadius;

		for (Player player : playersWithinRange(animal, searchRadius)) {
			double distanceSqr = player.distanceToSqr(animal);
			if (distanceSqr > nearestDistanceSqr) {
				continue;
			}

			if (predicate.test(animal, player) && canSeePlayer(animal, player)) {
				nearest = player;
				nearestDistanceSqr = distanceSqr;
			}
		}

		return nearest;
	}

	public static Player findNearestAggroPlayer(LivingEntity animal, double searchRadius) {
		Player nearest = null;
		double nearestDistanceSqr = searchRadius * searchRadius;

		for (Player player : playersWithinRange(animal, searchRadius)) {
			if (!player.isAlive() || ignoresWildBehavior(player)) {
				continue;
			}

			if (player.isPassenger() && player.getVehicle() == animal) {
				continue;
			}

			if (EntityPlayerTrust.bypassesAggressive(animal)) {
				continue;
			}

			if (animal instanceof TamableAnimal tamable && tamable.isTame() && tamable.isOwnedBy(player)) {
				continue;
			}

			if (!isWithinAggroDetectRange(animal, player)) {
				continue;
			}

			if (!canAggroSeePlayer(animal, player)) {
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

	private static Iterable<Player> playersWithinRange(LivingEntity animal, double searchRadius) {
		return animal.level().getEntitiesOfClass(Player.class, animal.getBoundingBox().inflate(searchRadius));
	}

	/**
	 * Effective line-of-sight for starting / refreshing player recognition.
	 * Vanilla eye-to-eye raycast, plus crawl-in-foliage stealth (grass does not block
	 * block-collider LOS, so concealment is treated as breaking sight unless colliding).
	 * After recognition, chase/flee continues without sight until Memory expires.
	 */
	public static boolean canSeePlayer(LivingEntity animal, Player player) {
		if (isHiddenByCrawlConcealment(animal, player)) {
			return false;
		}

		return animal.hasLineOfSight(player);
	}

	/**
	 * Hard line-of-sight for Aggressive player recognition only.
	 * Crawling breaks sight unless hitboxes collide; otherwise vanilla eye-to-eye raycast.
	 * Passive / PlayerFear still use {@link #canSeePlayer}.
	 */
	public static boolean canAggroSeePlayer(LivingEntity animal, Player player) {
		if (isCrawling(player)) {
			return animal.getBoundingBox().intersects(player.getBoundingBox());
		}

		return animal.hasLineOfSight(player);
	}

	/**
	 * Whether an already-aggroing mob still has a valid player chase target (Aggressive only).
	 * No LOS required — after sight breaks, pursuit continues until Memory expires while the
	 * player stays within aggressive pursuit range.
	 */
	public static boolean canContinueAggroOnPlayer(LivingEntity animal, Player player) {
		if (!player.isAlive() || ignoresWildBehavior(player)) {
			return false;
		}

		if (!player.level().dimension().equals(animal.level().dimension())) {
			return false;
		}

		if (player.isPassenger() && player.getVehicle() == animal) {
			return false;
		}

		if (EntityPlayerTrust.bypassesAggressive(animal)) {
			return false;
		}

		if (animal instanceof TamableAnimal tamable && tamable.isTame() && tamable.isOwnedBy(player)) {
			return false;
		}

		return isWithinAggressivePursuitRange(animal, player);
	}

	/**
	 * True when {@code nowTick - lastSeenTick} exceeds this mob's Memory setting (seconds → ticks).
	 * {@code lastSeenTick <= 0} is treated as unseen since the beginning of the engagement.
	 */
	public static boolean hasForgottenPlayer(LivingEntity animal, long lastSeenTick, long nowTick) {
		int memoryTicks = AnimalBehaviorConfig.getMemoryTicks(animal.getType());
		if (lastSeenTick <= 0L) {
			return memoryTicks <= 0 || nowTick > memoryTicks;
		}

		return nowTick - lastSeenTick > memoryTicks;
	}

	private static boolean isWithinAggroDetectRange(LivingEntity animal, Player player) {
		return isWithinPlayerDetectRange(animal, player);
	}

	/**
	 * Shared PlayerFear / Aggressive / Passive-player detect range for <em>new</em> recognition.
	 * Crawling: 4 blocks in the open; collision-only while touching concealing plants/crops.
	 */
	private static boolean isWithinPlayerDetectRange(LivingEntity animal, Player player) {
		if (isCrawling(player) && isTouchingCrawlConcealment(player)) {
			return animal.getBoundingBox().intersects(player.getBoundingBox());
		}

		return isWithinPlayerPursuitRange(animal, player);
	}

	/**
	 * Geometric detect range used for mid-chase Memory pursuit.
	 * Ignores foliage collision-only so crawl-in-grass breaks sight (via {@link #canSeePlayer})
	 * without instantly ending an already-started Aggressive chase. While crawl-hidden,
	 * use normal detect range so the mob searches the area until Memory expires.
	 */
	/**
	 * Mid-chase Aggressive pursuit range while the player is hidden (crawl / foliage).
	 * Uses normal detect range so the mob can search the last-known area until Memory expires.
	 */
	private static boolean isWithinAggressivePursuitRange(LivingEntity animal, Player player) {
		if (isCrawling(player)) {
			float detectRange = normalDetectRange();
			return player.distanceToSqr(animal) <= detectRange * detectRange;
		}

		float detectRange = isCalmApproach(player, animal) ? calmDetectRange(player) : normalDetectRange();
		return player.distanceToSqr(animal) <= detectRange * detectRange;
	}

	private static boolean isWithinPlayerPursuitRange(LivingEntity animal, Player player) {
		if (isCrawling(player) && isTouchingCrawlConcealment(player)) {
			float detectRange = normalDetectRange();
			return player.distanceToSqr(animal) <= detectRange * detectRange;
		}

		if (isCrawling(player)) {
			return player.distanceToSqr(animal) <= (double) CRAWL_DETECT_BLOCKS * CRAWL_DETECT_BLOCKS;
		}

		float detectRange = isCalmApproach(player, animal) ? calmDetectRange(player) : normalDetectRange();
		return player.distanceToSqr(animal) <= detectRange * detectRange;
	}

	/**
	 * Crawl + grass/fern/crops/etc., and not physically touching the animal — hidden from recognition.
	 */
	private static boolean isHiddenByCrawlConcealment(LivingEntity animal, Player player) {
		if (!isCrawling(player) || !isTouchingCrawlConcealment(player)) {
			return false;
		}

		return !animal.getBoundingBox().intersects(player.getBoundingBox());
	}

	private static boolean isCrawling(Player player) {
		return player.isVisuallyCrawling();
	}

	/**
	 * True when the player's hitbox overlaps grass, ferns, shrubs, dry grass, or farmland crops.
	 */
	private static boolean isTouchingCrawlConcealment(Player player) {
		AABB box = player.getBoundingBox();
		BlockPos min = BlockPos.containing(box.minX, box.minY, box.minZ);
		BlockPos max = BlockPos.containing(box.maxX - 1.0E-7, box.maxY - 1.0E-7, box.maxZ - 1.0E-7);
		for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
			if (isCrawlConcealmentBlock(player.level().getBlockState(pos))) {
				return true;
			}
		}

		return false;
	}

	private static boolean isCrawlConcealmentBlock(BlockState state) {
		return state.is(Blocks.SHORT_GRASS)
			|| state.is(Blocks.TALL_GRASS)
			|| state.is(Blocks.FERN)
			|| state.is(Blocks.LARGE_FERN)
			|| state.is(Blocks.BUSH)
			|| state.is(Blocks.SHORT_DRY_GRASS)
			|| state.is(Blocks.TALL_DRY_GRASS)
			|| state.is(BlockTags.CROPS);
	}

	private static float calmDetectRange(Player player) {
		if (isNearTallCover(player)) {
			return TALL_COVER_DETECT_BLOCKS;
		}

		if (isNearShortCover(player)) {
			return SHORT_COVER_DETECT_BLOCKS;
		}

		return sneakDetectRange();
	}

	private static boolean isNearTallCover(Player player) {
		return hasCoverBlock(player, PlayerApproachDetector::isTallCoverBlock);
	}

	private static boolean isNearShortCover(Player player) {
		return hasCoverBlock(player, PlayerApproachDetector::isShortCoverBlock);
	}

	private static boolean hasCoverBlock(Player player, Predicate<BlockState> matcher) {
		BlockPos center = player.blockPosition();
		for (BlockPos pos : BlockPos.betweenClosed(
			center.offset(-COVER_SEARCH_RADIUS, -COVER_SEARCH_RADIUS, -COVER_SEARCH_RADIUS),
			center.offset(COVER_SEARCH_RADIUS, COVER_SEARCH_RADIUS, COVER_SEARCH_RADIUS))) {
			if (matcher.test(player.level().getBlockState(pos))) {
				return true;
			}
		}

		return false;
	}

	private static boolean isTallCoverBlock(BlockState state) {
		return state.is(Blocks.TALL_GRASS)
			|| state.is(Blocks.LARGE_FERN)
			|| state.is(Blocks.SUGAR_CANE)
			|| state.is(Blocks.WHEAT)
			|| state.is(Blocks.CACTUS)
			|| state.is(BlockTags.LEAVES);
	}

	private static boolean isShortCoverBlock(BlockState state) {
		return state.is(Blocks.FERN)
			|| state.is(Blocks.SHORT_GRASS)
			|| state.is(SAPLINGS)
			|| state.is(BlockTags.FLOWERS);
	}

	private static boolean isCalmApproach(Player player, LivingEntity animal) {
		if (!player.isShiftKeyDown() || !player.onGround()) {
			updateCalmTracker(player, animal);
			return false;
		}

		if (player.getDeltaMovement().y > 0.2D && !player.onGround()) {
			updateCalmTracker(player, animal);
			return false;
		}

		CalmTracker tracker = CALM_TRACKERS.computeIfAbsent(player.getUUID(), ignored -> new CalmTracker());
		double distanceSqr = player.distanceToSqr(animal);

		float calmRange = calmDetectRange(player);
		if (distanceSqr < calmRange * calmRange) {
			if (tracker.hasPreviousSample()) {
				if (player.distanceToSqr(tracker.lastX, tracker.lastY, tracker.lastZ) > SUDDEN_MOVE_DISTANCE_SQR) {
					tracker.update(player);
					return false;
				}

				if (Math.abs(player.getXRot() - tracker.lastXRot) > SUDDEN_TURN_DEGREES
					|| Math.abs(player.getYRot() - tracker.lastYRot) > SUDDEN_TURN_DEGREES) {
					tracker.update(player);
					return false;
				}
			}
		} else {
			tracker.update(player);
			return player.isShiftKeyDown() && player.onGround();
		}

		tracker.update(player);
		return true;
	}

	private static void updateCalmTracker(Player player, LivingEntity animal) {
		CalmTracker tracker = CALM_TRACKERS.computeIfAbsent(player.getUUID(), ignored -> new CalmTracker());
		tracker.update(player);
	}

	private static final class CalmTracker {
		private double lastX;
		private double lastY;
		private double lastZ;
		private float lastXRot;
		private float lastYRot;
		private boolean hasPreviousSample;

		void update(Player player) {
			this.lastX = player.getX();
			this.lastY = player.getY();
			this.lastZ = player.getZ();
			this.lastXRot = player.getXRot();
			this.lastYRot = player.getYRot();
			this.hasPreviousSample = true;
		}

		boolean hasPreviousSample() {
			return this.hasPreviousSample;
		}
	}
}
