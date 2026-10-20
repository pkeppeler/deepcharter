package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.charter.terminal.ContractTerminal;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.ore.OreBlock;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.surface.SurfaceBlocks;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalActivity;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientPacks;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.OreBlocks;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestColony;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The world and the views that the texture test scenarios shoot (#336 in {@link TextureDensityScenario}, #367 in
 * {@link OreLookScenario}): one real world (the design tour's seed, so the surface is the generator's regolith), the rooms
 * built in layer 1, and a creative, flying camera with the HUD hidden, under the design tour's pins. Each view is shot once per
 * variant, named {@code <variant>-<view>}; a variant's test pack is turned on, as a player does in the pack screen, and off again.
 *
 * <p>The light is what a player has: a pod lamp is a level 12 light (a tier 3 lights part), with no night vision. The rooms: a
 * cavern in layer 1 with its south wall made of the layer's rock and every ore set in it, the lamp three blocks out; a bored shaft lit every
 * four blocks down; three terminals and two Company rocks in a lamp-lit room; and the seam wall, where the ores sit in plain rock
 * in a checkerboard, so that every edge of an ore meets stone, with the lamp two blocks out.
 */
final class TextureTestWorld {
    /** A variant's still prefix, and the test pack it turns on (none for A, the mod as it ships). */
    record Variant(String name, @Nullable String pack) {}

    private static final double EYE = 1.62;
    /** The cavern room in layer 1: its floor is at {@link #ROOM}, and its south wall, where the ores are, at {@code z + WALL}. */
    private static final BlockPos ROOM = new BlockPos(2600, 100, 2600);

    static final int WALL = 3;
    private static final int HALF_WIDTH = 6;
    private static final int HEIGHT = 5;
    private static final int LAMP = 12;
    /** The bored shaft: 3 x 3 blocks, from its mouth at {@link #SHAFT} down {@link #SHAFT_DEPTH} blocks. */
    private static final BlockPos SHAFT = new BlockPos(2640, 150, 2600);

    private static final int SHAFT_DEPTH = 28;
    /** The terminal room: three terminals against its south wall, for the 32x terminals of C. */
    private static final BlockPos TERMINAL_ROOM = new BlockPos(2600, 100, 2570);
    /** The seam room: its south wall, at {@code z + WALL}, is plain rock with the ores in a checkerboard. */
    private static final BlockPos SEAM_ROOM = new BlockPos(2600, 100, 2630);
    /** The reference room: its south wall, at {@code z + WALL}, is plain rock with every ore in a column of its own. */
    private static final BlockPos REFERENCE_ROOM = new BlockPos(2600, 100, 2660);

    private static final int REFERENCE_HALF_WIDTH = 7;
    /** The ores in the cavern wall, by their place in it: blocks east of the room's middle, and up from its floor. */
    private static final Map<BlockPos, Block> WALL_BLOCKS = Map.ofEntries(
            Map.entry(new BlockPos(-4, 3, 0), OreBlocks.of(OreType.IRONIUM)),
            Map.entry(new BlockPos(-3, 3, 0), OreBlocks.of(OreType.IRONIUM)),
            Map.entry(new BlockPos(-5, 0, 0), OreBlocks.of(OreType.CICATRIUM)),
            Map.entry(new BlockPos(-3, 1, 0), OreBlocks.of(OreType.BRONZIUM)),
            Map.entry(new BlockPos(-1, 2, 0), OreBlocks.of(OreType.EINSTEINIUM)),
            Map.entry(new BlockPos(1, 1, 0), OreBlocks.of(OreType.GOLDIUM)),
            Map.entry(new BlockPos(2, 3, 0), OreBlocks.of(OreType.SILVERIUM)),
            Map.entry(new BlockPos(4, 2, 0), OreBlocks.of(OreType.PLATINIUM)),
            Map.entry(new BlockPos(5, 0, 0), HazardBlocks.COMPANY_ROCK));
    /** The ores in the seam wall, 5 wide and 3 high from the floor, each with plain rock on all four sides. */
    private static final Map<BlockPos, Block> SEAM_BLOCKS = Map.ofEntries(
            Map.entry(new BlockPos(-2, 0, 0), OreBlocks.of(OreType.BRONZIUM)),
            Map.entry(new BlockPos(0, 0, 0), OreBlocks.of(OreType.SILVERIUM)),
            Map.entry(new BlockPos(2, 0, 0), OreBlocks.of(OreType.PLATINIUM)),
            Map.entry(new BlockPos(-1, 1, 0), OreBlocks.of(OreType.IRONIUM)),
            Map.entry(new BlockPos(1, 1, 0), OreBlocks.of(OreType.GOLDIUM)),
            Map.entry(new BlockPos(-2, 2, 0), OreBlocks.of(OreType.EINSTEINIUM)),
            Map.entry(new BlockPos(0, 2, 0), OreBlocks.of(OreType.CICATRIUM)),
            Map.entry(new BlockPos(2, 2, 0), OreBlocks.of(OreType.IRONIUM)));
    /** The wall-clock limit of one settle. */
    private static final long SETTLE_LIMIT_NANOS = 120_000_000_000L;

