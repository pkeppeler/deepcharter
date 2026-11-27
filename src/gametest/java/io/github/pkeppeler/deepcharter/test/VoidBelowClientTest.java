package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.client.pod.PodStatusHud;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.pod.PodTuning;
import io.github.pkeppeler.deepcharter.pod.VoidBelow;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * Client GameTest for the drop on the crust warning (#1387): a pod 7 slabs above the crust, over a cave that a fall
 * from the last crust row lands hard in, reads the warning with the drop's price from the client's own blocks. Its
 * hull of 32 outlasts the crust's 24, so without the drop there would be no line at all.
 */
public class VoidBelowClientTest implements FabricClientGameTest {
    private static final int CRUST_ROWS = LayerTuning.DEFAULT.crustThickness();
    private static final int SLABS_AWAY = 7;
    private static final int FEET_Y = TestLayers.worldY(1, CRUST_ROWS + SLABS_AWAY);
    private static final float HULL = 32f;
    private static final int RADIUS = 3;
    /** {@link RoomCarver#carvePocketBelow}'s 8 rows of air and the bored last crust row. */
    private static final int POCKET_FALL = 9;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40);
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                int floor = TestLayers.band(one, 1).minY();
                RoomCarver.carve(
                        one, -RADIUS, RADIUS, floor, floor + CRUST_ROWS - 1, -RADIUS, RADIUS, LayerBlocks.BREACH_CRUST);
                RoomCarver.carvePocketBelow(one, 1, -RADIUS, RADIUS, -RADIUS, RADIUS);
                RoomCarver.carve(one, -RADIUS, RADIUS, floor + CRUST_ROWS, FEET_Y - 1, -RADIUS, RADIUS, Blocks.STONE);
                RoomCarver.carve(one, -RADIUS, RADIUS, FEET_Y, FEET_Y + 6, -RADIUS, RADIUS, Blocks.AIR);
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SURVIVAL);
                player.teleportTo(one, 0, FEET_Y, 0, Set.of(), 0f, 0f, true);
                PodEntity pod = TestEntities.create(PodRegistry.POD, one);
                pod.setPos(0, FEET_Y, 0);
                pod.setHull(HULL);
                one.addFreshEntity(pod);
                if (!player.startRiding(pod)) {
                    throw new AssertionError("the player could not mount the pod");
                }
            });
            long drop = Math.round(VoidBelow.dropCost(PodStats.base(), POCKET_FALL));
            Object[] expected = {
                SLABS_AWAY,
                Math.round(HULL),
                Math.round(CRUST_ROWS * PodTuning.DEFAULT.drill().crustHullDamage()),
                drop
            };
            ClientWait.until(
                    context,
                    "the warning with the drop's price on the pod in layer 1",
                    client -> client.player != null
                            && client.player.getVehicle() instanceof PodEntity pod
                            && TestLayers.clientIsIn(client, 1)
                            && Math.round(pod.hull()) == Math.round(HULL)
                            && PodStatusHud.crustLines(pod, true).size() == 1);
            List<Component> lines = context.computeOnClient(client ->
                    PodStatusHud.crustLines(TestEntities.vehicle(ClientSession.player(client), PodEntity.class), true));
            TranslatableContents contents =
                    (TranslatableContents) lines.getFirst().getContents();
            if (!contents.getKey().equals("hud.deepcharter.pod.crust_ahead_drop")
                    || !Arrays.equals(contents.getArgs(), expected)) {
                throw new AssertionError("The warning reads " + Arrays.toString(expected) + " under its drop key: "
                        + contents.getKey() + " " + Arrays.toString(contents.getArgs()));
            }
            String shown = context.computeOnClient(client -> lines.getFirst().getString());
            String wanted = String.format("CRUST %s DOWN  HULL %s, IT TAKES %s + DROP %s", expected);
            if (!shown.equals(wanted)) {
                throw new AssertionError("The line reads " + wanted + ": " + shown);
            }
        }
    }
}
