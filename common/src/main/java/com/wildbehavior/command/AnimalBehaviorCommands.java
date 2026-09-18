package com.wildbehavior.command;

import com.wildbehavior.WildBehavior;
import com.wildbehavior.ai.AnimalCategories;
import com.wildbehavior.ai.EntityPlayerTrust;
import com.wildbehavior.ai.AnimalFleeSystem;
import com.wildbehavior.ai.AnimalMeleeCombat;
import com.wildbehavior.ai.MobBehavior;
import com.wildbehavior.ai.MobBehaviorConfig;
import com.wildbehavior.compat.ModCompat;
import com.wildbehavior.config.AnimalBehaviorConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.platform.Platform;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;

public final class AnimalBehaviorCommands {
	private static final int CHAT_LINE_WIDTH = 52;

	private static final DynamicCommandExceptionType NO_LIVING_TARGETS = new DynamicCommandExceptionType(
		ignored -> Component.literal("No living entities matched the selector")
	);
	private static final DynamicCommandExceptionType UNKNOWN_BEHAVIOR = new DynamicCommandExceptionType(
		id -> Component.literal("Unknown behavior: " + id + " (use herd, playerfear, passive, passivefear, weaponscare, aggressive, wateraggression, defender, or stayonland)")
	);
	private static final DynamicCommandExceptionType INVALID_MOB = new DynamicCommandExceptionType(
		id -> Component.literal("Cannot configure player entity: " + id)
	);
	private static final DynamicCommandExceptionType SCAREDY_CAT = new DynamicCommandExceptionType(
		ignored -> Component.literal("You cannot apply confidence to a scaredy cat!")
	);
	private static final DynamicCommandExceptionType INVALID_VALUE = new DynamicCommandExceptionType(
		value -> Component.literal("Invalid value: " + value + " (use true/false, or a number for confidence)")
	);
	private static final DynamicCommandExceptionType INVALID_FLEE_SPEED = new DynamicCommandExceptionType(
		value -> Component.literal("Flee speed must be greater than 0 blocks per second, not " + value)
	);
	private static final DynamicCommandExceptionType INVALID_CONFIDENCE = new DynamicCommandExceptionType(
		value -> Component.literal("Confidence must be a whole number of 1 or greater, not " + value)
	);
	private static final DynamicCommandExceptionType INVALID_DAMAGE = new DynamicCommandExceptionType(
		value -> Component.literal("Damage must be greater than 0, not " + value)
	);
	private static final DynamicCommandExceptionType INVALID_MEMORY = new DynamicCommandExceptionType(
		value -> Component.literal("Memory must be a whole number of 0 or greater seconds, not " + value)
	);
	private static final DynamicCommandExceptionType INVALID_HERD_SPEED = new DynamicCommandExceptionType(
		value -> Component.literal("Herd speed cannot be negative, not " + value)
	);
	private static final DynamicCommandExceptionType INVALID_FIGHTBACK_HELP = new DynamicCommandExceptionType(
		value -> Component.literal("FightBackHelp must be a whole number of 0 or greater, not " + value)
	);
	private static final DynamicCommandExceptionType INVALID_FLIGHT_FLEE = new DynamicCommandExceptionType(
		value -> Component.literal("FlightFlee min must be >= 1 and max must be >= min, not " + value)
	);
	private static final DynamicCommandExceptionType INVALID_AGGRESSIVE_KILL_TIMER = new DynamicCommandExceptionType(
		value -> Component.literal("AggressiveKillTimer must be a whole number of 0 or greater seconds, not " + value)
	);

	private static final SuggestionProvider<CommandSourceStack> HERD_SPEED_SUGGESTIONS = (context, builder) -> {
		builder.suggest("0");
		builder.suggest("3.0");
		builder.suggest("5.0");
		return builder.buildFuture();
	};

	private static final SuggestionProvider<CommandSourceStack> DAMAGE_SUGGESTIONS = (context, builder) -> {
		builder.suggest("2.0");
		builder.suggest("3.0");
		builder.suggest("4.0");
		return builder.buildFuture();
	};

	private static final SuggestionProvider<CommandSourceStack> MEMORY_SUGGESTIONS = (context, builder) -> {
		builder.suggest("0");
		builder.suggest("3");
		builder.suggest("6");
		builder.suggest("10");
		return builder.buildFuture();
	};

	private static final SuggestionProvider<CommandSourceStack> FIGHTBACK_HELP_SUGGESTIONS = (context, builder) -> {
		builder.suggest("0");
		builder.suggest("1");
		builder.suggest("2");
		builder.suggest("3");
		return builder.buildFuture();
	};

	private static final SuggestionProvider<CommandSourceStack> FLIGHT_FLEE_MIN_SUGGESTIONS = (context, builder) -> {
		builder.suggest("8");
		builder.suggest("12");
		builder.suggest("16");
		return builder.buildFuture();
	};

