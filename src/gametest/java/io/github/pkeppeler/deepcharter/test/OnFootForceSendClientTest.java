package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.ore.SlagBrick;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodTether;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.ForceSends;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Client GameTest for #1317: a real client on foot in a bore carved in the campaign world, dropped onto each of our
 * blocks, standing, walking and hauled up by the tether's winch, makes vanilla's server log no "standing on air -
 * force-sending blocks below". That line means the client reports on-ground where the server's last move found
 * nothing below, so a block the client holds and the server does not would make it. A mock player cannot show that:
 * only a real client holds its own copy of the chunks and does its own collision.
 *
 * <p>Jumping is left out on purpose. Vanilla's own check is a false alarm on a jump's take-off and landing, one tick
 * apart between client and server: a client holding the jump key on a plain stone slab in the End logged the line
 * too, with none of our blocks near.
 *
 * <p>Nor is a lagging server. A real client dropped on plain stone logged the line in 2 runs of 14, each 12 to 13 s
 * after the server fell over 130 ticks behind; lag is tracked in #1343. So the player is a spectator until the client
 * holds the carved bore, its light is settled and the server is calm, and only then on foot and counted: this test
 * pins that our blocks make no disagreement on a calm server. Vanilla logs at most one line in 200 ticks per player,
 * so a setup line would hide a measured one: the count waits that out first.
 *
 * <p>The control: the server then removes the stone patch without telling the client, and the player drops onto it
 * again. The client lands on a block the server lacks, and the line must come, so a zero above is not the appender or
 * the line's wording failing.
 */
public class OnFootForceSendClientTest implements FabricClientGameTest {
    private static final int X = 29000;
    private static final int DROP = 12;
    private static final int STAND_TICKS = 60;
    /** Past one of vanilla's 200-tick windows between lines. */
    private static final int LONG_STAND_TICKS = 220;

    private static final int LAPS = 4;
    private static final int LAP_TICKS = 15;
    private static final int TETHER_TRIPS = 3;
    private static final int SETTLE_TICKS = 40;
    /** More than vanilla's 200 ticks between two lines for one player. */
    private static final int LINE_COOLDOWN_TICKS = 220;
    /** One server tick's budget at 20 ticks a second. */
    private static final long TICK_BUDGET_NANOS = 50_000_000L;
    /**
     * How long the server may take to calm down. Short enough that a server that never does fails here, naming its tick
     * times, before the client-test watchdog's limit.
     */
    private static final int CALM_SECONDS = 30;
    /** The walls of the room above the bore, and how far the pod stands from its centre. */
    private static final int ROOM_RADIUS = 8;

    private static final double POD_OFFSET = 5.5;

    /** Vanilla sends nothing in the first 200 ticks of a connection. */
    private static final int SILENT_TICKS = 260;

    /** The blocks of the bore's floor, each in a column of its own: {@code dx, dz} from the bore's centre. */
    private record Patch(int dx, int dz, Block block) {}

    private static final List<Patch> PATCHES = List.of(
            new Patch(-1, -1, SlagBrick.BLOCK),
            new Patch(0, -1, LayerBlocks.BREACH_CRUST),
            new Patch(1, -1, LayerBlocks.GRAINED_CRUST),
            new Patch(-1, 1, LayerBlocks.COLUMNAR_BASALT),
            new Patch(1, 1, LayerBlocks.PACKED_SHALE_RUBBLE),
            new Patch(0, 0, Blocks.STONE));

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            ClientWait.until(
                    context, "the player in the world", client -> client.player != null && client.level != null);
            TestServerContext server = singleplayer.getServer();
            int start = ForceSends.total();
            int floor = CampaignBands.LAYER_1.minY() + 60;
            context.waitTicks(SILENT_TICKS); // tick-wait: the connection's first 200 ticks log nothing
            server.runOnServer(s -> {
                ServerPlayer player = player(s);
                player.setGameMode(GameType.SPECTATOR);
                player.teleportTo(s.overworld(), X + 0.5, floor + 2, X + 0.5, Set.of(), 0, 0, true);
            });
            ClientWait.until(
                    context,
                    "the chunks round the bore on the server",
                    () -> server.computeOnServer(s -> loaded(s.overworld())),
                    () -> "not loaded");
            server.runOnServer(s -> carve(s.overworld(), floor));
            BlockPos top = new BlockPos(X, floor + DROP, X);
            ClientWait.until(
                    context,
                    "the client holding the bore",
                    client -> client.level != null
                            && client.level.getBlockState(top).isAir()
                            && client.level.getBlockState(top.atY(floor - 1)).is(Blocks.STONE));
            ClientWait.chunksAround(context, X, X, ROOM_RADIUS);
            ClientWait.lightSettled(context, server, 1, X, X, ROOM_RADIUS);
            int setup = ForceSends.total() - start;
            if (setup > 0) {
                context.waitTicks(LINE_COOLDOWN_TICKS); // tick-wait: a setup line's cooldown would hide a measured line
            }
            awaitCalm(context, server);
            server.runOnServer(s -> player(s).setGameMode(GameType.SURVIVAL));
            int before = ForceSends.total();
            // The lines of each step, so a failure says where the line came.
            StringBuilder phases = new StringBuilder();
            ClientWait.until(
                    context,
                    "the player on foot on the bore's floor",
                    client -> client.player != null && client.player.onGround());
            int mark = phase(phases, "on foot", before);