    private static final int SETTLE_POLL_TICKS = 2;
    private static final int SETTLE_STABLE_POLLS = 5;

    private final ClientGameTestContext ctx;
    private final TestSingleplayerContext sp;
    private final Consumer<String> shoot;
    private long settleLimitNanos = SETTLE_LIMIT_NANOS;

    private TextureTestWorld(ClientGameTestContext ctx, TestSingleplayerContext sp, Consumer<String> shoot) {
        this.ctx = ctx;
        this.sp = sp;
        this.shoot = shoot;
    }

    /**
     * Opens the world, pins it, hides the HUD and hands the world to {@code body}; {@code shoot} takes each still by its name. The
     * HUD comes back in a finally, because a full-suite run goes on to tests that draw on it.
     */
    static void open(ClientGameTestContext ctx, Consumer<String> shoot, Consumer<TextureTestWorld> body) {
        ClientChecks.withHudHidden(ctx, () -> openHidden(ctx, shoot, body));
    }

    private static void openHidden(ClientGameTestContext ctx, Consumer<String> shoot, Consumer<TextureTestWorld> body) {
        try (TestSingleplayerContext singleplayer = ctx.worldBuilder()
                .setUseConsistentSettings(false)
                .adjustSettings(state -> state.setSeed("deepcharter-design-tour"))
                .create()) {
            ClientWait.until(ctx, "the player in the world", client -> client.player != null && client.level != null);
            TextureTestWorld world = new TextureTestWorld(ctx, singleplayer, shoot);
            world.pinWorld();
            ctx.runOnClient(client -> {
                client.options.setCameraType(CameraType.FIRST_PERSON);
                client.options.particles().set(ParticleStatus.MINIMAL);
                client.options.bobView().set(false);
            });
            body.accept(world);
        }
    }

    /** Sets how long a still waits for the world to settle, for a scenario whose first view needs more than the default. */
    void settleLimit(int seconds) {
        settleLimitNanos = seconds * 1_000_000_000L;
    }

    /** Shoots {@code views} once for each variant, with its pack on (and off again after, in a finally). */
    void eachVariant(List<Variant> variants, Consumer<String> views) {
        for (Variant variant : variants) {
            if (variant.pack() != null) {
                ClientPacks.enable(ctx, variant.pack());
            }
            try {
                views.accept(variant.name());
            } finally {
                if (variant.pack() != null) {
                    ClientPacks.disable(ctx, variant.pack());
                }
            }
        }
    }

    /**
     * A sealed room in the layer 1 rock with its south wall made of the layer's rock and the ores (and one Company rock) set into it, and a
     * pod lamp three blocks out from the wall. The room's other walls, floor and ceiling are the layer's own rock.
     */
    void buildCavern() {
        serverDo(server -> {
            ServerLevel level = ClientLight.serverLevel(server, LayerChain.dimension(1));
            buildWall(level, ROOM, HALF_WIDTH, WALL_BLOCKS);
            level.setBlock(ROOM.offset(0, 2, 0), lamp(LAMP), Block.UPDATE_ALL);
        });
    }

