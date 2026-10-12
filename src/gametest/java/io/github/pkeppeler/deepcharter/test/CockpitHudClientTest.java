package io.github.pkeppeler.deepcharter.test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.resources.Identifier;
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
import io.github.pkeppeler.deepcharter.client.theme.CockpitLook;
import io.github.pkeppeler.deepcharter.client.theme.PanelLook;
import io.github.pkeppeler.deepcharter.client.transmission.TransmissionOverlay;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientPacks;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.EvidencePins;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.ScannerPods;
import io.github.pkeppeler.deepcharter.test.support.TestPacks;

/**
 * Client GameTest for the cockpit HUD (#247, the Ribbon): a seated pilot gets the Ribbon by default and the vanilla hotbar, hearts and held item name are not drawn (read from
 * pixels, against a shot of the game with its HUD switched off) while the action bar stays; on foot, with a pack that turns the cockpit off, and on a GUI too small for the
 * Ribbon the vanilla HUD is back; the gauges read the pod's real fuel, hull and depth; every line of the plain readout is on the plate; and the Ribbon fits the GUI at every
 * GUI scale (1 to 4 and Auto) of every common window, with the longest warnings.
 */
public class CockpitHudClientTest implements FabricClientGameTest {
	private static final int FEET_Y = 150;
	private static final int ROOM_RADIUS = 8;
	private static final int ROOM_HEIGHT = 30;
	private static final int RISE = 20;
	/** The middle of the bottom of the screen at GUI scale 2 (427 x 240): where the hotbar, the hearts, the held item name and the action bar are, clear of every console. */
	private static final int[] VANILLA_BOX = {126, 165, 298, 232};
	/** The rows of the held item name and the action bar (the dismount hint), over the hotbar and the hearts: a leaked line of text is a few percent of the box above but most of this strip. */
	private static final int[] TEXT_STRIP = {126, 170, 298, 190};
	private static final double SAME_TEXT = 0.005;
	private static final int GUI_SCALE = 2;
	/**
	 * How much of the vanilla box may differ between a shot of the cockpit and a shot of the bare game (the pod's idle pose moves a few pixels between shots:
	 * 1.5 % measured), and how much must differ when the vanilla HUD is drawn there (the hotbar and the hearts alone are a third of it).
	 */
	private static final double SAME = 0.03;
	private static final double DIFFERENT = 0.10;
	private static final String MESSAGE = "Press Left Shift to dismount";
	/** The windows players have, in pixels. A GUI scale gives a window's GUI its size ({@link #guiSize}). */
	private static final int[][] WINDOWS = {{854, 480}, {1024, 768}, {1280, 720}, {1366, 768}, {1600, 900}, {1920, 1080}, {2560, 1440}, {3840, 2160}};
	/** The GUI scale settings: 0 is Auto. */
	private static final int[] GUI_SCALES = {0, 1, 2, 3, 4};
	/** The width of the altimeter's recess in the ribbon sprite (tools/cockpit_ribbon.py), which its longest reading must fit. */
	private static final int ALTIMETER_RECESS_WIDTH = 72;
	private static final String LONGEST_ALTIMETER = "-12,345 ft.";
	/** How much of the strip the action bar line takes when it is drawn: a few lines of text. */
	private static final double MESSAGE_DRAWN = 0.02;

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