            for (Patch patch : PATCHES) {
                dropOnto(context, server, floor, patch);
                context.waitTicks(STAND_TICKS); // tick-wait: standing on the block, with the position reminders
                mark = phase(
                        phases, BuiltInRegistries.BLOCK.getKey(patch.block()).getPath(), mark);
            }
            dropOnto(context, server, floor, PATCHES.getLast());
            context.waitTicks(LONG_STAND_TICKS); // tick-wait: a cooldown window of standing
            mark = phase(phases, "long stand", mark);
            for (int lap = 0; lap < LAPS; lap++) {
                context.getInput().holdKeyFor(options -> options.keyUp, LAP_TICKS);
                context.getInput().holdKeyFor(options -> options.keyLeft, LAP_TICKS);
                context.getInput().holdKeyFor(options -> options.keyDown, LAP_TICKS);
                context.getInput().holdKeyFor(options -> options.keyRight, LAP_TICKS);
            }
            mark = phase(phases, "laps", mark);
            int onTheBlocks = ForceSends.total() - before;

            UUID podId = server.computeOnServer(s -> addPodOnALedge(s.overworld(), floor));
            ClientWait.until(context, "the pod on the client", client -> holdsEntity(client, podId));
            for (int trip = 0; trip < TETHER_TRIPS; trip++) {
                server.runOnServer(s -> {
                    ServerPlayer player = player(s);
                    player.setHealth(player.getMaxHealth());
                    player.teleportTo(s.overworld(), X + 0.5, floor + DROP + 2, X + 0.5, Set.of(), 0, 0, true);
                    PodTether.clip(player, Objects.requireNonNull((PodEntity)
                            s.overworld().getEntity(podId)));
                });
                ClientWait.until(
                        context,
                        "the person on the bore floor",
                        client -> client.player != null && client.player.onGround() && client.player.getY() < floor + 2,
                        client -> "client player at " + (client.player == null ? "none" : client.player.position()));
                mark = phase(phases, "tether drop " + (trip + 1), mark);
                server.runOnServer(s -> PodTether.setWinching(player(s), true));
                ClientWait.until(
                        context,
                        "the winch to haul the person up the bore",
                        () -> server.computeOnServer(s -> !PodTether.isWinching(player(s))),
                        () -> "winching, at "
                                + server.computeOnServer(s -> player(s).position()));
                server.runOnServer(s -> PodTether.unclip(player(s), PodTether.Unclipped.UNHOOKED));
                mark = phase(phases, "haul " + (trip + 1), mark);
            }
            context.waitTicks(SETTLE_TICKS); // tick-wait: any last packet
            phase(phases, "settle", mark);
            int total = ForceSends.total() - before;
            require(
                    total == 0,
                    total + " force-send lines (" + onTheBlocks + " before the tether; " + phases
                            + ") for a real client on foot in a carved bore, and " + setup + " in the setup");

