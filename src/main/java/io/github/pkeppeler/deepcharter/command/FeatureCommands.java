package io.github.pkeppeler.deepcharter.command;

import java.util.function.Consumer;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * The one place a feature adds commands, always under {@code /deepcharter <feature>}.
 * Brigadier merges every registration of the shared literals, so features, and parts of one
 * feature, register independently:
 *
 * <pre>{@code
 * FeatureCommands.register("pod", pod -> pod.then(Commands.literal("spawn").executes(...)));
 * }</pre>
 */
public final class FeatureCommands {
	public static final String ROOT = "deepcharter";

	private FeatureCommands() {
	}

	/** Add commands under {@code /deepcharter <feature>}. Call from a part's {@code init()}. */
	public static void register(String feature, Consumer<LiteralArgumentBuilder<CommandSourceStack>> body) {
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, environment) ->
				dispatcher.register(build(feature, body)));
	}

	/** The {@code /deepcharter <feature> ...} tree for one registration. */
	public static LiteralArgumentBuilder<CommandSourceStack> build(String feature, Consumer<LiteralArgumentBuilder<CommandSourceStack>> body) {
		LiteralArgumentBuilder<CommandSourceStack> featureNode = Commands.literal(feature);
		body.accept(featureNode);
		return Commands.literal(ROOT).then(featureNode);
	}
}
