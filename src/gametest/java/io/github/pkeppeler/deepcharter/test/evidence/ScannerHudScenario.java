package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.test.ScannerHudTest;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Evidence scenario "scanner-hud": the scanner on the surface at midnight, ore appearing in the
 * map as it is placed, then the same in the shallow layer 1 with an ore-rich patch (#537), then in the dark of layer 2. There, lava in the rock shows as open space on a tier 1 scanner and
 * in its own colour on a tier 2 one (#300).
 */
public class ScannerHudScenario extends EvidenceScenario {
    private static final int FRAMES_PER_STEP = 6;
    private static final int TICKS_PER_FRAME = 3;
    private static final int LAVA_SHOT_TICKS = 100;
    /** The charter's transmission types and holds over the map for about this long after a layer is entered; the stills of the map wait it out. */
    private static final int TRANSMISSION_TICKS = 260;
    /** Lava at (ahead, up), down in the rock and inside both tiers' reach. */
    private static final int[][] LAVA = {{3, -9}, {4, -9}, {4, -10}, {-8, -15}, {-7, -15}};

    @Override
    protected String name() {
        return "scanner-hud";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            server.runCommand("time set midnight");
            context.waitTicks(40);

            ScannerHudTest.rideWithGoldAhead(context, server, 1);
            context.waitTicks(TRANSMISSION_TICKS);
            screenshot(context, "scanner-surface-midnight");
            frames(context);
            // The flat surface world has only 4 blocks of ground under the pod.
            addOre(context, server, -2, -3);

            // #537: the same scan in the shallow layer, then an ore-rich patch in it.
            ScannerHudTest.leavePod(context, server);
            ScannerHudTest.goToLayer(server, 1);
            ClientWait.until(
                    context,
                    "the client in layer 1",
                    client -> client.level.dimension().identifier().getPath().equals("layer_1"));
            context.waitTicks(40);
            RoomCarver.carveAroundFirstPlayer(server);
            ScannerHudTest.rideWithGoldAhead(context, server, 1);
            context.waitTicks(TRANSMISSION_TICKS);
            screenshot(context, "scanner-layer-1");
            frames(context);
            addOrePatch(context, server);
            screenshot(context, "scanner-ore-patch");
            frames(context);

            ScannerHudTest.leavePod(context, server);
            ScannerHudTest.goToLayer(server, 2);
            ClientWait.until(
                    context,
                    "the client in layer 2",
                    client -> client.level.dimension().identifier().getPath().equals("layer_2"));
            context.waitTicks(40);
            RoomCarver.carveAroundFirstPlayer(server);
            ScannerHudTest.rideWithGoldAhead(context, server, 1);
            context.waitTicks(TRANSMISSION_TICKS);
            screenshot(context, "scanner-layer-2");
            frames(context);
            addOre(context, server, -12, -20);

            // #300: lava in the rock below. A tier 1 scanner draws it as open space; a tier 2 scanner marks it in its
            // own colour.
            // The charter's transmission has played over the first tier 1 map by now, so the two lava shots are clear
            // of it.
            placeLava(server, Blocks.LAVA);
            ScannerHudTest.leavePod(context, server);
            mountAndShoot(context, server, 2, "scanner-layer-2-lava-tier-2");
            frames(context);
            ScannerHudTest.leavePod(context, server);
            mountAndShoot(context, server, 1, "scanner-layer-2-lava-tier-1");
            // Seal the lava in stone again so that it has no time to flow.
            placeLava(server, Blocks.STONE);
        }
    }

    private void mountAndShoot(
            ClientGameTestContext context, TestServerContext server, int scannerTier, String screenshotName) {
        ScannerHudTest.mountFirstPlayer(server, scannerTier);
        ClientWait.until(
                context,
                "the player riding a pod",
                client -> client.player != null && client.player.getVehicle() instanceof PodEntity);
        // A transmission can play over the map just after a mount; the shot waits it out. The pod stands in lava and
        // burns, but 100 hull outlasts the wait.
        context.waitTicks(LAVA_SHOT_TICKS);
        screenshot(context, screenshotName);
    }

    private static void placeLava(TestServerContext server, Block block) {
        for (int[] cell : LAVA) {
            ScannerHudTest.placeBesidePod(server, cell[0], cell[1], block);
        }
    }

    /** Diamond, then two iron, appear in the map a few ticks after they are placed. */
    private void addOre(ClientGameTestContext context, TestServerContext server, int diamondUp, int ironUp) {
        ScannerHudTest.placeBesidePod(server, -9, diamondUp, Blocks.DIAMOND_ORE);
        frames(context);
        ScannerHudTest.placeBesidePod(server, 12, ironUp, Blocks.IRON_ORE);
        ScannerHudTest.placeBesidePod(server, 13, ironUp, Blocks.IRON_ORE);
        frames(context);
    }

    /** A vein of mixed ore a few blocks below and ahead of the pod, inside tier 1's panel. */
    private void addOrePatch(ClientGameTestContext context, TestServerContext server) {
        Block[] vein = {
            Blocks.COAL_ORE,
            Blocks.IRON_ORE,
            Blocks.COPPER_ORE,
            Blocks.REDSTONE_ORE,
            Blocks.LAPIS_ORE,
            Blocks.DIAMOND_ORE,
            Blocks.EMERALD_ORE
        };
        for (int i = 0; i < 12; i++) {
            ScannerHudTest.placeBesidePod(server, -10 + (i * 5) % 7, -6 - i / 3, vein[i % vein.length]);
        }
        frames(context);
    }

    private void frames(ClientGameTestContext context) {
        for (int i = 0; i < FRAMES_PER_STEP; i++) {
            frame(context);
            context.waitTicks(TICKS_PER_FRAME);
        }
    }
}
