package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.layer.CampaignBands;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Evidence scenario "claim-scanner-descent" (#1277): the pilot's view of a pod with a tier 2 scanner going down a bore
 * through layer 1, The Claim, from the top of its rock to the last live rows over the floor's signal decay. The bore is
 * cut first, as the drill would cut it, and the pod is held at each step of the way down; a frame every two rows shows
 * the scanner's slice: the caves that open up, and the bands of Topsoil Claims' hardpan, Stone Benches' packed shale
 * and Deep Claim's black shale passing up the panel. A still at the middle of each zone.
 */
public class ClaimScannerDescentScenario extends EvidenceScenario {
    private static final int X = 3000;
    private static final int Z = 3000;
    private static final int TIER = 2;
    private static final int STEP = 2;
    /**
     * The pod this scenario holds, and the Y it is held at; Fabric events cannot be unregistered, so the pin acts only
     * on this pod.
     */
    private static final AtomicReference<@Nullable UUID> HELD = new AtomicReference<>();

    private static final AtomicReference<Double> HELD_Y = new AtomicReference<>(0.0);
    private static boolean pinned;

    @Override
    protected String name() {
        return "claim-scanner-descent";
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
            // The pilot arrives over the open shaft only now, held in the air until the pod holds it: the far teleport
            // must land (ClientWait.arrived) before the mount.
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.teleportTo(TestLayers.level(server, 1), X + 1.0, top, Z + 1.0, Set.of(), 0f, 0f, true);
                player.getAbilities().mayfly = true;
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
                player.setDeltaMovement(Vec3.ZERO);
            });
            ClientWait.arrived(context, singleplayer.getServer());
            ScannerHudTest.mountFirstPlayer(singleplayer.getServer(), TIER);
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                HELD_Y.set((double) top);
                HELD.set(TestEntities.vehicle(player, PodEntity.class).getUUID());
                player.getAbilities().mayfly = false;
                player.getAbilities().flying = false;
                player.onUpdateAbilities();
            });
            try {
                ClientChecks.withHudShown(context, () -> descend(context, top, bottom));
            } finally {
                HELD.set(null);
            }
        }
    }

    private void descend(ClientGameTestContext context, int top, int bottom) {
        CampaignBands.Band band = CampaignBands.LAYER_1;
        // A still in each zone: under Topsoil Claims' hardpan, in the middle of Stone Benches, and at the last live row
        // of the Deep Claim.
        int topsoil = Zones.span(band.minY(), band.height(), 0).low() + 14;
        Zones.Span benches = Zones.span(band.minY(), band.height(), 1);
        int[] stills = {topsoil, (benches.low() + benches.high()) / 2, bottom};
        String[] names = {"scanner-topsoil-claims", "scanner-stone-benches", "scanner-deep-claim"};
        for (int y = top; y >= bottom; y -= STEP) {
            double target = y;
            HELD_Y.set(target);
            ClientWait.until(
                    context,
                    "the pod held at y=" + y,
                    client -> client.player != null
                            && client.player.getVehicle() instanceof PodEntity pod
                            && Math.abs(pod.getY() - target) < 0.1);
            context.waitTicks(2 * ScannerTuning.DEFAULT.rescanTicks() + 2);
            frame(context);
            for (int i = 0; i < stills.length - 1; i++) {
                if (y <= stills[i] && y > stills[i] - STEP) {
                    screenshot(context, names[i]);
                }
            }
        }
        screenshot(context, names[stills.length - 1]);
    }
}
