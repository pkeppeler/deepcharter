package io.github.pkeppeler.deepcharter.test.evidence;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.client.continuity.ContinuityScreen;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientCursor;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RealInput;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.Objects;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Evidence scenario "death-sequence" for #1337: a pod worn down to its last few points of hull sits in a lit layer-1 chamber when the
 * floor under it gives way to lava. The lava burns the hull to nothing, the pod is a wreck and its pilot dies with it (a hull loss). The
 * recording runs on through the Continuity Event: the cut to black, the skull tearing in and laughing, RETURN TO WORK, and the return.
 * The skull's stills are taken by the clock from the death: once it has been up a while, and mid-laugh with the jaw open.
 * The client records no sound, so the frames show the picture only; the client GameTest checks the sound.
 */
public final class ContinuityEventScenario extends EvidenceScenario {
    private static final int X = 5200;
    private static final int Z = 5200;
    private static final int FLOOR_Y = LIVE_GAUGES_FLOOR_Y;
    private static final float LOOK_DOWN = 22f;
    private static final float HULL_LEFT = 28f;
    private static final int DEATH_LIMIT_TICKS = 600;
    private static final int SEQUENCE_LIMIT_TICKS = 400;
    /** The skull has been up for a second and a half: the theme's cut of 0.35 s, then 1.5 s. */
    private static final long SKULL_STILL_MILLIS = 1850;
    /**
     * Mid-laugh, the jaw open: the laugh starts about 1.07 s after the death (the cut, then the three passes of the skip less the
     * overlap), and its envelope holds near 0.7 from 1.48 s to 1.68 s into it ({@code continuity/laugh_envelope.json}).
     */
    private static final long LAUGH_STILL_MILLIS = 2650;

    @Override
    protected String name() {
        return "death-sequence";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            awaitWorld(context);
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                stoneChamber(
                        one,
                        X,
                        Z,
                        FLOOR_Y,
                        new BlockPos(X + 5, FLOOR_Y + 3, Z),
                        new BlockPos(X - 5, FLOOR_Y + 3, Z),
                        new BlockPos(X, FLOOR_Y + 3, Z + 5));
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SURVIVAL);
                player.teleportTo(one, X + 0.5, FLOOR_Y, Z + 0.5, Set.of(), 0, LOOK_DOWN, true);
                PodEntity pod = TestEntities.create(PodRegistry.POD, one);
                pod.setPos(X + 0.5, FLOOR_Y, Z + 0.5);
                one.addFreshEntity(pod);
                if (!player.startRiding(pod)) {
                    throw new AssertionError("the player could not mount the pod");
                }
                pod.damageHull(pod.maxHull() - HULL_LEFT);
            });
            ClientWait.until(
                    context,
                    "the player riding a pod in layer 1",
                    client -> client.player != null
                            && client.player.getVehicle() instanceof PodEntity
                            && TestLayers.clientIsIn(client, 1));
            context.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_BACK));
            ClientWait.until(context, "the room drawn", client -> client.levelRenderer.hasRenderedAllSections());
            tickFrames(context, 40);
            screenshot(context, "1-in-the-pod");

            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                for (int x = X - 2; x <= X + 2; x++) {
                    for (int z = Z - 2; z <= Z + 2; z++) {
                        one.setBlock(
                                new BlockPos(x, FLOOR_Y - 1, z), Blocks.LAVA.defaultBlockState(), Block.UPDATE_ALL);
                        one.setBlock(new BlockPos(x, FLOOR_Y, z), Blocks.LAVA.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
            });
            for (int i = 0; i < DEATH_LIMIT_TICKS && !inSequence(context); i++) {
                tickFrames(context, 1);
            }
            if (!inSequence(context)) {
                throw new AssertionError("the pilot did not die in the lava");
            }
            // The stills are taken by the client's clock from the death, as the sequence itself runs, never by ticks.
            long died = clock(context);
            boolean skull = false;
            boolean laugh = false;
            for (int i = 0; i < SEQUENCE_LIMIT_TICKS && !buttonShown(context); i++) {
                tickFrames(context, 1);
                long since = clock(context) - died;
                if (!skull && since >= SKULL_STILL_MILLIS) {
                    screenshot(context, "2-the-skull");
                    skull = true;
                } else if (!laugh && since >= LAUGH_STILL_MILLIS) {
                    screenshot(context, "3-the-laugh");
                    laugh = true;
                }
            }
            if (!skull || !laugh) {
                throw new AssertionError(
                        "the sequence ended before its stills: the skull " + skull + ", the laugh " + laugh);
            }
            if (!buttonShown(context)) {
                throw new AssertionError("RETURN TO WORK never came");
            }
            tickFrames(context, 20);
            screenshot(context, "4-return-to-work");

            Button button = context.computeOnClient(client -> ClientChecks.button(shown(client), "RETURN TO WORK"));
            ClientCursor.moveTo(
                    context, button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0);
            tickFrames(context, 10);
            RealInput.click(context, InputConstants.MOUSE_BUTTON_LEFT);
            ClientWait.until(
                    context,
                    "back at work",
                    client -> client.gui.screen() == null && client.player != null && client.player.isAlive());
            tickFrames(context, 40);
            screenshot(context, "5-back-at-work");
        }
    }

    /** The client's clock, the one the sequence runs on. */
    private static long clock(ClientGameTestContext context) {
        return context.computeOnClient(client -> Util.getMillis());
    }

    private static boolean inSequence(ClientGameTestContext context) {
        return context.computeOnClient(client -> client.gui.screen() instanceof ContinuityScreen);
    }

    private static boolean buttonShown(ClientGameTestContext context) {
        return context.computeOnClient(client -> client.gui.screen() instanceof ContinuityScreen screen
                && !ClientChecks.visibleWidgets(screen).isEmpty());
    }

    /** The screen open now, which the step before waited for. */
    private static Screen shown(Minecraft client) {
        return Objects.requireNonNull(client.gui.screen(), "<a screen is open>");
    }
}
