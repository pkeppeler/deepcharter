package io.github.pkeppeler.deepcharter.test.evidence;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;

import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;

/**
 * Evidence scenario "m2-charter-core": the operator charter commands, run as the real player and answered in its chat. The
 * player founds a charter, a second player applies, the player approves, then deposits, overspends, reads the charter and leaves.
 */
public class CharterCoreScenario extends EvidenceScenario {
	private static final String CHARTER = "Deep Crew";
	private static final String CREW = "Crewmate";
	private static final int TICKS_AFTER_COMMAND = 12;
	private static final int TICKS_PER_FRAME = 3;

	@Override
	protected String name() {
		return "m2-charter-core";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			TestServerContext server = singleplayer.getServer();
			context.waitFor(client -> client.player != null && client.level != null);
			context.waitTicks(20);
			frame(context);

			command(context, server, "deepcharter charter found \"" + CHARTER + "\"");
			server.runOnServer(minecraftServer -> {
				MockPlayers.join(minecraftServer, CREW);
				ServerPlayer crew = minecraftServer.getPlayerList().getPlayerByName(CREW);
				Charters.apply(minecraftServer, crew.getUUID(), Charters.findByName(minecraftServer, CHARTER).orElseThrow().id());
			});
			command(context, server, "deepcharter charter approve " + CREW);
			command(context, server, "deepcharter charter account deposit \"" + CHARTER + "\" 500");
			command(context, server, "deepcharter charter account spend \"" + CHARTER + "\" 9000");
			command(context, server, "deepcharter charter info");
			screenshot(context, "charter-info");
			command(context, server, "deepcharter charter leave");
			command(context, server, "deepcharter charter list");
			screenshot(context, "charter-after-leave");
		}
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