    /** A sealed room like the cavern, whose south wall holds the ores in a checkerboard of plain rock, lit two blocks out. */
    void buildSeamWall() {
        serverDo(server -> {
            ServerLevel level = ClientLight.serverLevel(server, LayerChain.dimension(1));
            buildWall(level, SEAM_ROOM, 4, SEAM_BLOCKS);
            level.setBlock(SEAM_ROOM.offset(0, 1, WALL - 2), lamp(LAMP), Block.UPDATE_ALL);
        });
    }

    /**
     * A sealed room like the cavern, whose south wall holds each ore in a column of its own, three high, with plain rock between
     * every two ores, in the order of {@link OreType}, lit two blocks out: every ore on its host rock, at the size it shows.
     */
    void buildReferenceWall() {
        serverDo(server -> {
            ServerLevel level = ClientLight.serverLevel(server, LayerChain.dimension(1));
            Map<BlockPos, Block> blocks = new HashMap<>();
            OreType[] ores = OreType.values();
            for (int column = 0; column < ores.length; column++) {
                for (int row = 0; row < 3; row++) {
                    blocks.put(new BlockPos(-6 + 2 * column, 2 * row, 0), OreBlocks.of(ores[column]));
                }
            }
            buildWall(level, REFERENCE_ROOM, REFERENCE_HALF_WIDTH, blocks);
            level.setBlock(REFERENCE_ROOM.offset(0, 2, WALL - 3), lamp(LAMP), Block.UPDATE_ALL);
        });
    }

