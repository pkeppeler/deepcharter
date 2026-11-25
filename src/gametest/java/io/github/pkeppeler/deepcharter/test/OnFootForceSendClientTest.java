package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.ore.SlagBrick;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodTether;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.ForceSendProbe;
import io.github.pkeppeler.deepcharter.test.support.ForceSends;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client GameTest for #1317: a real client on foot in a bore carved in the campaign world, dropped onto each of our
 * blocks, standing, walking and hauled up by the tether's winch, makes vanilla's server log no "standing on air -
 * force-sending blocks below". That line means the client reports on-ground where the server's last move found
 * nothing below, so a block the client holds and the server does not would make it. A mock player cannot show that:
 * only a real client holds its own copy of the chunks and does its own collision.
 *
 * <p>Jumping is left out: vanilla's check is a false alarm on a jump's take-off and landing, with none of our blocks
 * near. So is a lagging server, which makes the line on plain stone too (#1343): the render distance is small, so the
 * server generates nothing far off, and the player is counted only once the client holds every chunk in view and the
 * server is calm. Each step records the server's slowest tick, so a red run tells lag from a block disagreement.
 *
 * <p>A landing can still make the line over any floor, by a race in vanilla ({@link #landingRace}). So {@code
 * ForceSendProbe} records each line with the move packets before it and the blocks round the player on the server and
 * the client, and a line passes only when it is that race and both sides hold the same blocks.
 *
 * <p>The control: the server removes the stone patch without telling the client, and the player drops onto it again.
 * The line must come, so a zero above is not the appender or the line's wording failing.
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
    /** The walls of the room above the bore. */
    private static final int ROOM_RADIUS = 8;

    /** How far the pod stands from the bore's centre. */
    private static final double POD_OFFSET = 5.5;

    /**
     * A pit as deep as the bore, across the room from the pod: a person at its foot is past the tether's length, so the
     * taut line holds them. It stays in the bore's chunk, so the player's view never moves.
     */
    private static final int PIT_DX = -7;

    /** Long enough to fall the pit and hang on the line. */
    private static final int HANG_TICKS = 60;

    /** Chunks; the integrated server's view distance follows it, so nothing far off generates while counting. */
    private static final int RENDER_DISTANCE = 2;

    private static final Logger LOGGER = LoggerFactory.getLogger("OnFootForceSendClientTest");

    private static final AtomicLong SLOWEST_TICK = new AtomicLong();
    private static final AtomicLong LONGEST_GAP = new AtomicLong();
    private static final AtomicLong LAST_TICK_END = new AtomicLong();
    private static volatile boolean watching;
    /** Each force-send line, as the server and then the client saw it, while {@link #watching}. */
    private static final ConcurrentLinkedQueue<Seen> FORCE_SENDS = new ConcurrentLinkedQueue<>();

    private static volatile int switchTick;

    static {
        ServerTickEvents.END_SERVER_TICK.register(OnFootForceSendClientTest::watchTick);
        ClientTickEvents.END_CLIENT_TICK.register(OnFootForceSendClientTest::probeClient);
    }

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
            ClientChecks.withRenderDistance(context, RENDER_DISTANCE, () -> {
                try {
                    count(context, singleplayer.getServer());
                } finally {
                    watching = false;
                    ForceSendProbe.watch(false);
                    FORCE_SENDS.clear();
                }
            });
        }
    }

    private static void count(ClientGameTestContext context, TestServerContext server) {
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
        ClientWait.chunksAround(context, X, X, RENDER_DISTANCE * SectionPos.SECTION_SIZE);
        ClientWait.lightSettled(context, server, 1, X, X, ROOM_RADIUS);
        awaitCalm(context, server);
        // Vanilla logs at most one line in 200 ticks per player, so a setup line's cooldown would hide a measured line.
        int setup = 0;
        while (ForceSends.total() - start > setup) {
            setup = ForceSends.total() - start;
            context.waitTicks(LINE_COOLDOWN_TICKS); // tick-wait: a setup line's cooldown would hide a measured line
            awaitCalm(context, server);
        }
        switchTick = server.computeOnServer(s -> {
            player(s).setGameMode(GameType.SURVIVAL);
            return s.getTickCount();
        });
        int before = ForceSends.total();
        startWatching();
        ForceSendProbe.watch(true);
        LOGGER.info("Counting force-send lines from here; {} in the setup", setup);
        Steps steps = new Steps(before);
        ClientWait.until(
                context,
                "the player on foot on the bore's floor",
                client -> client.player != null && client.player.onGround());
        steps.end("on foot");

        for (Patch patch : PATCHES) {
            dropOnto(context, server, floor, patch);
            context.waitTicks(STAND_TICKS); // tick-wait: standing on the block, with the position reminders
            steps.end(BuiltInRegistries.BLOCK.getKey(patch.block()).getPath());
        }
        dropOnto(context, server, floor, PATCHES.getLast());
        context.waitTicks(LONG_STAND_TICKS); // tick-wait: a cooldown window of standing
        steps.end("long stand");
        for (int lap = 0; lap < LAPS; lap++) {
            context.getInput().holdKeyFor(options -> options.keyUp, LAP_TICKS);
            context.getInput().holdKeyFor(options -> options.keyLeft, LAP_TICKS);
            context.getInput().holdKeyFor(options -> options.keyDown, LAP_TICKS);
            context.getInput().holdKeyFor(options -> options.keyRight, LAP_TICKS);
        }
        steps.end("laps");
        int onTheBlocks = ForceSends.total() - before;

        UUID podId = server.computeOnServer(s -> addPodOnALedge(s.overworld(), floor));
        ClientWait.until(context, "the pod on the client", client -> holdsEntity(client, podId));
        for (int trip = 0; trip < TETHER_TRIPS; trip++) {
            clipAtTheMouth(server, floor, podId);
            ClientWait.until(
                    context,
                    "the person on the bore floor",
                    client -> client.player != null && client.player.onGround() && client.player.getY() < floor + 2,
                    client -> "client player at " + (client.player == null ? "none" : client.player.position()));
            steps.end("tether drop " + (trip + 1));
            server.runOnServer(s -> PodTether.setWinching(player(s), true));
            ClientWait.until(
                    context,
                    "the winch to haul the person up the bore",
                    () -> server.computeOnServer(s -> !PodTether.isWinching(player(s))),
                    () -> "winching, at "
                            + server.computeOnServer(s -> player(s).position()));
            server.runOnServer(s -> PodTether.unclip(player(s), PodTether.Unclipped.UNHOOKED));
            steps.end("haul " + (trip + 1));
        }

        clipAtTheMouth(server, floor, podId);
        server.runOnServer(s ->
                player(s).teleportTo(s.overworld(), X + PIT_DX + 0.5, floor + DROP + 1, X + 0.5, Set.of(), 0, 0, true));
        context.waitTicks(HANG_TICKS); // tick-wait: the fall down the pit, and hanging on the taut line
        double feet = server.computeOnServer(
                s -> PodTether.isClipped(player(s)) ? player(s).getY() : Double.NEGATIVE_INFINITY);
        require(
                feet > floor + 0.5,
                "the taut line did not hold the person over the pit's floor: feet at " + feet + ", floor at " + floor);
        steps.end("taut hold");
        server.runOnServer(s -> PodTether.unclip(player(s), PodTether.Unclipped.UNHOOKED));
        ClientWait.until(
                context,
                "the person on the pit's floor",
                client -> client.player != null && client.player.onGround() && client.player.getY() < floor + 2,
                client -> "client player at " + (client.player == null ? "none" : client.player.position()));
        steps.end("pit floor");
        context.waitTicks(SETTLE_TICKS); // tick-wait: any last packet
        steps.end("settle");
        int total = ForceSends.total() - before;
        LOGGER.info("Force-send lines and slowest server tick per step: {}", steps);
        List<Seen> seen = List.copyOf(FORCE_SENDS);
        long disagreements = seen.stream().filter(line -> !line.landingRace()).count();
        require(
                total == seen.size() && disagreements == 0,
                total + " force-send lines, " + seen.size() + " seen by the probe, " + disagreements
                        + " not vanilla's landing race (" + onTheBlocks + " before the tether; " + steps + "; " + seen
                        + ") for a real client on foot in a carved bore, and " + setup + " in the setup");

        // No line since the count began, and a setup line was waited out before it, so vanilla's cooldown is clear.
        Patch stone = PATCHES.getLast();
        server.runOnServer(s -> s.overworld()
                .setBlock(
                        new BlockPos(X + stone.dx(), floor - 1, X + stone.dz()),
                        Blocks.AIR.defaultBlockState(),
                        Block.UPDATE_INVISIBLE));
        int beforeControl = ForceSends.total();
        FORCE_SENDS.clear();
        dropOnto(context, server, floor, stone);
        int setupLines = setup;
        ClientWait.until(
                context,
                "a force-send line for a client landing on a block the server removed unseen",
                () -> ForceSends.total() > beforeControl,
                () -> "no line; " + setupLines + " in the setup");
        ClientWait.until(
                context,
                "the probe naming the removed block as a disagreement",
                () -> FORCE_SENDS.stream().anyMatch(line -> !line.landingRace()),
                () -> "seen: " + FORCE_SENDS);
    }

    /** Clips the player to the pod from the bore's mouth, at full health, so they drop down the bore. */
    private static void clipAtTheMouth(TestServerContext server, int floor, UUID podId) {
        server.runOnServer(s -> {
            ServerPlayer player = player(s);
            player.setHealth(player.getMaxHealth());
            player.teleportTo(s.overworld(), X + 0.5, floor + DROP + 2, X + 0.5, Set.of(), 0, 0, true);
            PodTether.clip(
                    player, Objects.requireNonNull((PodEntity) s.overworld().getEntity(podId)));
        });
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

    /**
     * A pod on the ledge of a room over the bore, which the tether's route follows up and across, and the pit of
     * {@link #PIT_DX}.
     */
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
        RoomCarver.carve(level, X + PIT_DX, X + PIT_DX, floor - 1, floor - 1, X, X, Blocks.STONE);
        RoomCarver.carve(level, X + PIT_DX, X + PIT_DX, floor, ledge, X, X, Blocks.AIR);
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

    private static void startWatching() {
        LAST_TICK_END.set(0);
        SLOWEST_TICK.set(0);
        LONGEST_GAP.set(0);
        watching = true;
    }

    /**
     * A force-send line as the probe saw it.
     *
     * @param landingRace the client and server hold the same blocks round the player, and the server took the landing
     *     packet with the one before it and no tick between: see {@link #landingRace(ForceSendProbe.Shot)}
     */
    private record Seen(String text, boolean landingRace) {
        @Override
        public String toString() {
            return (landingRace ? "landing race: " : "DISAGREEMENT: ") + text;
        }
    }

    /**
     * Whether the line came from vanilla's landing race, not a block. Vanilla's check reads the collision of the
     * server's last move of the player. Between two move packets the server simulates the player for a tick, and on a
     * landing that tick's move hits the floor. When the last in-air packet and the landing packet reach the server in
     * one tick (the integrated server's connection races its tick), that move is missing: the server's last move
     * was in the air, and the line comes over any floor.
     */
    private static boolean landingRace(ForceSendProbe.Shot shot) {
        List<ForceSendProbe.Move> moves = shot.moves();
        if (moves.size() < 2) {
            return false;
        }
        ForceSendProbe.Move air = moves.get(moves.size() - 2);
        ForceSendProbe.Move landing = moves.getLast();
        return air.tick() == landing.tick() && !air.onGround() && landing.onGround();
    }

    /** Adds the client's blocks, read this tick, to each force-send the server recorded. */
    private static void probeClient(Minecraft client) {
        ForceSendProbe.Shot shot;
        while ((shot = ForceSendProbe.poll()) != null) {
            List<String> clientBlocks = new ArrayList<>();
            if (client.level != null) {
                for (BlockPos pos : shot.positions()) {
                    clientBlocks.add(ForceSendProbe.describe(client.level, pos));
                }
            }
            String text = "force-send at tick " + shot.tick() + " (" + (shot.tick() - switchTick)
                    + " after the switch to survival): moves " + shot.moves() + ", box " + shot.box() + "; server "
                    + shot.serverBlocks() + "; client " + clientBlocks + ", client player at "
                    + (client.player == null
                            ? "none"
                            : client.player.position() + " onGround " + client.player.onGround());
            Seen seen = new Seen(text, landingRace(shot) && shot.serverBlocks().equals(clientBlocks));
            LOGGER.info("{}", seen);
            FORCE_SENDS.add(seen);
        }
    }

    /** Records each server tick's time and the wall-clock gap since the last one, while {@link #watching}. */
    private static void watchTick(MinecraftServer server) {
        if (!watching) {
            return;
        }
        long now = System.nanoTime();
        long last = LAST_TICK_END.getAndSet(now);
        if (last != 0) {
            LONGEST_GAP.accumulateAndGet(now - last, Math::max);
        }
        // Vanilla writes this tick's time at the end of tickServer, before Fabric's end-of-tick event.
        SLOWEST_TICK.accumulateAndGet(server.getTickTimesNanos()[server.getTickCount() % 100], Math::max);
    }

    /**
     * The lines of each step, with the server's slowest tick and longest gap between ticks in it, so a failure says
     * where the line came and whether the server lagged then.
     */
    private static final class Steps {
        private final StringBuilder record = new StringBuilder();
        private int mark;

        Steps(int from) {
            mark = from;
        }

        void end(String name) {
            int now = ForceSends.total();
            record.append(record.isEmpty() ? "" : ", ")
                    .append(name)
                    .append(' ')
                    .append(now - mark)
                    .append(" (tick ")
                    .append(SLOWEST_TICK.getAndSet(0) / 1_000_000L)
                    .append(" ms, gap ")
                    .append(LONGEST_GAP.getAndSet(0) / 1_000_000L)
                    .append(" ms)");
            mark = now;
        }

        @Override
        public String toString() {
            return record.toString();
        }
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