				expectTheCockpitHidesExactlyTheVanillaBottomCluster();
				expectEveryReadoutLineHasAKindAndTheWarningsAreTheAlerts();
				expectTheRibbonFitsEveryGuiScaleOfEveryWindow(context);
				expectTheRibbonIsTheDefaultSeatedHud(context);
				expectCockpitHidesTheVanillaHud(context, singleplayer, pod);
				expectTheActionBarStaysForTheMessagesOfTheGame(context);
				expectGaugesReadThePod(context, singleplayer, pod);
				expectEveryReadoutLineIsOnThePlate(context);
				expectThePlateKeepsEveryReadout(context, singleplayer, pod);
				expectAPackCanTurnTheCockpitOff(context);
				expectASmallGuiGivesBackThePlainHud(context);
			} finally {
				EvidencePins.reset();
			}
		}
	}

	private static void expectTheCockpitHidesExactlyTheVanillaBottomCluster() {
		Set<Identifier> expected = Set.of(VanillaHudElements.HOTBAR, VanillaHudElements.ARMOR_BAR, VanillaHudElements.HEALTH_BAR, VanillaHudElements.FOOD_BAR,
				VanillaHudElements.AIR_BAR, VanillaHudElements.MOUNT_HEALTH, VanillaHudElements.INFO_BAR, VanillaHudElements.EXPERIENCE_LEVEL,
				VanillaHudElements.HELD_ITEM_TOOLTIP);
		if (!Set.copyOf(CockpitHud.HIDDEN_WHILE_SEATED).equals(expected) || CockpitHud.HIDDEN_WHILE_SEATED.size() != expected.size()) {
			throw new AssertionError("The cockpit hides the hotbar, health, armour, food, air, mount health, experience bar and level and held item name, and leaves the action bar, got "
					+ CockpitHud.HIDDEN_WHILE_SEATED);
		}
	}

	/**
	 * The fourth lamp lights for a line of kind ALERT, so the set of alerts is pinned: every readout line the mod has (the lang keys under {@code hud.deepcharter.pod.} that are
	 * lines) has a kind, and the alerts are the warnings. A new readout line fails this until it says what it is.
	 */
	private static void expectEveryReadoutLineHasAKindAndTheWarningsAreTheAlerts() {
		JsonObject lang;
		try (Reader reader = new InputStreamReader(CockpitHudClientTest.class.getResourceAsStream("/assets/deepcharter/lang/en_us.json"), StandardCharsets.UTF_8)) {
			lang = JsonParser.parseReader(reader).getAsJsonObject();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		Set<String> lines = lang.keySet().stream().filter(key -> key.startsWith("hud.deepcharter.pod.") && !key.startsWith("hud.deepcharter.pod.seepage_side.")
				&& !key.startsWith("hud.deepcharter.pod.camera")).collect(Collectors.toSet());
		if (!lines.equals(PodStatusHud.KINDS.keySet())) {
			throw new AssertionError("Every line of the pod's readout has a kind and every kind is a line: lines without a kind " + difference(lines, PodStatusHud.KINDS.keySet())
					+ ", kinds without a line " + difference(PodStatusHud.KINDS.keySet(), lines));
		}
		Set<String> alerts = PodStatusHud.KINDS.entrySet().stream().filter(entry -> entry.getValue() == PodStatusHud.Kind.ALERT).map(Map.Entry::getKey).collect(Collectors.toSet());
		Set<String> expected = Stream.of("burning", "stranded", "crust_ahead", "crust_in", "brace_waits", "brace_dry", "brace_dear", "seepage", "seepage_beside", "seepage_bleeding",
				"lining_dry", "hard_landing", "lava_near", "lava_above", "glaze_holding", "glaze_spent").map(name -> "hud.deepcharter.pod." + name).collect(Collectors.toSet());
		if (!alerts.equals(expected)) {
			throw new AssertionError("The warnings that light the fourth lamp are the burning hull, the glaze that holds or is spent, stranded, the crust, the brace that cannot work, the seepage, no brick, a hard landing and lava in or above the shaft: got " + alerts);
		}
		PodStatusHud.Line note = new PodStatusHud.Line(Component.translatable("hud.deepcharter.pod.slag", 0, 0), 0);
		PodStatusHud.Line warning = new PodStatusHud.Line(Component.translatable("hud.deepcharter.pod.burning"), 0);
		if (CockpitHud.lampsLit(false, false, false, List.of(note))[3] || !CockpitHud.lampsLit(false, false, false, List.of(note, warning))[3]) {
			throw new AssertionError("The fourth lamp is lit by a warning and not by a note");
		}
		boolean[] lit = CockpitHud.lampsLit(true, false, true, List.of());
		if (!lit[0] || lit[1] || !lit[2] || lit[3]) {
			throw new AssertionError("The lamps are low fuel, low hull, burning and any other warning, got " + Arrays.toString(lit));
		}
	}

	private static Set<String> difference(Set<String> a, Set<String> b) {
		Set<String> only = new TreeSet<>(a);
		only.removeAll(b);
		return only;
	}

	private static void expectTheRibbonIsTheDefaultSeatedHud(ClientGameTestContext context) {
		boolean seated = context.computeOnClient(client -> CockpitHud.seated(client).isPresent());
		if (!seated) {
			throw new AssertionError("The mod ships the Ribbon: a seated pilot gets it with no pack");
		}
	}

	private void expectCockpitHidesTheVanillaHud(ClientGameTestContext context, TestSingleplayerContext singleplayer, PodEntity pod) {
		BufferedImage bare = shotOfBareGame(context);
		BufferedImage cockpit = shot(context, "cockpit-seated", false);
		double seated = differing(bare, cockpit, VANILLA_BOX);
		if (seated > SAME) {
			throw new AssertionError("Seated under the cockpit, the vanilla hotbar, hearts and held item name must not be drawn: "
					+ seated + " of the middle of the bottom of the screen differs from the game with no HUD");
		}
		double text = differing(bare, cockpit, TEXT_STRIP);
		if (text > SAME_TEXT) {
			throw new AssertionError("Seated under the cockpit, no held item name is drawn: " + text + " of the text rows differs from the game with no HUD");
		}
		singleplayer.getServer().runOnServer(server -> server.getPlayerList().getPlayers().getFirst().stopRiding());
		ClientWait.until(context, "the pilot on foot", client -> client.player.getVehicle() == null);
		ClientWait.until(context, "the cockpit gone on foot", client -> CockpitHud.seated(client).isEmpty());
		BufferedImage onFoot = shot(context, "cockpit-on-foot", true);
		double back = differing(bare, onFoot, VANILLA_BOX);
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
		BufferedImage[] bare = {null};
		ClientChecks.withHudHidden(context, () -> bare[0] = shot(context, "cockpit-bare", false));
		return bare[0];
	}

	/** A shot of the game, with the held item name up, as when a pilot has just changed the slot, and with an action bar message when {@code message} is set. */
	private BufferedImage shot(ClientGameTestContext context, String name, boolean message) {
		context.runOnClient(client -> {
			client.gui.hud.setOverlayMessage(message ? Component.literal(MESSAGE) : Component.empty(), false);
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
	private static double differing(BufferedImage a, BufferedImage b, int[] box) {
		int scale = GUI_SCALE;
		int differing = 0;
		int total = 0;
		for (int y = box[1] * scale; y < box[3] * scale; y++) {
			for (int x = box[0] * scale; x < box[2] * scale; x++) {
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

	/**
	 * The plate keeps every value and line of the plain readout reachable: the four values are the tally, in the order of the gauges, and every other line of the readout
	 * (a warning, the slag, the account) is a row of its own under it. The cargo gauge reads the bay's share.
	 */
	private void expectThePlateKeepsEveryReadout(ClientGameTestContext context, TestSingleplayerContext singleplayer, PodEntity pod) {
		singleplayer.getServer().runOnServer(server -> {
			pod.setFuel(0f);
			pod.setStranded(true);
			pod.setHull(pod.maxHull() / 2f);
			pod.setCargoUsed(PodStats.of(pod).cargoSlots());
		});
		ClientWait.until(context, "the stranded pod with a full bay on the client", client -> client.player.getVehicle() instanceof PodEntity shown && shown.stranded()
				&& shown.cargoUsed() == PodStats.of(shown).cargoSlots());
		List<List<String>> rows = context.computeOnClient(client -> {
			PodEntity shown = (PodEntity) client.player.getVehicle();
			CockpitLook look = CockpitLook.current();
			CockpitReading reading = CockpitReading.of(client, shown, 0);
			if (Math.abs(reading.cargo() - 1.0) > 1e-9) {
				throw new AssertionError("A full bay reads 1 on the cargo gauge, got " + reading);
			}
			List<PodStatusHud.Line> readout = PodStatusHud.readout(client, shown);
			if (!CockpitHud.lampsLit(false, false, false, readout)[3]) {
				throw new AssertionError("A stranded pod's warning lights the fourth lamp, the readout is " + readout);
			}
			return CockpitHud.rows(shown, reading, look, readout).stream()
					.map(row -> row.parts().stream().map(part -> ((TranslatableContents) part.text().getContents()).getKey()).toList()).toList();
		});
		List<String> tally = List.of("hud.deepcharter.pod.tally_hull", "hud.deepcharter.pod.tally_fuel", "hud.deepcharter.pod.tally_cargo", "hud.deepcharter.pod.tally_depth");
		if (!rows.contains(tally)) {
			throw new AssertionError("The plate shows the tally of hull, fuel, cargo and depth on one row, got " + rows);
		}
		if (!rows.contains(List.of("hud.deepcharter.pod.stranded"))) {
			throw new AssertionError("A stranded pod's warning is a row of the plate, got " + rows);
		}
		if (rows.stream().anyMatch(row -> row.contains("hud.deepcharter.pod.hull") || row.contains("hud.deepcharter.pod.fuel"))) {
			throw new AssertionError("The four values are in the tally, not listed again, got " + rows);
		}
		singleplayer.getServer().runOnServer(server -> {
			pod.setStranded(false);
			pod.setFuel(50f);
			pod.setHull(pod.maxHull());
			pod.setCargoUsed(0);
		});
		ClientWait.until(context, "the pod ready again on the client", client -> client.player.getVehicle() instanceof PodEntity shown && !shown.stranded()
				&& shown.cargoUsed() == 0 && shown.fuel() == 50f);
	}

	/** The game's messages (a grab on the hull, a refusal) arrive on the action bar, which the Ribbon leaves alone: a message set while seated is drawn. */
	private void expectTheActionBarStaysForTheMessagesOfTheGame(ClientGameTestContext context) {
		BufferedImage bare = shotOfBareGame(context);
		BufferedImage withMessage = shot(context, "cockpit-message", true);
		double drawn = differing(bare, withMessage, TEXT_STRIP);
		if (drawn < MESSAGE_DRAWN) {
			throw new AssertionError("Seated under the Ribbon, an action bar message is drawn, but only " + drawn + " of the text rows differs from the game with no HUD");
		}
	}

	/** A pack that sets {@code enabled} to 0 gives the plain HUD back while seated, with the vanilla hotbar. */
	private void expectAPackCanTurnTheCockpitOff(ClientGameTestContext context) {
		ClientPacks.enable(context, TestPacks.COCKPIT_OFF);
		try {
			boolean seated = context.computeOnClient(client -> CockpitHud.seated(client).isPresent() || CockpitHud.showsReadout(client));
			if (seated) {
				throw new AssertionError("With the cockpit turned off, a seated pilot gets the plain HUD");
			}
			BufferedImage bare = shotOfBareGame(context);
			double back = differing(bare, shot(context, "cockpit-off", true), VANILLA_BOX);
			if (back < DIFFERENT) {
				throw new AssertionError("With the cockpit turned off the vanilla HUD is drawn while seated, but only " + back + " of the bottom differs from the bare game");
			}
		} finally {
			ClientPacks.disable(context, TestPacks.COCKPIT_OFF);
		}
	}

	/** A GUI smaller than the Ribbon needs (a forced-unicode scale on a window, GUI scale 4 on this 854 x 480 window set by hand) gives the plain HUD back. */
	private void expectASmallGuiGivesBackThePlainHud(ClientGameTestContext context) {
		context.runOnClient(client -> client.getWindow().setGuiScale(4));
		try {
			boolean seated = context.computeOnClient(client -> CockpitHud.seated(client).isPresent() || CockpitHud.showsReadout(client));
			if (seated) {
				throw new AssertionError("On a GUI of 213 x 120 the Ribbon does not fit, so the plain HUD is drawn");
			}
		} finally {
			context.runOnClient(client -> client.getWindow().setGuiScale(GUI_SCALE));
		}
	}

	/** The GUI the game makes of a window at a GUI Scale setting (0 is Auto), as vanilla's {@code Window.calculateScale} does it: never under 320 x 240 while the window is that big. */
	private static int[] guiSize(int windowWidth, int windowHeight, int requested) {
		int scale = 1;
		while (scale != requested && scale < windowWidth && scale < windowHeight && windowWidth / (scale + 1) >= 320 && windowHeight / (scale + 1) >= 240) {
			scale++;
		}
		return new int[] {(int) Math.ceil((double) windowWidth / scale), (int) Math.ceil((double) windowHeight / scale), scale};
	}

	/**
	 * The Ribbon at every GUI scale of every common window, and on this client's own window at each scale it can set: the pieces are on the screen, the gauges on the band,
	 * the scanner in its bezel, nothing overlaps, the altimeter fits its recess, and the plate with the longest warnings (all the alerts a pilot can see at once) is on the
	 * screen under the bezel with no warning dropped.
	 */
	private void expectTheRibbonFitsEveryGuiScaleOfEveryWindow(ClientGameTestContext context) {
		List<String> problems = new ArrayList<>(context.computeOnClient(client -> {
			List<String> found = new ArrayList<>();
			for (int[] window : WINDOWS) {
				for (int requested : GUI_SCALES) {
					int[] gui = guiSize(window[0], window[1], requested);
					found.addAll(ribbonProblems(client.font, gui[0], gui[1], window[0] + "x" + window[1] + " at GUI scale " + requested));
				}
			}
			for (int requested : GUI_SCALES) {
				int scale = client.getWindow().calculateScale(requested, false);
				client.getWindow().setGuiScale(scale);
				try {
					int width = client.getWindow().getGuiScaledWidth();
					int height = client.getWindow().getGuiScaledHeight();
					if (!CockpitLook.current().fits(width, height)) {
						found.add("this client's window at GUI scale " + requested + " (a GUI of " + width + " x " + height + ") does not fit the Ribbon");
					}
					found.addAll(ribbonProblems(client.font, width, height, "this client's window at GUI scale " + requested));
				} finally {
					client.getWindow().setGuiScale(GUI_SCALE);
				}
			}
			return found;
		}));
		if (!problems.isEmpty()) {
			throw new AssertionError("The Ribbon must fit every GUI scale: " + problems);
		}
	}

	private static List<String> ribbonProblems(Font font, int guiWidth, int guiHeight, String where) {
		List<String> problems = new ArrayList<>();
		CockpitLook look = CockpitLook.current();
		CockpitLook.Layout layout = look.ribbon();
		if (!look.fits(guiWidth, guiHeight)) {
			problems.add(where + ": a GUI of " + guiWidth + " x " + guiHeight + " does not fit the Ribbon");
			return problems;
		}
		int[] screen = {0, 0, guiWidth, guiHeight};
		Map<String, int[]> pieces = new LinkedHashMap<>();
		pieces.put("band", box(layout.band(), guiWidth, guiHeight));
		pieces.put("bezel", box(layout.cap(), guiWidth, guiHeight));
		pieces.put("scanner", box(layout.scanner(), guiWidth, guiHeight));
		Map<String, CockpitLook.Gauge> gauges = Map.of("fuel", layout.fuel(), "hull", layout.hull(), "cargo", layout.cargo(), "depth", layout.depth(), "heat", layout.heat());
		Map<String, int[]> bars = new LinkedHashMap<>();
		gauges.forEach((name, gauge) -> bars.put(name, box(gauge.area(), guiWidth, guiHeight)));
		bars.put("lamps", box(layout.lamps(), guiWidth, guiHeight));
		for (Map.Entry<String, int[]> piece : bars.entrySet()) {
			if (!inside(piece.getValue(), pieces.get("band"))) {
				problems.add(where + ": the " + piece.getKey() + " is off the band");
			}
			for (Map.Entry<String, int[]> other : bars.entrySet()) {
				if (piece.getKey().compareTo(other.getKey()) < 0 && overlap(piece.getValue(), other.getValue())) {
					problems.add(where + ": the " + piece.getKey() + " and the " + other.getKey() + " overlap");
				}
			}
		}
		for (Map.Entry<String, int[]> piece : pieces.entrySet()) {
			if (!inside(piece.getValue(), screen)) {
				problems.add(where + ": the " + piece.getKey() + " leaves the screen");
			}
		}
		if (!inside(pieces.get("scanner"), pieces.get("bezel"))) {
			problems.add(where + ": the scanner window is not in its bezel");
		}
		if (overlap(pieces.get("band"), pieces.get("bezel"))) {
			problems.add(where + ": the bezel covers the band");
		}
		if (font.width(LONGEST_ALTIMETER) > ALTIMETER_RECESS_WIDTH || layout.altimeterY() + font.lineHeight > layout.band().height()) {
			problems.add(where + ": the altimeter reading does not fit its recess in the band");
		}
		for (List<CockpitHud.Row> rows : plateCases()) {
			List<CockpitHud.Row> kept = CockpitHud.fitting(rows, CockpitHud.roomForRows(font, layout, look, guiHeight));
			CockpitHud.Box plate = CockpitHud.plateBox(font, layout, look, kept, guiWidth, guiHeight);
			int[] plateBox = {plate.left(), plate.top(), plate.left() + plate.width(), plate.top() + plate.height()};
			if (!inside(plateBox, screen)) {
				problems.add(where + ": the plate with " + kept.size() + " rows leaves the screen: " + Arrays.toString(plateBox));
			}
			for (String piece : List.of("band", "bezel")) {
				if (overlap(plateBox, pieces.get(piece))) {
					problems.add(where + ": the plate with " + kept.size() + " rows covers the " + piece);
				}
			}
			long alerts = rows.stream().filter(row -> row.parts().getFirst().kind() == PodStatusHud.Kind.ALERT).count();
			long keptAlerts = kept.stream().filter(row -> row.parts().getFirst().kind() == PodStatusHud.Kind.ALERT).count();
			if (alerts != keptAlerts) {
				problems.add(where + ": the plate dropped a warning, " + keptAlerts + " of " + alerts + " kept");
			}
		}
		return problems;
	}

	/** The plates to fit: each warning alone, and every warning a pilot can have at once with every note too. */
	private static List<List<CockpitHud.Row>> plateCases() {
		List<PodStatusHud.Line> alerts = List.of(line("burning"), line("stranded"), line("crust_ahead", 7, 20, 24), line("crust_in", 20, 24), line("brace_waits"), line("brace_dry"),
				line("brace_dear"), line("seepage", 3), line("seepage_beside", "E S"), line("seepage_bleeding"), line("lining_dry"), line("hard_landing"), line("lava_near", 6),
				line("lava_above", 6), line("glaze_holding", 12), line("glaze_spent"));
		List<PodStatusHud.Line> notes = List.of(line("slag", 12, 8), line("liner", 3), line("lining", 5));
		PodStatusHud.Line tally = line("tally_hull", 100, 100);
		List<CockpitHud.Row> head = List.of(new CockpitHud.Row(List.of(tally, line("tally_fuel", 78), line("tally_cargo", 24), line("tally_depth", -420))),
				CockpitHud.Row.of(new PodStatusHud.Line(Component.literal("Scanner Player0  $12,345"), 0)));
		List<List<CockpitHud.Row>> cases = new ArrayList<>();
		for (PodStatusHud.Line alert : alerts) {
			cases.add(rowsOf(head, List.of(alert)));
		}
		cases.add(rowsOf(head, Stream.concat(Stream.of(alerts.get(0), alerts.get(12), alerts.get(2), alerts.get(4), alerts.get(8), alerts.get(11)), notes.stream()).toList()));
		return cases;
	}

	private static List<CockpitHud.Row> rowsOf(List<CockpitHud.Row> head, List<PodStatusHud.Line> lines) {
		List<CockpitHud.Row> rows = new ArrayList<>(head);
		lines.forEach(line -> rows.add(CockpitHud.Row.of(line)));
		return rows;
	}

	private static PodStatusHud.Line line(String name, Object... args) {
		return new PodStatusHud.Line(Component.translatable("hud.deepcharter.pod." + name, args), 0);
	}

	private static int[] box(PanelLook.Decal decal, int guiWidth, int guiHeight) {
		int left = decal.left(guiWidth);
		int top = decal.top(guiHeight);
		return new int[] {left, top, left + decal.width(), top + decal.height()};
	}

	private static boolean inside(int[] inner, int[] outer) {
		return outer[0] <= inner[0] && outer[1] <= inner[1] && inner[2] <= outer[2] && inner[3] <= outer[3];
	}

	private static boolean overlap(int[] a, int[] b) {
		return a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3];
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
