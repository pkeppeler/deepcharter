package io.github.pkeppeler.deepcharter.test;

import com.mojang.brigadier.CommandDispatcher;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.gametest.framework.GameTestHelper;

import io.github.pkeppeler.deepcharter.command.FeatureCommands;

public class FeatureCommandsTest {
	@GameTest
	public void registrationsMergeUnderOneRoot(GameTestHelper helper) {
		CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
		dispatcher.register(FeatureCommands.build("pod", pod -> pod.then(Commands.literal("spawn").executes(c -> 1))));
		dispatcher.register(FeatureCommands.build("pod", pod -> pod.then(Commands.literal("dump").executes(c -> 1))));
		dispatcher.register(FeatureCommands.build("layer", layer -> layer.then(Commands.literal("goto").executes(c -> 1))));

		var root = dispatcher.getRoot().getChild(FeatureCommands.ROOT);
		if (root == null || root.getChildren().size() != 2) {
			throw helper.assertionException("expected one /deepcharter root with 2 features, got %s", root);
		}
		if (dispatcher.getRoot().getChildren().size() != 1) {
			throw helper.assertionException("expected /deepcharter to be the only root literal");
		}
		var pod = root.getChild("pod");
		if (pod.getChild("spawn") == null || pod.getChild("dump") == null) {
			throw helper.assertionException("both pod registrations should survive the merge");
		}
		helper.succeed();
	}
}
