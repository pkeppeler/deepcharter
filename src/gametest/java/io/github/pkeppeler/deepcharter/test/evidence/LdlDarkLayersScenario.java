package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.transmission.TransmissionOverlay;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;

/**
 * Evidence scenario "ldl-dark-layers" (#629): a pod with a lights part and a pod without one stand in a sealed, pitch-dark chamber of
 * layer 1 and then of layer 2, and the player drives the lit one down the chamber with the HUD shown. Record it once as the game ships and
 * once with {@code DEEPCHARTER_LDL=1} (LambDynamicLights loaded) to see what the dynamic light adds and whether the dark pod stays dark.
 * Nothing in the scenario depends on LambDynamicLights.
 *
 * <p>Stills per layer {@code N}: {@code layer-N-pods-at-rest} and {@code layer-N-driving}. The clip is the drive in layer 2.
 */
public class LdlDarkLayersScenario extends EvidenceScenario {
    private static final BlockPos FLOOR = new BlockPos(3000, LAYER_ROOM_Y, 3000);
    private static final int LENGTH = 40;
    private static final int HALF_WIDTH = 6;
    private static final int HEIGHT = 6;
    /** How far east of the chamber's floor block the lit pod starts. */
    private static final int LIT_X = 14;

    private static final int LIGHTS_TIER = 3;
    private static final int TICKS_PER_FRAME = 4;
    private static final int DRIVE_FRAMES = 30;
    /** A fixed id, because a pod's paint is the slot of its charter id's hash (ADR 0041) and a random one paints it a new colour in each run. */
    private static final CharterId CHARTER = new CharterId(new UUID(0L, 0L));

    private static final int[] LAYERS = {1, 2};

    @Override
    protected String name() {
        return "ldl-dark-layers";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40); // tick-wait: the world's spawn settles before the player is moved
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                Charters.found(server, player.getUUID(), "Demo Charter", CHARTER);
            });
            for (int layer : LAYERS) {
                visit(context, singleplayer, layer);
            }
        }
    }

    private void visit(ClientGameTestContext context, TestSingleplayerContext singleplayer, int layer) {
        int[] litId = {0};
        UUID[] litUuid = {null};
        singleplayer.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            player.stopRiding();
            ServerLevel level = TestLayers.level(server, layer);
            // The chamber's chunks stay loaded and ticking, so the pods in them cannot unload before the player mounts.
            for (int cx = (FLOOR.getX() - 1) >> 4; cx <= (FLOOR.getX() + LENGTH) >> 4; cx++) {
                for (int cz = (FLOOR.getZ() - HALF_WIDTH - 1) >> 4; cz <= (FLOOR.getZ() + HALF_WIDTH + 1) >> 4; cz++) {
                    // force-ok: an evidence scenario has no GameTestHelper to release through; its world is discarded
                    // when
                    // the scenario ends.
                    level.setChunkForced(cx, cz, true);
                    level.getChunk(cx, cz);
                }
            }
            RoomCarver.carve(
                    level,
                    FLOOR.offset(-1, 0, -HALF_WIDTH - 1),
                    FLOOR.offset(LENGTH, HEIGHT + 1, HALF_WIDTH + 1),
                    Blocks.STONE.defaultBlockState(),
                    Block.UPDATE_ALL);
            RoomCarver.carve(
                    level,
                    FLOOR.offset(0, 1, -HALF_WIDTH),
                    FLOOR.offset(LENGTH - 1, HEIGHT, HALF_WIDTH),
                    Blocks.AIR.defaultBlockState(),
                    Block.UPDATE_ALL);
            player.teleportTo(level, FLOOR.getX() + 1, FLOOR.getY() + 1, FLOOR.getZ() + 0.5, Set.of(), -90f, 4f, true);
            PodEntity lit = TestEntities.create(PodRegistry.POD, level);
            lit.setPos(FLOOR.getX() + LIT_X, FLOOR.getY() + 1, FLOOR.getZ() + 0.5);
            lit.setFuel(100f);
            level.addFreshEntity(lit);
            PodComponents.register(
                    lit,
                    Charters.charterOfOrThrow(server, player.getUUID())
                            .orElseThrow()
                            .id());
            PodComponents.install(
                    lit,
                    ComponentItems.mint(
                            server,
                            ComponentTrack.LIGHTS,
                            LIGHTS_TIER,
                            PodComponents.registration(lit).orElseThrow().owner()));
            PodEntity dark = TestEntities.create(PodRegistry.POD, level);
            dark.setPos(FLOOR.getX() + 5, FLOOR.getY() + 1, FLOOR.getZ() - 3);
            dark.setFuel(100f);
            level.addFreshEntity(dark);
            litId[0] = lit.getId();
            litUuid[0] = lit.getUUID();
        });
        ClientWait.until(
                context,
                "the player and the lit pod in layer " + layer,
                client -> client.player != null
                        && client.level != null
                        && TestLayers.isIn(client.level, layer)
                        && client.level.getEntity(litId[0]) instanceof PodEntity);
        context.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));
        context.waitTicks(60); // tick-wait: chunks, light and the pod's light block reach the client
        ClientWait.until(context, "the arrival transmission over", client -> !TransmissionOverlay.active());
        screenshot(context, "layer-" + layer + "-pods-at-rest");

        ClientWait.until(
                context,
                "the lit pod on the server in layer " + layer,
                () -> singleplayer.getServer().computeOnServer(server -> litPod(server, layer, litUuid[0]) != null),
                () -> singleplayer.getServer().computeOnServer(server -> {
                    ServerLevel level = TestLayers.level(server, layer);
                    BlockPos spot = FLOOR.offset(LIT_X, 1, 0);
                    return "pod " + litUuid[0] + " not in " + level.dimension().identifier() + "; chunk at " + spot
                            + " loaded: " + level.isLoaded(spot);
                }));
        singleplayer.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            if (!player.startRiding(Objects.requireNonNull(litPod(server, layer, litUuid[0]), "waited for above"))) {
                throw new AssertionError("the player could not mount the lit pod");
            }
        });
        ClientWait.until(
                context,
                "the player riding in layer " + layer,
                client -> client.player != null && client.player.getVehicle() instanceof PodEntity);
        context.runOnClient(client -> {
            client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            ClientSession.player(client).setYRot(-90f);
        });
        context.waitTicks(80); // tick-wait: the mount fade ends
        ClientWait.until(context, "no transmission on screen", client -> !TransmissionOverlay.active());
        context.getInput().holdKey(options -> options.keyUp);
        for (int i = 0; i < DRIVE_FRAMES; i++) {
            context.waitTicks(TICKS_PER_FRAME);
            if (layer == LAYERS[LAYERS.length - 1]) {
                frame(context);
            }
        }
        screenshot(context, "layer-" + layer + "-driving");
        context.getInput().releaseKey(options -> options.keyUp);
    }

    /** The lit pod, or null while the server does not hold it in the layer. */
    private static @Nullable PodEntity litPod(MinecraftServer server, int layer, UUID id) {
        ServerLevel level = TestLayers.level(server, layer);
        return level.getEntity(id) instanceof PodEntity pod ? pod : null;
    }
}
