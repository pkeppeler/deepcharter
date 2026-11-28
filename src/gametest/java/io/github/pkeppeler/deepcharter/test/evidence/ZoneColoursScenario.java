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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Evidence scenario "zone-colours" (#1551): the zone colours of layers 2 and 3 and their tint-only contacts on the
 * scanner. A bore is cut down through each layer, its walls cleared of lava and water, and a pod is held at the middle
 * of each zone and on each zone contact, first with a tier 1 scanner (a slice of a few dozen rows) and then with the
 * top tier (a wide view): layer 2's sharp line with its thin bed and its short gradient, layer 3's interbedding and its
 * long gradient. A still at each stop for each tier, and a frame for the recording.
 */
public class ZoneColoursScenario extends EvidenceScenario {
    /**
     * The bore's corner, a few blocks from the world spawn: its columns are generated already, where a bore far out
     * waits on a ring of new campaign columns, which the software renderer of a cloud container cannot outrun.
     */
    private static volatile int x;

    private static volatile int z;
    /** Blocks from the spawn to the bore, clear of the spawn's own column. */
    private static final int FROM_SPAWN = 8;
    /** Rows kept clear of the band's floor and roof, where the crusts and the open roof rows lie. */
    private static final int MARGIN = 4;

    private static final AtomicReference<@Nullable UUID> HELD = new AtomicReference<>();
    private static final AtomicReference<Double> HELD_Y = new AtomicReference<>(0.0);
    private static boolean pinned;

    @Override
    protected String name() {
        return "zone-colours";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        if (!pinned) {
            pinned = true;
            PodEvents.AFTER_TICK.register(pod -> {
                if (pod.getUUID().equals(HELD.get())) {
                    pod.setPos(x + 1.0, HELD_Y.get(), z + 1.0);
                    pod.setDeltaMovement(Vec3.ZERO);
                }
            });
        }
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            awaitWorld(context);
            singleplayer.getServer().runOnServer(server -> {
                BlockPos spawn = server.getRespawnData().globalPos().pos();
                x = spawn.getX() + FROM_SPAWN;
                z = spawn.getZ() + FROM_SPAWN;
            });
            for (int layer = 2; layer <= 3; layer++) {
                CampaignBands.Band band = CampaignBands.LAYERS.get(layer - 1);
                Block rock = layer == 2 ? LayerBlocks.GREEN_SHALE : LayerBlocks.BASALT_CORE;
                int at = layer;
                singleplayer.getServer().runOnServer(server -> bore(TestLayers.level(server, at), band, rock));
                ClientWait.lightSettled(context, singleplayer.getServer(), layer, x, z, 1);
                List<Integer> stops = stops(band);
                int[] tiers = {1, ComponentTrack.SCANNER.maxTier()};
                for (int tier : tiers) {
                    ride(context, singleplayer.getServer(), layer, stops.getFirst(), tier);
                    try {
                        ClientChecks.withHudShown(context, () -> walk(context, at, stops, tier));
                    } finally {
                        HELD.set(null);
                    }
                    ScannerHudTest.leavePod(context, singleplayer.getServer());
                }
            }
        }
    }

    /**
     * Cuts a two-block bore down through {@code band} and turns any lava or water in the ring round it into
     * {@code rock}, so nothing flows in while the pod is held.
     */
    private static void bore(ServerLevel level, CampaignBands.Band band, Block rock) {
        int low = band.minY() + MARGIN;
        int high = band.maxY() - MARGIN;
        for (int y = low; y <= high; y++) {
            for (int dx = -1; dx <= 2; dx++) {
                for (int dz = -1; dz <= 2; dz++) {
                    BlockPos pos = new BlockPos(x + dx, y, z + dz);
                    if (!level.getFluidState(pos).isEmpty()) {
                        level.setBlock(pos, rock.defaultBlockState(), 2);
                    }
                }
            }
        }
        RoomCarver.carve(
                level, new BlockPos(x, low, z), new BlockPos(x + 1, high, z + 1), Blocks.AIR.defaultBlockState());
    }

    /** The rows the pod stops at, top first: the middle of each zone, and each zone contact's centre between. */
    private static List<Integer> stops(CampaignBands.Band band) {
        List<Integer> out = new ArrayList<>();
        for (int zone = 0; zone < Zones.COUNT; zone++) {
            Zones.Span span = Zones.span(band.minY(), band.height(), zone);
            out.add((span.low() + span.high()) / 2);
            if (zone + 1 < Zones.COUNT) {
                out.add(span.low());
            }
        }
        return out;
    }

    /**
     * Mounts the first player on a new pod with a scanner of {@code tier} in the bore and holds it at {@code top}.
     */
    private static void ride(ClientGameTestContext context, TestServerContext server, int layer, int top, int tier) {
        server.runOnServer(minecraftServer -> {
            ServerPlayer player = minecraftServer.getPlayerList().getPlayers().getFirst();
            player.teleportTo(
                    TestLayers.level(minecraftServer, layer), x + 1.0, top, z + 1.0, Set.of(), 0f, 0f, true);
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

    private void walk(ClientGameTestContext context, int layer, List<Integer> stops, int tier) {
        for (int step = 0; step < stops.size(); step++) {
            double target = stops.get(step);
            HELD_Y.set(target);
            ClientWait.until(
                    context,
                    "the pod held at y=" + target,
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
            screenshot(context, String.format("layer-%d-tier-%d-step-%d", layer, tier, step));
        }
    }
}
