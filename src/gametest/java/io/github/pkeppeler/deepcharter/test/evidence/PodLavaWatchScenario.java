package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.client.pod.CockpitHud;
import io.github.pkeppeler.deepcharter.client.pod.PodStatusHud;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientPacks;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestPacks;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * Evidence scenario "pod-lava-watch" (#454): a pilot sits in a pod that faces down a stone tunnel. At the far end of the tunnel, 9 blocks off and past the 3 blocks
 * the liner sees, lava pours out of the wall, falls to the floor and runs toward the pod. The plain HUD shows "LAVA IN THE SHAFT 6 OFF  LINE OR LEAVE" while the lava glows
 * dead ahead, and the Ribbon cockpit (the mod's own look, #247) shows the same line and lights its warning lamp. The first stills are shot with the cockpit off, a test pack. The hull is untouched at every still.
 */
public class PodLavaWatchScenario extends EvidenceScenario {
    private static final int X = 5600;
    private static final int Z = 5600;
    private static final int FLOOR_Y = 100;
    private static final int TUNNEL = 9;
    private static final int TICKS_PER_FRAME = 4;
    private static final int MAX_TICKS = 2400;
    private static final float FACING_EAST = -90f;

    @Override
    protected String name() {
        return "pod-lava-watch";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40);
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = ClientLight.serverLevel(server, LayerChain.dimension(1));
                build(one);
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SURVIVAL);
                player.teleportTo(one, X, FLOOR_Y, Z, Set.of(), FACING_EAST, 0f, true);
                PodEntity pod = TestEntities.create(PodRegistry.POD, one);
                pod.setPos(X, FLOOR_Y, Z);
                pod.setYRot(FACING_EAST);
                one.addFreshEntity(pod);
                if (!player.startRiding(pod)) {
                    throw new AssertionError("the player could not mount the pod");
                }
            });
            ClientWait.until(
                    context,
                    "the pilot seated in the pod in layer 1",
                    client -> client.player != null
                            && client.player.getVehicle() instanceof PodEntity
                            && ClientLight.clientLevel(client).dimension().equals(LayerChain.dimension(1)));
            context.runOnClient(client -> {
                client.options.setCameraType(CameraType.FIRST_PERSON);
                ClientSession.player(client).setXRot(0f);
            });
            ClientPacks.enable(context, TestPacks.COCKPIT_OFF);
            context.waitTicks(60);
            for (int i = 0; i < 4; i++) {
                frame(context);
                context.waitTicks(TICKS_PER_FRAME);
            }
            screenshot(context, "pod-lava-watch-clear");

            // Two lava sources in the end wall at head height: they fall to the floor and run west along the tunnel.
            singleplayer.getServer().runOnServer(server -> {
                for (int z = Z - 1; z <= Z; z++) {
                    ClientLight.serverLevel(server, LayerChain.dimension(1))
                            .setBlock(new BlockPos(X + TUNNEL, FLOOR_Y + 2, z), Blocks.LAVA.defaultBlockState(), 3);
                }
            });
            waitForWarning(context, "the plain HUD's lava line");
            for (int i = 0; i < 20; i++) {
                frame(context);
                context.waitTicks(TICKS_PER_FRAME);
            }
            screenshot(context, "pod-lava-watch-plain");

            ClientPacks.disable(context, TestPacks.COCKPIT_OFF);
            ClientWait.until(
                    context,
                    "the ribbon cockpit",
                    client -> CockpitHud.seated(client).isPresent());
            boolean lamp = context.computeOnClient(client -> CockpitHud.lampsLit(
                    false,
                    false,
                    false,
                    PodStatusHud.readout(
                            ClientSession.player(client),
                            TestEntities.vehicle(ClientSession.player(client), PodEntity.class)))[3]);
            if (!lamp) {
                throw new AssertionError("The cockpit's warning lamp should be lit by the lava line");
            }
            for (int i = 0; i < 6; i++) {
                frame(context);
                context.waitTicks(TICKS_PER_FRAME);
            }
            screenshot(context, "pod-lava-watch-ribbon");

            float hull = singleplayer
                    .getServer()
                    .computeOnServer(server -> TestEntities.vehicle(
                                    server.getPlayerList().getPlayers().getFirst(), PodEntity.class)
                            .hull());
            float maxHull = singleplayer
                    .getServer()
                    .computeOnServer(server -> TestEntities.vehicle(
                                    server.getPlayerList().getPlayers().getFirst(), PodEntity.class)
                            .maxHull());
            if (hull < maxHull) {
                throw new AssertionError(
                        "The warning should come before any hull is lost, the hull is " + hull + " of " + maxHull);
            }
        }
    }

    private void waitForWarning(ClientGameTestContext context, String what) {
        int ticks = 0;
        while (!context.computeOnClient(client -> ClientSession.player(client).getVehicle() instanceof PodEntity pod
                && PodStatusHud.lavaNearLine(pod).isPresent())) {
            if (ticks >= MAX_TICKS) {
                throw new AssertionError("Waited " + MAX_TICKS + " ticks for " + what);
            }
            context.waitTicks(TICKS_PER_FRAME);
            ticks += TICKS_PER_FRAME;
            frame(context);
        }
    }

    /** A stone block with a tunnel of air east from the pod, lit by lamps in the rock; the scenario places the lava in its end wall. */
    private static void build(ServerLevel level) {
        RoomCarver.carve(level, X - 4, X + TUNNEL + 4, FLOOR_Y - 4, FLOOR_Y + 6, Z - 4, Z + 4, Blocks.STONE);
        RoomCarver.carve(level, X - 2, X + TUNNEL, FLOOR_Y, FLOOR_Y + 2, Z - 1, Z, Blocks.AIR);
        for (int x = X + 3; x < X + TUNNEL - 1; x += 3) {
            level.setBlock(new BlockPos(x, FLOOR_Y + 3, Z), Blocks.GLOWSTONE.defaultBlockState(), 3);
        }
    }
}
