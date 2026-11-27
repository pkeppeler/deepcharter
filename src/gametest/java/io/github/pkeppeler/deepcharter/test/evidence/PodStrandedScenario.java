package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.PodReadout;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * Evidence scenario "pod-stranded" (#1430): a pilot with no fuel item and no money runs dry at the bottom of a shaft in layer 1. The
 * pod goes dark and the readout says STRANDED, with no countdown and no fee under it: the Company sends no crew (ADR 0104). Longer
 * after the old crew's minute, the pod is still dark at the bottom of the shaft.
 */
public class PodStrandedScenario extends EvidenceScenario {
    private static final int X = 5800;
    private static final int Z = 5800;
    private static final int FLOOR_Y = LIVE_GAUGES_FLOOR_Y;
    private static final int SHAFT_BLOCKS = 30;
    private static final int FRAME_TICKS = 5;
    private static final int STRANDED_FRAMES = 12;
    /** Past the old crew's minute (1,200 ticks), with a margin. */
    private static final int PAST_THE_OLD_WAIT_TICKS = 1_400;

    @Override
    protected String name() {
        return "pod-stranded";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40);
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                RoomCarver.carve(
                        one,
                        new BlockPos(X - 2, FLOOR_Y - 2, Z - 2),
                        new BlockPos(X + 2, FLOOR_Y, Z + 2),
                        Blocks.STONE.defaultBlockState());
                RoomCarver.carve(
                        one,
                        new BlockPos(X - 1, FLOOR_Y + 1, Z - 1),
                        new BlockPos(X + 1, FLOOR_Y + SHAFT_BLOCKS, Z + 1),
                        Blocks.AIR.defaultBlockState());
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SURVIVAL);
                player.teleportTo(one, X + 0.5, FLOOR_Y + 1, Z + 0.5, Set.of(), 0, 0, true);
            });
            ClientWait.until(
                    context,
                    "the player in layer 1",
                    client -> client.player != null && TestLayers.clientIsIn(client, 1));
            context.waitTicks(30);
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                PodEntity pod = TestEntities.create(PodRegistry.POD, one);
                pod.setPos(X + 0.5, FLOOR_Y + 1, Z + 0.5);
                one.addFreshEntity(pod);
                if (!player.startRiding(pod)) {
                    throw new AssertionError("the player could not mount the pod");
                }
                // The last sliver of fuel: the pod burns it and strands itself.
                pod.setFuel(0.01f);
            });
            ClientWait.until(
                    context,
                    "the stranded pod's readout says STRANDED",
                    client -> ClientSession.player(client).getVehicle() instanceof PodEntity pod
                            && pod.stranded()
                            && PodReadout.texts(client, pod).stream()
                                    .anyMatch(text -> text.getContents() instanceof TranslatableContents contents
                                            && contents.getKey().equals("hud.deepcharter.pod.stranded")));
            screenshot(context, "stranded-start");
            for (int i = 0; i < STRANDED_FRAMES; i++) {
                context.waitTicks(FRAME_TICKS);
                frame(context);
            }
            screenshot(context, "stranded-no-countdown");

            // Longer than the old crew waited: nobody comes, and the pod stays dark where it stopped.
            context.waitTicks(PAST_THE_OLD_WAIT_TICKS);
            ClientWait.until(
                    context,
                    "the pod still stranded in layer 1 after the old crew's minute",
                    client -> client.player != null
                            && TestLayers.clientIsIn(client, 1)
                            && client.player.getVehicle() instanceof PodEntity pod
                            && pod.stranded());
            for (int i = 0; i < STRANDED_FRAMES; i++) {
                context.waitTicks(FRAME_TICKS);
                frame(context);
            }
            screenshot(context, "still-stranded-after-a-minute");
        }
    }
}