    /**
     * A sealed room in a layer with its south wall (at {@code z + WALL}) made of the layer's own rock, and {@code blocks} set into it:
     * an ore sits in that rock ({@link OreBlock#in}).
     */
    static void buildWall(ServerLevel level, BlockPos room, int halfWidth, Map<BlockPos, Block> blocks) {
        loadChunks(level, room, 2);
        RoomCarver.carve(
                level,
                room.offset(-halfWidth, 0, -WALL - 5),
                room.offset(halfWidth, HEIGHT - 1, WALL - 1),
                Blocks.AIR.defaultBlockState());
        Block rock = LayerBlocks.groundOf(level);
        for (int dx = -halfWidth - 1; dx <= halfWidth + 1; dx++) {
            for (int dy = -1; dy <= HEIGHT; dy++) {
                level.setBlock(room.offset(dx, dy, WALL), rock.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        blocks.forEach((at, block) -> level.setBlock(
                room.offset(at.getX(), at.getY(), WALL),
                block instanceof OreBlock ore ? ore.in(rock.defaultBlockState()) : block.defaultBlockState(),
                Block.UPDATE_ALL));
    }

    /**
     * A small sealed room with three terminals against its south wall, facing north: the fuel pump repaired (online, its screen
     * lit), the ore processor broken (its red standby lamp) and the contract terminal; a Company rock each side; a pod lamp.
     */
    void buildTerminalRoom() {
        serverDo(server -> {
            ServerLevel level = ClientLight.serverLevel(server, LayerChain.dimension(1));
            loadChunks(level, TERMINAL_ROOM, 1);
            RoomCarver.carve(
                    level,
                    TERMINAL_ROOM.offset(-4, 0, -4),
                    TERMINAL_ROOM.offset(4, 4, 3),
                    Blocks.AIR.defaultBlockState());
            RepairState repairs = RepairState.get(level.getServer());
            for (Item part : TerminalTypes.FUEL_PUMP.parts()) {
                repairs.insert(TerminalTypes.FUEL_PUMP, part);
            }
            List<TerminalType> row =
                    List.of(TerminalTypes.FUEL_PUMP, TerminalTypes.ORE_PROCESSOR, ContractTerminal.TYPE);
            for (int i = 0; i < row.size(); i++) {
                BlockPos pos = TERMINAL_ROOM.offset(-1 + i, 0, 3);
                level.setBlock(
                        pos,
                        row.get(i)
                                .block()
                                .defaultBlockState()
                                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH),
                        Block.UPDATE_ALL);
                TerminalActivity.sync(level, pos);
            }
            for (int dx : new int[] {-2, 2}) {
                level.setBlock(
                        TERMINAL_ROOM.offset(dx, 0, 4),
                        HazardBlocks.COMPANY_ROCK.defaultBlockState(),
                        Block.UPDATE_ALL);
                level.setBlock(
                        TERMINAL_ROOM.offset(dx, 1, 4),
                        HazardBlocks.COMPANY_ROCK.defaultBlockState(),
                        Block.UPDATE_ALL);
            }
            level.setBlock(TERMINAL_ROOM.offset(0, 3, 0), lamp(LAMP), Block.UPDATE_ALL);
        });
    }

    /** A 3 x 3 shaft bored straight down through the layer's own rock, with a lamp at its middle every four blocks. */
    void buildShaft() {
        serverDo(server -> {
            ServerLevel level = ClientLight.serverLevel(server, LayerChain.dimension(1));
            loadChunks(level, SHAFT, 2);
            RoomCarver.carve(
                    level, SHAFT.offset(-1, -SHAFT_DEPTH, -1), SHAFT.offset(1, 0, 1), Blocks.AIR.defaultBlockState());
            for (int depth = 2; depth < SHAFT_DEPTH; depth += 4) {
                level.setBlock(SHAFT.below(depth), lamp(LAMP), Block.UPDATE_ALL);
            }
        });
    }

    /** The cavern wall in the lamp's light, the same wall close up (goldium and einsteinium), and the wall with the lamp out. */
    void cavernWall(String variant) {
        Vec3 wall = Vec3.atBottomCenterOf(ROOM).add(0, 2, WALL);
        view(1, wall.add(0, 0.1, -6), wall, 60);
        still(variant + "-cavern-wall-lamp-lit");
        view(1, wall.add(0.5, 0, -2), wall.add(0.5, 0, 0), 20);
        still(variant + "-cavern-wall-close");
        // The lamp out: only what glows of itself shows (D's glints).
        serverDo(server -> ClientLight.serverLevel(server, LayerChain.dimension(1))
                .setBlock(ROOM.offset(0, 2, 0), lamp(0), Block.UPDATE_ALL));
        view(1, wall.add(0, 0.1, -6), wall, 20);
        still(variant + "-cavern-wall-no-lamp");
        serverDo(server -> ClientLight.serverLevel(server, LayerChain.dimension(1))
                .setBlock(ROOM.offset(0, 2, 0), lamp(LAMP), Block.UPDATE_ALL));
    }

    /** The wall of the bored shaft at mid distance: from just under its mouth, by its north wall, down at its south wall. */
    void shaftWall(String variant) {
        Vec3 mouth = Vec3.atBottomCenterOf(SHAFT);
        view(1, mouth.add(0, -1.5, -1.2), mouth.add(0, -12, 1.5), 20);
        still(variant + "-shaft-wall-mid");
    }

    /** The seam wall square on: the whole checkerboard, then its goldium close up, with the plain rock on its four sides. */
    void seamWall(String variant) {
        Vec3 wall = Vec3.atBottomCenterOf(SEAM_ROOM).add(0, 1.5, WALL);
        view(1, wall.add(0, 0, -2.8), wall, 40);
        still(variant + "-seam-wall");
        Vec3 goldium = wall.add(1, 0, 0);
        view(1, goldium.add(0, 0, -1.7), goldium, 20);
        still(variant + "-seam-close");
    }

    /** The reference wall square on, with the lamp out for the second still. */
    void referenceWall(String variant) {
        Vec3 wall = Vec3.atBottomCenterOf(REFERENCE_ROOM).add(0, 2, WALL);
        view(1, wall.add(0, 0, -5.2), wall, 40);
        still(variant + "-reference-wall");
    }

    /** The regolith of an open plain near the colony, at noon. */
    void surface(String variant, Vec3 plain) {
        view(0, plain.add(0, 5, -9), plain, 40);
        still(variant + "-surface-regolith-day");
    }

    /** The terminal room, from its middle. */
    void terminals(String variant) {
        Vec3 terminals = Vec3.atBottomCenterOf(TERMINAL_ROOM);
        view(1, terminals.add(0, 1.3, 0.3), terminals.add(0, 0.8, 3.5), 40);
        still(variant + "-terminals-lamp-lit");
    }

    /**
     * Gameplay noon, the visual sky at its brightest dusk, clear weather, no random ticks or mobs, and a creative, flying camera: the
     * same pins as the design tour.
     */
    private void pinWorld() {
        serverDo(server -> {
            GameRules rules = server.getGameRules();
            rules.set(GameRules.ADVANCE_TIME, false, server);
            rules.set(GameRules.ADVANCE_WEATHER, false, server);
            rules.set(GameRules.SPAWN_MOBS, false, server);
            rules.set(GameRules.SPAWN_MONSTERS, false, server);
            rules.set(GameRules.RANDOM_TICK_SPEED, 0, server);
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "weather clear");
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon");
            ResourceKey<WorldClock> sky =
                    ResourceKey.create(Registries.WORLD_CLOCK, Identifier.fromNamespaceAndPath("deepcharter", "sky"));
            var clock = server.registryAccess()
                    .lookupOrThrow(Registries.WORLD_CLOCK)
                    .getOrThrow(sky);
            // world-clock: the helper of two evidence scenarios, which run in a world of their own, as every client
            // test does
            server.clockManager().setPaused(clock, true);
            // world-clock: the same world of its own
            server.clockManager().setTotalTicks(clock, 0);
            ServerPlayer player = player(server);
            player.setGameMode(GameType.CREATIVE);
            player.getInventory().clearContent();
            player.getAbilities().mayfly = true;
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
            player.setPermanentlyInvulnerable(true);
        });
    }

    static BlockState lamp(int level) {
        return Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, level);
    }

