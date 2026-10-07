package io.github.pkeppeler.deepcharter.layer;

import java.util.Set;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.Dynamic2CommandExceptionType;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

import io.github.pkeppeler.deepcharter.command.FeatureCommands;

/** {@code /deepcharter layer ...}, op-only. */
public final class LayerCommands {
	private static final Dynamic2CommandExceptionType NO_SUCH_LAYER = new Dynamic2CommandExceptionType(
			(layer, count) -> Component.translatable("deepcharter.layer.goto.no_such_layer", layer, count));

	private LayerCommands() {
	}

	public static void init() {
		FeatureCommands.register("layer", layer -> layer.then(Commands.literal("goto")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("n", IntegerArgumentType.integer(1))
						.executes(context -> goTo(context.getSource(), IntegerArgumentType.getInteger(context, "n"))))));
	}

	/** Moves the player to the surface of layer {@code n}, keeping their x and z. */
	private static int goTo(CommandSourceStack source, int n) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		int count = LayerChain.count(source.getServer().registryAccess());
		ServerLevel level = n > count ? null : source.getServer().getLevel(LayerChain.dimension(n));
		if (level == null) {
			throw NO_SUCH_LAYER.create(n, count);
		}
		int x = player.getBlockX();
		int z = player.getBlockZ();
		// The heightmap of an unloaded chunk reads as the layer's minY, so force the chunk first.
		level.getChunk(x >> 4, z >> 4, ChunkStatus.FULL);
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
		player.teleportTo(level, x + 0.5, y, z + 0.5, Set.of(), player.getYRot(), player.getXRot(), true);
		source.sendSuccess(() -> Component.translatable("deepcharter.layer.goto.success", n), true);
		return n;
	}
}
