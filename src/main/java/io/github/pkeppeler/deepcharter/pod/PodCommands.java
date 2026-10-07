package io.github.pkeppeler.deepcharter.pod;

import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;

import io.github.pkeppeler.deepcharter.command.FeatureCommands;

public final class PodCommands {
	private PodCommands() {
	}

	public static void init() {
		FeatureCommands.register("pod", root -> root.then(Commands.literal("spawn")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.executes(PodCommands::spawn)));
	}

	private static int spawn(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		ServerLevel level = source.getLevel();
		PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
		if (pod == null) {
			source.sendFailure(Component.translatable("commands.deepcharter.pod.spawn.failed"));
			return 0;
		}
		pod.setPos(source.getPosition());
		level.addFreshEntity(pod);
		source.sendSuccess(() -> Component.translatable("commands.deepcharter.pod.spawn.success"), true);
		return 1;
	}
}
