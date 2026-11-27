package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.client.pod.CockpitHud;
import io.github.pkeppeler.deepcharter.client.scanner.ScannerHud;
import io.github.pkeppeler.deepcharter.client.theme.CockpitLook;
import io.github.pkeppeler.deepcharter.client.theme.PanelLook.Decal;
import io.github.pkeppeler.deepcharter.client.theme.ScannerLook;
import io.github.pkeppeler.deepcharter.layer.Altimeter;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.scanner.ScanArea;
import io.github.pkeppeler.deepcharter.scanner.ScannerTuning;
import io.github.pkeppeler.deepcharter.test.ScannerHudTest.HudShot;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientPacks;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.DrawnText;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestPacks;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * #1272: digging straight down through uniform rock, the scanner must show the pod descending. The pod sits in a shaft
 * cut in a slab of stone that fills the whole scan area, and is moved one block down.
 *
 * <ul>
 *   <li>The rendered map differs between the two depths.
 *   <li>Rock grain is a property of the block's place: a rock cell looks the same in two frames, and the block one row
 *       up after the descent looks as it did before it.
 *   <li>Uniform rock is not one flat picture: its cells do not all look alike.
 *   <li>Each depth label on the ruler is the altimeter's number for its row, so the ruler and the HUD readout agree.
 * </ul>
 */
public class ScannerDescentClientTest implements FabricClientGameTest {
    private static final ScannerTuning TUNING = ScannerTuning.DEFAULT;
    private static final int TIER = 1;
    private static final ScanArea AREA = TUNING.area(TIER);
    /** Rows of stone above the pod's feet; past the scan's top, so no sky shows as the pod descends. */
    private static final int FILL_ABOVE = AREA.up() + 2;
    /** Rows of stone below the pod's feet; past the scan's bottom after the descent. */
    private static final int FILL_BELOW = AREA.down() + 3;
    /** The first column ahead that is rock: right of the 3-wide shaft, and on the side away from the ruler. */
    private static final int ROCK_FROM = 2;
    /**
     * Cells this far from the map's edge are compared cell by cell; the cockpit bezel's rounded corners cover the map's
     * corners.
     */
    private static final int EDGE = 2;
    /**
     * How far (levels of 255) the mean brightness of two stone cells may differ: the grain moves none, rounding a
     * little.
     */
    private static final double MEAN_SPREAD = 1;

    private static final Pattern DEPTH_LABEL = Pattern.compile("-?[0-9][0-9,]*");
    private static final int RGB = 0xFFFFFF;

    /**
     * A cockpit window smaller than the tier 1 slice, at the GUI's top left: no chassis takes the tier 4 scanner whose
     * slice overflows the real window, so this window makes the slice overflow it the same way, on every side.
     */
    private static final Decal NARROW_WINDOW = new Decal(30, 36, 0, 0, 10, 10);

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            context.waitTicks(20);
            ScannerHudTest.mountFirstPlayer(server, TIER);
            ClientWait.until(
                    context,
                    "the client riding a pod",
                    client -> client.player != null && client.player.getVehicle() instanceof PodEntity);
            BlockPos feet = buildShaft(context, server);

            HudShot before = settledShot(context, feet, "scanner-descent-before");
            HudShot again = settledShot(context, feet, "scanner-descent-again");
            List<String> labelProblems = new ArrayList<>(labelProblems(context, feet));

            descend(server, feet);
            BlockPos lower = feet.below();
            HudShot after = settledShot(context, lower, "scanner-descent-after");
            labelProblems.addAll(labelProblems(context, lower));
            labelProblems.addAll(narrowWindowProblems(context, lower));
            markProblem(context, after, lower).ifPresent(labelProblems::add);
            ClientPacks.enable(context, TestPacks.COCKPIT_OFF);
            try {
                HudShot panel = settledShot(context, lower, "scanner-descent-panel");
                require(panel.window() == null, "with the cockpit off, the scanner should be the plain panel");
                labelProblems.addAll(labelProblems(context, lower));
                markProblem(context, panel, lower).ifPresent(labelProblems::add);
            } finally {
                ClientPacks.disable(context, TestPacks.COCKPIT_OFF);
            }

