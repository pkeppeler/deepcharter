package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.Set;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;

import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.layer.LayerChain;

/**
 * Evidence scenario "deepest-point": the charter's deepest point rises as the player goes down, with nothing written by hand. The player
 * founds a charter and reads it on the surface, drops into layer 1 and reads it, then drops to the floor of layer 2 (where layer 3
 * begins, and the Morale Initiative opens) and reads it again.
 */
public class DeepestPointScenario extends EvidenceScenario {
	private static final int TICKS_AFTER_COMMAND = 12;
	private static final int TICKS_PER_FRAME = 3;
	/** Longer than the poll of the deepest point (20 ticks), so the next read sees the record. */
	private static final int POLL_TICKS = 30;
	private static final int ARRIVAL_TICKS = 40;

	@Override
	protected String name() {
		return "deepest-point";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			TestServerContext server = singleplayer.getServer();
			context.waitFor(client -> client.player != null && client.level != null);
			context.waitTicks(20);
			frame(context);

			server.runOnServer(minecraftServer -> {
				ServerPlayer player = minecraftServer.getPlayerList().getPlayers().getFirst();
				player.setPermanentlyInvulnerable(true);
				player.getInventory().clearContent();
				if (Charters.found(minecraftServer, player.getUUID(), "Deep Crew").isPresent()) {
					throw new AssertionError("founding should succeed");
				}
			});
			command(context, server, "deepcharter charter info");
			screenshot(context, "deepest-point-surface");

			descend(context, server, 1, 1);
			command(context, server, "deepcharter charter info");
			screenshot(context, "deepest-point-layer-1");

			descend(context, server, 2, 0);
			command(context, server, "deepcharter charter info");
			screenshot(context, "deepest-point-top-of-layer-3");
			server.runOnServer(minecraftServer -> {
				ServerPlayer player = minecraftServer.getPlayerList().getPlayers().getFirst();
				int deepest = Charters.charterOfOrThrow(minecraftServer, player.getUUID()).orElseThrow().deepestPoint();
				if (deepest < LayerChain.topDepth(minecraftServer.registryAccess(), 3)) {
					throw new AssertionError("the floor of layer 2 should have set the deepest point to the top of layer 3, got " + deepest);
				}
			});
		}
	}

	/** Puts the player {@code rowsAboveFloor} rows above the floor of {@code layer}, held in place, and waits for the next poll to see them. */
	private void descend(ClientGameTestContext context, TestServerContext server, int layer, int rowsAboveFloor) {
		server.runOnServer(minecraftServer -> {
			ServerPlayer player = minecraftServer.getPlayerList().getPlayers().getFirst();
			ServerLevel level = minecraftServer.getLevel(LayerChain.dimension(layer));
			player.setNoGravity(true);
			player.teleportTo(level, 0.5, level.getMinY() + rowsAboveFloor, 0.5, Set.of(), 0f, 0f, true);
		});
		context.waitTicks(ARRIVAL_TICKS);
		frame(context);
		context.waitTicks(POLL_TICKS);
	}

	/** Runs the command as the player with operator rights, and echoes it, so the answer reaches the real client's chat. */
	private void command(ClientGameTestContext context, TestServerContext server, String command) {
		server.runOnServer(minecraftServer -> {
			ServerPlayer player = minecraftServer.getPlayerList().getPlayers().getFirst();
			player.sendSystemMessage(Component.literal("> /" + command));
			try {
				minecraftServer.getCommands().getDispatcher().execute(command,
						player.createCommandSourceStack().withPermission(LevelBasedPermissionSet.GAMEMASTER));
			} catch (CommandSyntaxException e) {
				throw new AssertionError("command failed: " + command + ": " + e.getMessage(), e);
			}
		});
		for (int i = 0; i < TICKS_AFTER_COMMAND; i += TICKS_PER_FRAME) {
			context.waitTicks(TICKS_PER_FRAME);
			frame(context);
		}
	}
}
