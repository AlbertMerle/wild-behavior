package com.wildbehavior.ai;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;

/**
 * Server-wide gate: Wild Behavior overlay only runs when a non-spectator player is
 * within {@link #RADIUS_CHUNKS} (Chebyshev) of the mob's chunk.
 * <p>
 * Rebuilds a per-dimension chunk set once per server tick from the player list, then
 * each mob does an O(1) membership check. Vanilla {@code serverAiStep} still runs
 * according to simulation distance.
 */
public final class NearbyPlayerAiGate {
	/** Horizontal chunk radius (4 chunks = 64 blocks). */
	public static final int RADIUS_CHUNKS = 4;

	private static final Map<ResourceKey<Level>, LongOpenHashSet> ACTIVE_CHUNKS = new HashMap<>();
	private static final Set<UUID> OVERLAY_NEARBY = new HashSet<>();

	private static MinecraftServer builtForServer;
	private static int builtForTick = Integer.MIN_VALUE;

	private NearbyPlayerAiGate() {
	}

	public static void clear() {
		ACTIVE_CHUNKS.clear();
		OVERLAY_NEARBY.clear();
		builtForServer = null;
		builtForTick = Integer.MIN_VALUE;
	}

	public static void onEntityUnload(Entity entity) {
		OVERLAY_NEARBY.remove(entity.getUUID());
	}

	/**
	 * True when this entity's chunk is within {@link #RADIUS_CHUNKS} of a non-spectator player.
	 */
	public static boolean isNearPlayer(Entity entity) {
		if (!(entity.level() instanceof ServerLevel level)) {
			return false;
		}

		MinecraftServer server = level.getServer();
		if (server == null) {
			return false;
		}

		refresh(server);
		LongOpenHashSet chunks = ACTIVE_CHUNKS.get(level.dimension());
		if (chunks == null || chunks.isEmpty()) {
			return false;
		}

		return chunks.contains(packChunk(entity.getBlockX() >> 4, entity.getBlockZ() >> 4));
	}

	/**
	 * Recurring AI entry: run Wild Behavior only while near a player.
	 * On the first far tick after being near, releases overlay so vanilla/mod goals resume
	 * (does not stop navigation every distant tick).
	 */
	public static boolean allowOverlayTick(Mob mob) {
		if (isNearPlayer(mob)) {
			OVERLAY_NEARBY.add(mob.getUUID());
			return true;
		}

		if (OVERLAY_NEARBY.remove(mob.getUUID())) {
			WildBehaviorOverlay.release(mob);
		}

		return false;
	}

	private static void refresh(MinecraftServer server) {
		int tick = server.getTickCount();
		if (builtForServer == server && builtForTick == tick) {
			return;
		}

		builtForServer = server;
		builtForTick = tick;

		for (LongOpenHashSet chunks : ACTIVE_CHUNKS.values()) {
			chunks.clear();
		}

		for (ServerLevel level : server.getAllLevels()) {
			LongOpenHashSet chunks = ACTIVE_CHUNKS.computeIfAbsent(level.dimension(), key -> new LongOpenHashSet(128));
			for (ServerPlayer player : level.players()) {
				if (player.isSpectator()) {
					continue;
				}

				int cx = player.getBlockX() >> 4;
				int cz = player.getBlockZ() >> 4;
				for (int dx = -RADIUS_CHUNKS; dx <= RADIUS_CHUNKS; dx++) {
					for (int dz = -RADIUS_CHUNKS; dz <= RADIUS_CHUNKS; dz++) {
						chunks.add(packChunk(cx + dx, cz + dz));
					}
				}
			}
		}
	}

	private static long packChunk(int chunkX, int chunkZ) {
		return (chunkX & 0xFFFFFFFFL) | ((long) chunkZ << 32);
	}
}
