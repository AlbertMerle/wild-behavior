package com.wildbehavior.ai;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/**
 * Village / trader livestock that should not flee from players, while still fleeing
 * Aggressive threats via Passive.
 *
 * <p>Detection order:
 * <ol>
 *   <li>Wandering-trader llamas ({@link EntityTypes#TRADER_LLAMA})</li>
 *   <li>Inside a village structure piece or POI village ({@link ServerLevel#isVillage})</li>
 *   <li>Within 6 blocks of a fence, bed, or oak door (pen / village-marker heuristic)</li>
 * </ol>
 */
public final class VillageAnimalCalm {
	private static final int VILLAGE_MARKER_RADIUS = 6;
	private static final int CACHE_TTL_TICKS = 40;

	private static final Map<UUID, CacheEntry> CACHE = new HashMap<>();

	private VillageAnimalCalm() {
	}

	/**
	 * True when this animal should ignore players as flee threats (not Aggressive threats).
	 */
	public static boolean ignoresPlayerFear(LivingEntity animal) {
		if (animal.getType() == EntityTypes.TRADER_LLAMA) {
			return true;
		}

		if (!(animal.level() instanceof ServerLevel serverLevel)) {
			return false;
		}

		long now = serverLevel.getGameTime();
		UUID id = animal.getUUID();
		CacheEntry cached = CACHE.get(id);
		if (cached != null && now - cached.checkedAtTick < CACHE_TTL_TICKS) {
			return cached.result;
		}

		boolean result = isInVillage(serverLevel, animal.blockPosition())
			|| isNearVillageMarker(serverLevel, animal.blockPosition());
		CACHE.put(id, new CacheEntry(now, result));
		return result;
	}

	public static void clearCache(LivingEntity animal) {
		CACHE.remove(animal.getUUID());
	}

	private static boolean isInVillage(ServerLevel level, BlockPos pos) {
		if (level.isVillage(pos)) {
			return true;
		}

		StructureStart start = level.structureManager().getStructureWithPieceAt(pos, StructureTags.VILLAGE);
		return start != null && start.isValid();
	}

	private static boolean isNearVillageMarker(ServerLevel level, BlockPos origin) {
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int dx = -VILLAGE_MARKER_RADIUS; dx <= VILLAGE_MARKER_RADIUS; dx++) {
			for (int dy = -VILLAGE_MARKER_RADIUS; dy <= VILLAGE_MARKER_RADIUS; dy++) {
				for (int dz = -VILLAGE_MARKER_RADIUS; dz <= VILLAGE_MARKER_RADIUS; dz++) {
					if (dx * dx + dy * dy + dz * dz > VILLAGE_MARKER_RADIUS * VILLAGE_MARKER_RADIUS) {
						continue;
					}

					cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
					if (!level.isLoaded(cursor)) {
						continue;
					}

					if (isVillageMarker(level.getBlockState(cursor))) {
						return true;
					}
				}
			}
		}

		return false;
	}

	private static boolean isVillageMarker(BlockState state) {
		return state.is(BlockTags.FENCES)
			|| state.is(BlockTags.BEDS)
			|| state.is(Blocks.OAK_DOOR);
	}

	private record CacheEntry(long checkedAtTick, boolean result) {
	}
}
