package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.client.transmission.TransmissionOverlay;
import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.Zones;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.scanner.ScannerTuning;
import io.github.pkeppeler.deepcharter.test.ScannerHudTest;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Evidence scenario "rock-gradient" (#1551): the long gradient of layer 1's rock on the scanner. A bore is cut down
 * through layer 1 and a pod is held at a row every {@value #STRIDE} rows from over the hardpan's gradient to under
 * the packed shale's, first with a tier 1 scanner (a slice of a few dozen rows, in which the colour barely changes)
 * and then with the top tier (a wide view, in which it drifts from the hardpan's olive through the packed shale's rust
 * to the black shale's cold blue). A still at each row for each tier, and a frame for the recording.
 */
public class RockGradientScenario extends EvidenceScenario {
    private static final int X = 3000;
    private static final int Z = 3000;
    private static final int STRIDE = 32;
    /** Rows over the first contact's gradient where the walk starts: its top is half a gradient over the contact. */
    private static final int START_OVER_CONTACT = LayerBlocks.CLAIM_GRADIENT_LENGTH / 2 + STRIDE / 2;

    private static final AtomicReference<@Nullable UUID> HELD = new AtomicReference<>();
    private static final AtomicReference<Double> HELD_Y = new AtomicReference<>(0.0);
    private static boolean pinned;

    @Override
    protected String name() {
        return "rock-gradient";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        if (!pinned) {
            pinned = true;
            PodEvents.AFTER_TICK.register(pod -> {
                if (pod.getUUID().equals(HELD.get())) {
                    pod.setPos(X + 1.0, HELD_Y.get(), Z + 1.0);
                    pod.setDeltaMovement(Vec3.ZERO);
                }
            });
        }
        CampaignBands.Band band = CampaignBands.LAYER_1;
        int bottom = LIVE_GAUGES_FLOOR_Y;
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            awaitWorld(context);
            int top = singleplayer.getServer().computeOnServer(server -> {
                ServerLevel level = TestLayers.level(server, 1);
                int rock = band.topExclusive() - 8;
                while (level.getBlockState(new BlockPos(X, rock, Z)).isAir() && rock > bottom) {
                    rock--;
                }
                RoomCarver.carve(
                        level,
                        new BlockPos(X, bottom - 1, Z),
                        new BlockPos(X + 1, rock + 2, Z + 1),
                        Blocks.AIR.defaultBlockState());
                return rock;
            });
            ClientWait.lightSettled(context, singleplayer.getServer(), 1, X, Z, 1);
            int[] tiers = {1, ComponentTrack.SCANNER.maxTier()};
            for (int i = 0; i < tiers.length; i++) {
                if (i > 0) {
                    ScannerHudTest.leavePod(context, singleplayer.getServer());
                }
                ride(context, singleplayer.getServer(), top, tiers[i]);
                try {
                    int tier = tiers[i];
                    ClientChecks.withHudShown(context, () -> walk(context, band, bottom, tier));
                } finally {
                    HELD.set(null);
                }
            }
        }
    }

    /**
     * Mounts the first player on a new pod with a scanner of {@code tier} over the open shaft and holds it at
     * {@code top}.
     */
    private static void ride(ClientGameTestContext context, TestServerContext server, int top, int tier) {
        server.runOnServer(minecraftServer -> {
            ServerPlayer player = minecraftServer.getPlayerList().getPlayers().getFirst();
            player.teleportTo(TestLayers.level(minecraftServer, 1), X + 1.0, top, Z + 1.0, Set.of(), 0f, 0f, true);
            player.getAbilities().mayfly = true;
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
            player.setDeltaMovement(Vec3.ZERO);
        });
        ClientWait.arrived(context, server);
        ScannerHudTest.mountFirstPlayer(server, tier);
        server.runOnServer(minecraftServer -> {
            ServerPlayer player = minecraftServer.getPlayerList().getPlayers().getFirst();
            HELD_Y.set((double) top);
            HELD.set(TestEntities.vehicle(player, PodEntity.class).getUUID());
            player.getAbilities().mayfly = false;
            player.getAbilities().flying = false;
            player.onUpdateAbilities();
        });
    }

    private void walk(ClientGameTestContext context, CampaignBands.Band band, int bottom, int tier) {
        int firstContact = Zones.span(band.minY(), band.height(), 0).low() - LayerBlocks.CLAIM_CONTACT_UNDER_EDGE;
        int step = 0;
        for (int y = firstContact + START_OVER_CONTACT; y >= bottom; y -= STRIDE) {
            double target = y;
            HELD_Y.set(target);
            ClientWait.until(
                    context,
                    "the pod held at y=" + y,
                    client -> client.player != null
                            && client.player.getVehicle() instanceof PodEntity pod
                            && Math.abs(pod.getY() - target) < 0.1);
            context.waitTicks(2 * ScannerTuning.DEFAULT.rescanTicks() + 2);
            // A transmission or a toast that came on the way down would cover the scanner panel.
            context.runOnClient(client -> {
                TransmissionOverlay.reset();
                client.gui.toastManager().clear();
            });
            frame(context);
            screenshot(context, String.format("scanner-tier-%d-step-%d", tier, step));
            step++;
        }
    }
}
