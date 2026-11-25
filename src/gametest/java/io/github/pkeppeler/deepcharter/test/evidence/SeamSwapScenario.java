package io.github.pkeppeler.deepcharter.test.evidence;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;
import static io.github.pkeppeler.deepcharter.test.support.SeamSite.HOLD_HEIGHT;

import io.github.pkeppeler.deepcharter.client.layer.SeamClient;
import io.github.pkeppeler.deepcharter.layer.SeamSender;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.SeamPodScene;
import io.github.pkeppeler.deepcharter.test.support.SeamSite;
import io.github.pkeppeler.deepcharter.test.support.SeamSite.Site;
import io.github.pkeppeler.deepcharter.test.support.SwapProbes;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Evidence scenario "seam-swap" (#1229, ADR 0089): a pod falls through the floor of layer 1 into layer 2 with its pilot
 * in third person. The client holds layer 2 ahead, and when every rider is ready the crossing swaps to it in place: the
 * drop carries on with no respawn, no loading screen and no fade. The frames run from before the floor to after the
 * arrival; {@link SwapProbes} counts what a vanilla respawn would have done and the scenario fails if it did any.
 */
public class SeamSwapScenario extends EvidenceScenario {
    private static final double X = SeamSite.FIRST_SITE;
    private static final double Z = SeamSite.FIRST_SITE;
    private static final int TICKS_PER_FRAME = 1;
    /** Frames recorded after the client is in layer 2. */
    private static final int TAIL_FRAMES = 30;

    private static final int LAMP_SPACING = 8;
    /** Ticks the toasts of the layer entry get to clear before the drop. */
    private static final int TOAST_TICKS = 160;

    private static final int MAX_FALL_TICKS = 600;
    /** Height above the floor of layer 1 at which the pod is let go: a fall of a few seconds, clear of the hold. */
    private static final int RELEASE_HEIGHT = 96;

    /** Where the pod is pinned until its drop; read on the server thread. */
    private static volatile @Nullable UUID pinned;

    private static volatile @Nullable Vec3 pinAt;

    static {
        PodEvents.AFTER_TICK.register(pod -> {
            Vec3 at = pinAt;
            if (at != null && pod.getUUID().equals(pinned)) {
                pod.setPos(at);
                pod.setDeltaMovement(Vec3.ZERO);
            }
        });
    }

    @Override
    protected String name() {
        return "seam-swap";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        SwapProbes.install();
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40);
            Site site = singleplayer
                    .getServer()
                    .computeOnServer(server ->
                            new Site(TestLayers.layerDimension(server, 1), TestLayers.layerDimension(server, 2), X, Z));
            Vec3 hold = singleplayer.getServer().computeOnServer(server -> site.at(HOLD_HEIGHT));
            singleplayer.getServer().runOnServer(server -> {
                site.carveShaft(site.x(), HOLD_HEIGHT + 4);
                // A glowstone lamp on the shaft wall every few blocks lights the descent, so the fall reads on screen.
                for (int height = LAMP_SPACING; height < HOLD_HEIGHT; height += LAMP_SPACING) {
                    site.one()
                            .setBlock(
                                    BlockPos.containing(site.at(height)).offset(SeamSite.SHAFT_HALF_WIDTH, 0, 0),
                                    Blocks.GLOWSTONE.defaultBlockState(),
                                    Block.UPDATE_ALL);
                }
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SURVIVAL);
                player.stopRiding();
                player.setNoGravity(true);
                player.teleportTo(site.one(), hold.x, hold.y, hold.z, Set.of(), 0, 0, true);
            });
            ClientWait.until(
                    context,
                    "the chunk of the hold to tick entities in layer 1 and reach the client",
                    () -> singleplayer
                                    .getServer()
                                    .computeOnServer(
                                            server -> site.one().isPositionEntityTicking(BlockPos.containing(hold)))
                            && context.computeOnClient(client -> client.level != null
                                    && SeamPodScene.inLayer(client, 1)
                                    && client.level
                                            .getChunkSource()
                                            .hasChunk(
                                                    site.centre().x(),
                                                    site.centre().z())),
                    () -> "hold " + hold + ", entity ticking "
                            + singleplayer
                                    .getServer()
                                    .computeOnServer(
                                            server -> site.one().isPositionEntityTicking(BlockPos.containing(hold)))
                            + ", client chunk "
                            + context.computeOnClient(client -> client.level != null
                                    && client.level
                                            .getChunkSource()
                                            .hasChunk(
                                                    site.centre().x(),
                                                    site.centre().z()))
                            + ", " + context.computeOnClient(ClientWait::describe));
            PodEntity[] made = new PodEntity[1];
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                PodEntity pod = TestEntities.create(PodRegistry.POD, site.one());
                pod.setPos(hold);
                site.one().addFreshEntity(pod);
                pinned = pod.getUUID();
                pinAt = hold;
                made[0] = pod;
                player.setNoGravity(false);
                require(player.startRiding(pod), "the player could not board the pod");
            });
            PodEntity pod = made[0];
            ClientWait.until(
                    context,
                    "the player riding the pod in layer 1",
                    client -> SeamPodScene.inLayer(client, 1)
                            && ClientSession.player(client).getVehicle() instanceof PodEntity);
            context.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_BACK));

            // The held level fills with the arrival area and the rider reports ready.
            int area = (2 * SeamSender.SEND_RADIUS + 1) * (2 * SeamSender.SEND_RADIUS + 1);
            ClientWait.until(
                    context,
                    "the held level to hold the " + area + " chunks of the arrival area",
                    client -> SeamClient.background()
                            .filter(level -> level.getChunkSource().getLoadedChunksCount() >= area)
                            .isPresent(),
                    ClientWait::describe);
            ClientWait.until(
                    context,
                    "the rider to be ready to swap",
                    () -> singleplayer
                            .getServer()
                            .computeOnServer(server -> SeamSender.readyToSwap(
                                    server.getPlayerList().getPlayers().getFirst(), site.two(), site.centre())),
                    () -> "pod at " + pod.position() + ", " + context.computeOnClient(ClientWait::describe));
            context.waitTicks(TOAST_TICKS); // tick-wait: the held level's light drains and the entry toasts clear
            LocalState before = context.computeOnClient(client -> new LocalState(
                    ClientSession.player(client),
                    Objects.requireNonNull(SeamPodScene.vehicleOf(client)).getId()));

            // The pod is let go a little over the floor, so the frames show the fall from before the seam.
            singleplayer.getServer().runOnServer(server -> {
                pinAt = null;
                pod.setPos(site.at(RELEASE_HEIGHT));
                pod.setDeltaMovement(Vec3.ZERO);
            });
            SwapProbes.arm();
            screenshot(context, "seam-swap-before");
            int ticks = 0;
            while (!arrived(context) && ticks < MAX_FALL_TICKS) {
                context.waitTicks(TICKS_PER_FRAME);
                ticks += TICKS_PER_FRAME;
                frame(context);
            }
            if (!arrived(context)) {
                throw new AssertionError("The client did not reach layer 2 within " + MAX_FALL_TICKS + " ticks");
            }
            screenshot(context, "seam-swap-after");
            for (int i = 0; i < TAIL_FRAMES; i++) {
                context.waitTicks(TICKS_PER_FRAME);
                frame(context);
            }
            SwapProbes.Counts counts = SwapProbes.disarm();
            screenshot(context, "seam-swap-settled");
            require(
                    context.computeOnClient(client -> client.player == before.player()
                            && SeamPodScene.vehicleOf(client) instanceof PodEntity vehicle
                            && vehicle.getId() == before.vehicleId()),
                    "the swap did not keep the client's player and the pod's id");
            require(counts.respawns() == 0, "the crossing made the client handle " + counts.respawns() + " respawns");
            require(
                    counts.loadingScreens() == 0,
                    "the crossing opened the loading screen " + counts.loadingScreens() + " times");
            require(counts.soundStops() == 0, "the crossing stopped every sound " + counts.soundStops() + " times");
            require(!counts.everUnloaded(), "the client was not loaded at some point of the crossing");
            pinned = null;
        }
    }

    private static boolean arrived(ClientGameTestContext context) {
        return context.computeOnClient(client -> SeamPodScene.inLayer(client, 2));
    }

    /** What the client held before the crossing. */
    private record LocalState(LocalPlayer player, int vehicleId) {}
}
