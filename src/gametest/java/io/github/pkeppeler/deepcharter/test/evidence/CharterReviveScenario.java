package io.github.pkeppeler.deepcharter.test.evidence;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;

import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;

/**
 * Evidence scenario "m2-charter-revive": a charter whose last member left is dormant and keeps its name and account. The real
 * player cannot found a charter under that name, revives it by name, and is its Director with the account intact.
 */
public class CharterReviveScenario extends EvidenceScenario {
	private static final String CHARTER = "Lost Crew";
	private static final String FORMER = "Former";
	private static final long ACCOUNT = 750;
	private static final int TICKS_AFTER_COMMAND = 12;
	private static final int TICKS_PER_FRAME = 3;

	@Override
	protected String name() {
		return "m2-charter-revive";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			TestServerContext server = singleplayer.getServer();
			context.waitFor(client -> client.player != null && client.level != null);
			context.waitTicks(20);
			frame(context);

			server.runOnServer(minecraftServer -> {
				MockPlayers.join(minecraftServer, FORMER);
				ServerPlayer former = minecraftServer.getPlayerList().getPlayerByName(FORMER);
				Charters.found(minecraftServer, former.getUUID(), CHARTER);
				Charter charter = Charters.findByName(minecraftServer, CHARTER).orElseThrow();
				Charters.deposit(minecraftServer, charter.id(), ACCOUNT);
				Charters.leave(minecraftServer, former.getUUID());
			});
			command(context, server, "deepcharter charter list");
			command(context, server, "deepcharter charter found \"" + CHARTER + "\"");
			screenshot(context, "name-is-held");
			command(context, server, "deepcharter charter revive \"" + CHARTER + "\"");
			command(context, server, "deepcharter charter info");
			screenshot(context, "revived");
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
