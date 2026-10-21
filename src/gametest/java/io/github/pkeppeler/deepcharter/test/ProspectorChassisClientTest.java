package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.client.theme.CockpitLook;
import io.github.pkeppeler.deepcharter.client.theme.PanelLook.Decal;
import io.github.pkeppeler.deepcharter.client.theme.ScannerLook;
import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.scanner.ScannerTuning;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.ScannerPods;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;
import java.awt.image.BufferedImage;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Client GameTest on a real dedicated server: a mock player pilots a Prospector and the real client rides it as the navigator. Both
 * show on the client, the navigator's screen holds the scanner (its marker fills all three cells of the Prospector) and not the pod
 * status lines, and when the pilot gets off the client takes the controls and the status lines come back.
 */
public class ProspectorChassisClientTest implements FabricClientGameTest {
    private static final int X = 1200;
    private static final int Z = 1200;
    private static final int FLOOR_Y = TestLayers.worldY(TestLayers.SURFACE, 200);
    private static final int ROOM_RADIUS = 6;
    private static final int ROOM_HEIGHT = 8;
    private static final int POD_CELLS = 3;
    private static final int RGB = 0xFFFFFF;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TwoPlayerServer two = TwoPlayerServer.start(context)) {
            UUID mockId = two.mock().player().getUUID();
            two.server().runOnServer(server -> buildRoom(server.overworld(), two));
            // The client must have the room before the players board, or the boarding reaches it before the pod does.
            ClientWait.until(
                    context,
                    "the room's stone floor and air above it",
                    client -> ClientLight.clientLevel(client)
                                    .getBlockState(new BlockPos(X, FLOOR_Y - 1, Z))
                                    .is(Blocks.STONE)
                            && ClientLight.clientLevel(client)
                                    .getBlockState(new BlockPos(X + 1, FLOOR_Y, Z))
                                    .isAir());
            int podId = two.server().computeOnServer(server -> board(server.overworld(), two));
            ClientWait.until(
                    context,
                    "the client riding the pod with two passengers",
                    client -> client.player != null
                            && client.player.getVehicle() instanceof PodEntity pod
                            && pod.getId() == podId
                            && pod.getPassengers().size() == 2);
            context.runOnClient(client -> client.gui.toastManager().clear());
            context.waitTicks(2 * ScannerTuning.DEFAULT.rescanTicks() + 2);

            boolean ridingTogether = context.computeOnClient(client -> {
                PodEntity pod = TestEntities.vehicle(ClientSession.player(client), PodEntity.class);
                return pod.chassis() == Chassis.PROSPECTOR
                        && pod.getPassengers().size() == 2
                        && pod.getControllingPassenger()
                                == ClientLight.clientLevel(client).getPlayerByUUID(mockId)
                        && pod.getPassengers().get(1) == client.player;
            });
            require(
                    ridingTogether,
                    "The client should see itself as the navigator of a two-seat Prospector piloted by the mock player");

            ScannerHudTest.HudShot navigator =
                    ScannerHudTest.HudShot.take(context, "prospector-navigator", ScannerHudTest.TIER_ONE);
            for (int up = 0; up < POD_CELLS; up++) {
                expectPixel(
                        navigator,
                        "the navigator's scanner marks cell " + up + " of the Prospector",
                        up,
                        ScannerLook.current().podColor());
            }
            expectPixel(
                    navigator,
                    "the cell above the Prospector is air",
                    POD_CELLS,
                    ScannerLook.current().airColor());
            int navigatorBars = hullBarPixels(navigator);
            require(
                    navigatorBars == 0,
                    "The navigator should see the scanner only, but " + navigatorBars
                            + " pixels of lit hull bar are on screen");

            two.server().runOnServer(server -> two.mock().player().stopRiding());
            ClientWait.until(
                    context,
                    "the pod with one passenger",
                    client -> ClientSession.player(client).getVehicle() instanceof PodEntity pod
                            && pod.getPassengers().size() == 1);
            context.waitTicks(2);
            ScannerHudTest.HudShot pilot =
                    ScannerHudTest.HudShot.take(context, "prospector-pilot", ScannerHudTest.TIER_ONE);
            require(
                    hullBarPixels(pilot) != 0,
                    "With the pilot gone the client takes the controls and should see the Ribbon's lit hull bar");
        }
    }

    /** Builds a closed stone room and puts both players in it. */
    private static void buildRoom(ServerLevel level, TwoPlayerServer two) {
        fill(level, FLOOR_Y - 1, FLOOR_Y + ROOM_HEIGHT + 1, Blocks.STONE);
        fill(level, FLOOR_Y, FLOOR_Y + ROOM_HEIGHT, Blocks.AIR, ROOM_RADIUS - 1);
        level.setBlock(new BlockPos(X, FLOOR_Y + ROOM_HEIGHT, Z), Blocks.GLOWSTONE.defaultBlockState(), 3);
        ServerPlayer real = level.getServer().getPlayerList().getPlayers().stream()
                .filter(player -> player != two.mock().player())
                .findFirst()
                .orElseThrow();
        real.teleportTo(level, X + 0.5, FLOOR_Y, Z + 3.5, Set.of(), 180f, 0f, true);
        two.mock().teleportTo(level, new Vec3(X + 0.5, FLOOR_Y, Z + 0.5), 0f, 0f);
    }

    /** Seats the mock first and the real player second in a new Prospector with a tier 1 scanner owned by the real player. Returns the pod's id. */
    private static int board(ServerLevel level, TwoPlayerServer two) {
        ServerPlayer real = level.getServer().getPlayerList().getPlayers().stream()
                .filter(player -> player != two.mock().player())
                .findFirst()
                .orElseThrow();
        PodEntity pod = TestEntities.create(PodRegistry.PROSPECTOR, level);
        pod.setPos(X + 0.5, FLOOR_Y, Z + 0.5);
        pod.setFuel(100f);
        level.addFreshEntity(pod);
        require(
                two.mock().player().startRiding(pod) && real.startRiding(pod),
                "Both players should board the Prospector");
        // Since scanner tiers, only a fitted scanner draws the panel. It is fitted after boarding: a registered pod
        // admits its crew only.
        ScannerPods.fit(level.getServer(), real, pod, 1);
        return pod.getId();
    }

    private static void fill(ServerLevel level, int yFrom, int yTo, Block block) {
        fill(level, yFrom, yTo, block, ROOM_RADIUS);
    }

    private static void fill(ServerLevel level, int yFrom, int yTo, Block block, int radius) {
        for (int x = X - radius; x <= X + radius; x++) {
            for (int y = yFrom; y <= yTo; y++) {
                for (int z = Z - radius; z <= Z + radius; z++) {
                    level.setBlock(new BlockPos(x, y, z), block.defaultBlockState(), 3);
                }
            }
        }
    }

    private static void expectPixel(ScannerHudTest.HudShot shot, String what, int up, int colour) {
        int actual = shot.pixel(0, up);
        require(
                actual == (colour & RGB),
                "%s: the pixel should be %06X, it is %06X".formatted(what, colour & RGB, actual));
    }

    /** The pixels of the hull gauge's colour on the Ribbon along the top edge: the lit segments of a full hull, which only the pilot's Ribbon draws. */
    private static int hullBarPixels(ScannerHudTest.HudShot shot) {
        CockpitLook look = CockpitLook.current();
        Decal band = look.ribbon().band();
        BufferedImage image = shot.image();
        int hull = look.hullColor() & RGB;
        int left = band.left(shot.guiWidth()) * shot.scale();
        int top = band.top(shot.guiHeight()) * shot.scale();
        int count = 0;
        for (int x = left; x < left + band.width() * shot.scale(); x++) {
            for (int y = top; y < top + band.height() * shot.scale(); y++) {
                if ((image.getRGB(x, y) & RGB) == hull) {
                    count++;
                }
            }
        }
        return count;
    }
}
