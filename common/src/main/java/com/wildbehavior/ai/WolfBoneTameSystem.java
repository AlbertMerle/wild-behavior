package com.wildbehavior.ai;

import com.wildbehavior.config.AnimalBehaviorConfig;
import com.wildbehavior.mixin.WolfAccessor;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * While an untamed wolf is aggroing a player, every 5 ticks check whether that player holds a bone
 * or raw meat ({@link Wolf#isFood}). On first detection, roll 50/50 between an offer approach
 * (player may tame) or a steal-and-flee attack.
 *
 * <p>Vanilla-tamed wolves skip all Wild Behavior overlays and use vanilla wolf AI only.
 */
public final class WolfBoneTameSystem {
	private static final double HOLD_DISTANCE = 5.0D;
	private static final double HOLD_DISTANCE_TOLERANCE = 0.75D;
	private static final double FLEE_DISTANCE = 8.0D;
	private static final double APPROACH_SPEED = 0.7D;
	private static final double HOLD_REPOSITION_SPEED = 1.0D;
	private static final double CHARGE_SPEED = 1.35D;
	private static final double FLEE_SPEED = 1.4D;
	private static final float CHANCE = 0.5F;

	private static final Map<UUID, WolfBoneTameState> STATES = new ConcurrentHashMap<>();

	private WolfBoneTameSystem() {
	}

	public static void onEntityLoad(LivingEntity entity) {
		if (entity.getType() != EntityTypes.WOLF) {
			return;
		}

		int stagger = Math.abs(entity.getUUID().hashCode()) % WolfBoneTameState.ITEM_CHECK_INTERVAL_TICKS;
		STATES.put(entity.getUUID(), new WolfBoneTameState(stagger));
	}

	public static void onEntityUnload(LivingEntity entity) {
		STATES.remove(entity.getUUID());
	}

	public static void release(LivingEntity entity) {
		WolfBoneTameState state = STATES.get(entity.getUUID());
		if (state != null) {
			state.clearImmediate();
		}
	}

	public static boolean isActive(LivingEntity entity) {
		WolfBoneTameState state = STATES.get(entity.getUUID());
		return state != null && state.isActive();
	}

	/** True when this system owns navigation / combat for the wolf this tick. */
	public static boolean suppressesWildBehaviorOverlay(LivingEntity entity) {
		return isActive(entity);
	}

	/**
	 * @return true when this system consumed the tick (caller should skip other WB overlays)
	 */
	public static boolean tick(Wolf wolf) {
		if (wolf.level().isClientSide() || !wolf.isAlive()) {
			return false;
		}

		if (wolf.isTame()) {
			release(wolf);
			WildBehaviorOverlay.release(wolf);
			return true;
		}

		if (!MobBehaviorConfig.isModEnabled(wolf.getType())) {
			return false;
		}

		WolfBoneTameState state = getState(wolf);
		if (state.isActive()) {
			tickActiveSession(wolf, state);
			return true;
		}

		if (state.isOnCooldown(wolf.level().getGameTime())) {
			return false;
		}

		if (!AnimalAggroSystem.isAggroing(wolf)) {
			return false;
		}

		Player player = resolveAggroPlayer(wolf);
		if (player == null) {
			return false;
		}

		if (!state.tickItemCheckCooldown()) {
			return false;
		}

		if (!isHoldingBoneOrRawMeat(wolf, player)) {
			return false;
		}

		AnimalFleeSystem.stopFleeing(wolf);
		AnimalFightBackSystem.stopFightBack(wolf);
		AnimalHerdSystem.releaseHerd(wolf);

		if (wolf.getRandom().nextFloat() < CHANCE) {
			state.beginOffer(player.getUUID());
		} else {
			state.beginSteal(player.getUUID());
		}

		tickActiveSession(wolf, state);
		return true;
	}

	private static void tickActiveSession(Wolf wolf, WolfBoneTameState state) {
		Player player = resolveSessionPlayer(wolf, state);
		if (player == null) {
			endSession(wolf, state);
			return;
		}

		if (wolf.isTame()) {
			endSessionImmediate(wolf, state);
			WildBehaviorOverlay.release(wolf);
			return;
		}

		WolfBoneTameState.Phase phase = state.getPhase();
		if (phase == WolfBoneTameState.Phase.OFFER_HOLD
			|| phase == WolfBoneTameState.Phase.OFFER_APPROACH
			|| phase == WolfBoneTameState.Phase.STEAL_HOLD
			|| phase == WolfBoneTameState.Phase.STEAL_ATTACK) {
			if (!isHoldingBoneOrRawMeat(wolf, player)) {
				resolveItemSwap(wolf, state, player);
				return;
			}
		}

		state.tickSession();
		wolf.setTarget(null);
		wolf.setSprinting(false);

		switch (state.getPhase()) {
			case OFFER_HOLD -> tickOfferHold(wolf, state, player);
			case OFFER_APPROACH -> tickOfferApproach(wolf, state, player);
			case STEAL_HOLD -> tickStealHold(wolf, state, player);
			case STEAL_ATTACK -> tickStealAttack(wolf, state, player);
			case FLEE -> tickFlee(wolf, state, player);
			case ATTACK -> tickAttack(wolf, state, player);
			case IDLE -> {
			}
		}
	}

	private static void tickOfferHold(Wolf wolf, WolfBoneTameState state, Player player) {
		lookAndGrowl(wolf, state, player);
		holdAtDistance(wolf, player, HOLD_DISTANCE, HOLD_REPOSITION_SPEED);

		if (state.getPhaseTicks() >= WolfBoneTameState.OFFER_HOLD_TICKS) {
			state.advanceToOfferApproach();
		}

		if (state.getSessionTicks() >= WolfBoneTameState.OFFER_TOTAL_TICKS) {
			resolveOfferTimeout(wolf, state, player);
		}
	}

	private static void tickOfferApproach(Wolf wolf, WolfBoneTameState state, Player player) {
		lookAndGrowl(wolf, state, player);
		wolf.setIsInterested(true);
		MobNavigationHelper.setSpeedModifier(wolf, APPROACH_SPEED);
		MobNavigationHelper.moveTo(wolf, player, APPROACH_SPEED);

		if (state.getSessionTicks() >= WolfBoneTameState.OFFER_TOTAL_TICKS) {
			wolf.setIsInterested(false);
			resolveOfferTimeout(wolf, state, player);
		}
	}

	private static void tickStealHold(Wolf wolf, WolfBoneTameState state, Player player) {
		lookAndGrowl(wolf, state, player);
		holdAtDistance(wolf, player, HOLD_DISTANCE, HOLD_REPOSITION_SPEED);

		if (state.getPhaseTicks() >= WolfBoneTameState.STEAL_HOLD_TICKS) {
			state.advanceToStealAttack();
		}
	}

	private static void tickStealAttack(Wolf wolf, WolfBoneTameState state, Player player) {
		lookAndGrowl(wolf, state, player);
		wolf.setSprinting(true);
		MobNavigationHelper.setSpeedModifier(wolf, CHARGE_SPEED);
		MobNavigationHelper.moveTo(wolf, player, CHARGE_SPEED);

		if (!wolf.isWithinMeleeAttackRange(player) || !wolf.hasLineOfSight(player)) {
			return;
		}

		if (!state.hasStolenItem()) {
			wolf.swing(InteractionHand.MAIN_HAND);
			float damage = AnimalMeleeCombat.getMeleeDamage(wolf, AnimalMeleeCombat.DEFAULT_AGGRESSIVE_DAMAGE);
			player.hurt(wolf.damageSources().mobAttack(wolf), damage);
			stealHeldItemIntoMouth(wolf, player);
			state.markStolenItem();
		}

		beginFleePhase(wolf, state, player);
	}

	private static void tickFlee(Wolf wolf, WolfBoneTameState state, Player player) {
		wolf.setIsInterested(false);
		wolf.setSprinting(true);
		Vec3 fleePos = DefaultRandomPos.getPosAway(wolf, (int) FLEE_DISTANCE, 7, player.position());
		if (fleePos == null) {
			Vec3 away = wolf.position().subtract(player.position());
			if (away.lengthSqr() < 1.0E-4D) {
				away = new Vec3(wolf.getRandom().nextDouble() - 0.5D, 0.0D, wolf.getRandom().nextDouble() - 0.5D);
			}

			fleePos = wolf.position().add(away.normalize().scale(FLEE_DISTANCE));
		}

		MobNavigationHelper.moveTo(wolf, fleePos.x, fleePos.y, fleePos.z, FLEE_SPEED);

		if (wolf.distanceToSqr(player) >= FLEE_DISTANCE * FLEE_DISTANCE
			|| state.getPhaseTicks() >= WolfBoneTameState.FLEE_TIMEOUT_TICKS) {
			AnimalAggroSystem.releaseOverlay(wolf);
			endSession(wolf, state);
		}
	}

	private static void tickAttack(Wolf wolf, WolfBoneTameState state, Player player) {
		wolf.setIsInterested(false);
		endSession(wolf, state);
		AnimalAggroSystem.forceChainAggro(wolf, player);
	}

	private static void resolveOfferTimeout(Wolf wolf, WolfBoneTameState state, Player player) {
		wolf.setIsInterested(false);
		if (wolf.getRandom().nextFloat() < CHANCE) {
			beginFleePhase(wolf, state, player);
		} else {
			state.beginAttack(player.getUUID());
			tickAttack(wolf, state, player);
		}
	}

	private static void resolveItemSwap(Wolf wolf, WolfBoneTameState state, Player player) {
		wolf.setIsInterested(false);
		if (wolf.getRandom().nextFloat() < CHANCE) {
			beginFleePhase(wolf, state, player);
		} else {
			state.beginAttack(player.getUUID());
			tickAttack(wolf, state, player);
		}
	}

	private static void beginFleePhase(Wolf wolf, WolfBoneTameState state, Player player) {
		wolf.getNavigation().stop();
		state.beginFlee(player.getUUID());
		tickFlee(wolf, state, player);
	}

	private static void holdAtDistance(Wolf wolf, Player player, double desiredDistance, double speed) {
		double distance = wolf.distanceTo(player);
		if (Math.abs(distance - desiredDistance) <= HOLD_DISTANCE_TOLERANCE) {
			wolf.getNavigation().stop();
			return;
		}

		Vec3 away = wolf.position().subtract(player.position());
		if (away.lengthSqr() < 1.0E-4D) {
			away = new Vec3(wolf.getRandom().nextDouble() - 0.5D, 0.0D, wolf.getRandom().nextDouble() - 0.5D);
		}

		Vec3 holdPos = player.position().add(away.normalize().scale(desiredDistance));
		MobNavigationHelper.setSpeedModifier(wolf, speed);
		MobNavigationHelper.moveTo(wolf, holdPos.x, holdPos.y, holdPos.z, speed);
	}

	private static void lookAndGrowl(Wolf wolf, WolfBoneTameState state, Player player) {
		wolf.getLookControl().setLookAt(player, 30.0F, 30.0F);
		if (!state.tickGrowlCooldown()) {
			return;
		}

		Holder<SoundEvent> growl = ((WolfAccessor) wolf).wildBehavior$getSoundSet().growlSound();
		wolf.level().playSound(
			null,
			wolf.getX(),
			wolf.getY(),
			wolf.getZ(),
			growl.value(),
			SoundSource.NEUTRAL,
			1.0F,
			wolf.getVoicePitch()
		);
	}

	private static void stealHeldItemIntoMouth(Wolf wolf, Player player) {
		InteractionHand hand = findBoneOrMeatHand(wolf, player);
		if (hand == null) {
			return;
		}

		ItemStack held = player.getItemInHand(hand);
		if (held.isEmpty()) {
			return;
		}

		ItemStack stolen = held.split(1);
		if (held.isEmpty()) {
			player.setItemInHand(hand, ItemStack.EMPTY);
		}

		wolf.setItemInHand(InteractionHand.MAIN_HAND, stolen);
		wolf.setGuaranteedDrop(EquipmentSlot.MAINHAND);
	}

	public static boolean isHoldingBoneOrRawMeat(Wolf wolf, Player player) {
		return isBoneOrRawMeat(wolf, player.getMainHandItem()) || isBoneOrRawMeat(wolf, player.getOffhandItem());
	}

	private static boolean isBoneOrRawMeat(Wolf wolf, ItemStack stack) {
		if (stack.isEmpty()) {
			return false;
		}

		return stack.is(Items.BONE) || wolf.isFood(stack);
	}

	@Nullable
	private static InteractionHand findBoneOrMeatHand(Wolf wolf, Player player) {
		if (isBoneOrRawMeat(wolf, player.getMainHandItem())) {
			return InteractionHand.MAIN_HAND;
		}

		if (isBoneOrRawMeat(wolf, player.getOffhandItem())) {
			return InteractionHand.OFF_HAND;
		}

		return null;
	}

	@Nullable
	private static Player resolveAggroPlayer(Wolf wolf) {
		LivingEntity target = wolf.getTarget();
		if (!(target instanceof Player player) || !player.isAlive()) {
			return null;
		}

		if (PlayerApproachDetector.ignoresWildBehavior(player)) {
			return null;
		}

		return player;
	}

	@Nullable
	private static Player resolveSessionPlayer(Wolf wolf, WolfBoneTameState state) {
		UUID playerId = state.getPlayerId();
		if (playerId == null) {
			return null;
		}

		double searchRadius = Math.max(AnimalBehaviorConfig.normalDetectRange(), FLEE_DISTANCE + HOLD_DISTANCE + 4.0D);
		for (Player player : wolf.level().getEntitiesOfClass(Player.class, wolf.getBoundingBox().inflate(searchRadius))) {
			if (playerId.equals(player.getUUID()) && player.isAlive()) {
				return player;
			}
		}

		return null;
	}

	private static void endSession(Wolf wolf, WolfBoneTameState state) {
		wolf.setIsInterested(false);
		wolf.setSprinting(false);
		state.clear(wolf.level().getGameTime());
	}

	private static void endSessionImmediate(Wolf wolf, WolfBoneTameState state) {
		wolf.setIsInterested(false);
		wolf.setSprinting(false);
		state.clearImmediate();
	}

	private static WolfBoneTameState getState(Wolf wolf) {
		return STATES.computeIfAbsent(wolf.getUUID(), id -> {
			int stagger = Math.abs(id.hashCode()) % WolfBoneTameState.ITEM_CHECK_INTERVAL_TICKS;
			return new WolfBoneTameState(stagger);
		});
	}
}
