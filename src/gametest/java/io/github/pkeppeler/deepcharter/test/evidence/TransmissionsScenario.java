package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.transmission.TransmissionOverlay;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.transmission.Transmission;
import io.github.pkeppeler.deepcharter.transmission.Transmissions;

/**
 * Evidence scenario "m2-transmissions": a charter's player drops through the first breach. The rumble and the fade play, and under
 * them the CRT overlay types the breach transmission (live, green header). Arriving in the top zone of layer 2 brings a second one from
 * an unknown sender (red header), and a repair relayed by the colony beacon (amber header) is fired after them. Each has the charter's name
 * and the Director's name filled in.
 */
public class TransmissionsScenario extends EvidenceScenario {
	private static final double X = 2600.5;
	private static final double Z = 2600.5;
	private static final String CHARTER_NAME = "Deep Charter Co.";
	private static final int PATIENCE = 1500;
	/** Frames recorded once a transmission has finished typing, before the recording skips its hold. */
	private static final int TAIL_FRAMES = 10;
	private static final int SETTLE_TICKS = 40;

	@Override
	protected String name() {
		return "m2-transmissions";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			context.waitFor(client -> client.player != null && client.level != null);
			CharterId charter = singleplayer.getServer().computeOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				player.setPermanentlyInvulnerable(true);
				if (Charters.found(server, player.getUUID(), CHARTER_NAME).isPresent()) {
					throw new AssertionError("founding the charter should succeed");
				}
				// Generate the arrival area in layer 2 now so the client has less to wait for after the crossing.
				ServerLevel two = server.getLevel(LayerChain.dimension(2));
				for (int dx = -2; dx <= 2; dx++) {
					for (int dz = -2; dz <= 2; dz++) {
						two.getChunk((int) X / 16 + dx, (int) Z / 16 + dz);
					}
				}
				ServerLevel one = server.getLevel(LayerChain.dimension(1));
				player.teleportTo(one, X, one.getMinY() + 40, Z, Set.of(), 0, 0, true);
				return Charters.charterOf(server, player.getUUID()).orElseThrow().id();
			});
			context.waitFor(client -> client.level.dimension().equals(LayerChain.dimension(1)));
			context.waitTicks(SETTLE_TICKS);

			singleplayer.getServer().runOnServer(server -> {
				ServerLevel one = server.getLevel(LayerChain.dimension(1));
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				// One block under the floor of layer 1, so it crosses on the next server tick.
				player.teleportTo(one, X, one.getMinY() - 1, Z, Set.of(), 0, 0, true);
			});

			Set<Identifier> shot = new HashSet<>();
			boolean relayFired = false;
			Optional<Identifier> last = Optional.empty();
			int tail = 0;
			boolean finished = false;
			for (int tick = 0; tick < PATIENCE && !finished; tick++) {
				Optional<Identifier> current = context.computeOnClient(client -> TransmissionOverlay.transmission().map(Transmission::id));
				boolean typed = context.computeOnClient(client -> TransmissionOverlay.typed());
				tail = typed && current.equals(last) ? tail + 1 : 0;
				last = current;
				// Vanilla covers the HUD with a "Loading terrain" screen while the client waits for the new layer's chunks: leave those out.
				if (tail < TAIL_FRAMES && context.computeOnClient(client -> client.gui.screen() == null)) {
					frame(context);
				}
				if (typed && current.isPresent() && shot.add(current.get())) {
					screenshot(context, current.get().getPath());
				}
				if (typed && !relayFired && current.equals(Optional.of(id("t05")))) {
					relayFired = true;
					singleplayer.getServer().runOnServer(server -> Transmissions.fire(server, charter, id("t02")));
				}
				finished = typed && current.equals(Optional.of(id("t02"))) && tail >= TAIL_FRAMES;
				context.waitTick();
			}
			if (!finished) {
				throw new AssertionError("The transmissions t05, t06 and t02 should all have typed out, only " + shot + " did");
			}
		}
	}

	private static Identifier id(String name) {
		return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, name);
	}
}
