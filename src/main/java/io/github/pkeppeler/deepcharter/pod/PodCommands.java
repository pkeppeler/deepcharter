package io.github.pkeppeler.deepcharter.pod;

import java.util.Optional;

import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;

import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.command.FeatureCommands;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;

public final class PodCommands {
	private static final int DEV_SCANNER_TIER = 1;

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
		boolean fitted = fitScanner(source, pod);
		source.sendSuccess(() -> Component.translatable(fitted ? "commands.deepcharter.pod.spawn.success_scanner"
				: "commands.deepcharter.pod.spawn.success"), true);
		return 1;
	}

	/**
	 * Registers the pod to the operator's charter and installs a tier 1 scanner stamped with it, the one way a part counts. Does
	 * nothing, and returns false, for a source that is not a player on a charter: the pod stays unowned and bare.
	 */
	private static boolean fitScanner(CommandSourceStack source, PodEntity pod) {
		if (!(source.getEntity() instanceof ServerPlayer player)) {
			return false;
		}
		Optional<CharterId> charter = Charters.charterOf(source.getServer(), player.getUUID()).map(Charter::id);
		if (charter.isEmpty()) {
			return false;
		}
		PodComponents.register(pod, charter.get());
		PodComponents.install(pod, ComponentItems.mint(source.getServer(), ComponentTrack.SCANNER, DEV_SCANNER_TIER, charter.get()));
		return true;
	}
}
