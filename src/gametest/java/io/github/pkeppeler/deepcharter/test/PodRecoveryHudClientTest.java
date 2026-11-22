package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.repair.RecoveryTuning;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.PodReadout;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * Client GameTest for what the pilot of a stranded pod is told (#963). A pod that runs dry shows STRANDED and, under it, the Company
 * winch crew's countdown with its fee, read from the pod's synced clock. Fuelling the pod takes both lines away.
 */
public class PodRecoveryHudClientTest implements FabricClientGameTest {
    private static final int FEET_Y = TestLayers.worldY(1, 10);
    private static final String STRANDED = "hud.deepcharter.pod.stranded";
    private static final String RECOVERY = "hud.deepcharter.pod.recovery";

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40);
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SURVIVAL);
                RoomCarver.carve(one, -3, 3, FEET_Y - 1, FEET_Y + 6, -3, 3, Blocks.AIR);
                player.teleportTo(one, 0, FEET_Y, 0, Set.of(), 0f, 0f, true);
                PodEntity pod = TestEntities.create(PodRegistry.POD, one);
                pod.setPos(0, FEET_Y, 0);
                pod.setNoGravity(true);
                one.addFreshEntity(pod);
                if (!player.startRiding(pod)) {
                    throw new AssertionError("the player could not mount the pod");
                }
                // The last sliver of fuel: the pod burns it and strands itself.
                pod.setFuel(0.01f);
            });
            ClientWait.until(
                    context,
                    "the stranded pod's readout names the winch crew",
                    client -> ClientSession.player(client).getVehicle() instanceof PodEntity pod
                            && pod.stranded()
                            && keys(client, pod).contains(RECOVERY));
            List<Object> shown = context.computeOnClient(client -> Arrays.asList(argsOf(
                    PodReadout.texts(client, TestEntities.vehicle(ClientSession.player(client), PodEntity.class)))));
            int seconds = (Integer) shown.get(0);
            if (seconds < 1 || seconds > RecoveryTuning.DEFAULT.delayTicks() / 20) {
                throw new AssertionError("the countdown reads whole seconds of the minute, shows " + seconds);
            }
            if (!Long.valueOf(RecoveryTuning.DEFAULT.fee()).equals(shown.get(1))) {
                throw new AssertionError(
                        "the line names the fee of " + RecoveryTuning.DEFAULT.fee() + ", shows " + shown.get(1));
            }

            singleplayer.getServer().runOnServer(server -> {
                PodEntity pod =
                        TestEntities.vehicle(server.getPlayerList().getPlayers().getFirst(), PodEntity.class);
                pod.setFuel(50f);
                pod.setStranded(false);
            });
            ClientWait.until(
                    context,
                    "a pod with fuel again shows neither line",
                    client -> ClientSession.player(client).getVehicle() instanceof PodEntity pod
                            && !pod.stranded()
                            && !keys(client, pod).contains(STRANDED)
                            && !keys(client, pod).contains(RECOVERY));
        }
    }

    private static List<String> keys(Minecraft client, PodEntity pod) {
        return PodReadout.texts(client, pod).stream()
                .filter(text -> text.getContents() instanceof TranslatableContents)
                .map(text -> ((TranslatableContents) text.getContents()).getKey())
                .toList();
    }

    /** The arguments of the recovery line of {@code texts}. */
    private static Object[] argsOf(List<Component> texts) {
        return texts.stream()
                .map(Component::getContents)
                .filter(contents -> contents instanceof TranslatableContents)
                .map(contents -> (TranslatableContents) contents)
                .filter(contents -> contents.getKey().equals(RECOVERY))
                .findFirst()
                .orElseThrow()
                .getArgs();
    }
}
