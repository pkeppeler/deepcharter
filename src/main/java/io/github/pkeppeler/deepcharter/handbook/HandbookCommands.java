package io.github.pkeppeler.deepcharter.handbook;

import java.util.Set;

import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import io.github.pkeppeler.deepcharter.command.FeatureCommands;

/**
 * Operator commands under {@code /deepcharter handbook}, acting as the player who runs them: a way to test and to repair a world.
 * {@code complete} goes through {@link Directives#fire}, so it exercises the same path as game code.
 */
public final class HandbookCommands {
	private static final String ID = "id";

	private HandbookCommands() {
	}

	public static void init() {
		FeatureCommands.register("handbook", root -> root
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("give").executes(HandbookCommands::give))
				.then(Commands.literal("complete").then(idArgument().executes(HandbookCommands::complete)))
				.then(Commands.literal("read").then(idArgument().executes(HandbookCommands::read)))
				.then(Commands.literal("unread").then(idArgument().executes(HandbookCommands::unread)))
				.then(Commands.literal("progress").executes(HandbookCommands::progress)));
	}

	private static RequiredArgumentBuilder<CommandSourceStack, Identifier> idArgument() {
		return Commands.argument(ID, IdentifierArgument.id());
	}

	/** Gives the handbook even to a player who holds one, to test the binding; the next sweep removes the extra. */
	private static int give(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		if (!player.getInventory().add(new ItemStack(HandbookRegistry.HANDBOOK))) {
			context.getSource().sendFailure(Component.translatable("deepcharter.handbook.give.full"));
			return 0;
		}
		context.getSource().sendSuccess(() -> Component.translatable("deepcharter.handbook.give.success"), false);
		return 1;
	}

	private static int complete(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		Identifier directive = IdentifierArgument.getId(context, ID);
		if (!HandbookChapters.directives(context.getSource().getServer()).contains(directive)) {
			context.getSource().sendFailure(Component.translatable("deepcharter.handbook.complete.unknown", directive.toString()));
			return 0;
		}
		Directives.fire(player, directive);
		if (!HandbookProgress.completedFor(context.getSource().getServer(), player.getUUID()).contains(directive)) {
			context.getSource().sendFailure(Component.translatable("deepcharter.handbook.complete.no_charter", directive.toString()));
			return 0;
		}
		context.getSource().sendSuccess(() -> Component.translatable("deepcharter.handbook.complete.success", directive.toString()), true);
		return 1;
	}

	private static int read(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		Identifier entry = IdentifierArgument.getId(context, ID);
		ReadMarks.mark(context.getSource().getPlayerOrException(), entry);
		context.getSource().sendSuccess(() -> Component.translatable("deepcharter.handbook.read.success", entry.toString()), false);
		return 1;
	}

	private static int unread(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		Identifier entry = IdentifierArgument.getId(context, ID);
		ReadMarks.unmark(context.getSource().getPlayerOrException(), entry);
		context.getSource().sendSuccess(() -> Component.translatable("deepcharter.handbook.unread.success", entry.toString()), false);
		return 1;
	}

	private static int progress(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		Set<Identifier> done = HandbookProgress.completedFor(context.getSource().getServer(), player.getUUID());
		Set<Identifier> all = HandbookChapters.directives(context.getSource().getServer());
		context.getSource().sendSuccess(() -> Component.translatable("deepcharter.handbook.progress.header", done.size(), all.size()), false);
		for (Identifier directive : all) {
			context.getSource().sendSuccess(() -> Component.translatable(
					done.contains(directive) ? "deepcharter.handbook.progress.done" : "deepcharter.handbook.progress.open", directive.toString()), false);
		}
		return done.size();
	}
}
