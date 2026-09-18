package com.wildbehavior.ai;

import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.schedule.Activity;

public final class VillagerFleeSupport {
	private VillagerFleeSupport() {
	}

	public static boolean usesModPassiveFlee(Villager villager) {
		return MobBehaviorConfig.isModEnabled(villager.getType())
			&& MobBehaviorConfig.isEnabled(villager.getType(), MobBehavior.PASSIVE);
	}

	public static boolean hasModFleeThreat(Villager villager) {
		if (AnimalFleeSystem.isFleeing(villager)) {
			return true;
		}

		return ThreatScanCache.get(villager, villager.level().getGameTime()).fleeThreat() != null;
	}

	public static void suppressVanillaPanic(Villager villager) {
		if (villager.getBrain().isActive(Activity.PANIC)) {
			villager.getBrain().setActiveActivityIfPossible(Activity.IDLE);
		}

		villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
		villager.getBrain().eraseMemory(MemoryModuleType.PATH);
		villager.setSprinting(false);
	}
}
