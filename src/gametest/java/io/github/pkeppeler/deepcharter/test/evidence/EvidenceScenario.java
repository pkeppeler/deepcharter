package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.layer.StructureSite;
import io.github.pkeppeler.deepcharter.pod.SignalDecayTuning;
import io.github.pkeppeler.deepcharter.test.ClientTestLog;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.QuietTransmissions;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.transmission.TransmissionCatalog;
import io.github.pkeppeler.deepcharter.transmission.TransmissionData;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

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
    /** Above the layer 1 signal decay (#502), so the gauges are live: the decay starts {@code onset} slabs above the floor, and a stock console shrinks that. */
    protected static final int LIVE_GAUGES_LOCAL_Y =
            SignalDecayTuning.DEFAULT.band(1).onset() + 4;

    /** {@link #LIVE_GAUGES_LOCAL_Y} as a world Y in layer 1: a layer is a band of the campaign level. */
    protected static final int LIVE_GAUGES_FLOOR_Y = TestLayers.worldY(1, LIVE_GAUGES_LOCAL_Y);

    /** Where a scenario carves a room in a layer, counted from the layer's floor: 60 slabs above the live-gauges floor, so the gauges stay live. */
    protected static final int LAYER_ROOM_LOCAL_Y = LIVE_GAUGES_LOCAL_Y + 60;

    /** {@link #LAYER_ROOM_LOCAL_Y} as a world Y in layer 1. */
    protected static final int LAYER_ROOM_Y = TestLayers.worldY(1, LAYER_ROOM_LOCAL_Y);

    /** Where a scenario builds a slab or hall in the overworld's sky, clear of the terrain. */
    // fixed-pos: the overworld has no anchor to derive a sky slab from; 200 sits 120 under its 320 build limit
    protected static final int SKY_SLAB_Y = 200;

    /** The size of a player's default window, which is also the size of every frame and still. */
    private static final int WIDTH = 854;

    private static final int HEIGHT = 480;
    private static final float FLOAT_YAW_EAST = -90f;
    private static final float FLOAT_PITCH_DOWN = 85f;
    private static final int MINE_LIMIT_TICKS = 160;

    /** How many times {@link #zoomStill} scales a crop up. */
    private static final int ZOOM = 8;

    private int frame;

    /** Pass as {@code ticksPerFrame} to {@link #mineBlock} to mine without recording a frame. */
    protected static final int NO_FRAMES = 0;

    /** The scenario name: also the output directory name and the argument to record-evidence.sh. */
    protected abstract String name();

    /** Drive the game, calling {@link #frame} and {@link #screenshot} as things happen. */
    protected abstract void run(ClientGameTestContext context);

    /** The scenario's output directory, derived from the environment each time it is read. */
    private Path outputDir() {
        String root = System.getenv("DEEPCHARTER_EVIDENCE_DIR");
        if (root == null) {
            throw new IllegalStateException("DEEPCHARTER_EVIDENCE_DIR is not set; run via tools/record-evidence.sh");
        }
        return Path.of(root).toAbsolutePath().resolve(name());
    }

    private Path framesDir() {
        return outputDir().resolve("frames");
    }

    private Path screenshotsDir() {
        return outputDir().resolve("screenshots");
    }

    @Override
    public final void runTest(ClientGameTestContext context) {
        if (!name().equals(System.getenv("DEEPCHARTER_EVIDENCE"))) {
            return;
        }
        // A recording is long by design and the CI job limit bounds it.
        ClientTestLog.startUntimed(this);
        try {
            deleteTree(outputDir());
            Files.createDirectories(framesDir());
            Files.createDirectories(screenshotsDir());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // A recording shows the game as a player sees it, transmissions included.
        try (var _ = QuietTransmissions.shown()) {
            run(context);
        }
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

    /**
     * Puts the first player's feet at {@code feet} in the overworld, turned to look from {@code eye} at {@code target}.
     */
    protected static void teleportLooking(TestSingleplayerContext singleplayer, Vec3 feet, Vec3 eye, Vec3 target) {
        Vec3 d = target.subtract(eye);
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z)));
        singleplayer
                .getServer()
                .runOnServer(server -> server.getPlayerList()
                        .getPlayers()
                        .getFirst()
                        .teleportTo(server.overworld(), feet.x, feet.y, feet.z, Set.of(), yaw, pitch, true));
    }

    /**
     * Marks every transmission with a bonus fired for {@code charter}, so a scenario that drops through a breach and its
     * zones keeps the balance its stills show: the breach and the zones on the way down would pay Personnel's bonuses.
     */
    protected static void markBonusesFired(MinecraftServer server, CharterId charter) {
        TransmissionData data = TransmissionData.get(server);
        TransmissionCatalog.all().stream()
                .filter(transmission -> transmission.bonus().isPresent())
                .forEach(transmission -> data.fire(charter, transmission));
    }

    /** Waits for the client to have joined the world: a player and a level. */
    protected static void awaitWorld(ClientGameTestContext context) {
        ClientWait.until(
                context, "the player and level of the world", client -> client.player != null && client.level != null);
    }

    /**
     * Right-clicks {@code target} as a player does: looks at its middle, waits for the crosshair to rest on it, then
     * presses the use key, so the game's own path ({@code Minecraft.startUseItem}) picks the entity and sends the
     * interaction.
     */
    protected static void rightClick(ClientGameTestContext context, Entity target) {
        context.getInput().lookAt(BlockPos.containing(target.getBoundingBox().getCenter()));
        ClientWait.until(
                context,
                "the crosshair on " + target,
                client -> client.hitResult instanceof EntityHitResult hit && hit.getEntity() == target);
        context.getInput().pressKey(options -> options.keyUse);
    }

    /**
     * The client's copy of the server entity with network id {@code id}, waited for until the client holds it. The
     * integrated server and its client share ids, so this finds a scenario's own entity where another of its type
     * shares the level (the colony's pod does).
     */
    protected static <T extends Entity> T clientEntity(ClientGameTestContext context, int id, Class<T> type) {
        ClientWait.until(
                context,
                type.getSimpleName() + " " + id + " in the client's world",
                client -> type.isInstance(ClientLight.clientLevel(client).getEntity(id)));
        return context.computeOnClient(client -> TestEntities.require(ClientLight.clientLevel(client), id, type));
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
            if (ticksPerFrame != NO_FRAMES && tick % ticksPerFrame == 1) {
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

    /** The world position of a point in the structure's own axes, {@code y} up from the floor of its hollow; {@code u} and {@code v} are from the middle of the origin block. */
    protected static Vec3 at(StructureSite site, double u, double y, double v) {
        BlockPos origin = site.origin();
        return new Vec3(
                origin.getX() + 0.5 + (site.alongZ() ? v : u),
                origin.getY() + y,
                origin.getZ() + 0.5 + (site.alongZ() ? u : v));
    }

    /** Take a named still screenshot. */
    protected final void screenshot(ClientGameTestContext context, String screenshotName) {
        shoot(context, screenshotName, screenshotsDir());
    }

    /**
     * Saves {@code zoomName}: the rectangle at ({@code x}, {@code y}), {@code width} by {@code height} GUI pixels, of the still {@code screenshotName}
     * already taken, scaled up {@link #ZOOM} times with nearest-neighbour so each GUI pixel stays a sharp block.
     */
    protected final void zoomStill(
            ClientGameTestContext context,
            String screenshotName,
            String zoomName,
            int x,
            int y,
            int width,
            int height) {
        int guiScale = context.computeOnClient(client -> client.getWindow().getGuiScale());
        try {
            BufferedImage full = ImageIO.read(
                    screenshotsDir().resolve(screenshotName + ".png").toFile());
            BufferedImage crop = full.getSubimage(x * guiScale, y * guiScale, width * guiScale, height * guiScale);
            BufferedImage out =
                    new BufferedImage(crop.getWidth() * ZOOM, crop.getHeight() * ZOOM, BufferedImage.TYPE_INT_ARGB);
            for (int py = 0; py < out.getHeight(); py++) {
                for (int px = 0; px < out.getWidth(); px++) {
                    out.setRGB(px, py, crop.getRGB(px / ZOOM, py / ZOOM));
                }
            }
            ImageIO.write(
                    out, "png", screenshotsDir().resolve(zoomName + ".png").toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