	private static final SuggestionProvider<CommandSourceStack> FLIGHT_FLEE_MAX_SUGGESTIONS = (context, builder) -> {
		builder.suggest("16");
		builder.suggest("24");
		builder.suggest("32");
		return builder.buildFuture();
	};

	private static final SuggestionProvider<CommandSourceStack> AGGRESSIVE_KILL_TIMER_SUGGESTIONS = (context, builder) -> {
		builder.suggest("0");
		builder.suggest("30");
		builder.suggest("60");
		builder.suggest("120");
		return builder.buildFuture();
	};

	private static final SuggestionProvider<CommandSourceStack> FLEE_SPEED_SUGGESTIONS = (context, builder) -> {
		builder.suggest("5.0");
		builder.suggest("5.5");
		builder.suggest("6.0");
		return builder.buildFuture();
	};

	private static final SuggestionProvider<CommandSourceStack> MOB_SUGGESTIONS = (context, builder) -> {
		for (EntityType<?> type : AnimalCategories.commandTargets()) {
			Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
			builder.suggest(id.toString());
			// Vanilla IDs may be typed without the minecraft: namespace (ResourceArgument default).
			if (Identifier.DEFAULT_NAMESPACE.equals(id.getNamespace())) {
				builder.suggest(id.getPath());
			}
		}

		return builder.buildFuture();
	};

	private static final SuggestionProvider<CommandSourceStack> BEHAVIOR_SUGGESTIONS = (context, builder) -> {
		SharedSuggestionProvider.suggest(
			Arrays.stream(MobBehavior.values()).map(behavior -> behavior.name().toLowerCase(Locale.ROOT)),
			builder
		);
		builder.suggest("confidence");
		return builder.buildFuture();
	};

	private static final SuggestionProvider<CommandSourceStack> VALUE_SUGGESTIONS = (context, builder) -> {
		String behavior = context.getArgument("behavior", String.class).toLowerCase(Locale.ROOT);
		if ("confidence".equals(behavior)) {
			for (int value = 1; value <= 10; value++) {
				builder.suggest(Integer.toString(value));
			}
		} else {
			builder.suggest("true");
			builder.suggest("false");
		}

		return builder.buildFuture();
	};

	private static final SuggestionProvider<CommandSourceStack> SPECIAL_SETTING_SUGGESTIONS = (context, builder) -> {
		builder.suggest("oneshotonFear");
		builder.suggest("playerCrawl");
		return builder.buildFuture();
	};

	private static final DynamicCommandExceptionType UNKNOWN_SPECIAL_SETTING = new DynamicCommandExceptionType(
		id -> Component.literal("Unknown special setting: " + id + " (use oneshotonFear or playerCrawl)")
	);

	private AnimalBehaviorCommands() {
	}

	public static void register() {
		CommandRegistrationEvent.EVENT.register(AnimalBehaviorCommands::registerCommands);
	}

