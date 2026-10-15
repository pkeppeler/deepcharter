package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.test.ClientTestLog;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.GameType;
import org.jspecify.annotations.Nullable;

/**
 * Base class for PR evidence scenarios. Run with tools/record-evidence.sh, which sets
 * DEEPCHARTER_EVIDENCE to the scenario name and DEEPCHARTER_EVIDENCE_DIR to build/evidence.
 * A plain runClientGameTest skips every scenario.
 *
 * Output (rendered inside the game only, never captured from the desktop):
 *   build/evidence/NAME/frames/frame-0001.png ...   frame sequence, assembled by ffmpeg
 *   build/evidence/NAME/screenshots/*.png            still screenshots
 */
public abstract class EvidenceScenario implements FabricClientGameTest {
    /** The size of a player's default window, which is also the size of every frame and still. */
    private static final int WIDTH = 854;

    private static final int HEIGHT = 480;
    private static final float FLOAT_YAW_EAST = -90f;
    private static final float FLOAT_PITCH_DOWN = 85f;
    private static final int MINE_LIMIT_TICKS = 160;

    private int frame;
    private @Nullable Path framesDir;
    private @Nullable Path screenshotsDir;

    /** The scenario name: also the output directory name and the argument to record-evidence.sh. */
    protected abstract String name();

    /** Drive the game, calling {@link #frame} and {@link #screenshot} as things happen. */
    protected abstract void run(ClientGameTestContext context);

    private Path framesDir() {
        return Objects.requireNonNull(framesDir, "framesDir was read before the scenario set it");
    }

    private Path screenshotsDir() {
        return Objects.requireNonNull(screenshotsDir, "screenshotsDir was read before the scenario set it");
    }

    @Override
    public final void runTest(ClientGameTestContext context) {
        if (!name().equals(System.getenv("DEEPCHARTER_EVIDENCE"))) {
            return;
        }
        // A recording is long by design and the CI job limit bounds it.
        ClientTestLog.startUntimed(this);
        String root = System.getenv("DEEPCHARTER_EVIDENCE_DIR");
        if (root == null) {
            throw new IllegalStateException("DEEPCHARTER_EVIDENCE_DIR is not set; run via tools/record-evidence.sh");
        }
        Path out = Path.of(root).toAbsolutePath().resolve(name());
        framesDir = out.resolve("frames");
        screenshotsDir = out.resolve("screenshots");
        try {
            deleteTree(out);
            Files.createDirectories(framesDir());
            Files.createDirectories(screenshotsDir());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        run(context);
        if (frame == 0) {
            throw new AssertionError("Scenario " + name() + " produced no frames");
        }
    }

    /** Add one frame to the recording. */
    protected final void frame(ClientGameTestContext context) {
        frame++;
        shoot(context, String.format("frame-%04d", frame), framesDir());
    }

    /**
     * Lets the first player out of the pod and floats them, as a spectator, at ({@code x}, {@code y}, {@code z}) looking nearly straight
     * down, facing east: the cab frame blocks a look up the shaft from the seat. Terminal: only frames and stills may follow.
     */
    protected final void floatAbovePod(TestSingleplayerContext singleplayer, double x, double y, double z) {
        singleplayer.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            player.stopRiding();
            player.setGameMode(GameType.SPECTATOR);
            player.teleportTo(player.level(), x, y, z, Set.of(), FLOAT_YAW_EAST, FLOAT_PITCH_DOWN, true);
        });
    }

    /** Waits for the client to have joined the world: a player and a level. */
    protected static void awaitWorld(ClientGameTestContext context) {
        ClientWait.until(
                context, "the player and level of the world", client -> client.player != null && client.level != null);
    }

    /**
     * Looks at the block and mines it as the attack button does, through the game mode: one start and then one continue on every tick, with a
     * swing, so the crack shows. A frame every {@code ticksPerFrame} ticks. (The test client's mouse is not grabbed, so a held button does nothing.)
     */
    protected final void mineBlock(
            ClientGameTestContext context, TestSingleplayerContext singleplayer, BlockPos target, int ticksPerFrame) {
        context.getInput().lookAt(target);
        for (int tick = 0; tick < MINE_LIMIT_TICKS; tick++) {
            boolean first = tick == 0;
            context.runOnClient(client -> {
                if (first) {
                    ClientSession.gameMode(client).startDestroyBlock(target, Direction.SOUTH);
                } else {
                    ClientSession.gameMode(client).continueDestroyBlock(target, Direction.SOUTH);
                }
                ClientSession.player(client).swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            });
            context.waitTicks(1); // tick-wait: the mining is one call a tick
            if (tick % ticksPerFrame == 1) {
                frame(context);
            }
            if (singleplayer
                    .getServer()
                    .computeOnServer(
                            server -> server.overworld().getBlockState(target).isAir())) {
                return;
            }
        }
        throw new AssertionError("the block at " + target.toShortString() + " was not broken in " + MINE_LIMIT_TICKS
                + " ticks bare handed");
    }

    /** Take a named still screenshot. */
    protected final void screenshot(ClientGameTestContext context, String screenshotName) {
        shoot(context, screenshotName, screenshotsDir());
    }

    private static void shoot(ClientGameTestContext context, String fileName, Path dir) {
        int[] window = context.computeOnClient(client ->
                new int[] {client.getWindow().getWidth(), client.getWindow().getHeight()});
        if (window[0] != WIDTH || window[1] != HEIGHT) {
            throw new AssertionError("The client window is " + window[0] + "x" + window[1] + " but evidence frames are "
                    + WIDTH + "x" + HEIGHT
                    + ": a frame of another size resizes the window and lays the GUI out differently from a player's."
                    + " Launch the client at the default " + WIDTH + "x" + HEIGHT
                    + " window; HiDPI scaling or a resized window breaks evidence frames");
        }
        context.takeScreenshot(TestScreenshotOptions.of(fileName)
                .disableCounterPrefix()
                .withSize(WIDTH, HEIGHT)
                .withDestinationDir(dir));
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