    /**
     * The top of the first open regolith plain out from the colony's square, going west, north and east in steps of 20 blocks: a
     * column whose 5 x 5 blocks round it are all regolith on top, within 3 blocks of each other in height. Fails, naming the ground it
     * saw, if none is in reach.
     */
    Vec3 regolithPlain() {
        BlockPos square = serverGet(server -> TestColony.anchor(
                Colony.placed(server).orElseThrow(() -> new AssertionError("no colony")), ColonyAnchor.STATUE));
        return serverGet(server -> {
            ServerLevel surface = server.overworld();
            List<String> seen = new ArrayList<>();
            for (int distance = 60; distance <= 300; distance += 20) {
                for (BlockPos step : List.of(new BlockPos(-1, 0, 0), new BlockPos(0, 0, -1), new BlockPos(1, 0, 0))) {
                    BlockPos at = square.offset(step.getX() * distance, 0, step.getZ() * distance);
                    loadChunks(surface, at, 1);
                    BlockPos top = surface.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, at);
                    if (isPlain(surface, at)) {
                        return Vec3.atBottomCenterOf(top);
                    }
                    seen.add(BuiltInRegistries.BLOCK
                                    .getKey(surface.getBlockState(top.below()).getBlock())
                                    .getPath() + "@" + top.below().toShortString());
                }
            }
            throw new AssertionError("no open regolith plain within 300 blocks of the square at "
                    + square.toShortString() + "; saw " + seen);
        });
    }

    private static boolean isPlain(ServerLevel surface, BlockPos at) {
        int low = Integer.MAX_VALUE;
        int high = Integer.MIN_VALUE;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos top = surface.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, at.offset(dx, 0, dz));
                if (!surface.getBlockState(top.below()).is(SurfaceBlocks.REGOLITH)) {
                    return false;
                }
                low = Math.min(low, top.getY());
                high = Math.max(high, top.getY());
            }
        }
        return high - low <= 3;
    }

    static void loadChunks(ServerLevel level, BlockPos at, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                level.getChunk((at.getX() >> 4) + dx, (at.getZ() >> 4) + dz);
            }
        }
    }

    /** Puts the camera at {@code eye} in {@code layer}, looking at {@code target}, then waits {@code wait} ticks. */
    void view(int layer, Vec3 eye, Vec3 target, int wait) {
        Vec3 d = target.subtract(eye);
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z)));
        serverDo(server -> {
            ServerPlayer player = player(server);
            player.setNoGravity(true);
            player.teleportTo(
                    ClientLight.serverLevel(server, LayerChain.dimension(layer)),
                    eye.x,
                    eye.y - EYE,
                    eye.z,
                    Set.of(),
                    yaw,
                    pitch,
                    true);
        });
        ClientWait.until(
                ctx,
                "the camera at " + eye + " in layer " + layer,
                client -> ClientLight.clientLevel(client).dimension().equals(LayerChain.dimension(layer))
                        && ClientSession.player(client).distanceToSqr(eye.x, eye.y - EYE, eye.z) < 0.0001
                        && Math.abs(Mth.wrapDegrees(ClientSession.player(client).getYRot() - yaw)) < 0.01f
                        && Math.abs(ClientSession.player(client).getXRot() - pitch) < 0.01f);
        ctx.waitTicks(wait);
    }

    /**
     * Waits, on a wall-clock limit, until the light has settled, every chunk section in view is rendered, and no mob or loose item is
     * in the world, for {@link #SETTLE_STABLE_POLLS} looks in a row; then shoots the still.
     */
    void still(String stillName) {
        long deadline = System.nanoTime() + settleLimitNanos;
        int stable = 0;
        while (stable < SETTLE_STABLE_POLLS) {
            serverDo(server -> server.getAllLevels()
                    .forEach(level -> level.getAllEntities().forEach(entity -> {
                        if (stray(entity)) {
                            entity.discard();
                        }
                    })));
            ctx.runOnClient(client -> client.particleEngine.clearParticles());
            ctx.waitTicks(SETTLE_POLL_TICKS);
            boolean lit = serverGet(server -> {
                for (ServerLevel level : server.getAllLevels()) {
                    if (level.getLightEngine().hasLightWork()) {
                        return false;
                    }
                }
                return true;
            });
            String pending = ctx.computeOnClient(TextureTestWorld::pending);
            stable = lit && pending == null ? stable + 1 : 0;
            if (System.nanoTime() > deadline) {
                throw new AssertionError(
                        "still " + stillName + ": the world did not settle in " + settleLimitNanos / 1_000_000_000L
                                + " s (light settled: " + lit + ", the client waits for: " + pending + ")");
            }
        }
        shoot.accept(stillName);
    }

    /** What the client still waits for before a still, or null when it waits for nothing: a stray entity, a chunk, or a section to render. */
    private static @Nullable String pending(Minecraft client) {
        for (Entity entity : ClientLight.clientLevel(client).entitiesForRendering()) {
            if (stray(entity)) {
                return "the stray entity " + entity.getType() + " at "
                        + entity.blockPosition().toShortString();
            }
        }
        int radius = client.options.getEffectiveRenderDistance();
        int centreX = ClientSession.player(client).chunkPosition().x();
        int centreZ = ClientSession.player(client).chunkPosition().z();
        for (int x = centreX - radius; x <= centreX + radius; x++) {
            for (int z = centreZ - radius; z <= centreZ + radius; z++) {
                if (ClientLight.clientLevel(client).getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) == null) {
                    return "the chunk " + x + ", " + z;
                }
            }
        }
        return client.levelRenderer.hasRenderedAllSections() ? null : "the sections of the level renderer";
    }

    private static boolean stray(Entity entity) {
        return entity instanceof Mob || entity instanceof ItemEntity;
    }

    private static ServerPlayer player(MinecraftServer server) {
        return server.getPlayerList().getPlayers().getFirst();
    }

    <T> T serverGet(Function<MinecraftServer, T> action) {
        return sp.getServer().computeOnServer(action::apply);
    }

    void serverDo(Consumer<MinecraftServer> action) {
        sp.getServer().runOnServer(action::accept);
    }
}
