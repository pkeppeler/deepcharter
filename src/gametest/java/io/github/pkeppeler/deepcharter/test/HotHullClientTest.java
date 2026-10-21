package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.client.pod.PodGeoAnimatable;
import io.github.pkeppeler.deepcharter.client.pod.PodGeoRenderState;
import io.github.pkeppeler.deepcharter.client.pod.PodGeoRenderer;
import io.github.pkeppeler.deepcharter.client.pod.PodHotLayer;
import io.github.pkeppeler.deepcharter.client.pod.PodLook;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.io.StringReader;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * Client GameTest for the hot hull (#634): a pod in a real pool of lava, with its hull burning, is drawn with the hot-hull layer and its
 * hot mask (a texture the look names, so a pack can replace it), and once the lava is gone and the hull has stopped burning the layer is
 * not drawn. The mask comes from the pod's look and is no colour in code.
 */
public class HotHullClientTest implements FabricClientGameTest {
    private static final int FEET_Y = TestLayers.worldY(1, 10);

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40);
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SURVIVAL);
                // Hung in a sealed room, in a 2 x 2 pool of lava, as the heat shield's test does.
                RoomCarver.carve(one, -3, 3, FEET_Y - 1, FEET_Y + 6, -3, 3, Blocks.AIR);
                RoomCarver.carve(one, -1, 0, FEET_Y, FEET_Y, -1, 0, Blocks.LAVA);
                player.teleportTo(one, 0, FEET_Y, 0, Set.of(), 0f, 0f, true);
                PodEntity pod = TestEntities.create(PodRegistry.POD, one);
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
                            && TestLayers.isIn(ClientLight.clientLevel(client), 1)
                            && pod.hullBurning());
            context.runOnClient(client -> {
                PodEntity pod = TestEntities.vehicle(ClientSession.player(client), PodEntity.class);
                PodGeoRenderState state = stateOf(client, pod);
                require(state.hot, "a pod whose hull burns shows the hot hull in its render state");
                require(PodHotLayer.draws(state), "the hot-hull layer draws over a burning hull");
                PodHotLayer layer = hotLayer(client, pod);
                require(
                        layer.getRenderType(state) != null,
                        "the hot-hull layer has a render type while the hull burns");
                require(
                        layer.getTextureResource(state)
                                .equals(state.appearance().variant().hotmask()),
                        "the hot-hull layer draws the look's hot mask");
                var hotmask = state.appearance().variant().hotmask();
                require(
                        client.getResourceManager().getResource(hotmask).isPresent(),
                        "the hot mask " + hotmask + " is a texture in the pack");
            });

            singleplayer
                    .getServer()
                    .runOnServer(server ->
                            RoomCarver.carve(TestLayers.level(server, 1), -1, 0, FEET_Y, FEET_Y, -1, 0, Blocks.AIR));
            ClientWait.until(
                    context,
                    "the hull cool again",
                    client -> ClientSession.player(client).getVehicle() instanceof PodEntity pod && !pod.hullBurning());
            context.runOnClient(client -> {
                PodEntity pod = TestEntities.vehicle(ClientSession.player(client), PodEntity.class);
                PodGeoRenderState state = stateOf(client, pod);
                require(!state.hot, "a pod whose hull has stopped burning shows no hot hull in its render state");
                require(!PodHotLayer.draws(state), "the hot-hull layer is not drawn once the hull is cool");
                require(
                        hotLayer(client, pod).getRenderType(state) == null,
                        "the hot-hull layer has no render type once the hull is cool");
                requireHotMaskOptional(client, pod);
            });
        }
    }

    /** The hot-hull layer the pod's own renderer holds: a renderer that dropped the layer fails here. */
    private static PodHotLayer hotLayer(Minecraft client, PodEntity pod) {
        PodGeoRenderer renderer =
                (PodGeoRenderer) client.getEntityRenderDispatcher().getRenderer(pod);
        return renderer.getRenderLayers().stream()
                .filter(PodHotLayer.class::isInstance)
                .map(PodHotLayer.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the pod's renderer has no hot-hull layer"));
    }

    /**
     * The mod's look has a hot mask. A look whose intact texture has no {@code _hot} file (a pack that restyles the pod and ships none)
     * still loads, and has no hot look.
     */
    private static void requireHotMaskOptional(Minecraft client, PodEntity pod) {
        require(
                PodGeoRenderer.load(client.getResourceManager(), pod.chassis()).hot(),
                "the mod's own look has a hot mask");
        PodLook bare = PodLook.parse("a look with no hot mask", new StringReader("""
                        {"model": "deepcharter:pod/mole", "texture": "deepcharter:textures/entity/pod/mole_wreck.png",
                         "glow": "never",
                         "cutters": {"0": "tricone", "1": "stacked", "2": "fluted", "3": "cluster"},
                         "wreck": {"texture": "deepcharter:textures/entity/pod/mole_wreck.png"}}
                        """));
        require(
                client.getResourceManager().getResource(bare.intact().hotmask()).isEmpty(),
                "the test's texture has no hot mask, " + bare.intact().hotmask());
        require(
                !PodGeoRenderer.validate(client.getResourceManager(), bare).hot(),
                "a look with no hot mask loads and has no hot look");
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