            expectMapChanged(before, after);
            expectGrainStable(before, again, after);
            expectGrain(before);
            require(labelProblems.isEmpty(), String.join("; ", labelProblems));
        }
    }

    /**
     * Fills the scan plane round the pod with stone, cuts a 3 by 3 shaft from the pod's new feet to the top of the
     * fill, and moves the pod there; returns its feet. The fill sits above the flat world's ground, so nothing but
     * stone and the shaft is in the scan.
     */
    private static BlockPos buildShaft(ClientGameTestContext context, TestServerContext server) {
        BlockPos feet = server.computeOnServer(minecraftServer -> {
            ServerPlayer player = minecraftServer.getPlayerList().getPlayers().getFirst();
            PodEntity pod = TestEntities.vehicle(player, PodEntity.class);
            ServerLevel level = (ServerLevel) pod.level();
            Direction facing = pod.getDirection();
            Direction across = facing.getClockWise();
            BlockPos at = pod.blockPosition().above(FILL_BELOW + 4);
            RoomCarver.carve(
                    level,
                    at.relative(facing, -(AREA.halfWidth() + 1))
                            .relative(across, -2)
                            .below(FILL_BELOW),
                    at.relative(facing, AREA.halfWidth() + 1)
                            .relative(across, 2)
                            .above(FILL_ABOVE),
                    Blocks.STONE.defaultBlockState());
            RoomCarver.carve(
                    level,
                    at.relative(facing, -1).relative(across, -1),
                    at.relative(facing, 1).relative(across, 1).above(FILL_ABOVE),
                    Blocks.AIR.defaultBlockState());
            moveTo(pod, at);
            return at;
        });
        ClientWait.until(
                context,
                "the stone round the shaft",
                client -> ClientLight.clientLevel(client)
                        .getBlockState(feet.below(FILL_BELOW).above())
                        .is(Blocks.STONE));
        return feet;
    }

    /** Cuts the shaft one block deeper and moves the pod down into it. */
    private static void descend(TestServerContext server, BlockPos feet) {
        server.runOnServer(minecraftServer -> {
            ServerPlayer player = minecraftServer.getPlayerList().getPlayers().getFirst();
            PodEntity pod = TestEntities.vehicle(player, PodEntity.class);
            ServerLevel level = (ServerLevel) pod.level();
            Direction facing = pod.getDirection();
            Direction across = facing.getClockWise();
            BlockPos lower = feet.below();
            RoomCarver.carve(
                    level,
                    lower.relative(facing, -1).relative(across, -1),
                    lower.relative(facing, 1).relative(across, 1),
                    Blocks.AIR.defaultBlockState());
            moveTo(pod, lower);
        });
    }

    /** Moves the pod, rider and all, to stand at {@code feet}; a teleport would put the rider off. */
    private static void moveTo(PodEntity pod, BlockPos feet) {
        pod.setPos(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
        pod.setDeltaMovement(Vec3.ZERO);
    }

    /**
     * Waits until the client's pod stands at {@code feet} and the scanner has rescanned there, then takes a shot of the
     * map.
     */
    private static HudShot settledShot(ClientGameTestContext context, BlockPos feet, String name) {
        ClientWait.until(
                context,
                "the client's pod at " + feet.toShortString(),
                // The client's player can be without a vehicle for a moment after the move, so the wait asks for the
                // seat instead of assuming it; a rider that never sits again times out here.
                client -> ClientSession.player(client).getVehicle() instanceof PodEntity pod
                        && pod.blockPosition().equals(feet));
        context.waitTicks(2 * TUNING.rescanTicks() + 2);
        return HudShot.take(context, name, AREA);
    }

    /** Whether the cell at ({@code ahead}, {@code up}) is stone, right of the shaft and clear of the map's edge. */
    private static boolean rock(int ahead, int up) {
        return ahead >= ROCK_FROM
                && ahead <= AREA.halfWidth() - EDGE
                && up >= -AREA.down() + EDGE
                && up <= AREA.up() - EDGE;
    }

    private static void expectMapChanged(HudShot before, HudShot after) {
        long changed = 0;
        for (int up = -AREA.down(); up <= AREA.up(); up++) {
            for (int ahead = -AREA.halfWidth(); ahead <= AREA.halfWidth(); ahead++) {
                int[] was = before.cellPixels(ahead, up);
                int[] now = after.cellPixels(ahead, up);
                for (int i = 0; i < was.length; i++) {
                    if (was[i] != now[i]) {
                        changed++;
                    }
                }
            }
        }
        require(
                changed > 0,
                "one block down through uniform rock, the scanner's picture should change, but every pixel of the map"
                        + " is the same: the descent does not show");
    }

    /**
     * A rock cell's look follows its block: the same in two frames at one depth, and after the descent the block that
     * was at {@code up} is drawn, one row up, as it was.
     */
    private static void expectGrainStable(HudShot before, HudShot again, HudShot after) {
        for (int up = -AREA.down() + EDGE; up <= AREA.up() - EDGE; up++) {
            for (int ahead = ROCK_FROM; ahead <= AREA.halfWidth() - EDGE; ahead++) {
                require(
                        Arrays.equals(before.cellPixels(ahead, up), again.cellPixels(ahead, up)),
                        "the rock cell (ahead %d, up %d) should look the same in two frames at one depth"
                                .formatted(ahead, up));
                if (rock(ahead, up + 1)) {
                    require(
                            Arrays.equals(before.cellPixels(ahead, up), after.cellPixels(ahead, up + 1)),
                            "the block at (ahead %d, up %d) should look the same one row up after the pod descends one block"
                                    .formatted(ahead, up));
                }
            }
        }
    }

    /**
     * Uniform rock shows a grain: its cells are not all drawn alike, so the picture has something to move. The grain
     * leaves each cell's mean where it was, so a band of rock keeps its contrast with the next and an ore mark with the
     * rock round it.
     */
    private static void expectGrain(HudShot shot) {
        Set<List<Integer>> looks = new HashSet<>();
        double darkest = Double.MAX_VALUE;
        double lightest = -Double.MAX_VALUE;
        for (int up = -AREA.down() + EDGE; up <= AREA.up() - EDGE; up++) {
            for (int ahead = ROCK_FROM; ahead <= AREA.halfWidth() - EDGE; ahead++) {
                looks.add(Arrays.stream(shot.cellPixels(ahead, up)).boxed().toList());
                double brightness = shot.brightness(ahead, up);
                darkest = Math.min(darkest, brightness);
                lightest = Math.max(lightest, brightness);
            }
        }
        require(
                looks.size() > 1,
                "every stone cell of the scanner is drawn the same: uniform rock has no grain to show it moving past");
        require(
                lightest - darkest <= MEAN_SPREAD,
                "the stone cells' mean brightness runs from %.2f to %.2f: the grain should move no cell's mean by more than %.0f"
                        .formatted(darkest, lightest, MEAN_SPREAD));
    }

    /**
     * The scanner draws a depth label beside some rows; each is the number the altimeter shows for a player at that
     * row's height, so the ruler reads in the readout's units with its formula. A label is centred on its row. Returns
     * what is wrong.
     */
    private static List<String> labelProblems(ClientGameTestContext context, BlockPos feet) {
        return context.computeOnClient(client -> {
            Optional<CockpitLook.Layout> seated = CockpitHud.seated(client);
            List<DrawnText.Drawn> drawn = DrawnText.of(graphics -> seated.ifPresentOrElse(
                    layout -> ScannerHud.drawInWindow(graphics, client, layout.scanner()),
                    () -> ScannerHud.extract(graphics, DeltaTracker.ZERO)));
            int guiWidth = client.getWindow().getGuiScaledWidth();
            int guiHeight = client.getWindow().getGuiScaledHeight();
            int cell = seated.map(layout -> ScannerHud.windowCellSize(layout.scanner(), AREA))
                    .orElseGet(() -> ScannerHud.cellSize(guiWidth, guiHeight, AREA));
            int top = seated.map(layout -> ScannerHud.windowCellTop(guiHeight, layout.scanner(), AREA, AREA.up()))
                    .orElseGet(() -> ScannerHud.cellTop(guiWidth, guiHeight, AREA, AREA.up()));
            return checkLabels(client, drawn, feet, cell, top, 2);
        });
    }

    /**
     * A slice wider and taller than its cockpit window keeps its ruler inside the window: at least one label is drawn,
     * whole (not cut by the window's edge), and reads as the altimeter does.
     */
    private static List<String> narrowWindowProblems(ClientGameTestContext context, BlockPos feet) {
        return context.computeOnClient(client -> {
            int guiWidth = client.getWindow().getGuiScaledWidth();
            int guiHeight = client.getWindow().getGuiScaledHeight();
            List<DrawnText.Drawn> drawn =
                    DrawnText.of(graphics -> ScannerHud.drawInWindow(graphics, client, NARROW_WINDOW));
            List<String> problems = new ArrayList<>();
            for (DrawnText.Drawn label : labels(drawn)) {
                if (label.box().x() < NARROW_WINDOW.left(guiWidth)
                        || label.box().width() != client.font.width(label.text())
                        || label.box().height() != client.font.lineHeight) {
                    problems.add("in a window smaller than the slice, the label '%s' is cut by the window's edge: %s"
                            .formatted(label.text(), label.box()));
                }
            }
            if (!problems.isEmpty()) {
                return problems;
            }
            return checkLabels(
                    client,
                    drawn,
                    feet,
                    ScannerHud.windowCellSize(NARROW_WINDOW, AREA),
                    ScannerHud.windowCellTop(guiHeight, NARROW_WINDOW, AREA, AREA.up()),
                    1);
        });
    }

    private static List<DrawnText.Drawn> labels(List<DrawnText.Drawn> drawn) {
        return drawn.stream()
                .filter(text -> DEPTH_LABEL.matcher(text.text()).matches())
                .toList();
    }

    /**
     * At least {@code least} of the depth labels in {@code drawn} are there, and each is the altimeter's reading
     * for the row it is centred on, the map's top row at {@code top} with cells {@code cell} GUI pixels.
     */
    private static List<String> checkLabels(
            Minecraft client, List<DrawnText.Drawn> drawn, BlockPos feet, int cell, int top, int least) {
        // A digit fills the top lineHeight - 2 rows of its line; its middle is the row's.
        int halfLine = (client.font.lineHeight - 2) / 2;
        List<DrawnText.Drawn> labels = labels(drawn);
        if (labels.size() < least) {
            return List.of("the scanner should draw at least %d depth labels, it drew %s"
                    .formatted(least, drawn.stream().map(DrawnText.Drawn::text).toList()));
        }
        return labels.stream()
                .flatMap(label -> {
                    int up = AREA.up() - Math.floorDiv(label.box().y() + halfLine - top, cell);
                    String reading = Altimeter.reading(ClientLight.clientLevel(client), feet.getY() + up)
                            .getString();
                    String labelled = Component.translatable("deepcharter.breach.altimeter", label.text())
                            .getString();
                    return reading.equals(labelled)
                            ? Stream.<String>empty()
                            : Stream.of(
                                    "the label '%s' sits on the row %d above the feet, where the altimeter reads '%s'"
                                            .formatted(label.text(), up, reading));
                })
                .toList();
    }

    /**
     * The ruler's mark is on the player's row, at the edge of what shows of the map: its colour is there in
     * {@code shot}.
     */
    private static Optional<String> markProblem(ClientGameTestContext context, HudShot shot, BlockPos feet) {
        int playerUp =
                context.computeOnClient(client -> ClientSession.player(client).getBlockY()) - feet.getY();
        Decal window = shot.window();
        int cell = window == null
                ? ScannerHud.cellSize(shot.guiWidth(), shot.guiHeight(), AREA)
                : ScannerHud.windowCellSize(window, AREA);
        int edge = window == null
                ? ScannerHud.cellLeft(shot.guiWidth(), shot.guiHeight(), AREA, -AREA.halfWidth())
                : Math.max(
                        ScannerHud.windowCellLeft(shot.guiWidth(), window, AREA, -AREA.halfWidth()),
                        window.left(shot.guiWidth()));
        int rowTop = window == null
                ? ScannerHud.cellTop(shot.guiWidth(), shot.guiHeight(), AREA, playerUp)
                : ScannerHud.windowCellTop(shot.guiHeight(), window, AREA, playerUp);
        int x = edge * shot.scale() + 1;
        int y = rowTop * shot.scale() + cell * shot.scale() / 2;
        int found = shot.image().getRGB(x, y) & RGB;
        int expected = ScannerLook.current().rulerPodMarkColor() & RGB;
        return found == expected
                ? Optional.empty()
                : Optional.of(
                        "the ruler's mark on the player's row (%d above the feet) should be %06X at (%d, %d), it is %06X"
                                .formatted(playerUp, expected, x, y, found));
    }
}
