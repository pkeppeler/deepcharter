package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.ore.OreBlock;
import io.github.pkeppeler.deepcharter.ore.OreBlock.Host;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.scanner.ScannerTuning;
import io.github.pkeppeler.deepcharter.test.ScannerHudTest.HudShot;
import io.github.pkeppeler.deepcharter.test.support.OreBlocks;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * #549: the scanner draws an ore cell with the rock the ore sits in. The same ore in packed shale and in green shale has the same flecks, so
 * the step in its cell's colour between the two is the host's: it must point the way the step between the two rock cells does. A scanner that
 * read the default state of the ore would draw every ore in packed shale, and the step would be nothing.
 */
public class ScannerOreHostClientTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("ScannerOreHost");
    private static final ScannerTuning TUNING = ScannerTuning.DEFAULT;
    private static final int ROW = ScannerHudTest.GOLD_UP;
    private static final int FIRST = 1;
    /** The two rocks' cells must be this far apart (RGB distance), or the test says nothing. */
    private static final double MIN_ROCK_STEP = 20;
    /** The colour step of an ore cell between the rocks must point the way the rocks' step does (cosine). */
    private static final double MIN_COSINE = 0.9;
    /** And be at least this share of the rocks' step: the flecks cover part of the cell. */
    private static final double MIN_SHARE = 0.4;
    /** The cells from {@link #FIRST} ahead: the two rocks, then one ore in each of them. */
    private static final List<BlockState> CELLS = List.of(
            LayerBlocks.PACKED_SHALE.defaultBlockState(),
            LayerBlocks.GREEN_SHALE.defaultBlockState(),
            ore(OreType.IRONIUM, Host.PACKED_SHALE),
            ore(OreType.IRONIUM, Host.GREEN_SHALE),
            ore(OreType.SILVERIUM, Host.PACKED_SHALE),
            ore(OreType.SILVERIUM, Host.GREEN_SHALE));

    private static BlockState ore(OreType type, Host host) {
        return OreBlocks.of(type).defaultBlockState().setValue(OreBlock.HOST, host);
    }

    private static double[] mean(int[] pixels) {
        double[] mean = new double[3];
        for (int pixel : pixels) {
            mean[0] += (pixel >> 16) & 0xFF;
            mean[1] += (pixel >> 8) & 0xFF;
            mean[2] += pixel & 0xFF;
        }
        for (int i = 0; i < 3; i++) {
            mean[i] /= pixels.length;
        }
        return mean;
    }

    private static double[] minus(double[] a, double[] b) {
        return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double length(double[] a) {
        return Math.sqrt(dot(a, a));
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            server.runCommand("time set midnight");
            context.waitTicks(20);
            ScannerHudTest.rideWithGoldAhead(context, server, 1);
            for (int i = 0; i < CELLS.size(); i++) {
                ScannerHudTest.placeBesidePod(server, FIRST + i, ROW, CELLS.get(i));
            }
            context.waitTicks(2 * TUNING.rescanTicks() + 2);
            HudShot shot = HudShot.take(context, "scanner-ore-host", ScannerHudTest.TIER_ONE);
            double[] rockStep = minus(mean(shot.cellPixels(FIRST + 1, ROW)), mean(shot.cellPixels(FIRST, ROW)));
            require(length(rockStep) > MIN_ROCK_STEP, "the two rocks should look unlike each other in the scanner");
            for (int i = 2; i < CELLS.size(); i += 2) {
                double[] oreStep =
                        minus(mean(shot.cellPixels(FIRST + i + 1, ROW)), mean(shot.cellPixels(FIRST + i, ROW)));
                double along = dot(oreStep, rockStep) / (length(oreStep) * length(rockStep));
                LOGGER.info(
                        "[scanner-ore-host] {}: colour step {} (rocks {}), cosine {}",
                        CELLS.get(i).getBlock(),
                        length(oreStep),
                        length(rockStep),
                        along);
                require(
                        along > MIN_COSINE && length(oreStep) > MIN_SHARE * length(rockStep),
                        "%s: moving the ore from packed shale to green shale should move its cell the way the rock cells move (cosine %.2f, step %.0f of the rocks' %.0f)"
                                .formatted(CELLS.get(i).getBlock(), along, length(oreStep), length(rockStep)));
            }
        }
    }
}
