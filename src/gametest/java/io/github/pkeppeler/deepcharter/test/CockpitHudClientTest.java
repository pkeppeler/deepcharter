package io.github.pkeppeler.deepcharter.test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

import io.github.pkeppeler.deepcharter.client.pod.CockpitHud;
import io.github.pkeppeler.deepcharter.client.pod.CockpitReading;
import io.github.pkeppeler.deepcharter.client.pod.PodStatusHud;
import io.github.pkeppeler.deepcharter.client.transmission.TransmissionOverlay;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientPacks;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.EvidencePins;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.ScannerPods;
import io.github.pkeppeler.deepcharter.test.support.TestPacks;

/**
 * Client GameTest for the cockpit HUD (#444, #247): with a style picked, a seated pilot gets that style and the vanilla hotbar, hearts, held item name and
 * dismount hint are not drawn (read from pixels, against a shot of the game with its HUD switched off); on foot the vanilla HUD is back; with no style
 * the vanilla HUD stays while seated; the gauges read the pod's real fuel, hull and depth; and every line of the plain readout is still on the cockpit's plate.
 */
public class CockpitHudClientTest implements FabricClientGameTest {
	private static final int FEET_Y = 150;
	private static final int ROOM_RADIUS = 8;
	private static final int ROOM_HEIGHT = 30;
	private static final int RISE = 20;
	/** The middle of the bottom of the screen at GUI scale 2 (427 x 240): where the hotbar, the hearts, the held item name and the action bar are, clear of every console. */
	private static final int[] VANILLA_BOX = {126, 165, 298, 232};
	private static final int GUI_SCALE = 2;
	/**
	 * How much of the vanilla box may differ between a shot of the cockpit and a shot of the bare game (the pod's idle pose moves a few pixels between shots:
	 * 1.5 % measured), and how much must differ when the vanilla HUD is drawn there (the hotbar and the hearts alone are a third of it).
	 */
	private static final double SAME = 0.03;
	private static final double DIFFERENT = 0.10;
	private static final String MESSAGE = "Press Left Shift to dismount";

	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			context.waitTicks(40);
			context.runOnClient(client -> client.options.guiScale().set(GUI_SCALE));
			EvidencePins.lightFlickerOff = true;
			EvidencePins.handsHidden = true;
			try {
				PodEntity[] made = {null};
				singleplayer.getServer().runOnServer(server -> {
					ServerLevel one = server.getLevel(LayerChain.dimension(1));
					ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
					player.setGameMode(GameType.SURVIVAL);
					player.getInventory().setItem(1, new ItemStack(Items.GOLDEN_APPLE));
					player.getInventory().setSelectedSlot(1);
					RoomCarver.carve(one, -ROOM_RADIUS, ROOM_RADIUS, FEET_Y - 1, FEET_Y + ROOM_HEIGHT, -ROOM_RADIUS, ROOM_RADIUS, Blocks.AIR);
					player.teleportTo(one, 0.5, FEET_Y, 0.5, Set.of(), 0f, 0f, true);
					PodEntity pod = PodRegistry.POD.create(one, EntitySpawnReason.COMMAND);
					pod.setPos(0.5, FEET_Y, 0.5);
					pod.setNoGravity(true);
					one.addFreshEntity(pod);
					ScannerPods.fit(server, player, pod, 1);
					if (!player.startRiding(pod)) {
						throw new AssertionError("the player could not mount the pod");
					}
					made[0] = pod;
				});
				PodEntity pod = made[0];
				ClientWait.until(context, "the pilot seated in the pod in layer 1", client -> client.player != null && client.player.getVehicle() instanceof PodEntity
						&& client.level.dimension().equals(LayerChain.dimension(1)));
				context.waitTicks(40); // tick-wait: the charter's first transmission arrives a moment after the mount, and is cleared below

				expectNoCockpitWithoutAStyle(context);
				ClientPacks.enable(context, TestPacks.COCKPIT_CONSOLES);
				try {
					expectCockpitHidesTheVanillaHud(context, singleplayer, pod);
					expectGaugesReadThePod(context, singleplayer, pod);
					expectEveryReadoutLineIsOnThePlate(context);
				} finally {
					ClientPacks.disable(context, TestPacks.COCKPIT_CONSOLES);
				}
			} finally {
				EvidencePins.reset();
			}
		}
	}

	private static void expectNoCockpitWithoutAStyle(ClientGameTestContext context) {
		boolean seated = context.computeOnClient(client -> CockpitHud.seated(client).isPresent());
		if (seated) {
			throw new AssertionError("The mod ships style 0: a seated pilot gets no cockpit until a pack picks one");
		}
	}

	private void expectCockpitHidesTheVanillaHud(ClientGameTestContext context, TestSingleplayerContext singleplayer, PodEntity pod) {
		String layout = context.computeOnClient(client -> CockpitHud.seated(client).orElseThrow(() -> new AssertionError("a seated pilot should have the consoles")).name());
		if (!layout.equals("consoles")) {
			throw new AssertionError("The consoles pack picks the consoles look, got " + layout);
		}
		BufferedImage bare = shotOfBareGame(context);
		BufferedImage cockpit = shot(context, "cockpit-seated");
		double seated = differing(bare, cockpit);
		if (seated > SAME) {
			throw new AssertionError("Seated under the cockpit, the vanilla hotbar, hearts, held item name and dismount hint must not be drawn: "
					+ seated + " of the middle of the bottom of the screen differs from the game with no HUD");
		}
		singleplayer.getServer().runOnServer(server -> server.getPlayerList().getPlayers().getFirst().stopRiding());
		ClientWait.until(context, "the pilot on foot", client -> client.player.getVehicle() == null);
		ClientWait.until(context, "the cockpit gone on foot", client -> CockpitHud.seated(client).isEmpty());
		BufferedImage onFoot = shot(context, "cockpit-on-foot");
		double back = differing(bare, onFoot);
		if (back < DIFFERENT) {
			throw new AssertionError("On foot the vanilla HUD is back: the middle of the bottom of the screen should differ from the bare game, but only " + back + " of it does");
		}
		singleplayer.getServer().runOnServer(server -> {
			if (!server.getPlayerList().getPlayers().getFirst().startRiding(pod)) {
				throw new AssertionError("the pilot could not board the pod again");
			}
		});
		ClientWait.until(context, "the pilot seated again", client -> client.player.getVehicle() instanceof PodEntity);
	}

	/** A shot with the whole HUD switched off, which is what the vanilla box shows when nothing is drawn in it. */
	private BufferedImage shotOfBareGame(ClientGameTestContext context) {
		context.runOnClient(client -> client.gui.hud.toggle());
		try {
			return shot(context, "cockpit-bare");
		} finally {
			context.runOnClient(client -> client.gui.hud.toggle());
		}
	}

	/** A shot of the game, with the held item name and a message above the hotbar up, as when a pilot has just boarded. */
	private BufferedImage shot(ClientGameTestContext context, String name) {
		context.runOnClient(client -> {
			client.gui.hud.setOverlayMessage(Component.literal(MESSAGE), false);
			client.player.setYRot(0f);
			client.player.setXRot(0f);
			client.gui.toastManager().clear();
			TransmissionOverlay.reset();
		});
		ClientWait.until(context, "every section in view rendered", client -> client.levelRenderer.hasRenderedAllSections());
		context.waitTicks(5); // tick-wait: the pod and the view settle for a few frames
		Path file = context.takeScreenshot(name);
		try {
			return ImageIO.read(file.toFile());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** The share of the pixels of the vanilla box that differ by more than a few levels between two shots. */
	private static double differing(BufferedImage a, BufferedImage b) {
		int scale = GUI_SCALE;
		int differing = 0;
		int total = 0;
		for (int y = VANILLA_BOX[1] * scale; y < VANILLA_BOX[3] * scale; y++) {
			for (int x = VANILLA_BOX[0] * scale; x < VANILLA_BOX[2] * scale; x++) {
				total++;
				int pa = a.getRGB(x, y);
				int pb = b.getRGB(x, y);
				int delta = Math.abs((pa >> 16 & 0xFF) - (pb >> 16 & 0xFF)) + Math.abs((pa >> 8 & 0xFF) - (pb >> 8 & 0xFF)) + Math.abs((pa & 0xFF) - (pb & 0xFF));
				if (delta > 12) {
					differing++;
				}
			}
		}
		return (double) differing / total;
	}

	private void expectGaugesReadThePod(ClientGameTestContext context, TestSingleplayerContext singleplayer, PodEntity pod) {
		singleplayer.getServer().runOnServer(server -> {
			pod.setFuel(50f);
			pod.setHull(pod.maxHull() / 2f);
			pod.setPos(0.5, FEET_Y, 0.5);
		});
		ClientWait.until(context, "the pod's fuel and hull on the client", client -> client.player.getVehicle() instanceof PodEntity shown
				&& shown.fuel() == 50f && Math.abs(shown.hull() - shown.maxHull() / 2f) < 0.01f);
		CockpitReading reading = context.computeOnClient(client -> CockpitReading.of(client, (PodEntity) client.player.getVehicle(), 0.25));
		if (Math.abs(reading.fuel() - 0.5) > 1e-6 || Math.abs(reading.hull() - 0.5) > 1e-3 || reading.heat() != 0.25) {
			throw new AssertionError("A pod with half a tank and half its hull reads 0.5 and 0.5, and the heat it was given: " + reading);
		}
		if (!(reading.depth() > 0.0 && reading.depth() < 1.0)) {
			throw new AssertionError("A pod in layer 1 reads a depth between the surface and the deepest floor: " + reading);
		}
		singleplayer.getServer().runOnServer(server -> pod.setPos(0.5, FEET_Y + RISE, 0.5));
		ClientWait.until(context, RISE + " blocks higher on the client", client -> client.player.getVehicle() instanceof PodEntity shown && shown.getBlockY() >= FEET_Y + RISE);
		CockpitReading higher = context.computeOnClient(client -> CockpitReading.of(client, (PodEntity) client.player.getVehicle(), 0));
		if (!(higher.depth() < reading.depth())) {
			throw new AssertionError("A pod " + RISE + " blocks higher reads shallower: " + reading.depth() + " then " + higher.depth());
		}
		singleplayer.getServer().runOnServer(server -> pod.setPos(0.5, FEET_Y, 0.5));
	}

	private void expectEveryReadoutLineIsOnThePlate(ClientGameTestContext context) {
		List<String> keys = context.computeOnClient(client -> PodStatusHud.readout(Minecraft.getInstance(), (PodEntity) client.player.getVehicle()).stream()
				.map(line -> ((TranslatableContents) line.text().getContents()).getKey()).toList());
		for (String key : Stream.of("hull", "fuel", "cargo", "depth").map(name -> "hud.deepcharter.pod." + name).toList()) {
			if (!keys.contains(key)) {
				throw new AssertionError("The cockpit's plate shows the readout's " + key + " line, got " + keys);
			}
		}
		if (!context.computeOnClient(CockpitHud::showsReadout)) {
			throw new AssertionError("The pilot's seat shows the readout on the plate, so the plain readout and the account line stay quiet");
		}
	}
}
