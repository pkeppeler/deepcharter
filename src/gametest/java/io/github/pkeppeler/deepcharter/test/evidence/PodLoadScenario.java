package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.client.pod.CockpitHud;
import io.github.pkeppeler.deepcharter.client.theme.CockpitLook;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodLoad;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.EvidencePins;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;

/**
 * Evidence scenario "pod-load" (#1449): the cockpit's LOAD gauge as einsteinium goes into the bay one at a time, light, near the climb limit and OVERLOADED,
 * then a dump of one that puts the lamp out. Stills of each state at GUI scale 2 and 1 (an 854 x 480 window allows no larger scale), and a zoom of the
 * ribbon's left half at scale 2.
 */
public class PodLoadScenario extends EvidenceScenario {
    private static final int X = 5600;
    private static final int Z = 5400;
    private static final int FLOOR_Y = LAYER_ROOM_Y;
    private static final int ROOM_HEIGHT = 10;
    /** Frames of each state in the recording, one a tick: a second at its 15 frames a second. */
    private static final int FRAMES_PER_STATE = 15;

    @Override
    protected String name() {
        return "pod-load";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40); // tick-wait: the new world's first ticks, as every scenario waits
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                RoomCarver.carve(
                        one,
                        new BlockPos(X - 4, FLOOR_Y - 2, Z - 4),
                        new BlockPos(X + 4, FLOOR_Y, Z + 4),
                        Blocks.STONE.defaultBlockState());
                RoomCarver.carve(
                        one,
                        new BlockPos(X - 3, FLOOR_Y + 1, Z - 3),
                        new BlockPos(X + 3, FLOOR_Y + ROOM_HEIGHT, Z + 3),
                        Blocks.AIR.defaultBlockState());
                one.setBlock(new BlockPos(X + 4, FLOOR_Y + 4, Z), Blocks.GLOWSTONE.defaultBlockState(), 3);
                one.setBlock(new BlockPos(X - 4, FLOOR_Y + 4, Z), Blocks.GLOWSTONE.defaultBlockState(), 3);
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SURVIVAL);
                player.teleportTo(one, X + 0.5, FLOOR_Y + 1, Z + 0.5, Set.of(), 0, 0, true);
            });
            ClientWait.until(
                    context,
                    "the player in layer 1",
                    client -> client.player != null && TestLayers.clientIsIn(client, 1));
            ClientWait.lightSettled(context, singleplayer.getServer(), 1, X, Z, 4);
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                PodEntity pod = TestEntities.create(PodRegistry.POD, one);
                pod.setPos(X + 0.5, FLOOR_Y + 1, Z + 0.5);
                one.addFreshEntity(pod);
                if (!player.startRiding(pod)) {
                    throw new AssertionError("the player could not mount the pod");
                }
            });
            ClientWait.until(context, "the Ribbon over the seated pilot", CockpitHud::showsReadout);
            ClientWait.until(
                    context, "every section in view rendered", client -> client.levelRenderer.hasRenderedAllSections());
            // The stills show the cockpit, not the rider's arm or the dismount hint.
            EvidencePins.handsHidden = true;
            try {
                context.runOnClient(client -> client.gui.hud.setOverlayMessage(Component.empty(), false));
                state(context, singleplayer, 0, "light");
                state(context, singleplayer, 1, null);
                state(context, singleplayer, 2, "near-limit");
                state(context, singleplayer, 3, "overloaded");
                // A dump of one einsteinium: back under the climb limit, the lamp goes out.
                state(context, singleplayer, 2, "dumped");
            } finally {
                EvidencePins.reset();
            }
        }
    }

    /** Puts {@code einsteinium} in the bay, records frames of it, and, when {@code still} is named, its stills at GUI scale 2 and 1 and a zoom. */
    private void state(
            ClientGameTestContext context,
            TestSingleplayerContext singleplayer,
            int einsteinium,
            @Nullable String still) {
        singleplayer.getServer().runOnServer(server -> {
            PodEntity pod = pod(server);
            int held = pod.cargo().count(OreType.EINSTEINIUM);
            if (held > einsteinium) {
                pod.cargo().take(pod, OreType.EINSTEINIUM, held - einsteinium);
            }
            for (int i = held; i < einsteinium; i++) {
                pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.EINSTEINIUM));
            }
        });
        float expected = einsteinium * OreType.EINSTEINIUM.mass();
        ClientWait.until(
                context,
                "the client's load of " + einsteinium + " einsteinium",
                client -> ClientSession.player(client).getVehicle() instanceof PodEntity pod
                        && PodLoad.of(pod).load() == expected);
        context.runOnClient(client -> client.gui.toastManager().clear());
        tickFrames(context, FRAMES_PER_STATE);
        if (still == null) {
            return;
        }
        // The first boarding completes a directive whose toast lands some ticks later, over the plate.
        context.runOnClient(client -> client.gui.toastManager().clear());
        context.waitTicks(2); // tick-wait: a frame without the cleared toast
        screenshot(context, "pod-load-" + still + "-gui2");
        CockpitLook.Layout layout = CockpitLook.current().ribbon();
        int left = layout.band().left(427);
        zoomStill(context, "pod-load-" + still + "-gui2", "pod-load-" + still + "-zoom", left, 0, 160, 32);
        context.runOnClient(client -> client.options.guiScale().set(1));
        context.waitTicks(2); // tick-wait: the frame after the scale change lays the GUI out again
        screenshot(context, "pod-load-" + still + "-gui1");
        context.runOnClient(client -> client.options.guiScale().set(2));
        context.waitTicks(2); // tick-wait: as above, back to the default scale
    }

    private static PodEntity pod(MinecraftServer server) {
        return TestEntities.vehicle(server.getPlayerList().getPlayers().getFirst(), PodEntity.class);
    }
}