	private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext registryAccess, Commands.CommandSelection environment) {
		dispatcher.register(
			Commands.literal("wildbehavior")
				.executes(AnimalBehaviorCommands::showHelp)
				.then(
					Commands.literal("set")
						.requires(source -> source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))
						.then(
							Commands.argument("mob", ResourceArgument.resource(registryAccess, Registries.ENTITY_TYPE))
								.suggests(MOB_SUGGESTIONS)
								.then(
									Commands.literal("speed")
										.then(
											Commands.argument("bps", DoubleArgumentType.doubleArg(0.1D, 100.0D))
												.suggests(FLEE_SPEED_SUGGESTIONS)
												.executes(AnimalBehaviorCommands::setFleeSpeed)
										)
								)
								.then(
									Commands.literal("herdspeed")
										.then(
											Commands.argument("bps", DoubleArgumentType.doubleArg(0.0D, 100.0D))
												.suggests(HERD_SPEED_SUGGESTIONS)
												.executes(AnimalBehaviorCommands::setHerdSpeed)
										)
								)
								.then(
									Commands.literal("fightback")
										.then(
											Commands.argument("enabled", BoolArgumentType.bool())
												.executes(AnimalBehaviorCommands::setFightBack)
										)
								)
								.then(
									Commands.literal("fightbackhelp")
										.then(
											Commands.argument("count", IntegerArgumentType.integer(0, 64))
												.suggests(FIGHTBACK_HELP_SUGGESTIONS)
												.executes(AnimalBehaviorCommands::setFightBackHelp)
										)
								)
								.then(
									Commands.literal("wateraggression")
										.then(
											Commands.argument("enabled", BoolArgumentType.bool())
												.executes(AnimalBehaviorCommands::setWaterAggression)
										)
								)
								.then(
									Commands.literal("damage")
										.then(
											Commands.argument("amount", DoubleArgumentType.doubleArg(0.1D, 1000.0D))
												.suggests(DAMAGE_SUGGESTIONS)
												.executes(AnimalBehaviorCommands::setMeleeDamage)
										)
								)
								.then(
									Commands.literal("memory")
										.then(
											Commands.argument("seconds", IntegerArgumentType.integer(0, 300))
												.suggests(MEMORY_SUGGESTIONS)
												.executes(AnimalBehaviorCommands::setMemory)
										)
								)
								.then(
									Commands.literal("flightflee")
										.then(
											Commands.argument("min", IntegerArgumentType.integer(1, 128))
												.suggests(FLIGHT_FLEE_MIN_SUGGESTIONS)
												.then(
													Commands.argument("max", IntegerArgumentType.integer(1, 128))
														.suggests(FLIGHT_FLEE_MAX_SUGGESTIONS)
														.executes(AnimalBehaviorCommands::setFlightFlee)
												)
										)
								)
								.then(
									Commands.literal("aggressivekilltimer")
										.then(
											Commands.argument("seconds", IntegerArgumentType.integer(0, 3600))
												.suggests(AGGRESSIVE_KILL_TIMER_SUGGESTIONS)
												.executes(AnimalBehaviorCommands::setAggressiveKillTimer)
										)
								)
								.then(
									Commands.argument("behavior", StringArgumentType.word())
										.suggests(BEHAVIOR_SUGGESTIONS)
										.then(
											Commands.argument("value", StringArgumentType.word())
												.suggests(VALUE_SUGGESTIONS)
												.executes(AnimalBehaviorCommands::setBehaviorOrValue)
										)
								)
						)
				)
				.then(
					Commands.literal("info")
						.executes(AnimalBehaviorCommands::infoAllMobs)
						.then(
							Commands.argument("mob", ResourceArgument.resource(registryAccess, Registries.ENTITY_TYPE))
								.suggests(MOB_SUGGESTIONS)
								.executes(AnimalBehaviorCommands::infoMob)
						)
				)
				.then(
					Commands.literal("toggle")
						.requires(source -> source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))
						.then(
							Commands.literal("all")
								.executes(AnimalBehaviorCommands::toggleAllMobs)
						)
						.then(
							Commands.argument("mob", ResourceArgument.resource(registryAccess, Registries.ENTITY_TYPE))
								.suggests(MOB_SUGGESTIONS)
								.executes(AnimalBehaviorCommands::toggleMob)
						)
				)
				.then(
					Commands.literal("reset")
						.requires(source -> source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))
						.then(
							Commands.argument("mob", ResourceArgument.resource(registryAccess, Registries.ENTITY_TYPE))
								.suggests(MOB_SUGGESTIONS)
								.executes(AnimalBehaviorCommands::resetMob)
						)
						.executes(AnimalBehaviorCommands::resetAll)
				)
				.then(
					Commands.literal("player")
						.requires(source -> source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))
						.then(
							Commands.argument("player", EntityArgument.player())
								.then(
									Commands.literal("passivefear")
										.then(
											Commands.argument("enabled", BoolArgumentType.bool())
												.executes(AnimalBehaviorCommands::setPlayerPassiveFear)
										)
								)
						)
				)
				.then(
					Commands.literal("entity")
						.requires(source -> source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))
						.then(
							Commands.literal("tamed")
								.then(
									Commands.argument("targets", EntityArgument.entities())
										.then(
											Commands.argument("enabled", BoolArgumentType.bool())
												.executes(AnimalBehaviorCommands::setEntityTamed)
										)
								)
						)
				)
				.then(
					Commands.literal("specialsetting")
						.requires(source -> source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))
						.executes(AnimalBehaviorCommands::listSpecialSettings)
						.then(
							Commands.argument("setting", StringArgumentType.word())
								.suggests(SPECIAL_SETTING_SUGGESTIONS)
								.executes(AnimalBehaviorCommands::toggleSpecialSetting)
								.then(
									Commands.argument("enabled", BoolArgumentType.bool())
										.executes(AnimalBehaviorCommands::setSpecialSetting)
								)
						)
				)
		);
	}

	private static int showHelp(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		String version = Platform.getOptionalMod(WildBehavior.MOD_ID)
			.or(() -> Platform.getOptionalMod(WildBehavior.NEOFORGE_MOD_ID))
			.map(mod -> mod.getVersion())
			.orElse("1.0.0");

		source.sendSuccess(() -> centeredTitle("Wild Behavior " + version), false);
		source.sendSuccess(() -> Component.empty(), false);
		source.sendSuccess(() -> Component.literal("commands:"), false);
		source.sendSuccess(() -> commandLine("/wildbehavior toggle all", "Enable all mobs with Aggressive only (except Warden, Ender Dragon, Enderman)"), false);
		source.sendSuccess(() -> commandLine("/wildbehavior toggle <mob>", "Enable or disable Wild Behavior for any mob type"), false);
		source.sendSuccess(() -> commandLine("/wildbehavior info [mob]", "Lists current behaviors attributed to mob(s)"), false);
		source.sendSuccess(() -> commandLine("/wildbehavior set <mob> speed <bps>", "Sets flee speed in blocks per second"), false);
		source.sendSuccess(() -> commandLine("/wildbehavior set <mob> herdspeed <bps>", "Sets herd walk speed in blocks per second"), false);
		source.sendSuccess(() -> commandLine("/wildbehavior set <mob> fightback <true|false>", "Passive or Aggressive mob retaliates only when damaged"), false);
		source.sendSuccess(() -> commandLine("/wildbehavior set <mob> fightbackhelp <count>", "Nearest same-species allies within 20 blocks that join a FightBack (0 = alone)"), false);
		source.sendSuccess(() -> commandLine("/wildbehavior set <mob> wateraggression <true|false>", "Like Aggressive, but only acquires targets in water (standalone behavior)"), false);
		source.sendSuccess(() -> commandLine("/wildbehavior set <mob> damage <amount>", "Sets melee damage for aggressive/defender mobs"), false);
		source.sendSuccess(() -> commandLine("/wildbehavior set <mob> memory <seconds>", "Seconds to keep chasing/fleeing after losing line of sight"), false);
		source.sendSuccess(() -> commandLine("/wildbehavior set <mob> flightflee <min> <max>", "Bird/bat/bee flee distance in blocks when startled (default 12–24)"), false);
		source.sendSuccess(() -> commandLine("/wildbehavior set <mob> aggressivekilltimer <seconds>", "Seconds after a kill before Aggressive can attack again (default 60; 0 = none)"), false);
		source.sendSuccess(() -> commandLine("/wildbehavior set <mob> [behavior|confidence] [true/false|number]", "Turns on or off behavior, or sets pack confidence for aggressive mobs"), false);
		source.sendSuccess(() -> commandLine("/wildbehavior reset [mob]", "Resets one or more mobs to default behaviors"), false);
		source.sendSuccess(() -> commandLine("/wildbehavior player [playername] [behavior] [true/false]", "Changes player relation to mob behavior"), false);
		source.sendSuccess(() -> commandLine("/wildbehavior entity tamed <selector> <true|false>", "Mark individual mobs as tamed (skip all Wild Behavior; vanilla AI only)"), false);
		source.sendSuccess(() -> commandLine("/wildbehavior specialsetting [name] [true|false]", "Toggle or set a special convenience setting"), false);
		source.sendSuccess(() -> Component.empty(), false);
		source.sendSuccess(() -> Component.literal("Special settings:"), false);
		source.sendSuccess(() -> Component.literal("\toneshotonFear - Arrows instantly kill mobs with Passive or PlayerFear"), false);
		source.sendSuccess(() -> Component.literal("\tplayerCrawl - Hold sneak + sprint to crawl on the floor"), false);
		source.sendSuccess(() -> Component.empty(), false);
		source.sendSuccess(() -> Component.literal("Behaviors:"), false);
		source.sendSuccess(() -> behaviorLine(MobBehavior.HERD), false);
		source.sendSuccess(() -> behaviorLine(MobBehavior.PLAYER_FEAR), false);
		source.sendSuccess(() -> behaviorLine(MobBehavior.PASSIVE), false);
		source.sendSuccess(() -> behaviorLine(MobBehavior.AGGRESSIVE), false);
		source.sendSuccess(() -> behaviorLine(MobBehavior.WATER_AGGRESSION), false);
		source.sendSuccess(() -> behaviorLine(MobBehavior.DEFENDER), false);
		source.sendSuccess(() -> behaviorLine(MobBehavior.WEAPON_SCARE), false);
		source.sendSuccess(() -> behaviorLine(MobBehavior.PASSIVE_FEAR), false);
		source.sendSuccess(() -> behaviorLine(MobBehavior.STAY_ON_LAND), false);
		source.sendSuccess(() -> Component.literal("\tConfidence - Pack size needed before aggressive mobs attack instead of fleeing"), false);
		source.sendSuccess(() -> Component.literal("\tMemory - Seconds a mob keeps chasing or fleeing after losing line of sight (aggressive default 6)"), false);
		source.sendSuccess(() -> Component.literal("\tFlightFlee - How far birds, bats, and bees fly when startled (default 12–24 blocks)"), false);
		source.sendSuccess(() -> Component.literal("\tAggressiveKillTimer - Seconds after a kill before Aggressive may attack again (default 60)"), false);
		source.sendSuccess(() -> Component.literal("\tFightBackHelp - Nearest same-species allies within 20 blocks that join FightBack (default 2 when FightBack is on)"), false);
		source.sendSuccess(() -> Component.literal("\tSetting aggressive or wateraggression disables passive flee-from-aggressive behavior."), false);

		return 1;
	}

	private static Component centeredTitle(String text) {
		int padding = Math.max(0, (CHAT_LINE_WIDTH - text.length()) / 2);
		return Component.literal(" ".repeat(padding) + text)
			.withStyle(style -> style.withBold(true).withColor(ChatFormatting.AQUA));
	}

	private static Component commandLine(String command, String description) {
		return Component.literal(command + " - " + description);
	}

	private static Component behaviorLine(MobBehavior behavior) {
		return Component.literal("\t" + behavior.getDisplayName() + " - " + behavior.getDescription());
	}

	private static int setBehaviorOrValue(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		EntityType<?> type = getMobType(context, "mob");
		String behaviorId = StringArgumentType.getString(context, "behavior");
		String value = StringArgumentType.getString(context, "value");

		if ("confidence".equalsIgnoreCase(behaviorId)) {
			return setConfidence(context, type, value);
		}

		MobBehavior behavior = parseBehavior(behaviorId);
		boolean enabled = parseBool(value);
		CommandSourceStack source = context.getSource();

		Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
		if (behavior == MobBehavior.AGGRESSIVE && enabled && MobBehaviorConfig.isEnabled(type, MobBehavior.PASSIVE)) {
			source.sendSuccess(() -> Component.literal(id + " was passive, and is now aggressive"), true);
		} else if (behavior == MobBehavior.WATER_AGGRESSION && enabled && MobBehaviorConfig.isEnabled(type, MobBehavior.PASSIVE)) {
			source.sendSuccess(() -> Component.literal(id + " was passive, and is now water aggressive"), true);
		} else if (behavior == MobBehavior.PASSIVE && enabled && MobBehaviorConfig.isAggroBehavior(type)) {
			source.sendSuccess(() -> Component.literal(id + " was aggressive, and is now passive"), true);
		}

		MobBehaviorConfig.setEnabled(type, behavior, enabled);

		source.sendSuccess(
			() -> Component.literal("Set " + behavior.getDisplayName() + " to " + enabled + " for " + id + " (saved to config)"),
			true
		);

		return 1;
	}

	private static int setConfidence(CommandContext<CommandSourceStack> context, EntityType<?> type, String value) throws CommandSyntaxException {
		int confidence = parseConfidence(value);
		CommandSourceStack source = context.getSource();

		if (!MobBehaviorConfig.isAggroBehavior(type)) {
			throw SCAREDY_CAT.create("");
		}

		MobBehaviorConfig.setConfidence(type, confidence);

		Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
		source.sendSuccess(
			() -> Component.literal("Set confidence to " + confidence + " for " + id + " (saved to config)"),
			true
		);
		return 1;
	}

	private static int setFleeSpeed(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		EntityType<?> type = getMobType(context, "mob");
		double speedBps = DoubleArgumentType.getDouble(context, "bps");
		CommandSourceStack source = context.getSource();
		Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);

		if (speedBps <= 0.0D) {
			throw INVALID_FLEE_SPEED.create(Double.toString(speedBps));
		}

		try {
			MobBehaviorConfig.setFleeSpeedBps(type, speedBps);
		} catch (IllegalArgumentException exception) {
			throw INVALID_FLEE_SPEED.create(Double.toString(speedBps));
		}

		source.sendSuccess(
			() -> Component.literal("Set flee speed to " + speedBps + " bps for " + id + " (saved to config)"),
			true
		);
		return 1;
	}

	private static int setMeleeDamage(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		EntityType<?> type = getMobType(context, "mob");
		double damageAmount = DoubleArgumentType.getDouble(context, "amount");
		CommandSourceStack source = context.getSource();
		Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);

		if (damageAmount <= 0.0D) {
			throw INVALID_DAMAGE.create(Double.toString(damageAmount));
		}

		try {
			MobBehaviorConfig.setMeleeDamage(type, (float) damageAmount);
		} catch (IllegalArgumentException exception) {
			throw INVALID_DAMAGE.create(Double.toString(damageAmount));
		}

		source.sendSuccess(
			() -> Component.literal("Set damage to " + damageAmount + " for " + id + " (saved to config)"),
			true
		);
		return 1;
	}

	private static int setMemory(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		EntityType<?> type = getMobType(context, "mob");
		int seconds = IntegerArgumentType.getInteger(context, "seconds");
		CommandSourceStack source = context.getSource();
		Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);

		try {
			MobBehaviorConfig.setMemorySeconds(type, seconds);
		} catch (IllegalArgumentException exception) {
			throw INVALID_MEMORY.create(Integer.toString(seconds));
		}

		source.sendSuccess(
			() -> Component.literal("Set Memory to " + seconds + "s for " + id + " (saved to config)"),
			true
		);
		return 1;
	}

	private static int setFlightFlee(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		EntityType<?> type = getMobType(context, "mob");
		int min = IntegerArgumentType.getInteger(context, "min");
		int max = IntegerArgumentType.getInteger(context, "max");
		CommandSourceStack source = context.getSource();
		Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);

		try {
			MobBehaviorConfig.setFlightFlee(type, min, max);
		} catch (IllegalArgumentException exception) {
			throw INVALID_FLIGHT_FLEE.create(min + " " + max);
		}

		source.sendSuccess(
			() -> Component.literal("Set FlightFlee to " + min + "-" + max + " for " + id + " (saved to config)"),
			true
		);
		return 1;
	}

	private static int setAggressiveKillTimer(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		EntityType<?> type = getMobType(context, "mob");
		int seconds = IntegerArgumentType.getInteger(context, "seconds");
		CommandSourceStack source = context.getSource();
		Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);

		try {
			MobBehaviorConfig.setAggressiveKillTimerSeconds(type, seconds);
		} catch (IllegalArgumentException exception) {
			throw INVALID_AGGRESSIVE_KILL_TIMER.create(Integer.toString(seconds));
		}

		source.sendSuccess(
			() -> Component.literal("Set AggressiveKillTimer to " + seconds + "s for " + id + " (saved to config)"),
			true
		);
		return 1;
	}

	private static int setFightBack(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		EntityType<?> type = getMobType(context, "mob");
		boolean enabled = BoolArgumentType.getBool(context, "enabled");
		CommandSourceStack source = context.getSource();
		Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);

		MobBehaviorConfig.setFightBackEnabled(type, enabled);

		source.sendSuccess(
			() -> Component.literal("Set FightBack to " + enabled + " for " + id + " (saved to config)"),
			true
		);
		return 1;
	}

	private static int setFightBackHelp(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		EntityType<?> type = getMobType(context, "mob");
		int count = IntegerArgumentType.getInteger(context, "count");
		CommandSourceStack source = context.getSource();
		Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);

		try {
			MobBehaviorConfig.setFightBackHelp(type, count);
		} catch (IllegalArgumentException exception) {
			throw INVALID_FIGHTBACK_HELP.create(Integer.toString(count));
		}

		source.sendSuccess(
			() -> Component.literal("Set FightBackHelp to " + count + " for " + id + " (saved to config)"),
			true
		);
		return 1;
	}

	private static int setWaterAggression(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		EntityType<?> type = getMobType(context, "mob");
		boolean enabled = BoolArgumentType.getBool(context, "enabled");
		CommandSourceStack source = context.getSource();
		Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);

		MobBehaviorConfig.setWaterAggression(type, enabled);
		source.sendSuccess(
			() -> Component.literal("Set WaterAggression to " + enabled + " for " + id + " (saved to config)"),
			true
		);
		return 1;
	}

	private static int setHerdSpeed(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		EntityType<?> type = getMobType(context, "mob");
		double speedBps = DoubleArgumentType.getDouble(context, "bps");
		CommandSourceStack source = context.getSource();
		Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);

		if (speedBps < 0.0D) {
			throw INVALID_HERD_SPEED.create(Double.toString(speedBps));
		}

		try {
			MobBehaviorConfig.setHerdSpeedBps(type, speedBps);
		} catch (IllegalArgumentException exception) {
			throw INVALID_HERD_SPEED.create(Double.toString(speedBps));
		}

		source.sendSuccess(
			() -> Component.literal(
				speedBps <= 0.0D
					? "Set herd speed to vanilla walk speed for " + id + " (saved to config)"
					: "Set herd speed to " + speedBps + " bps for " + id + " (saved to config)"
			),
			true
		);
		return 1;
	}

	private static int setPlayerPassiveFear(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		ServerPlayer player = EntityArgument.getPlayer(context, "player");
		boolean enabled = BoolArgumentType.getBool(context, "enabled");
		MobBehaviorConfig.setPlayerPassiveFear(player.getUUID(), player.getGameProfile().name(), enabled);
		if (!enabled) {
			AnimalFleeSystem.clearFleeFromPlayer(context.getSource().getServer(), player.getUUID());
		}
		context.getSource().sendSuccess(
			() -> Component.literal(
				enabled
					? "Mobs with PlayerFear will flee from " + player.getGameProfile().name()
					: "Mobs with PlayerFear will ignore " + player.getGameProfile().name()
			),
			true
		);
		return 1;
	}

	private static int setEntityTamed(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		boolean enabled = BoolArgumentType.getBool(context, "enabled");
		int count = 0;
		for (Entity entity : EntityArgument.getEntities(context, "targets")) {
			if (entity instanceof LivingEntity living) {
				EntityPlayerTrust.setTamed(living, enabled);
				count++;
			}
		}

		if (count == 0) {
			throw NO_LIVING_TARGETS.create(true);
		}

		final int applied = count;
		context.getSource().sendSuccess(
			() -> Component.literal("Set tamed=" + enabled + " on " + applied + " mob(s)"),
			true
		);
		return applied;
	}

	private static int listSpecialSettings(CommandContext<CommandSourceStack> context) {
		context.getSource().sendSuccess(
			() -> Component.literal("oneshotonFear: " + AnimalBehaviorConfig.isOneShotOnFearEnabled()),
			false
		);
		context.getSource().sendSuccess(
			() -> Component.literal("playerCrawl: " + AnimalBehaviorConfig.isPlayerCrawlEnabled()),
			false
		);
		return 1;
	}

	private static int toggleSpecialSetting(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		String setting = StringArgumentType.getString(context, "setting");
		requireKnownSpecialSetting(setting);
		boolean enabled = switch (normalizeSpecialSetting(setting)) {
			case "oneshotonfear" -> AnimalBehaviorConfig.toggleOneShotOnFear();
			case "playercrawl" -> AnimalBehaviorConfig.togglePlayerCrawl();
			default -> throw UNKNOWN_SPECIAL_SETTING.create(setting);
		};
		context.getSource().sendSuccess(
			() -> Component.literal("Set " + setting + " to " + enabled + " (saved to config)"),
			true
		);
		return 1;
	}

	private static int setSpecialSetting(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		String setting = StringArgumentType.getString(context, "setting");
		requireKnownSpecialSetting(setting);
		boolean enabled = BoolArgumentType.getBool(context, "enabled");
		switch (normalizeSpecialSetting(setting)) {
			case "oneshotonfear" -> AnimalBehaviorConfig.setOneShotOnFearEnabled(enabled);
			case "playercrawl" -> AnimalBehaviorConfig.setPlayerCrawlEnabled(enabled);
			default -> throw UNKNOWN_SPECIAL_SETTING.create(setting);
		}
		context.getSource().sendSuccess(
			() -> Component.literal("Set " + setting + " to " + enabled + " (saved to config)"),
			true
		);
		return 1;
	}

	private static void requireKnownSpecialSetting(String setting) throws CommandSyntaxException {
		if (!isKnownSpecialSetting(setting)) {
			throw UNKNOWN_SPECIAL_SETTING.create(setting);
		}
	}

	private static boolean isKnownSpecialSetting(String setting) {
		return switch (normalizeSpecialSetting(setting)) {
			case "oneshotonfear", "playercrawl" -> true;
			default -> false;
		};
	}

	private static String normalizeSpecialSetting(String setting) {
		return setting.toLowerCase();
	}

	private static int toggleMob(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		EntityType<?> type = getMobType(context, "mob");
		boolean enabled = MobBehaviorConfig.toggleMod(type);
		Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);

		context.getSource().sendSuccess(
			() -> Component.literal(
				enabled
					? enableMessage(type, id)
					: "Disabled Wild Behavior for " + id + " (saved to config)"
			),
			true
		);
		return 1;
	}

	private static String enableMessage(EntityType<?> type, Identifier id) {
		if (ModCompat.isAlexsMob(type) && !ModCompat.isAlexsMobDefaultDisabled(type)) {
			return "Enabled Wild Behavior for " + id + " (saved Alex's Mobs presets; saved to config)";
		}
		if (MobBehaviorConfig.isOptionalMob(type)) {
			return "Enabled Wild Behavior for " + id + " (Aggressive only; saved to config)";
		}
		return "Enabled Wild Behavior for " + id + " (saved to config)";
	}

	private static int toggleAllMobs(CommandContext<CommandSourceStack> context) {
		int count = MobBehaviorConfig.applyAggressiveOnlyToAllMobs();
		context.getSource().sendSuccess(
			() -> Component.literal(
				"Enabled Wild Behavior for " + count + " mob types with Aggressive only "
					+ "(Warden, Ender Dragon, and Enderman left disabled; saved to config)"
			),
			true
		);
		return count;
	}

	private static int infoAllMobs(CommandContext<CommandSourceStack> context) {
		List<EntityType<?>> targets = AnimalCategories.commandTargets();
		for (EntityType<?> type : targets) {
			sendMobInfo(context.getSource(), type);
		}

		context.getSource().sendSuccess(
			() -> Component.literal("Edit defaults and tuning values in " + AnimalBehaviorConfig.getConfigPath()),
			false
		);
		return targets.size();
	}

	private static int infoMob(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		EntityType<?> type = getMobType(context, "mob");
		sendMobInfo(context.getSource(), type);
		return 1;
	}

	private static void sendMobInfo(CommandSourceStack source, EntityType<?> type) {
		Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
		boolean modEnabled = MobBehaviorConfig.isModEnabled(type);
		boolean hasBehavior = false;

		if (MobBehaviorConfig.isOptionalMob(type) || AnimalBehaviorConfig.get().mobBehaviors.containsKey(id.toString())) {
			hasBehavior = true;
			source.sendSuccess(
				() -> Component.literal(id + " — Mod: " + (modEnabled ? "enabled" : "disabled")),
				false
			);
		}

		if (!modEnabled) {
			if (!hasBehavior) {
				source.sendSuccess(
					() -> Component.literal(id + " — Wild Behavior disabled (use /wildbehavior toggle " + id + ")"),
					false
				);
			}

			return;
		}

		for (MobBehavior behavior : MobBehavior.values()) {
			hasBehavior = true;
			boolean enabled = MobBehaviorConfig.isEnabled(type, behavior);
			source.sendSuccess(
				() -> Component.literal(id + " — " + behavior.getDisplayName() + ": " + enabled),
				false
			);
		}

		if (MobBehaviorConfig.isAggroBehavior(type)) {
			hasBehavior = true;
			int confidence = MobBehaviorConfig.getConfidence(type);
			source.sendSuccess(
				() -> Component.literal(id + " — Confidence: " + confidence),
				false
			);
			int killTimer = MobBehaviorConfig.getAggressiveKillTimerSeconds(type);
			source.sendSuccess(
				() -> Component.literal(id + " — AggressiveKillTimer: " + killTimer + "s"),
				false
			);
		}

		hasBehavior = true;
		int memory = MobBehaviorConfig.getMemorySeconds(type);
		source.sendSuccess(
			() -> Component.literal(id + " — Memory: " + memory + "s"),
			false
		);

		hasBehavior = true;
		int flightFleeMin = MobBehaviorConfig.getFlightFleeMin(type);
		int flightFleeMax = MobBehaviorConfig.getFlightFleeMax(type);
		source.sendSuccess(
			() -> Component.literal(id + " — FlightFlee: " + flightFleeMin + "-" + flightFleeMax),
			false
		);

		hasBehavior = true;
		double speedBps = MobBehaviorConfig.getFleeSpeedBps(type);
		source.sendSuccess(
			() -> Component.literal(id + " — Flee Speed: " + speedBps + " bps"),
			false
		);

		hasBehavior = true;
		if (MobBehaviorConfig.usesVanillaHerdSpeed(type)) {
			source.sendSuccess(
				() -> Component.literal(id + " — Herd Speed: vanilla"),
				false
			);
		} else {
			double herdSpeedBps = MobBehaviorConfig.getHerdSpeedBps(type);
			source.sendSuccess(
				() -> Component.literal(id + " — Herd Speed: " + herdSpeedBps + " bps"),
				false
			);
		}

		hasBehavior = true;
		boolean fightBack = MobBehaviorConfig.isFightBackEnabled(type);
		int fightBackHelp = MobBehaviorConfig.getFightBackHelp(type);
		source.sendSuccess(
			() -> Component.literal(id + " — FightBack: " + fightBack),
			false
		);
		source.sendSuccess(
			() -> Component.literal(id + " — FightBackHelp: " + fightBackHelp),
			false
		);

		hasBehavior = true;
		float configuredDamage = MobBehaviorConfig.getMeleeDamage(type);
		if (configuredDamage > 0.0F) {
			source.sendSuccess(
				() -> Component.literal(id + " — Damage: " + configuredDamage),
				false
			);
		} else if (MobBehaviorConfig.isAggroBehavior(type)) {
			float effectiveDamage = MobBehaviorConfig.getEffectiveMeleeDamage(type, AnimalMeleeCombat.DEFAULT_AGGRESSIVE_DAMAGE);
			source.sendSuccess(
				() -> Component.literal(id + " — Damage: " + effectiveDamage + " (default)"),
				false
			);
		} else if (MobBehaviorConfig.isEnabled(type, MobBehavior.DEFENDER)) {
			float effectiveDamage = MobBehaviorConfig.getEffectiveMeleeDamage(type, AnimalMeleeCombat.DEFAULT_DEFENDER_DAMAGE);
			source.sendSuccess(
				() -> Component.literal(id + " — Damage: " + effectiveDamage + " (default)"),
				false
			);
		}

		if (!hasBehavior) {
			source.sendSuccess(
				() -> Component.literal(id + " has no Wild Behavior settings"),
				false
			);
		}
	}

	private static int resetMob(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		EntityType<?> type = getMobType(context, "mob");
		MobBehaviorConfig.reset(type);
		Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
		context.getSource().sendSuccess(() -> Component.literal("Reset " + id + " to default behaviors"), true);
		return 1;
	}

	private static int resetAll(CommandContext<CommandSourceStack> context) {
		MobBehaviorConfig.resetAll();
		context.getSource().sendSuccess(() -> Component.literal("Reset all mob behaviors to defaults"), true);
		return 1;
	}

	private static MobBehavior parseBehavior(String id) throws CommandSyntaxException {
		if ("confidence".equalsIgnoreCase(id)) {
			throw UNKNOWN_BEHAVIOR.create(id);
		}

		MobBehavior behavior = MobBehavior.fromId(id);
		if (behavior == null) {
			throw UNKNOWN_BEHAVIOR.create(id);
		}

		return behavior;
	}

	private static boolean parseBool(String value) throws CommandSyntaxException {
		return switch (value.toLowerCase(Locale.ROOT)) {
			case "true", "yes", "on" -> true;
			case "false", "no", "off" -> false;
			default -> throw INVALID_VALUE.create(value);
		};
	}

	private static int parseConfidence(String value) throws CommandSyntaxException {
		try {
			int confidence = Integer.parseInt(value);
			if (confidence < 1) {
				throw INVALID_CONFIDENCE.create(value);
			}

			return confidence;
		} catch (NumberFormatException exception) {
			throw INVALID_CONFIDENCE.create(value);
		}
	}

	private static EntityType<?> getMobType(CommandContext<CommandSourceStack> context, String name) throws CommandSyntaxException {
		Holder.Reference<EntityType<?>> holder = ResourceArgument.getResource(context, name, Registries.ENTITY_TYPE);
		EntityType<?> type = holder.value();
		if (!AnimalCategories.isCommandTarget(type)) {
			throw INVALID_MOB.create(BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());
		}

		return type;
	}
}
