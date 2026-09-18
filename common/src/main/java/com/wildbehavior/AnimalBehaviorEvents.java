package com.wildbehavior;

import com.wildbehavior.ai.AnimalFoodLureSystem;
import com.wildbehavior.ai.AnimalAggroSystem;
import com.wildbehavior.ai.AnimalCategories;
import com.wildbehavior.ai.AnimalDefenderSystem;
import com.wildbehavior.ai.AnimalFightBackSystem;
import com.wildbehavior.ai.AnimalFleeSystem;
import com.wildbehavior.ai.AnimalHerdSystem;
import com.wildbehavior.ai.HerdFenceProximity;
import com.wildbehavior.ai.VanillaAiFallback;
import com.wildbehavior.ai.AnimalStayOnLandSystem;
import com.wildbehavior.ai.AnimalStuckRecoverySystem;
import com.wildbehavior.ai.AnimalWaterFleeEscapeSystem;
import com.wildbehavior.ai.MobBehavior;
import com.wildbehavior.ai.MobBehaviorConfig;
import com.wildbehavior.ai.NearbyPlayerAiGate;
import com.wildbehavior.ai.VillageAnimalCalm;
import com.wildbehavior.ai.ThreatScanCache;
import com.wildbehavior.ai.WeaponScareDetector;
import com.wildbehavior.ai.WolfBoneTameSystem;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.EntityEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;

public final class AnimalBehaviorEvents {
	private AnimalBehaviorEvents() {
	}

	public static void register() {
		LifecycleEvent.SERVER_STOPPED.register(server -> NearbyPlayerAiGate.clear());

		EntityEvent.ADD.register((entity, world) -> {
			onEntityAdd(entity, world);
			return EventResult.pass();
		});

		WildBehavior.LOGGER.info("Wild Behavior Architectury events registered.");
	}

	private static void onEntityAdd(Entity entity, Level world) {
		if (world.isClientSide()) {
			return;
		}

		if (entity instanceof Projectile projectile) {
			WeaponScareDetector.onProjectileAdded(projectile);
		}

		if (!(entity instanceof LivingEntity livingEntity) || !MobBehaviorConfig.isModEnabled(livingEntity.getType())) {
			return;
		}

		AnimalStuckRecoverySystem.onEntityLoad(livingEntity);

		EntityType<?> type = livingEntity.getType();
		if (AnimalCategories.isGolem(type)) {
			if (MobBehaviorConfig.isEnabled(type, MobBehavior.DEFENDER)) {
				AnimalDefenderSystem.onEntityLoad(livingEntity);
			}
			return;
		}

		if (AnimalCategories.receivesFleeBehavior(livingEntity)) {
			AnimalFleeSystem.onEntityLoad(livingEntity);
			AnimalWaterFleeEscapeSystem.onEntityLoad(livingEntity);
		}

		if (livingEntity instanceof Animal animal) {
			AnimalFoodLureSystem.onEntityLoad(animal);
		}

		if (type == EntityTypes.WOLF) {
			WolfBoneTameSystem.onEntityLoad(livingEntity);
		}

		if (MobBehaviorConfig.isEnabled(type, MobBehavior.HERD)) {
			AnimalHerdSystem.onEntityLoad(livingEntity);
		}

		if (MobBehaviorConfig.isEnabled(type, MobBehavior.STAY_ON_LAND)) {
			AnimalStayOnLandSystem.onEntityLoad(livingEntity);
		}

		if (MobBehaviorConfig.canFightBack(type)) {
			AnimalFightBackSystem.onEntityLoad(livingEntity);
		}

		if (type != EntityTypes.VILLAGER
			&& MobBehaviorConfig.isAggroBehavior(type)) {
			AnimalAggroSystem.onEntityLoad(livingEntity);
		}
	}

	public static void onEntityUnload(LivingEntity livingEntity) {
		if (livingEntity.level().isClientSide()) {
			return;
		}
		NearbyPlayerAiGate.onEntityUnload(livingEntity);
		ThreatScanCache.invalidate(livingEntity.getUUID());
		AnimalStuckRecoverySystem.onEntityUnload(livingEntity);
		AnimalFleeSystem.onEntityUnload(livingEntity);
		AnimalWaterFleeEscapeSystem.onEntityUnload(livingEntity);
		AnimalFoodLureSystem.onEntityUnload(livingEntity);
		WolfBoneTameSystem.onEntityUnload(livingEntity);
		AnimalAggroSystem.onEntityUnload(livingEntity);
		AnimalHerdSystem.onEntityUnload(livingEntity);
		AnimalStayOnLandSystem.onEntityUnload(livingEntity);
		AnimalDefenderSystem.onEntityUnload(livingEntity);
		AnimalFightBackSystem.onEntityUnload(livingEntity);
		VillageAnimalCalm.clearCache(livingEntity);
		HerdFenceProximity.clearCache(livingEntity);
		VanillaAiFallback.clear(livingEntity);
	}
}
