package com.wildbehavior.ai;

import java.util.Comparator;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import com.wildbehavior.config.AnimalBehaviorConfig;

final class VillagerShelterFlee {
	private static final double SHELTER_ARRIVAL_DISTANCE_SQR = 2.25D;
	private static final int BED_SEARCH_RADIUS = 48;
	private static final int DOOR_SEARCH_RADIUS = 32;

	private VillagerShelterFlee() {
	}

	static Vec3 pickShelterPosition(Villager villager, Vec3 threatPosition, AnimalFleeState state) {
		BlockPos shelter = findShelterBlock(villager, threatPosition);
		if (shelter != null) {
			state.setShelterTarget(shelter);
			return Vec3.atBottomCenterOf(shelter);
		}

		state.setShelterTarget(null);
		return pickFallbackPosition(villager, threatPosition);
	}

	static boolean isSeekingShelter(AnimalFleeState state) {
		return state.getShelterTarget() != null;
	}

	static boolean hasReachedShelter(Villager villager, AnimalFleeState state) {
		BlockPos shelter = state.getShelterTarget();
		if (shelter == null) {
			return false;
		}

		return villager.blockPosition().distSqr(shelter) <= SHELTER_ARRIVAL_DISTANCE_SQR
			|| villager.position().distanceToSqr(Vec3.atBottomCenterOf(shelter)) <= SHELTER_ARRIVAL_DISTANCE_SQR;
	}

	static boolean shouldHoldAtShelter(Villager villager, AnimalFleeState state, @Nullable LivingEntity threat) {
		if (!hasReachedShelter(villager, state)) {
			return false;
		}

		if (threat == null || !threat.isAlive()) {
			return false;
		}

		return threat.distanceToSqr(villager) <= (double) (AnimalBehaviorConfig.normalDetectRange() * AnimalBehaviorConfig.normalDetectRange());
	}

	private static Vec3 pickFallbackPosition(Villager villager, Vec3 threatPosition) {
		int fleeDistanceMin = AnimalBehaviorConfig.fleeDistanceMin();
		int fleeDistanceMax = AnimalBehaviorConfig.fleeDistanceMax();
		int fleeDistance = fleeDistanceMin + villager.getRandom().nextInt(fleeDistanceMax - fleeDistanceMin + 1);
		Vec3 fleePos = net.minecraft.world.entity.ai.util.DefaultRandomPos.getPosAway(villager, fleeDistance, 7, threatPosition);
		if (fleePos != null) {
			return fleePos;
		}

		Vec3 away = villager.position().subtract(threatPosition);
		if (away.lengthSqr() < 1.0E-4D) {
			away = new Vec3(villager.getRandom().nextDouble() - 0.5D, 0.0D, villager.getRandom().nextDouble() - 0.5D);
		}

		return villager.position().add(away.normalize().scale(fleeDistance));
	}

	@Nullable
	private static BlockPos findShelterBlock(Villager villager, Vec3 threatPosition) {
		BlockPos threatBlock = BlockPos.containing(threatPosition);
		Optional<BlockPos> shelter = findBrainShelter(villager, threatBlock);
		if (shelter.isPresent()) {
			return shelter.get();
		}

		shelter = findBedShelter(villager, threatBlock);
		if (shelter.isPresent()) {
			return shelter.get();
		}

		return findDoorShelter(villager, threatBlock);
	}

	private static Optional<BlockPos> findBrainShelter(Villager villager, BlockPos threatBlock) {
		Optional<GlobalPos> hidingPlace = villager.getBrain().getMemory(MemoryModuleType.HIDING_PLACE);
		if (hidingPlace.isPresent()) {
			Optional<BlockPos> candidate = toLocalPos(villager, hidingPlace.get());
			if (candidate.isPresent() && isValidShelter(villager, candidate.get(), threatBlock)) {
				return candidate;
			}
		}

		Optional<GlobalPos> home = villager.getBrain().getMemory(MemoryModuleType.HOME);
		if (home.isPresent()) {
			Optional<BlockPos> candidate = toLocalPos(villager, home.get());
			if (candidate.isPresent() && isBedOrDoorShelter(villager, candidate.get()) && isValidShelter(villager, candidate.get(), threatBlock)) {
				return candidate;
			}
		}

		Optional<BlockPos> nearestBed = villager.getBrain().getMemory(MemoryModuleType.NEAREST_BED);
		if (nearestBed.isPresent() && isBedOrDoorShelter(villager, nearestBed.get()) && isValidShelter(villager, nearestBed.get(), threatBlock)) {
			return nearestBed;
		}

		return Optional.empty();
	}

	private static Optional<BlockPos> findBedShelter(Villager villager, BlockPos threatBlock) {
		if (!(villager.level() instanceof ServerLevel serverLevel)) {
			return Optional.empty();
		}

		PoiManager poiManager = serverLevel.getPoiManager();
		return poiManager.getInRange(
				holder -> holder.is(PoiTypes.HOME),
				villager.blockPosition(),
				BED_SEARCH_RADIUS,
				PoiManager.Occupancy.ANY
			)
			.map(record -> record.getPos())
			.filter(pos -> isBedOrDoorShelter(villager, pos))
			.filter(pos -> isValidShelter(villager, pos, threatBlock))
			.min(Comparator.<BlockPos>comparingDouble(pos -> pos.distSqr(threatBlock)).reversed()
				.thenComparingDouble(pos -> pos.distSqr(villager.blockPosition())));
	}

	@Nullable
	private static BlockPos findDoorShelter(Villager villager, BlockPos threatBlock) {
		BlockPos origin = villager.blockPosition();
		BlockPos best = null;
		double bestScore = Double.NEGATIVE_INFINITY;

		for (BlockPos pos : BlockPos.betweenClosed(
			origin.offset(-DOOR_SEARCH_RADIUS, -4, -DOOR_SEARCH_RADIUS),
			origin.offset(DOOR_SEARCH_RADIUS, 4, DOOR_SEARCH_RADIUS))) {
			if (!isDoorShelter(villager, pos) || !isValidShelter(villager, pos, threatBlock)) {
				continue;
			}

			double score = pos.distSqr(threatBlock) - pos.distSqr(origin) * 0.25D;
			if (score > bestScore) {
				bestScore = score;
				best = pos.immutable();
			}
		}

		return best;
	}

	private static Optional<BlockPos> toLocalPos(Villager villager, GlobalPos globalPos) {
		if (!globalPos.dimension().equals(villager.level().dimension())) {
			return Optional.empty();
		}

		return Optional.of(globalPos.pos());
	}

	private static boolean isValidShelter(Villager villager, BlockPos pos, BlockPos threatBlock) {
		if (!villager.level().isLoaded(pos)) {
			return false;
		}

		return pos.distSqr(threatBlock) > 4.0D;
	}

	private static boolean isBedOrDoorShelter(Villager villager, BlockPos pos) {
		return isBedShelter(villager, pos) || isDoorShelter(villager, pos);
	}

	private static boolean isBedShelter(Villager villager, BlockPos pos) {
		BlockState state = villager.level().getBlockState(pos);
		return state.getBlock() instanceof BedBlock;
	}

	private static boolean isDoorShelter(Villager villager, BlockPos pos) {
		BlockState state = villager.level().getBlockState(pos);
		if (!(state.getBlock() instanceof DoorBlock)) {
			return false;
		}

		if (state.hasProperty(DoorBlock.HALF) && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER) {
			pos = pos.below();
		}

		return villager.getNavigation().isStableDestination(pos);
	}
}
