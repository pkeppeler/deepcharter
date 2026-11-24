package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;
import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.requireNavigator;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.client.pod.SonarHud;
import io.github.pkeppeler.deepcharter.client.pod.SonarKeys;
import io.github.pkeppeler.deepcharter.client.pod.SonarScope;
import io.github.pkeppeler.deepcharter.client.theme.SonarLook;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodSeat;
import io.github.pkeppeler.deepcharter.pod.PodSeats;
import io.github.pkeppeler.deepcharter.pod.PodSonar;
import io.github.pkeppeler.deepcharter.pod.SonarEcho;
import io.github.pkeppeler.deepcharter.pod.SonarKind;
import io.github.pkeppeler.deepcharter.pod.SonarTuning;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.OreBlocks;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Client GameTest on a real dedicated server (#988): a mock player pilots a Prospector and the real client rides it as the navigator. The
 * navigator sees the sonar scope and the pilot does not; the real ping key sends the ping, and the echo of an ore block 8 ahead of the pod lands
 * on the scope in the ore colour, at the pixel {@link SonarScope} projects it to; the mark key then marks it, as a diamond at the middle of the
 * screen where the navigator looks.
 *
 * <p>Roles come from the server's seat assignment (#1088): a navigator the assignment has not reached yet, or whose pilot is missing from the
 * client, keeps the scope and can ping. When the pilot gets off, the client is the pilot and the scope is gone.
 */
public class SonarHudClientTest implements FabricClientGameTest {
    static final int X = 1600;
    static final int Z = 1600;
    private static final int FLOOR_Y = TestLayers.worldY(TestLayers.SURFACE, 200);
    private static final int ORE_AHEAD = 8;
    static final int SITE_RADIUS = 12;
    private static final int CELL = 4;
    /** How far a channel of the drawn pixel may be from the echo's colour: the echo is blended over the scope's face at its fading alpha. */
    private static final int CHANNEL_TOLERANCE = 80;
    /** A GUI pixel off the middle both ways: inside the mark's diamond, and off the crosshair's two lines. */
    private static final int MARK_OFFSET = 2;

    private static final int MARK_TOLERANCE = 8;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TwoPlayerServer two = TwoPlayerServer.start(context)) {
            two.server().runOnServer(server -> buildSite(server.overworld(), two));
            // Every chunk of the site must be on the client before anyone boards (#1082).
            ClientWait.chunksAround(context, X, Z, SITE_RADIUS);
            int podId = two.server().computeOnServer(server -> board(server.overworld(), two));
            ClientWait.until(
                    context,
                    "the client riding the pod as its navigator",
                    client -> client.player != null
                            && client.player.getVehicle() instanceof PodEntity pod
                            && pod.getId() == podId
                            && pod.getPassengers().size() == 2
                            && pod.getPassengers().get(1) == client.player);
            ClientWait.until(
                    context,
                    "the sonar scope for the navigator",
                    client -> SonarHud.frame(client).isPresent());
            ScopeShot before = ScopeShot.take(context, "sonar-navigator-before");
            requireNavigator(context);
            context.getInput().pressKey(SonarKeys.PING);
            ClientWait.until(
                    context,
                    "the ping's echoes on the client",
                    client -> ClientSession.player(client).getVehicle() instanceof PodEntity pod
                            && PodSonar.reading(pod)
                                    .filter(state -> !state.echoes().isEmpty())
                                    .isPresent());
            Optional<SonarEcho> ore = context.computeOnClient(client -> {
                PodEntity pod = TestEntities.vehicle(ClientSession.player(client), PodEntity.class);
                return PodSonar.reading(pod).orElseThrow().echoes().stream()
                        .filter(echo -> echo.kind() == SonarKind.ORE)
                        .findFirst();
            });
            require(ore.isPresent(), "the ping should echo the ore block as an ore contact");
            ScopeShot after = ScopeShot.take(context, "sonar-navigator-after");
            int[] at = context.computeOnClient(client -> {
                PodEntity pod = TestEntities.vehicle(ClientSession.player(client), PodEntity.class);
                SonarScope.Dot dot = SonarScope.project(
                                pod.position(),
                                pod.getDirection(),
                                ore.get().pos(),
                                SonarTuning.DEFAULT.rangeBlocks(),
                                SonarLook.current().scopePixels() / 2)
                        .orElseThrow();
                return after.centre(dot.x(), dot.y());
            });
            int drawn = after.image.getRGB(at[0], at[1]);
            int expected = SonarLook.current().oreColor();
            for (int shift = 0; shift <= 16; shift += 8) {
                int got = drawn >> shift & 0xFF;
                int want = expected >> shift & 0xFF;
                require(
                        Math.abs(got - want) <= CHANNEL_TOLERANCE,
                        "the ore echo should be drawn in the ore colour %06X at pixel (%d, %d), it is %06X"
                                .formatted(expected & 0xFFFFFF, at[0], at[1], drawn & 0xFFFFFF));
            }
            require(
                    before.image.getRGB(at[0], at[1]) != drawn,
                    "the scope at the ore's pixel should change when the ping lands");

            // The navigator turns to the ore echo and marks it: a diamond in the ore colour sits at the middle of the
            // screen, where it looks.
            context.runOnClient(client -> {
                LocalPlayer player = ClientSession.player(client);
                Vec3 to = Vec3.atCenterOf(ore.get().pos()).subtract(player.getEyePosition());
                player.setYRot((float) Math.toDegrees(Math.atan2(-to.x, to.z)));
                player.setXRot((float) -Math.toDegrees(Math.atan2(to.y, Math.hypot(to.x, to.z))));
            });
            context.getInput().pressKey(SonarKeys.MARK);
            ClientWait.until(
                    context,
                    "the mark on the pod's picture on the client",
                    client -> ClientSession.player(client).getVehicle() instanceof PodEntity pod
                            && PodSonar.reading(pod)
                                    .filter(state -> state.marks().size() == 1
                                            && state.marks()
                                                    .get(0)
                                                    .pos()
                                                    .equals(ore.get().pos()))
                                    .isPresent());
            ScopeShot marked = ScopeShot.take(context, "sonar-navigator-marked");
            int[] middle = context.computeOnClient(client -> new int[] {
                (client.getWindow().getGuiScaledWidth() / 2 + MARK_OFFSET)
                        * client.getWindow().getGuiScale(),
                (client.getWindow().getGuiScaledHeight() / 2 + MARK_OFFSET)
                        * client.getWindow().getGuiScale()
            });
            int diamond = marked.image.getRGB(middle[0], middle[1]);
            for (int shift = 0; shift <= 16; shift += 8) {
                require(
                        Math.abs((diamond >> shift & 0xFF) - (expected >> shift & 0xFF)) <= MARK_TOLERANCE,
                        "the mark should be a diamond in the ore colour %06X at the middle of the screen, the pixel there is %06X"
                                .formatted(expected & 0xFFFFFF, diamond & 0xFFFFFF));
            }

            // A pod entity the server's assignment has not reached yet (new after a crossing, or a boarding after the
            // pod's tick)
            // leaves no rider without a role: the visible riders take their places in the passenger list.
            Versioned<PodSeats.State> assigned = context.computeOnClient(
                    client -> TestEntities.vehicle(ClientSession.player(client), PodEntity.class)
                            .getAttached(PodSeats.STATE));
            context.runOnClient(client -> TestEntities.vehicle(ClientSession.player(client), PodEntity.class)
                    .setAttached(PodSeats.STATE, Versioned.of(PodSeats.State.EMPTY)));
            require(
                    context.computeOnClient(client -> PodSeat.isNavigator(
                                    TestEntities.vehicle(ClientSession.player(client), PodEntity.class),
                                    ClientSession.player(client))
                            && SonarHud.frame(client).isPresent()),
                    "a navigator the seat assignment has not reached still has its role and its scope");
            context.runOnClient(client -> TestEntities.vehicle(ClientSession.player(client), PodEntity.class)
                    .setAttached(PodSeats.STATE, assigned));
            // The pilot's chunk is queued for the navigator's client (#1088): the pilot is missing from the client's
            // level, and the
            // pod's passengers there are the navigator alone. The seat is the server's, so the navigator keeps the
            // scope and can
            // still ping.
            long pingedBefore = context.computeOnClient(
                    client -> PodSonar.reading(TestEntities.vehicle(ClientSession.player(client), PodEntity.class))
                            .orElseThrow()
                            .pingedAt());
            int pilotId =
                    two.server().computeOnServer(server -> two.mock().player().getId());
            context.runOnClient(
                    client -> ClientLight.clientLevel(client).removeEntity(pilotId, Entity.RemovalReason.DISCARDED));
            ClientWait.until(
                    context,
                    "the pilot missing from the client's pod",
                    client -> ClientSession.player(client).getVehicle() instanceof PodEntity pod
                            && pod.getPassengers().size() == 1);
            requireNavigator(context);
            ClientWait.until(
                    context,
                    "the sonar cooling down for the next ping",
                    () -> context.computeOnClient(client -> {
                        PodEntity pod = TestEntities.vehicle(ClientSession.player(client), PodEntity.class);
                        return ClientLight.clientLevel(client).getGameTime()
                                >= PodSonar.reading(pod).orElseThrow().readyAt();
                    }),
                    () -> "no cooldown end");
            requireNavigator(context);
            context.getInput().pressKey(SonarKeys.PING);
            ClientWait.until(
                    context,
                    "a second ping from the navigator whose pilot is not on the client",
                    client -> PodSonar.reading(TestEntities.vehicle(ClientSession.player(client), PodEntity.class))
                            .filter(state -> state.pingedAt() > pingedBefore)
                            .isPresent());

            two.server().runOnServer(server -> two.mock().player().stopRiding());
            ClientWait.until(
                    context,
                    "the client alone in the pod, as its pilot",
                    client -> ClientSession.player(client).getVehicle() instanceof PodEntity pod
                            && pod.getPassengers().size() == 1
                            && PodSeats.pilot(pod)
                                    .filter(ClientSession.player(client).getUUID()::equals)
                                    .isPresent());
            context.waitTicks(2);
            require(
                    !context.computeOnClient(client -> SonarHud.frame(client).isPresent()),
                    "the pilot of a Prospector sees no sonar scope: it is the navigator's");
        }
    }

    /** A screenshot with the geometry of the scope: where its middle pixel is on the screen. */
    private record ScopeShot(BufferedImage image, int guiHeight, int scale) {
        static ScopeShot take(ClientGameTestContext context, String name) {
            requireNavigator(context);
            int[] window = context.computeOnClient(client -> new int[] {
                client.getWindow().getWidth(),
                client.getWindow().getGuiScaledHeight(),
                client.getWindow().getGuiScale()
            });
            context.runOnClient(client -> client.gui.toastManager().clear());
            Path file = context.takeScreenshot(name);
            BufferedImage image;
            try {
                image = ImageIO.read(file.toFile());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            require(
                    image.getWidth() == window[0],
                    "the screenshot is " + image.getWidth() + " wide but the window is " + window[0]);
            return new ScopeShot(image, window[1], window[2]);
        }

        /** The screen pixel at the middle of the GUI pixel {@code dx} right and {@code dy} below the scope's middle. */
        int[] centre(int dx, int dy) {
            SonarLook look = SonarLook.current();
            int radius = look.scopePixels() / 2;
            int guiX = look.margin() + radius + dx;
            int guiY = (guiHeight - look.scopePixels()) / 2 + radius + dy;
            return new int[] {guiX * scale + scale / 2, guiY * scale + scale / 2};
        }
    }

    /**
     * A cube of stone round the pod's corner with the cab's four cells opened, and one ore block {@link #ORE_AHEAD} blocks south of the pod. Both
     * players stand at the pod before it is spawned, so the chunks are theirs.
     */
    static void buildSite(ServerLevel level, TwoPlayerServer two) {
        RoomCarver.carve(
                level,
                new BlockPos(X - SITE_RADIUS, FLOOR_Y - SITE_RADIUS, Z - SITE_RADIUS),
                new BlockPos(X + SITE_RADIUS, FLOOR_Y + SITE_RADIUS, Z + SITE_RADIUS),
                Blocks.STONE.defaultBlockState());
        RoomCarver.carve(
                level,
                new BlockPos(X - CELL, FLOOR_Y, Z - CELL),
                new BlockPos(X + CELL - 1, FLOOR_Y + CELL - 1, Z + CELL - 1),
                Blocks.AIR.defaultBlockState());
        level.setBlock(
                new BlockPos(X, FLOOR_Y + 1, Z + ORE_AHEAD),
                OreBlocks.of(OreType.IRONIUM).defaultBlockState(),
                3);
        ServerPlayer real = real(level, two);
        real.teleportTo(level, X, FLOOR_Y, Z, Set.of(), 0f, 0f, true);
        two.mock().teleportTo(level, new Vec3(X, FLOOR_Y, Z), 0f, 0f);
    }

    private static ServerPlayer real(ServerLevel level, TwoPlayerServer two) {
        return level.getServer().getPlayerList().getPlayers().stream()
                .filter(player -> player != two.mock().player())
                .findFirst()
                .orElseThrow();
    }

    /** Seats the mock first and the real player second in a new Prospector at the corner of the cab's cells, facing south. Returns the pod's id. */
    static int board(ServerLevel level, TwoPlayerServer two) {
        ServerPlayer real = real(level, two);
        PodEntity pod = TestEntities.create(PodRegistry.PROSPECTOR, level);
        pod.setPos(new Vec3(X, FLOOR_Y, Z));
        pod.setYRot(0f);
        pod.setFuel(100f);
        level.addFreshEntity(pod);
        require(
                two.mock().player().startRiding(pod) && real.startRiding(pod),
                "Both players should board the Prospector");
        return pod.getId();
    }
}
