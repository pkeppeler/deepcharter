package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.client.pod.PodGeoAnimatable;
import io.github.pkeppeler.deepcharter.client.pod.PodGeoRenderState;
import io.github.pkeppeler.deepcharter.client.pod.PodGeoRenderer;
import io.github.pkeppeler.deepcharter.client.pod.PodHotLayer;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * Client GameTest for the hot hull (#634): a pod in a real pool of lava, with its hull burning, is drawn with the hot-hull layer and its
 * hot mask (a texture the look names, so a pack can replace it), and once the lava is gone and the hull has stopped burning the layer is
 * not drawn. The mask comes from the pod's look and is no colour in code.
 */
public class HotHullClientTest implements FabricClientGameTest {
    private static final int FEET_Y = 10;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40);
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = server.getLevel(LayerChain.dimension(1));
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SURVIVAL);
                // Hung in a sealed room, in a 2 x 2 pool of lava, as the heat shield's test does.
                RoomCarver.carve(one, -3, 3, FEET_Y - 1, FEET_Y + 6, -3, 3, Blocks.AIR);
                RoomCarver.carve(one, -1, 0, FEET_Y, FEET_Y, -1, 0, Blocks.LAVA);
                player.teleportTo(one, 0, FEET_Y, 0, Set.of(), 0f, 0f, true);
                PodEntity pod = PodRegistry.POD.create(one, EntitySpawnReason.COMMAND);
                pod.setPos(0, FEET_Y, 0);
                pod.setNoGravity(true);
                one.addFreshEntity(pod);
                if (!player.startRiding(pod)) {
                    throw new AssertionError("the player could not mount the pod");
                }
            });
            ClientWait.until(
                    context,
                    "the pod in layer 1, burning",
                    client -> client.player != null
                            && client.player.getVehicle() instanceof PodEntity pod
                            && client.level.dimension().equals(LayerChain.dimension(1))
                            && pod.hullBurning());
            context.runOnClient(client -> {
                PodEntity pod = (PodEntity) client.player.getVehicle();
                PodGeoRenderState state = stateOf(client, pod);
                require(state.hot, "a pod whose hull burns shows the hot hull in its render state");
                require(PodHotLayer.draws(state), "the hot-hull layer draws over a burning hull");
                var hotmask = state.appearance.variant().hotmask();
                require(
                        client.getResourceManager().getResource(hotmask).isPresent(),
                        "the hot mask " + hotmask + " is a texture in the pack");
            });

            singleplayer
                    .getServer()
                    .runOnServer(server -> RoomCarver.carve(
                            server.getLevel(LayerChain.dimension(1)), -1, 0, FEET_Y, FEET_Y, -1, 0, Blocks.AIR));
            ClientWait.until(
                    context,
                    "the hull cool again",
                    client -> client.player.getVehicle() instanceof PodEntity pod && !pod.hullBurning());
            context.runOnClient(client -> {
                PodEntity pod = (PodEntity) client.player.getVehicle();
                PodGeoRenderState state = stateOf(client, pod);
                require(!state.hot, "a pod whose hull has stopped burning shows no hot hull in its render state");
                require(!PodHotLayer.draws(state), "the hot-hull layer is not drawn once the hull is cool");
            });
        }
    }

    private static PodGeoRenderState stateOf(Minecraft client, PodEntity pod) {
        Object drawn = client.getEntityRenderDispatcher().getRenderer(pod);
        require(drawn instanceof PodGeoRenderer, "the pod is not drawn with the GeckoLib renderer, but with " + drawn);
        PodGeoRenderer renderer = (PodGeoRenderer) drawn;
        PodGeoRenderState state = renderer.createRenderState(new PodGeoAnimatable(), pod);
        renderer.addRenderData(new PodGeoAnimatable(), pod, state, 0f);
        return state;
    }
}
