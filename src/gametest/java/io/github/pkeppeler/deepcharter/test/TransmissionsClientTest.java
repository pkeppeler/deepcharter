package io.github.pkeppeler.deepcharter.test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.imageio.ImageIO;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.layer.BreachEffects;
import io.github.pkeppeler.deepcharter.client.transmission.TransmissionOverlay;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;
import io.github.pkeppeler.deepcharter.transmission.Transmission;
import io.github.pkeppeler.deepcharter.transmission.TransmissionData;
import io.github.pkeppeler.deepcharter.transmission.TransmissionPayload;
import io.github.pkeppeler.deepcharter.transmission.Transmissions;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

/**
 * Client GameTest for #62. The overlay shows the transmission of a breach crossing over the fade, with {@code [CHARTER]} and
 * {@code [DIRECTOR]} filled in and the header in the colour of its framing. Transmissions fired for a charter reach the real client in
 * order, one after another, and those fired while it was offline are delivered when it logs in again.
 */
public class TransmissionsClientTest implements FabricClientGameTest {
	private static final String CREW_NAME = "Overlay Crew";
	private static final String TWO_PLAYER_NAME = "Two Player Overlay";
	private static final String MOCK_NAME = "MockPilot";
	private static final double X = 2400.5;
	private static final double Z = 2400.5;
	/** A fuse on client ticks for a wait that has no other limit. About 3 minutes at 20 ticks a second. */
	private static final int CLIENT_TICK_FUSE = 3600;
	/** Player count once the client has dropped: the mock stays online. */
	private static final int MOCK_ONLY = 1;

	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);
		aCrossingShowsItsTransmissionOverTheFade(context);
		transmissionsReachTheClientInOrderAndOnLogin(context);
	}

	/** The overlay's state, for a wait's failure message. */
	private static String seen() {
		return "overlay transmission " + TransmissionOverlay.transmission().map(Transmission::id) + ", active " + TransmissionOverlay.active()
				+ ", typed " + TransmissionOverlay.typed() + ", header '" + TransmissionOverlay.headerShown() + "'";
	}

	private static Identifier id(String name) {
		return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, name);
	}

	/** A real crossing brings t05 to the charter's player, with the fields filled, and the text is drawn over the black of the fade. */
	private static void aCrossingShowsItsTransmissionOverTheFade(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			ClientWait.until(context, "the client in the world", client -> client.player != null && client.level != null);
			String player = singleplayer.getServer().computeOnServer(server -> {
				ServerPlayer first = server.getPlayerList().getPlayers().getFirst();
				first.setPermanentlyInvulnerable(true);
				require(Charters.found(server, first.getUUID(), CREW_NAME).isEmpty(), "founding should succeed");
				// Generate the arrival area in layer 2 now so the crossing does not wait on it.
				ServerLevel two = server.getLevel(LayerChain.dimension(2));
				for (int dx = -2; dx <= 2; dx++) {
					for (int dz = -2; dz <= 2; dz++) {
						two.getChunk((int) X / 16 + dx, (int) Z / 16 + dz);
					}
				}
				ServerLevel one = server.getLevel(LayerChain.dimension(1));
				first.teleportTo(one, X, one.getMinY() - 1, Z, Set.of(), 0, 0, true);
				return first.getGameProfile().name();
			});

			ClientWait.until(context, "transmission t05 on the overlay", client -> TransmissionOverlay.transmission().map(Transmission::id).equals(Optional.of(id("t05"))), client -> seen());
			String body = context.computeOnClient(client -> TransmissionOverlay.bodyFull());
			require(!body.contains("[CHARTER]") && !body.contains("[DIRECTOR]") && body.contains(CREW_NAME) && body.contains(player), "The charter and Director fields should be filled with '" + CREW_NAME + "' and '" + player + "', the text is: " + body);
			require(context.computeOnClient(client -> TransmissionOverlay.headerFull()).equals("> LIVE FROM THE EMPLOYER"), "t05 is live from the employer, the header is: " + context.computeOnClient(client -> TransmissionOverlay.headerFull()));
			ClientWait.until(context, "the transmission typed out", client -> TransmissionOverlay.typed(), client -> seen());
			require(context.computeOnClient(client -> TransmissionOverlay.bodyShown().equals(TransmissionOverlay.bodyFull())), "A typed transmission shows its whole text");
			require(!(greenPixels(context.takeScreenshot("transmission-overlay")) < 50), "The overlay should draw its text on screen");

			// The breach fade keeps running under the text: at its black plateau the text is the only bright thing on screen.
			context.runOnClient(client -> {
				TransmissionOverlay.reset();
				BreachEffects.begin();
				TransmissionOverlay.enqueue(new TransmissionPayload(id("t01"), CREW_NAME, player));
			});
			ClientWait.until(context, "the breach fade done with the transmission header shown", client -> BreachEffects.fadeAlpha(0f) >= 1f && TransmissionOverlay.active() && !TransmissionOverlay.headerShown().isEmpty(), client -> seen());
			require(!(greenPixels(context.takeScreenshot("transmission-over-fade")) < 20), "The transmission should be drawn over the black of the fade, not under it");
			context.runOnClient(client -> {
				BreachEffects.reset();
				TransmissionOverlay.reset();
			});

			// A transmission the data file does not list must be dropped, not thrown from the packet handler, which would disconnect the player.
			context.runOnClient(client -> TransmissionOverlay.enqueue(new TransmissionPayload(id("t99"), CREW_NAME, player)));
			require(!context.computeOnClient(client -> TransmissionOverlay.waiting() != 0 || TransmissionOverlay.active()), "An unknown transmission should be dropped");
		}
	}

	/**
	 * The mock player founds the charter and the real client joins it. A live transmission arrives, typed in the colour of its framing.
	 * A second one waits behind it. Then the client disconnects while the mock stays online, two transmissions fire while it is away, and
	 * its own login alone delivers them, from where it left off.
	 */
	private static void transmissionsReachTheClientInOrderAndOnLogin(ClientGameTestContext context) {
		try (TwoPlayerServer two = TwoPlayerServer.start(context)) {
			UUID mock = two.mock().player().getUUID();
			CharterId charter = two.server().computeOnServer(server -> {
				require(Charters.found(server, mock, TWO_PLAYER_NAME).isEmpty(), "founding should succeed");
				return Charters.charterOfOrThrow(server, mock).orElseThrow().id();
			});
			UUID real = two.server().computeOnServer(server -> server.getPlayerList().getPlayers().stream()
					.map(player -> player.getUUID()).filter(uuid -> !uuid.equals(mock)).findFirst().orElseThrow());
			two.server().runOnServer(server -> {
				require(Charters.apply(server, real, charter).isEmpty() && Charters.approve(server, mock, real).isEmpty(), "applying and approving should succeed");
			});

			// Two at once: the first types out, the second waits for it.
			two.server().runOnServer(server -> {
				Transmissions.fire(server, charter, id("t01"));
				Transmissions.fire(server, charter, id("t06"));
			});
			ClientWait.until(context, "transmission t01 on the overlay", client -> TransmissionOverlay.transmission().map(Transmission::id).equals(Optional.of(id("t01"))), client -> seen());
			require(context.computeOnClient(client -> TransmissionOverlay.waiting()) == 1, "The second transmission should wait behind the first");
			String body = context.computeOnClient(client -> TransmissionOverlay.bodyFull());
			require(body.contains(TWO_PLAYER_NAME) && body.contains(MOCK_NAME) && !body.contains("[CHARTER]") && !body.contains("[DIRECTOR]"), "The Director is " + MOCK_NAME + " and the charter " + TWO_PLAYER_NAME + ", the text is: " + body);
			ClientWait.until(context, "the transmission typed out", client -> TransmissionOverlay.typed(), client -> seen());
			require(!(greenPixels(context.takeScreenshot("transmission-live")) < 50), "A live transmission is typed in phosphor green");
			ClientWait.until(context, "transmission t06 on the overlay", client -> TransmissionOverlay.transmission().map(Transmission::id).equals(Optional.of(id("t06"))), client -> seen());
			ClientWait.until(context, "the transmission typed out", client -> TransmissionOverlay.typed(), client -> seen());
			require(!(redPixels(context.takeScreenshot("transmission-unknown")) < 20), "A transmission from an unknown sender has a red header");

			// Offline: the client leaves, the mock stays. The mock is sent these at once, the client when it logs in again.
			two.connection().close();
			ClientWait.until(context, "the client out of the world", client -> client.level == null);
			for (int tick = 0; two.server().computeOnServer(server -> server.getPlayerCount()) > MOCK_ONLY; tick++) {
				require(tick <= CLIENT_TICK_FUSE, "the server never dropped the disconnected client");
				context.waitTick();
			}
			List<Identifier> missed = two.server().computeOnServer(server -> {
				Transmissions.fire(server, charter, id("t07"));
				Transmissions.fire(server, charter, id("t09"));
				return TransmissionData.get(server).progress(charter).unsent(real);
			});
			require(missed.equals(List.of(id("t07"), id("t09"))), "The offline client has not been sent t07 and t09, in order: " + missed);

			try (var connection = two.server().connect()) {
				ClientWait.until(context, "transmission t07 on the overlay", client -> TransmissionOverlay.transmission().map(Transmission::id).equals(Optional.of(id("t07"))), client -> seen());
				ClientWait.until(context, "transmission t09 on the overlay", client -> TransmissionOverlay.transmission().map(Transmission::id).equals(Optional.of(id("t09"))), client -> seen());
				require(two.server().computeOnServer(server -> TransmissionData.get(server).progress(charter).unsent(real).isEmpty()), "Nothing should be left to send the client once the login has delivered it");
			}
		}
	}

	/** Pixels of the phosphor green of the text, 0x7CFC9A, give or take what the scanlines and scaling do to it. */
	private static long greenPixels(Path screenshot) {
		return countPixels(screenshot, (red, green, blue) -> near(red, 0x7C) && near(green, 0xFC) && near(blue, 0x9A));
	}

	/** Pixels of the red of an unknown sender's header, 0xFF5A4F. */
	private static long redPixels(Path screenshot) {
		return countPixels(screenshot, (red, green, blue) -> near(red, 0xFF) && near(green, 0x5A) && near(blue, 0x4F));
	}

	private static boolean near(int channel, int expected) {
		return Math.abs(channel - expected) <= 12;
	}

	private interface Colour {
		boolean matches(int red, int green, int blue);
	}

	private static long countPixels(Path screenshot, Colour colour) {
		try {
			BufferedImage image = ImageIO.read(screenshot.toFile());
			long count = 0;
			for (int x = 0; x < image.getWidth(); x++) {
				for (int y = 0; y < image.getHeight(); y++) {
					int argb = image.getRGB(x, y);
					if (colour.matches((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF)) {
						count++;
					}
				}
			}
			return count;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
