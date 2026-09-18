package com.wildbehavior.ai;

public enum MobBehavior {
	HERD("Herd", "Mobs move in herds, graze on higher ground, and travel together. All herd members step back from drop-offs; leaders also redirect herd travel away from cliffs"),
	PLAYER_FEAR("PlayerFear", "Mob fears players and flees when they get too close"),
	PASSIVE("Passive", "Mob fears mobs with Aggressive or WaterAggression enabled and flees from them"),
	PASSIVE_FEAR("PassiveFear", "Passive mobs flee from this entity type (players use /wildbehavior player ...)"),
	WEAPON_SCARE("WeaponScare", "Mob flees nearby explosions, flying arrows, and gunshots within gunshot radius"),
	AGGRESSIVE("Aggressive", "Attacks nearby players when pack size meets confidence threshold"),
	WATER_AGGRESSION("WaterAggression", "Like Aggressive, but only acquires targets that are in water"),
	DEFENDER("Defender", "Attacks mobs with Aggressive or WaterAggression enabled; ignores players, passive, and neutral mobs"),
	STAY_ON_LAND("StayOnLandFix", "Herding mobs avoid water at all costs; if they end up in water they pathfind to the nearest land before resuming herd behavior");

	private final String displayName;
	private final String description;

	MobBehavior(String displayName, String description) {
		this.displayName = displayName;
		this.description = description;
	}

	public String getDisplayName() {
		return this.displayName;
	}

	public String getDescription() {
		return this.description;
	}

	public static MobBehavior fromId(String id) {
		return switch (id.toLowerCase()) {
			case "herd" -> HERD;
			case "playerfear", "player_fear" -> PLAYER_FEAR;
			case "passive" -> PASSIVE;
			case "passivefear", "passive_fear", "fear" -> PASSIVE_FEAR;
			case "weaponscare", "weapon_scare" -> WEAPON_SCARE;
			case "aggressive", "agro", "aggro" -> AGGRESSIVE;
			case "wateraggression", "water_aggression" -> WATER_AGGRESSION;
			case "defender" -> DEFENDER;
			case "stayonlandfix", "stayonland" -> STAY_ON_LAND;
			default -> null;
		};
	}
}
