package com.wildbehavior.ai;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Detects pens / enclosures so Wild Behavior can fall back to vanilla AI.
 * Cached scan every {@link #CHECK_INTERVAL_TICKS} for fences, gates, walls, bars,
 * or matching block classes within {@link #RADIUS} blocks (horizontal cylinder).
 */
public final class HerdFenceProximity {
	/** Blocks from the mob to the nearest enclosure block that triggers vanilla fallback. */
	public static final int RADIUS = 8;
	static final int CHECK_INTERVAL_TICKS = 10;
	/** Feet ± a couple of blocks — fences sit at ground level, not in a full sphere. */
	private static final int VERTICAL_DOWN = 2;
	private static final int VERTICAL_UP = 3;

	private static final Map<UUID, CacheEntry> CACHE = new ConcurrentHashMap<>();

	private HerdFenceProximity() {
	}

	/** Cached; safe to call every tick from the dispatcher. */
	public static boolean isNearFenceOrGate(LivingEntity entity) {
		long now = entity.level().getGameTime();
		UUID id = entity.getUUID();
		CacheEntry cached = CACHE.get(id);
		if (cached != null && now - cached.checkedAtTick < CHECK_INTERVAL_TICKS) {
			return cached.near;
		}

		boolean near = scanNearFenceOrGate(entity);
		CACHE.put(id, new CacheEntry(now, near));
		return near;
	}

	public static void clearCache(LivingEntity entity) {
		CACHE.remove(entity.getUUID());
	}

	private static boolean scanNearFenceOrGate(LivingEntity entity) {
		BlockPos origin = entity.blockPosition();
		var level = entity.level();
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		int radius = RADIUS;
		int radiusSqr = radius * radius;
		int originY = origin.getY();

		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				if (dx * dx + dz * dz > radiusSqr) {
					continue;
				}

				for (int dy = -VERTICAL_DOWN; dy <= VERTICAL_UP; dy++) {
					cursor.set(origin.getX() + dx, originY + dy, origin.getZ() + dz);
					if (!level.isLoaded(cursor)) {
						continue;
					}

					if (isEnclosureBlock(level.getBlockState(cursor))) {
						return true;
					}
				}
			}
		}

		return false;
	}

	private static boolean isEnclosureBlock(BlockState state) {
		if (state.is(BlockTags.FENCES)
			|| state.is(BlockTags.FENCE_GATES)
			|| state.is(BlockTags.WALLS)
			|| state.is(BlockTags.BARS)) {
			return true;
		}

		// Modded / untagged fence-like blocks that still extend the vanilla classes.
		var block = state.getBlock();
		return block instanceof FenceBlock
			|| block instanceof FenceGateBlock
			|| block instanceof WallBlock
			|| block instanceof IronBarsBlock;
	}

	private record CacheEntry(long checkedAtTick, boolean near) {
	}
}