            // No line since the count began, and a setup line was waited out before it, so vanilla's cooldown is clear.
            Patch stone = PATCHES.getLast();
            server.runOnServer(s -> s.overworld()
                    .setBlock(
                            new BlockPos(X + stone.dx(), floor - 1, X + stone.dz()),
                            Blocks.AIR.defaultBlockState(),
                            Block.UPDATE_INVISIBLE));
            int beforeControl = ForceSends.total();
            dropOnto(context, server, floor, stone);
            ClientWait.until(
                    context,
                    "a force-send line for a client landing on a block the server removed unseen",
                    () -> ForceSends.total() > beforeControl,
                    () -> "no line; " + setup + " in the setup");
        }
    }

    /**
     * A stone bed with a 3 by 3 bore over it, whose floor holds each of {@link #PATCHES}, and a room round its mouth.
     */
    private static void carve(ServerLevel level, int floor) {
        RoomCarver.carve(level, X - 3, X + 3, floor - 1, floor - 1, X - 3, X + 3, Blocks.STONE);
        RoomCarver.carve(level, X - 1, X + 1, floor, floor + DROP + 2, X - 1, X + 1, Blocks.AIR);
        for (Patch patch : PATCHES) {
            level.setBlock(
                    new BlockPos(X + patch.dx(), floor - 1, X + patch.dz()),
                    patch.block().defaultBlockState(),
                    Block.UPDATE_ALL);
        }
    }

    /** A pod on the ledge of a room over the bore, which the tether's route follows up and across. */
    private static UUID addPodOnALedge(ServerLevel level, int floor) {
        int ledge = floor + DROP;
        RoomCarver.carve(
                level, X - ROOM_RADIUS, X + ROOM_RADIUS, ledge, ledge, X - ROOM_RADIUS, X + ROOM_RADIUS, Blocks.STONE);
        RoomCarver.carve(level, X - 1, X + 1, ledge, ledge, X - 1, X + 1, Blocks.AIR);
        RoomCarver.carve(
                level,
                X - ROOM_RADIUS,
                X + ROOM_RADIUS,
                ledge + 1,
                ledge + 8,
                X - ROOM_RADIUS,
                X + ROOM_RADIUS,
                Blocks.AIR);
        PodEntity pod = TestEntities.create(PodRegistry.POD, level);
        pod.setPos(X + POD_OFFSET, ledge + 1, X + 0.5);
        level.addFreshEntity(pod);
        return pod.getUUID();
    }

    private static void dropOnto(ClientGameTestContext context, TestServerContext server, int floor, Patch patch) {
        server.runOnServer(s -> {
            ServerPlayer player = player(s);
            player.setHealth(player.getMaxHealth());
            player.teleportTo(
                    s.overworld(), X + patch.dx() + 0.5, floor + DROP, X + patch.dz() + 0.5, Set.of(), 0, 0, true);
        });
        ClientWait.until(
                context,
                "the player landing on " + patch.block(),
                client -> client.player != null && client.player.onGround() && client.player.getY() < floor + 2,
                client -> "client player at " + (client.player == null ? "none" : client.player.position()));
    }

    /**
     * Waits until every tick in the server's record of its last 100 (5 s) took under {@link #TICK_BUDGET_NANOS}: lag
     * alone can make the line (#1343), and this test is about our blocks.
     */
    private static void awaitCalm(ClientGameTestContext context, TestServerContext server) {
        FarChunks.Deadline deadline = FarChunks.deadline(CALM_SECONDS);
        while (!server.computeOnServer(s -> slowTicksMillis(s).isEmpty())) {
            if (deadline.expired()) {
                throw new AssertionError("The server never settled in " + CALM_SECONDS
                        + " s: ticks over 50 ms in its last 100, in ms: "
                        + server.computeOnServer(OnFootForceSendClientTest::slowTicksMillis));
            }
            context.waitTicks(1); // tick-wait: one tick between polls of a wall-clock wait
        }
    }

    private static List<Long> slowTicksMillis(MinecraftServer server) {
        List<Long> slow = new ArrayList<>();
        for (long nanos : server.getTickTimesNanos()) {
            if (nanos >= TICK_BUDGET_NANOS) {
                slow.add(nanos / 1_000_000L);
            }
        }
        return slow;
    }

    /** Appends the lines since {@code from} under {@code name}, and returns the count now. */
    private static int phase(StringBuilder phases, String name, int from) {
        int now = ForceSends.total();
        phases.append(phases.isEmpty() ? "" : ", ").append(name).append(' ').append(now - from);
        return now;
    }

    private static ServerPlayer player(MinecraftServer server) {
        return server.getPlayerList().getPlayers().getFirst();
    }

    private static boolean holdsEntity(Minecraft client, UUID id) {
        if (client.level == null) {
            return false;
        }
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity.getUUID().equals(id)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the server holds every chunk the bore and the room round its mouth touch. */
    private static boolean loaded(ServerLevel level) {
        for (int cx = (X - ROOM_RADIUS) >> 4; cx <= (X + ROOM_RADIUS) >> 4; cx++) {
            for (int cz = (X - ROOM_RADIUS) >> 4; cz <= (X + ROOM_RADIUS) >> 4; cz++) {
                if (level.getChunkSource().getChunkNow(cx, cz) == null) {
                    return false;
                }
            }
        }
        return true;
    }
}
