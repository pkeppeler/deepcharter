package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.colony.ColonyLayout;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.colony.FounderStatue;
import io.github.pkeppeler.deepcharter.hangar.Hangar;
import io.github.pkeppeler.deepcharter.hangar.HangarTerminal;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.layer.LightSync;
import io.github.pkeppeler.deepcharter.layer.StructureKind;
import io.github.pkeppeler.deepcharter.layer.StructureSite;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientLight.Box;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.HangarConsole;
import io.github.pkeppeler.deepcharter.test.support.TestColony;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client GameTest for #427: can a real player be left with a client light that differs from the server's? Each case places blocks as the
 * mod does, with a real client in range or arriving, waits until the server's light engine is idle, and compares the light the client
 * reads with the server's ({@link ClientLight}). Nothing in the test sends light: a mismatch is a light the client keeps.
 */
public class StaleClientLightClientTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(StaleClientLightClientTest.class);
    private static final int RADIUS = 4;
    private static final int HEIGHT = 4;
    /** Far enough from the colony and the player that no chunk of the room is in the client's view before the test sends it. */
    private static final int FAR = 1_600;
    /** The first spacing cell of the layer structures this test draws, far from the colony's Conduit. */
    private static final int CELL = 3;
    /**
     * How many times a path that races the light engine is tried. The light engine works off the server thread, so whether a chunk is sent
     * while its light is pending depends on timing: one try can pass by luck.
     */
    private static final int TRIALS = 6;

    /** One row of the result: the path, and what the two sides read when they differ. */
    private record Result(String path, Optional<String> mismatch) {}

    private final List<Result> results = new ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            ClientWait.until(
                    context, "the player in the world", client -> client.player != null && client.level != null);
            TestServerContext server = singleplayer.getServer();
            ClientWait.until(
                    context,
                    "the colony built",
                    () -> server.computeOnServer(s -> Colony.placed(s).isPresent()),
                    () -> "no colony");

            carveInRange(context, server);
            digIntoSealedRoom(context, server);
            carveThenArrive(context, server, false);
            carveThenArrive(context, server, true);
            for (int trial = 0; trial < TRIALS; trial++) {
                breachInto(context, server, 1, trial);
                breachInto(context, server, 2, trial);
            }
            colonyPieceReplaced(context, server);
            hostHandsAndHangarConsole(context, server);
            for (int trial = 0; trial < TRIALS; trial++) {
                layerStructuresOnArrival(context, server, 1, CELL + trial);
                layerStructuresOnArrival(context, server, 2, CELL + trial);
            }
        }
        StringBuilder table = new StringBuilder("stale client light, per path:");
        results.forEach(result -> table.append("\n  ")
                .append(result.mismatch().isPresent() ? "STALE " : "ok    ")
                .append(result.path())
                .append(result.mismatch().map(m -> ": " + m).orElse("")));
        LOGGER.info(table.toString());
        List<Result> stale =
                results.stream().filter(result -> result.mismatch().isPresent()).toList();
        if (!stale.isEmpty()) {
            throw new AssertionError(table.toString());
        }
    }

    // ---------------------------------------------------------------------------------------------- the cases

    /** A sealed room is carved with {@code setBlock}, as a breach pocket or {@code RoomCarver} does, while the player stands in range of it. */
    private void carveInRange(ClientGameTestContext context, TestServerContext server) {
        BlockPos floor = server.computeOnServer(
                s -> surface(s.overworld(), player(s).blockPosition().offset(30, 0, 0)));
        arrive(context, server, Level.OVERWORLD, floor.above(6).offset(-6, 0, 0));
        server.runOnServer(s -> carveRoom(s.overworld(), floor));
        record(
                "a sealed room carved with setBlock while a client is in range",
                context,
                server,
                Level.OVERWORLD,
                room(floor));
    }

    /** A player digs the roof of a sealed room: ordinary block updates; the client must read the sky light that falls in. */
    private void digIntoSealedRoom(ClientGameTestContext context, TestServerContext server) {
        BlockPos floor = server.computeOnServer(
                s -> surface(s.overworld(), player(s).blockPosition().offset(-30, 0, 0)));
        arrive(context, server, Level.OVERWORLD, floor.above(6).offset(6, 0, 0));
        server.runOnServer(s -> carveRoom(s.overworld(), floor));
        require(
                ClientLight.settledMismatch(context, server, Level.OVERWORLD, room(floor))
                        .isEmpty(),
                "the room to be in step before the dig");
        BlockPos roof = floor.above(HEIGHT + 1);
        server.runOnServer(s -> {
            if (!player(s).gameMode.destroyBlock(roof)) {
                throw new AssertionError("the player could not break the roof at " + roof);
            }
        });
        ClientWait.until(
                context,
                "the dug room to take sky light on the server",
                () -> server.computeOnServer(s -> ClientLight.maxSky(s.overworld(), room(floor))) > 0,
                () -> "no sky light in the room");
        record("a player digs the roof of a sealed room", context, server, Level.OVERWORLD, room(floor));
    }

    /**
     * A room is carved in chunks the client has not been sent, and the player arrives in the same tick or after the light has settled: the
     * breach does the first (it carves the pocket and teleports the entity in one tick).
     */
    private void carveThenArrive(ClientGameTestContext context, TestServerContext server, boolean sameTick) {
        int offset = sameTick ? FAR : -FAR;
        BlockPos floor = server.computeOnServer(s -> {
            BlockPos at = new BlockPos(offset, 0, 0);
            s.overworld().getChunk(at);
            return surface(s.overworld(), at);
        });
        server.runOnServer(s -> {
            for (int cx = (floor.getX() - 12) >> 4; cx <= (floor.getX() + 12) >> 4; cx++) {
                for (int cz = (floor.getZ() - 12) >> 4; cz <= (floor.getZ() + 12) >> 4; cz++) {
                    s.overworld().setChunkForced(cx, cz, true);
                    s.overworld().getChunk(cx, cz);
                }
            }
        });
        BlockPos stand = floor.above(HEIGHT + 3).offset(10, 0, 0);
        // The breach (sameTick): the pocket is carved and the player teleported in one server task.
        server.runOnServer(s -> {
            carveRoom(s.overworld(), floor);
            LightSync.holdSendUntilLit(
                    s.overworld(), room(floor).min(), room(floor).max());
            if (sameTick) {
                teleport(s, s.overworld(), stand);
            }
        });
        if (!sameTick) {
            ClientWait.until(
                    context,
                    "the server's light engine to be idle",
                    () -> ClientLight.serverLightIdle(context, server),
                    () -> "light work pending");
            server.runOnServer(s -> teleport(s, s.overworld(), stand));
        }
        ClientWait.until(
                context,
                "the client to hold the chunks of the room",
                client -> ClientLight.clientLevel(client).dimension().equals(Level.OVERWORLD)
                        && ClientLight.chunksPresent(ClientLight.clientLevel(client), room(floor)),
                client -> "chunks missing in " + room(floor) + ", " + ClientWait.describe(client)
                        + ", server light idle " + ClientLight.serverLightIdle(context, server));
        record(
                "a sealed room carved "
                        + (sameTick
                                ? "in the tick the player arrives (breach pocket)"
                                : "before the player arrives, after the light settled"),
                context,
                server,
                Level.OVERWORLD,
                room(floor));
    }

    /**
     * The player crosses a breach into {@code layer}: {@code BreachService} carves the pocket and teleports the player in the same tick.
     * Layer 2's rock holds lava, which lights what it touches, so a pocket carved out of it must not keep that glow on the client.
     */
    private void breachInto(ClientGameTestContext context, TestServerContext server, int layer, int trial) {
        ResourceKey<Level> from = LayerChain.dimension(layer - 1);
        ResourceKey<Level> to = LayerChain.dimension(layer);
        int column = 4_000 + layer * 1_000 + trial * 200;
        arriveSpectator(
                context,
                server,
                from,
                new BlockPos(
                        column,
                        server.computeOnServer(
                                s -> ClientLight.serverLevel(s, from).getMinY() + 40),
                        column));
        server.runOnServer(s -> {
            ServerLevel level = ClientLight.serverLevel(s, from);
            player(s).teleportTo(level, column + 0.5, level.getMinY() - 2, column + 0.5, Set.of(), 0, 0, true);
        });
        ClientWait.until(
                context,
                "the client to cross into layer " + layer,
                client -> ClientLight.clientLevel(client).dimension().equals(to),
                ClientWait::describe);
        BlockPos arrival = server.computeOnServer(s -> player(s).blockPosition());
        int radius = LayerTuning.DEFAULT.pocketRadius();
        Box pocket = new Box(
                arrival.offset(-radius, -1, -radius),
                arrival.offset(radius, LayerTuning.DEFAULT.pocketHeight() - 1, radius));
        ClientWait.until(
                context,
                "the client to hold the chunks of the pocket",
                client -> ClientLight.chunksPresent(ClientLight.clientLevel(client), pocket),
                client -> "chunks missing in " + pocket);
        record(
                "a breach crossing into layer " + layer + " carves its pocket in the tick the player arrives (#" + trial
                        + ")",
                context,
                server,
                to,
                pocket);
    }

    /**
     * The piece of the colony that the player stands in is wiped and placed again from its structure file, as {@code ColonyBuilder} places
     * its pieces, with the player in the colony. (The build runs when the server starts, before any player is in range; this is the
     * same placement with one in range.)
     */
    private void colonyPieceReplaced(ClientGameTestContext context, TestServerContext server) {
        ColonySite.Placed colony = server.computeOnServer(s -> Colony.placed(s).orElseThrow());
        arrive(context, server, Level.OVERWORLD, colony.center().above(3));
        ColonyLayout.Piece piece =
                server.computeOnServer(s -> nearestPiece(s, colony, player(s).blockPosition()));
        Box box = server.computeOnServer(s -> pieceBox(s, colony, piece));
        server.runOnServer(s -> {
            for (BlockPos pos : BlockPos.betweenClosed(box.min(), box.max())) {
                s.overworld()
                        .setBlock(
                                pos,
                                Blocks.AIR.defaultBlockState(),
                                2); // room-carver: the overworld's colony pad, not layer rock
            }
        });
        Optional<String> wiped = ClientLight.settledMismatch(context, server, Level.OVERWORLD, box);
        require(wiped.isEmpty(), "the wiped piece " + piece.structure() + " to be in step: " + wiped.orElse(""));
        server.runOnServer(s -> {
            StructureTemplate template =
                    s.getStructureTemplateManager().get(piece.structure()).orElseThrow();
            BlockPos at = colony.center().offset(piece.offset());
            template.placeInWorld(
                    s.overworld(),
                    at,
                    at,
                    new StructurePlaceSettings(),
                    s.overworld().getRandom(),
                    2);
        });
        record(
                "the colony piece " + piece.structure().getPath() + " placed with the client in the colony",
                context,
                server,
                Level.OVERWORLD,
                box);
    }

    /** The Host's hands and the hangar console, put back as a work order and a rebuild put them. */
    private void hostHandsAndHangarConsole(ClientGameTestContext context, TestServerContext server) {
        ColonySite.Placed colony = server.computeOnServer(s -> Colony.placed(s).orElseThrow());
        arrive(context, server, Level.OVERWORLD, colony.center().above(3));
        BlockPos statue = TestColony.anchor(colony, ColonyAnchor.STATUE);
        BlockPos hangar = TestColony.anchor(colony, ColonyAnchor.HANGAR);
        server.runOnServer(s -> FounderStatue.restoreHands(s));
        record(
                "the Host's hands restored with the client in the colony",
                context,
                server,
                Level.OVERWORLD,
                Box.around(statue, 8, 2, 8));
        BlockPos console = server.computeOnServer(s -> HangarConsole.find(s).orElseThrow());
        server.runOnServer(s -> {
            s.overworld()
                    .setBlock(
                            console,
                            Blocks.AIR.defaultBlockState(),
                            3); // room-carver: the overworld's colony pad, not layer rock
            Hangar.onBuilt(s, colony);
            if (!s.overworld().getBlockState(console).is(HangarTerminal.TYPE.block())) {
                throw new AssertionError("the hangar console was not placed at " + console);
            }
        });
        record(
                "the hangar console placed with the client in the colony",
                context,
                server,
                Level.OVERWORLD,
                Box.around(hangar, 12, 2, 8));
    }

    /**
     * The player arrives in a layer next to a cell whose chunks are not generated: each chunk draws its structures when it loads
     * ({@code LayerStructures}), with lit candles and the Prospector wreck's lights, while the client is sent the chunk.
     */
    private void layerStructuresOnArrival(
            ClientGameTestContext context, TestServerContext server, int layer, int cell) {
        ResourceKey<Level> dimension = LayerChain.dimension(layer);
        List<StructureSite> sites = server.computeOnServer(s -> {
            BlockPos conduit = Colony.anchor(s, ColonyAnchor.CONDUIT).orElseThrow();
            ServerLevel level = ClientLight.serverLevel(s, dimension);
            List<StructureSite> found = new ArrayList<>();
            for (StructureKind kind : StructureKind.inLayer(layer)) {
                found.add(StructureSite.in(
                        s.overworld().getSeed(), kind, level.getMinY(), level.getHeight(), cell, cell, conduit));
            }
            return found;
        });
        for (StructureSite site : sites) {
            BlockPos stand = site.origin().above(2);
            server.runOnServer(s -> {
                ServerPlayer player = player(s);
                player.setGameMode(GameType.SPECTATOR);
                player.teleportTo(
                        ClientLight.serverLevel(s, dimension),
                        stand.getX() + 0.5,
                        stand.getY(),
                        stand.getZ() + 0.5,
                        Set.of(),
                        0,
                        0,
                        true);
            });
            BoundingBox bounds = site.bounds();
            Box box = new Box(
                    new BlockPos(bounds.minX(), bounds.minY(), bounds.minZ()),
                    new BlockPos(bounds.maxX(), bounds.maxY(), bounds.maxZ()));
            ClientWait.until(
                    context,
                    "the client to hold the chunks of the " + site.kind() + " in layer " + layer,
                    client -> ClientLight.clientLevel(client).dimension().equals(dimension)
                            && ClientLight.chunksPresent(ClientLight.clientLevel(client), box),
                    client -> "chunks missing in " + box);
            record(
                    "layer " + layer + " structure " + site.kind() + " in cell " + cell
                            + " drawn as its chunk loads for an arriving client",
                    context,
                    server,
                    dimension,
                    box);
        }
    }

    // ---------------------------------------------------------------------------------------------- helpers

    private void record(
            String path,
            ClientGameTestContext context,
            TestServerContext server,
            ResourceKey<Level> dimension,
            Box box) {
        results.add(new Result(path, ClientLight.settledMismatch(context, server, dimension, box)));
    }

    /** Moves the player to {@code at} in {@code dimension} and waits until the client has the chunk, so the player is in range. */
    private static void arrive(
            ClientGameTestContext context, TestServerContext server, ResourceKey<Level> dimension, BlockPos at) {
        server.runOnServer(s -> teleport(s, ClientLight.serverLevel(s, dimension), at));
        ClientWait.until(
                context,
                "the client to hold the chunk of the player's arrival",
                client -> ClientLight.clientLevel(client).dimension().equals(dimension)
                        && ClientLight.clientLevel(client).getChunkSource().hasChunk(at.getX() >> 4, at.getZ() >> 4),
                client -> "no chunk at " + at.toShortString());
    }

    private static void arriveSpectator(
            ClientGameTestContext context, TestServerContext server, ResourceKey<Level> dimension, BlockPos at) {
        server.runOnServer(s -> player(s).setGameMode(GameType.SPECTATOR));
        arrive(context, server, dimension, at);
    }

    private static void teleport(MinecraftServer server, ServerLevel level, BlockPos at) {
        player(server).teleportTo(level, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, Set.of(), 0, 0, true);
    }

    private static ServerPlayer player(MinecraftServer server) {
        return server.getPlayerList().getPlayers().getFirst();
    }

    private static BlockPos surface(ServerLevel level, BlockPos near) {
        return new BlockPos(
                near.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING, near.getX(), near.getZ()), near.getZ());
    }

    /** A stone shell with air in it, written block by block with notification, as a breach pocket and the tour's dark room are. */
    private static void carveRoom(ServerLevel level, BlockPos floor) {
        for (int dx = -RADIUS - 1; dx <= RADIUS + 1; dx++) {
            for (int dz = -RADIUS - 1; dz <= RADIUS + 1; dz++) {
                for (int dy = 0; dy <= HEIGHT + 1; dy++) {
                    boolean shell =
                            dy == 0 || dy == HEIGHT + 1 || Math.abs(dx) == RADIUS + 1 || Math.abs(dz) == RADIUS + 1;
                    BlockState state = shell ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
                    level.setBlock(floor.offset(dx, dy, dz), state, 3);
                }
            }
        }
    }

    private static Box room(BlockPos floor) {
        return new Box(floor.offset(-RADIUS, 1, -RADIUS), floor.offset(RADIUS, HEIGHT, RADIUS));
    }

    private static Box pieceBox(MinecraftServer server, ColonySite.Placed colony, ColonyLayout.Piece piece) {
        StructureTemplate template =
                server.getStructureTemplateManager().get(piece.structure()).orElseThrow();
        BlockPos low = colony.center().offset(piece.offset());
        return new Box(
                low,
                low.offset(
                        template.getSize().getX() - 1,
                        template.getSize().getY() - 1,
                        template.getSize().getZ() - 1));
    }

    private static ColonyLayout.Piece nearestPiece(MinecraftServer server, ColonySite.Placed colony, BlockPos near) {
        return ColonyLayout.read(server).pieces().stream()
                .min(Comparator.comparingDouble(
                        piece -> pieceBox(server, colony, piece).min().distSqr(near)))
                .orElseThrow();
    }

    private static void require(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError("Expected " + what);
        }
    }
}
