package com.wildbehavior.ai;

import com.wildbehavior.config.AnimalBehaviorConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;

/**
 * Voluntary crawl: hold sneak + sprint (Shift + Ctrl by default) to drop into the
 * swimming/crawl pose on land. Uses server {@link Input} so dedicated servers work
 * with vanilla clients — both keys are sent even when sprint is cancelled by sneak.
 */
public final class PlayerCrawlSystem {
	private PlayerCrawlSystem() {
	}

	/**
	 * Called at the start of {@code Player.updatePlayerPose}. Sets the swimming flag
	 * so vanilla pose selection returns {@code Pose.SWIMMING} (crawl on land).
	 */
	public static void beforeUpdatePlayerPose(Player player) {
		if (!(player instanceof ServerPlayer serverPlayer)) {
			return;
		}

		if (!wantsToCrawl(serverPlayer)) {
			return;
		}

		serverPlayer.setSwimming(true);
	}

	private static boolean wantsToCrawl(ServerPlayer player) {
		if (!AnimalBehaviorConfig.isPlayerCrawlEnabled()) {
			return false;
		}

		if (player.isSpectator()
			|| player.isPassenger()
			|| player.isSleeping()
			|| player.isFallFlying()
			|| player.getAbilities().flying) {
			return false;
		}

		Input input = player.getLastClientInput();
		return input.shift() && input.sprint();
	}
}
