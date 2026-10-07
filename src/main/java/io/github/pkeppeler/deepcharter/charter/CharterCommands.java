package io.github.pkeppeler.deepcharter.charter;

import java.util.Collection;
import java.util.Optional;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import io.github.pkeppeler.deepcharter.command.FeatureCommands;

/**
 * Operator commands under {@code /deepcharter charter}. They act as the player who runs them, through the same {@link Charters}
 * calls as everything else, so they are a way to test and to repair a world until the colony's contract terminal exists.
 */
public final class CharterCommands {
	private static final String NAME = "name";
	private static final String PLAYER = "player";
	private static final String AMOUNT = "amount";

	private CharterCommands() {
	}

	public static void init() {
		FeatureCommands.register("charter", root -> root
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("found").then(nameArgument().executes(CharterCommands::found)))
				.then(Commands.literal("apply").then(nameArgument().executes(CharterCommands::apply)))
				.then(Commands.literal("approve").then(playerArgument().executes(CharterCommands::approve)))
				.then(Commands.literal("deny").then(playerArgument().executes(CharterCommands::deny)))
				.then(Commands.literal("leave").executes(CharterCommands::leave))
				.then(Commands.literal("info").executes(CharterCommands::info))
				.then(Commands.literal("list").executes(CharterCommands::list))
				.then(account()));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> account() {
		return Commands.literal("account")
				.then(Commands.literal("deposit").then(nameArgument().then(amountArgument()
						.executes(context -> changeAccount(context, "deposit", Charters::deposit)))))
				.then(Commands.literal("spend").then(nameArgument().then(amountArgument()
						.executes(context -> changeAccount(context, "spend", Charters::spend)))));
	}

	private static RequiredArgumentBuilder<CommandSourceStack, String> nameArgument() {
		return Commands.argument(NAME, StringArgumentType.string());
	}

	private static RequiredArgumentBuilder<CommandSourceStack, EntitySelector> playerArgument() {
		return Commands.argument(PLAYER, EntityArgument.player());
	}

	private static RequiredArgumentBuilder<CommandSourceStack, Long> amountArgument() {
		return Commands.argument(AMOUNT, LongArgumentType.longArg(1));
	}

	private static int found(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		String name = StringArgumentType.getString(context, NAME);
		return report(context, Charters.found(server(context), player(context).getUUID(), name), "found", name);
	}

	private static int apply(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		String name = StringArgumentType.getString(context, NAME);
		Optional<Charter> charter = Charters.findByName(server(context), name);
		if (charter.isEmpty()) {
			return refuse(context, CharterRefusal.NO_SUCH_CHARTER);
		}
		return report(context, Charters.apply(server(context), player(context).getUUID(), charter.get().id()), "apply", charter.get().name());
	}

	private static int approve(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		ServerPlayer applicant = EntityArgument.getPlayer(context, PLAYER);
		return report(context, Charters.approve(server(context), player(context).getUUID(), applicant.getUUID()), "approve", applicant.getName());
	}

	private static int deny(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		ServerPlayer applicant = EntityArgument.getPlayer(context, PLAYER);
		return report(context, Charters.deny(server(context), player(context).getUUID(), applicant.getUUID()), "deny", applicant.getName());
	}

	private static int leave(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		return report(context, Charters.leave(server(context), player(context).getUUID()), "leave");
	}

	private static int info(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		Optional<Charter> charter = Charters.charterOf(server(context), player(context).getUUID());
		if (charter.isEmpty()) {
			return refuse(context, CharterRefusal.NOT_ON_A_CHARTER);
		}
		Charter found = charter.get();
		context.getSource().sendSuccess(() -> Component.translatable("deepcharter.charter.info",
				found.name(), found.roster().size(), found.applications().size(), found.account(), found.deepestPoint()), false);
		return 1;
	}

	private static int list(CommandContext<CommandSourceStack> context) {
		Collection<Charter> charters = Charters.all(server(context));
		context.getSource().sendSuccess(() -> Component.translatable("deepcharter.charter.list.header", charters.size()), false);
		for (Charter charter : charters) {
			context.getSource().sendSuccess(() -> Component.translatable(
					charter.dormant() ? "deepcharter.charter.list.dormant" : "deepcharter.charter.list.entry",
					charter.name(), charter.roster().size(), charter.account()), false);
		}
		return charters.size();
	}

	private static int changeAccount(CommandContext<CommandSourceStack> context, String verb, AccountChange change) {
		String name = StringArgumentType.getString(context, NAME);
		Optional<Charter> charter = Charters.findByName(server(context), name);
		if (charter.isEmpty()) {
			return refuse(context, CharterRefusal.NO_SUCH_CHARTER);
		}
		long amount = LongArgumentType.getLong(context, AMOUNT);
		return report(context, change.apply(server(context), charter.get().id(), amount), verb, amount, charter.get().name());
	}

	private static int report(CommandContext<CommandSourceStack> context, Optional<CharterRefusal> refusal, String verb, Object... arguments) {
		if (refusal.isPresent()) {
			return refuse(context, refusal.get());
		}
		context.getSource().sendSuccess(() -> Component.translatable("deepcharter.charter." + verb + ".success", arguments), true);
		return 1;
	}

	private static int refuse(CommandContext<CommandSourceStack> context, CharterRefusal refusal) {
		context.getSource().sendFailure(refusal.message());
		return 0;
	}

	private static MinecraftServer server(CommandContext<CommandSourceStack> context) {
		return context.getSource().getServer();
	}

	private static ServerPlayer player(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		return context.getSource().getPlayerOrException();
	}

	@FunctionalInterface
	private interface AccountChange {
		Optional<CharterRefusal> apply(MinecraftServer server, CharterId id, long amount);
	}
}
