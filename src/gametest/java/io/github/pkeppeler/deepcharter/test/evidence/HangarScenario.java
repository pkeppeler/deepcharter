package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.Set;
import java.util.function.Predicate;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.hangar.HangarScreen;
import io.github.pkeppeler.deepcharter.hangar.Hangar;
import io.github.pkeppeler.deepcharter.hangar.HangarParts;
import io.github.pkeppeler.deepcharter.hangar.HangarTuning;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.terminal.Terminals;

/**
 * Evidence scenario "m2-hangar" for #77: the derelict Mole stands dark in the colony hangar. A charter puts the four parts into the
 * hangar console, which repairs the Mole and gives it to the charter as MOLE-0001. The console's screen then sells a refurbished
 * Mole, which stands in the bay beside the first. Stills of the derelict, the repaired Mole, the screen and the new Mole.
 */
public class HangarScenario extends EvidenceScenario {
	private static final int TICKS_PER_FRAME = 3;
	private static final int TYPING_FRAMES = 30;
	private static final int HOLD_FRAMES = 8;
	private static final int WAIT_TICKS = 400;
	private static final long ACCOUNT = 5_000;

	@Override
	protected String name() {
		return "m2-hangar";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			context.waitFor(client -> client.player != null && client.level != null);
			BlockPos console = singleplayer.getServer().computeOnServer(HangarScenario::setUp);
			// The derelict is an entity of the hangar's chunk, which loads a tick or more after the player arrives.
			await(context, singleplayer, server -> Hangar.derelict(server).isPresent());
			Vec3 derelict = singleplayer.getServer().computeOnServer(server -> Hangar.derelict(server).orElseThrow().position());
			lookAt(singleplayer, console, derelict);
			context.waitTicks(60);
			hold(context);
			screenshot(context, "derelict-in-the-hangar");

			singleplayer.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				for (Item part : HangarParts.ALL) {
					player.getInventory().add(new ItemStack(part));
					Terminals.insertPart(player, console, part).ifPresent(refusal -> {
						throw new AssertionError("inserting " + part + ": " + refusal);
					});
				}
			});
			await(context, singleplayer, server -> Hangar.derelict(server).flatMap(PodComponents::registration).isPresent());
			String serial = singleplayer.getServer().computeOnServer(server ->
					PodComponents.registration(Hangar.derelict(server).orElseThrow()).orElseThrow().serial());
			if (!serial.equals("MOLE-0001")) {
				throw new AssertionError("the founding Mole should be MOLE-0001, it is " + serial);
			}
			hold(context);
			screenshot(context, "founding-mole-repaired");

			context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(console)));
			context.waitForScreen(HangarScreen.class);
			HangarScreen screen = context.computeOnClient(client -> (HangarScreen) client.gui.screen());
			for (int i = 0; i < TYPING_FRAMES && !screen.typewriter().done(); i++) {
				context.waitTicks(TICKS_PER_FRAME);
				frame(context);
			}
			hold(context);
			screenshot(context, "hangar-console");

			HangarTuning tuning = HangarTuning.DEFAULT;
			context.clickScreenButton("BUY REFURBISHED MOLE: $" + tuning.refurbishedMole() + " + $" + tuning.registrationFee() + " PER POD");
			long price = tuning.refurbishedPrice(1);
			await(context, singleplayer, server -> balance(server) == ACCOUNT - price);
			hold(context);
			screenshot(context, "bought-a-refurbished-mole");
			context.setScreen(() -> null);

			Vec3 bay = singleplayer.getServer().computeOnServer(server -> Hangar.derelict(server).orElseThrow().position());
			lookAt(singleplayer, console, bay.add(0, 0, 3));
			context.waitTicks(40);
			hold(context);
			screenshot(context, "two-moles-in-the-bay");
		}
	}

	/** Waits for {@code condition} on the server. A client {@code waitFor} predicate cannot ask the server. */
	private static void await(ClientGameTestContext context, TestSingleplayerContext singleplayer, Predicate<MinecraftServer> condition) {
		for (int tick = 0; tick < WAIT_TICKS; tick++) {
			if (singleplayer.getServer().computeOnServer(condition::test)) {
				return;
			}
			context.waitTicks(1);
		}
		throw new AssertionError("the server did not reach the awaited state in " + WAIT_TICKS + " ticks");
	}

	/** The player founds a charter with money and stands in front of the hangar console. Returns the console. */
	private static BlockPos setUp(MinecraftServer server) {
		ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
		if (Charters.found(server, player.getUUID(), "Founding Charter").isPresent()) {
			throw new AssertionError("founding should succeed");
		}
		if (Charters.deposit(server, Charters.charterOf(server, player.getUUID()).orElseThrow().id(), ACCOUNT).isPresent()) {
			throw new AssertionError("funding should succeed");
		}
		player.setPermanentlyInvulnerable(true);
		// The Handbook in the hand would cover the view.
		player.getInventory().clearContent();
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon");
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "weather clear");
		return Hangar.consolePos(server).orElseThrow(() -> new AssertionError("the colony has no hangar"));
	}

	private static long balance(MinecraftServer server) {
		ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
		return Charters.charterOf(server, player.getUUID()).orElseThrow().account();
	}

	/** Stands the player two blocks in front of the console (west of it), looking at {@code target}. */
	private static void lookAt(TestSingleplayerContext singleplayer, BlockPos console, Vec3 target) {
		Vec3 at = Vec3.atBottomCenterOf(console.west(2));
		Vec3 d = target.subtract(at.add(0, 1.62, 0));
		float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		float pitch = (float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z)));
		singleplayer.getServer().runOnServer(server ->
				server.getPlayerList().getPlayers().getFirst().teleportTo(server.overworld(), at.x, at.y, at.z, Set.of(), yaw, pitch, true));
	}

	private void hold(ClientGameTestContext context) {
		for (int i = 0; i < HOLD_FRAMES; i++) {
			context.waitTicks(TICKS_PER_FRAME);
			frame(context);
		}
	}
}
