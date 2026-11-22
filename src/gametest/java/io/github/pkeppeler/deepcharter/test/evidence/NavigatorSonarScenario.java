package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.client.pod.SonarHud;
import io.github.pkeppeler.deepcharter.client.pod.SonarKeys;
import io.github.pkeppeler.deepcharter.client.theme.SonarLook;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodSonar;
import io.github.pkeppeler.deepcharter.pod.SonarKind;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.OreBlocks;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Evidence scenario "navigator-sonar" (#988): a mock player pilots a Prospector in a block of stone and the real client rides second, as the navigator.
 * Before the ping the scope is empty. One press of the ping key returns the contacts round the pod: ore (filled squares), a gas pocket close by (a hollow
 * square) and one far off that reads as an empty cave (a dot), a cave (dots), and a body standing in a hollow (an ore echo, since it stands still). The
 * navigator then turns toward the ore echo ahead and marks it, and the mark shows on the HUD. The echoes dim over their ten seconds, and the bar under the scope fills as the sonar clears for the next ping. The stills zoom on the scope.
 */
public class NavigatorSonarScenario extends EvidenceScenario {
    private static final int X = 1600;
    private static final int Z = 1600;
    private static final int FLOOR_Y = SKY_SLAB_Y;
    private static final int REACH = 26;
    private static final int CELL = 4;
    private static final int FRAMES_BEFORE = 6;
    private static final int FRAMES_AFTER = 24;
    private static final int TICKS_PER_FRAME = 4;
    private static final int ZOOM_MARGIN = 12;

    @Override
    protected String name() {
        return "navigator-sonar";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TwoPlayerServer two = TwoPlayerServer.start(context)) {
            two.server().runOnServer(server -> buildSite(server.overworld(), two));
            ClientWait.until(
                    context,
                    "the stone round the pod on the client",
                    client -> ClientLight.clientLevel(client)
                            .getBlockState(new BlockPos(X + 20, FLOOR_Y, Z))
                            .is(Blocks.STONE));
            two.server().runOnServer(server -> board(server.overworld(), two));
            context.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));
            ClientWait.until(
                    context,
                    "the client the navigator of the Prospector",
                    client -> ClientSession.player(client).getVehicle() instanceof PodEntity pod
                            && pod.getPassengers().size() == 2
                            && pod.getPassengers().get(1) == client.player);
            ClientWait.until(
                    context,
                    "the sonar scope on the navigator's screen",
                    client -> SonarHud.frame(client).isPresent());
            for (int i = 0; i < FRAMES_BEFORE; i++) {
                context.waitTicks(TICKS_PER_FRAME);
                frame(context);
            }
            scopeStill(context, "sonar-before-the-ping");

            context.getInput().pressKey(SonarKeys.PING);
            ClientWait.until(
                    context,
                    "the echoes of the ping on the client",
                    client -> ClientSession.player(client).getVehicle() instanceof PodEntity pod
                            && PodSonar.reading(pod)
                                    .filter(state -> !state.echoes().isEmpty())
                                    .isPresent());
            context.waitTicks(TICKS_PER_FRAME);
            scopeStill(context, "sonar-ping-echoes");
            // The navigator turns toward the ore echo ahead and marks it: it appears on the HUD of everyone riding the
            // pod, the pilot included.
            context.runOnClient(NavigatorSonarScenario::lookAtTheOreEcho);
            context.waitTicks(TICKS_PER_FRAME);
            scopeStill(context, "sonar-aiming-at-an-echo");
            context.getInput().pressKey(SonarKeys.MARK);
            ClientWait.until(
                    context,
                    "the mark on the pod's picture on the client",
                    client -> ClientSession.player(client).getVehicle() instanceof PodEntity pod
                            && PodSonar.reading(pod)
                                    .filter(state -> !state.marks().isEmpty())
                                    .isPresent());
            context.waitTicks(TICKS_PER_FRAME);
            scopeStill(context, "sonar-mark-on-the-hud");
            for (int i = 0; i < FRAMES_AFTER; i++) {
                context.waitTicks(TICKS_PER_FRAME);
                frame(context);
            }
            scopeStill(context, "sonar-echoes-fading");
        }
    }

    /** Turns the local player to look at the middle of the first ore echo of the pod's picture. */
    private static void lookAtTheOreEcho(Minecraft client) {
        LocalPlayer player = ClientSession.player(client);
        PodEntity pod = TestEntities.vehicle(player, PodEntity.class);
        BlockPos echo = PodSonar.reading(pod).orElseThrow().echoes().stream()
                .filter(e -> e.kind() == SonarKind.ORE)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the ping returned no ore echo"))
                .pos();
        Vec3 to = Vec3.atCenterOf(echo).subtract(player.getEyePosition());
        player.setYRot((float) Math.toDegrees(Math.atan2(-to.x, to.z)));
        player.setXRot((float) -Math.toDegrees(Math.atan2(to.y, Math.hypot(to.x, to.z))));
    }

    /** A still of the whole screen and its zoom on the scope, as the navigator reads it. */
    private void scopeStill(ClientGameTestContext context, String name) {
        screenshot(context, name);
        frame(context);
        int[] scope = context.computeOnClient(client -> new int[] {
            SonarLook.current().margin(),
            (client.getWindow().getGuiScaledHeight() - SonarLook.current().scopePixels()) / 2,
            SonarLook.current().scopePixels()
        });
        zoomStill(
                context,
                name,
                name + "-zoom",
                Math.max(0, scope[0] - 2),
                Math.max(0, scope[1] - ZOOM_MARGIN),
                scope[2] + 4,
                scope[2] + 2 * ZOOM_MARGIN);
    }

    /** A block of stone round the pod, with its cab room, and what the ping will find. All in the overworld's sky slab. */
    private static void buildSite(ServerLevel level, TwoPlayerServer two) {
        RoomCarver.carve(
                level,
                new BlockPos(X - REACH, FLOOR_Y - REACH, Z - REACH),
                new BlockPos(X + REACH, FLOOR_Y + REACH, Z + REACH),
                Blocks.STONE.defaultBlockState());
        // The cab: the four cells that share the pod's corner.
        room(level, X - CELL, FLOOR_Y, Z - CELL, 2, 2);
        // Ahead (south): a cave, then ore above it. East: ore and a gas pocket close by. West: gas far off, which reads
        // as a cave.
        room(level, X - CELL, FLOOR_Y, Z + 3 * CELL, 2, 2);
        for (BlockPos ore : List.of(
                new BlockPos(X, FLOOR_Y + 3, Z + 9),
                new BlockPos(X + 10, FLOOR_Y + 1, Z + 2),
                new BlockPos(X + 7, FLOOR_Y - 5, Z - 6),
                new BlockPos(X - 11, FLOOR_Y + 6, Z - 3))) {
            put(level, ore, OreBlocks.of(OreType.IRONIUM));
        }
        put(level, new BlockPos(X + 8, FLOOR_Y + 1, Z - 9), HazardBlocks.GAS_POCKET);
        put(level, new BlockPos(X - 18, FLOOR_Y + 1, Z + 6), HazardBlocks.GAS_POCKET);
        // A body standing in a hollow of its own, east: it is in the echo tag the test mod adds, so it returns an ore
        // echo.
        room(level, X + 4 * CELL, FLOOR_Y, Z - 2 * CELL, 1, 1);
        ArmorStand body = TestEntities.create(EntityTypes.ARMOR_STAND, level);
        body.setNoGravity(true);
        body.setPos(X + 4 * CELL + 2, FLOOR_Y + 1, Z - 2 * CELL + 2);
        level.addFreshEntity(body);
        ServerPlayer real = real(level, two);
        real.teleportTo(level, X, FLOOR_Y, Z, Set.of(), 0f, 0f, true);
        two.mock().teleportTo(level, new Vec3(X, FLOOR_Y, Z), 0f, 0f);
    }

    private static void room(ServerLevel level, int x, int y, int z, int cellsX, int cellsZ) {
        RoomCarver.carve(
                level,
                new BlockPos(x, y, z),
                new BlockPos(x + cellsX * CELL - 1, y + CELL - 1, z + cellsZ * CELL - 1),
                Blocks.AIR.defaultBlockState());
    }

    private static void put(ServerLevel level, BlockPos pos, Block block) {
        level.setBlock(pos, block.defaultBlockState(), 3);
    }

    private static ServerPlayer real(ServerLevel level, TwoPlayerServer two) {
        return level.getServer().getPlayerList().getPlayers().stream()
                .filter(player -> player != two.mock().player())
                .findFirst()
                .orElseThrow();
    }

    /** Seats the mock first and the real player second in a new Prospector at the corner of the cab's cells, facing south. */
    private static void board(ServerLevel level, TwoPlayerServer two) {
        PodEntity pod = TestEntities.create(PodRegistry.PROSPECTOR, level);
        pod.setPos(X, FLOOR_Y, Z);
        pod.setYRot(0f);
        pod.setFuel(100f);
        level.addFreshEntity(pod);
        if (!two.mock().player().startRiding(pod) || !real(level, two).startRiding(pod)) {
            throw new AssertionError("Both players should board the Prospector");
        }
    }
}
