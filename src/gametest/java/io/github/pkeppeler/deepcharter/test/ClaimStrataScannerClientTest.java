package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.theme.ScannerLook;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.scanner.ScanArea;
import io.github.pkeppeler.deepcharter.scanner.ScannerTuning;
import io.github.pkeppeler.deepcharter.test.ScannerHudTest.HudShot;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * #1277: layer 1's strata read as bands on the scanner. A row of cells beside the pod holds, top zone first, Topsoil
 * Claims' hardpan, Stone Benches' packed shale and Deep Claim's black shale, with a gap of open space after each.
 * Measured at the cells' size on screen, once with large cells (tier 1) and once with the smallest the game draws (tier
 * 2), each stratum's cell must be clearly apart from the next one's, so a contact shows as you descend, and from open
 * space, so a cave in it still reads as a cave. /
public class ClaimStrataScannerClientTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("ClaimStrataScanner");
    private static final ScannerTuning TUNING = ScannerTuning.DEFAULT;
    private static final int RGB = 0xFFFFFF;
    private static final int ROW = ScannerHudTest.GOLD_UP;
    private static final int FIRST = 1;
    /** How far apart (RGB distance of the cells' mean colours, of 441) two neighbouring strata must be for a contact to
     * show. */
    private static final double STRATA_DISTANCE = 20;
    /** How far from open space's colour a stratum's cell must be for a cave in it to read. */
    private static final double AIR_DISTANCE = 25;

    private static final List<Block> STRATA = List.of(block("hardpan"), block("packed_shale"), block("black_shale"));

    private static Block block(String path) {
        return BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path));
    }

    /** The cell ahead of the pod that holds stratum {@code index}: each is followed by a cell of open space. */
    private static int ahead(int index) {
        return FIRST + 2 * index;
    }

    /** Mounts the first player on a new pod with a scanner of {@code tier} and builds the row, each cell walled in
     * stone so it stays put. */
    private static void buildRow(ClientGameTestContext context, TestServerContext server, int tier) {
        ScannerHudTest.mountFirstPlayer(server, tier);
        ClientWait.until(
                context,
                "the client riding a pod",
                client -> client.player != null && client.player.getVehicle() instanceof PodEntity);
        BlockPos last = server.computeOnServer(minecraftServer -> {
            ServerPlayer player = minecraftServer.getPlayerList().getPlayers().getFirst();
            PodEntity pod = TestEntities.vehicle(player, PodEntity.class);
            ServerLevel level = (ServerLevel) pod.level();
            Direction facing = pod.getDirection();
            BlockPos row = pod.blockPosition().above(ROW);
            BlockPos at = row;
            for (int i = 0; i < STRATA.size(); i++) {
                at = row.relative(facing, ahead(i));
                level.setBlock(at, STRATA.get(i).defaultBlockState(), 3);
                level.setBlock(at.relative(facing), Blocks.AIR.defaultBlockState(), 3);
            }
            return at;
        });
        Block lastStratum = STRATA.getLast();
        ClientWait.until(
                context,
                "the row of strata",
                client -> ClientLight.clientLevel(client).getBlockState(last).is(lastStratum));
        context.waitTicks(2 * TUNING.rescanTicks() + 2);
    }

    /** The mean RGB of a cell's screen pixels. */
    private static double[] mean(HudShot shot, int ahead) {
        int[] pixels = shot.cellPixels(ahead, ROW);
        double[] out = new double[3];
        for (int pixel : pixels) {
            out[0] += (pixel >> 16) & 0xFF;
            out[1] += (pixel >> 8) & 0xFF;
            out[2] += pixel & 0xFF;
        }
        for (int i = 0; i < 3; i++) {
            out[i] /= pixels.length;
        }
        return out;
    }

    private static double distance(double[] a, double[] b) {
        return Math.sqrt(Math.pow(a[0] - b[0], 2) + Math.pow(a[1] - b[1], 2) + Math.pow(a[2] - b[2], 2));
    }

    private static void measure(ClientGameTestContext context, String name, ScanArea area) {
        HudShot shot = HudShot.take(context, name, area);
        int air = ScannerLook.current().airColor() & RGB;
        double[] open = {(air >> 16) & 0xFF, (air >> 8) & 0xFF, air & 0xFF};
        List<double[]> means = new ArrayList<>();
        for (int i = 0; i < STRATA.size(); i++) {
            require(
                    !shot.isOpenSpace(ahead(i), ROW),
                    "%s: the cell of %s should draw its texture, not open space".formatted(name, STRATA.get(i)));
            require(
                    shot.isOpenSpace(ahead(i) + 1, ROW),
                    "%s: the cell after %s should be open space".formatted(name, STRATA.get(i)));
            means.add(mean(shot, ahead(i)));
            double fromAir = distance(means.getLast(), open);
            LOGGER.info(
                    "[claim-strata] {}: {} mean {}, {} from open space",
                    name,
                    STRATA.get(i),
                    Arrays.toString(means.getLast()),
                    String.format(Locale.ROOT, "%.1f", fromAir));
            require(
                    fromAir >= AIR_DISTANCE,
                    "%s: %s is %.1f from open space's colour, under %.0f: a cave in it would not read"
                            .formatted(name, STRATA.get(i), fromAir, AIR_DISTANCE));
        }
        for (int i = 1; i < STRATA.size(); i++) {
            double apart = distance(means.get(i - 1), means.get(i));
            LOGGER.info(
                    "[claim-strata] {}: {} to {}: {}",
                    name,
                    STRATA.get(i - 1),
                    STRATA.get(i),
                    String.format(Locale.ROOT, "%.1f", apart));
            require(
                    apart >= STRATA_DISTANCE,
                    "%s: %s and %s are %.1f apart on the scanner, under %.0f: their contact would not show"
                            .formatted(name, STRATA.get(i - 1), STRATA.get(i), apart, STRATA_DISTANCE));
        }
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            server.runCommand("time set midnight");
            context.waitTicks(20);
            buildRow(context, server, 1);
            measure(context, "claim-strata-large", TUNING.area(1));
            ScannerHudTest.leavePod(context, server);
            buildRow(context, server, 2);
            measure(context, "claim-strata-small", TUNING.area(2));
        }
    }
}
