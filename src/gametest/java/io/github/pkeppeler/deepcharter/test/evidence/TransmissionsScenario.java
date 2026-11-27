package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.transmission.TransmissionOverlay;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.transmission.Transmission;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Evidence scenario "m2-transmissions": the script's first transmissions in their canon framing. Founding the charter brings Personnel's
 * welcome (T01, live, green header, the charter's name filled in). Standing in the Deep Claim brings a fragment from an unknown source
 * (T10, red header, the signal lost). The player then drops through the first breach: the rumble and the fade play, and under them the
 * CRT overlay types T11. Arriving in the Upper Levels of layer 2 brings a dead crew's relay (T12, amber header, its pod and loop
 * counter). Each closes with its framing's last line.
 */
public class TransmissionsScenario extends EvidenceScenario {
    private static final double X = 2600.5;
    private static final double Z = 2600.5;
    private static final String CHARTER_NAME = "Deep Charter Co.";
    private static final int PATIENCE = 4000;
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
            awaitWorld(context);
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setPermanentlyInvulnerable(true);
                if (Charters.found(server, player.getUUID(), CHARTER_NAME).isPresent()) {
                    throw new AssertionError("founding the charter should succeed");
                }
                // Generate the arrival area in layer 2 now so the client has less to wait for after the crossing.
                ServerLevel two = TestLayers.level(server, 2);
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        two.getChunk((int) X / 16 + dx, (int) Z / 16 + dz);
                    }
                }
                ServerLevel one = TestLayers.level(server, 1);
                // In the Deep Claim, the bottom third of layer 1, whose transmission is T10.
                player.teleportTo(one, X, TestLayers.band(one, 1).minY() + 40, Z, Set.of(), 0, 0, true);
            });
            ClientWait.until(context, "the client in layer 1", client -> TestLayers.clientIsIn(client, 1));
            context.waitTicks(SETTLE_TICKS);

            Set<Identifier> shot = new HashSet<>();
            boolean crossed = false;
            Optional<Identifier> last = Optional.empty();
            int tail = 0;
            boolean finished = false;
            for (int tick = 0; tick < PATIENCE && !finished; tick++) {
                Optional<Identifier> current = context.computeOnClient(
                        client -> TransmissionOverlay.transmission().map(Transmission::id));
                boolean typed = context.computeOnClient(client -> TransmissionOverlay.typed());
                tail = typed && current.equals(last) ? tail + 1 : 0;
                last = current;
                // Vanilla covers the HUD with a "Loading terrain" screen while the client waits for the new layer's
                // chunks: leave those out.
                if (tail < TAIL_FRAMES && context.computeOnClient(client -> client.gui.screen() == null)) {
                    frame(context);
                }
                if (typed && current.isPresent() && shot.add(current.get())) {
                    screenshot(context, current.get().getPath());
                }
                if (typed && !crossed && current.equals(Optional.of(id("t10")))) {
                    crossed = true;
                    singleplayer.getServer().runOnServer(server -> {
                        ServerLevel one = TestLayers.level(server, 1);
                        ServerPlayer player =
                                server.getPlayerList().getPlayers().getFirst();
                        // One block under the floor of layer 1, so it crosses on the next server tick.
                        player.teleportTo(one, X, TestLayers.band(one, 1).minY() - 1, Z, Set.of(), 0, 0, true);
                    });
                }
                finished = typed && current.equals(Optional.of(id("t12"))) && tail >= TAIL_FRAMES;
                context.waitTick();
            }
            if (!finished) {
                throw new AssertionError(
                        "The transmissions t01, t10, t11 and t12 should all have typed out, only " + shot + " did");
            }
        }
    }

    private static Identifier id(String name) {
        return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, name);
    }
}
