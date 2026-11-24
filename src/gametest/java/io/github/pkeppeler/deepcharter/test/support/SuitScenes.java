package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.colony.ColonyKit;
import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.outpost.OutpostBuilder;
import io.github.pkeppeler.deepcharter.outpost.OutpostData;
import io.github.pkeppeler.deepcharter.outpost.OutpostData.Outpost;
import io.github.pkeppeler.deepcharter.suit.SuitRegistry;
import io.github.pkeppeler.deepcharter.suit.SuitState;
import io.github.pkeppeler.deepcharter.suit.SuitTicker;
import io.github.pkeppeler.deepcharter.suit.SuitTracks;
import io.github.pkeppeler.deepcharter.terminal.TerminalBlock;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/**
 * Stages for the suit timer's tests (#993): an air pocket cut into a depth of the campaign world, a person put on foot in it for the length
 * of a body, and the suit's state read back. The suit's real tick runs every second tick, so a body that counts seconds with
 * {@link SuitTicker#second} runs on one tick and leaves its player a spectator when it is done: no real second can land in between.
 */
public final class SuitScenes {
    private static final int Z = 7400;
    private static final int ROCK = 4;
    private static final int HEIGHT = 4;
    /** Blocks above the top of the unbuilt depth's crust where its pocket's floor lies, as the placement readout's stage has it. */
    private static final int ABOVE_CRUST = 10;
    /** Blocks above a band's floor where a pocket of a built band lies. */
    private static final int BAND_OFFSET = 20;

    /** An air pocket in a level: the floor block's position, and a place to stand. */
    public record Pocket(ServerLevel level, BlockPos floor) {
        public Vec3 stand() {
            return Vec3.atBottomCenterOf(floor.above());
        }
    }

    private SuitScenes() {}

    /** Cuts a pocket in the unbuilt depth, the one depth that asks something today, at column {@code x}. Call from the test method. */
    public static Pocket unbuilt(ServerLevel level, int x) {
        return pocket(level, x, CampaignBands.UNBUILT.minY() + LayerTuning.DEFAULT.crustThickness() + ABOVE_CRUST);
    }

    /** Cuts a pocket in {@code band}, a built band, at column {@code x}. Call from the test method. */
    public static Pocket inBand(ServerLevel level, CampaignBands.Band band, int x) {
        return pocket(level, x, band.minY() + BAND_OFFSET);
    }

    private static Pocket pocket(ServerLevel level, int x, int floorY) {
        BlockPos floor = new BlockPos(x, floorY, Z);
        RoomCarver.carve(
                level,
                floor.offset(-ROCK, -1, -ROCK),
                floor.offset(ROCK, HEIGHT, ROCK),
                Blocks.STONE.defaultBlockState());
        RoomCarver.carve(
                level, floor.offset(-2, 1, -2), floor.offset(2, HEIGHT - 1, 2), Blocks.AIR.defaultBlockState());
        return new Pocket(level, floor);
    }

    /** Runs {@code body} on the first tick every pocket's chunk is entity-ticking. Call from the test method. */
    public static void whenReady(GameTestHelper helper, List<Pocket> pockets, Runnable body) {
        int[] ready = {0};
        // All pockets are in one level.
        FarChunks.awaitEntityTicking(
                helper,
                pockets.getFirst().level(),
                pockets.stream().map(Pocket::floor).toList(),
                index -> {
                    if (++ready[0] == pockets.size()) {
                        body.run();
                    }
                });
    }

    /** As {@link #whenReady(GameTestHelper, List, Runnable)} for one pocket. */
    public static void whenReady(GameTestHelper helper, Pocket pocket, Runnable body) {
        whenReady(helper, List.of(pocket), body);
    }

    /** A sequence that starts on the first tick the pocket's chunk is entity-ticking. Call from the test method. */
    public static GameTestSequence sequence(GameTestHelper helper, Pocket pocket) {
        return sequence(helper, pocket.level(), pocket.floor(), "pocket");
    }

    private static GameTestSequence sequence(GameTestHelper helper, ServerLevel level, BlockPos pos, String what) {
        boolean[] ready = {false};
        FarChunks.awaitEntityTicking(helper, level, pos, () -> ready[0] = true);
        return helper.startSequence().thenWaitUntil(() -> {
            if (!ready[0]) {
                throw helper.assertionException("waiting for the %s's chunk to tick entities", what);
            }
        });
    }

    /** Puts {@code mock} in {@code pocket}, on foot (survival), runs {@code body}, and leaves it a spectator, whom the suit never touches. */
    public static void onFootIn(MockPlayer mock, Pocket pocket, Runnable body) {
        mock.teleportTo(pocket.level(), pocket.stand(), 0f, 0f);
        mock.player().setGameMode(GameType.SURVIVAL);
        try {
            body.run();
        } finally {
            mock.player().setGameMode(GameType.SPECTATOR);
        }
    }

    /** The suit state of {@code player}, which must be readable. */
    public static SuitState state(ServerPlayer player) {
        return Versioned.readable(player, SuitRegistry.SUIT).orElseThrow();
    }

    /** Sets the suit of {@code player} to a clean state with {@code reserve} left. */
    public static void setReserve(ServerPlayer player, float reserve, boolean explained) {
        player.setAttached(
                SuitRegistry.SUIT, Versioned.of(new SuitState(reserve, explained, SuitState.Mode.INERT, 0f, 0)));
    }

    /** Gives {@code player} suit {@code tracks}. */
    public static void setTracks(ServerPlayer player, SuitTracks tracks) {
        player.setAttached(SuitRegistry.TRACKS, Versioned.of(tracks));
    }

    /** The suit tracks of {@code player}, which must be readable. */
    public static SuitTracks tracks(ServerPlayer player) {
        return Versioned.readable(player, SuitRegistry.TRACKS).orElseThrow();
    }

    /** Runs {@code count} seconds of {@code player}'s suit. */
    public static void seconds(ServerPlayer player, int count) {
        for (int i = 0; i < count; i++) {
            SuitTicker.second(player);
        }
    }

    /** Seconds of {@code player}'s suit until its reserve is spent, at most {@code limit}. */
    public static int secondsToEmpty(ServerPlayer player, int limit) {
        int seconds = 0;
        while (state(player).reserve() > 0f && seconds < limit) {
            SuitTicker.second(player);
            seconds++;
        }
        return seconds;
    }

    /** The middle of the floor of the hall that {@link #unbuiltHall} cuts in the unbuilt depth at column {@code x}, row {@code z}. */
    public static BlockPos unbuiltHallFloor(int x, int z) {
        return new BlockPos(x, CampaignBands.UNBUILT.minY() + LayerTuning.DEFAULT.crustThickness() + ABOVE_CRUST, z);
    }

    /**
     * Cuts a hall like the kit's in the unbuilt depth, where the kit does not work: plated walls, ceiling and floor, a pump and a processor
     * against the north wall, lamps. Call from the test method or on the server; the caller records the outpost.
     */
    public static void unbuiltHall(ServerLevel level, BlockPos floor) {
        RoomCarver.carve(level, floor.offset(-8, -1, -8), floor.offset(8, 8, 8), Blocks.STONE.defaultBlockState());
        RoomCarver.carve(
                level, floor.offset(-5, 0, -5), floor.offset(5, 6, 5), ColonyKit.RIVETED_PLATE.defaultBlockState());
        RoomCarver.carve(level, floor.offset(-4, 1, -4), floor.offset(4, 5, 4), Blocks.AIR.defaultBlockState());
        RoomCarver.carve(
                level, floor.offset(-4, 0, -4), floor.offset(4, 0, 4), ColonyKit.CONCRETE_FOOTING.defaultBlockState());
        BlockPos north = floor.offset(0, 0, -4);
        TerminalBlock.place(
                level,
                north.offset(-2, 1, 0),
                TerminalTypes.FUEL_PUMP
                        .block()
                        .defaultBlockState()
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH),
                Block.UPDATE_CLIENTS);
        TerminalBlock.place(
                level,
                north.offset(2, 1, 0),
                TerminalTypes.ORE_PROCESSOR
                        .block()
                        .defaultBlockState()
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH),
                Block.UPDATE_CLIENTS);
        for (BlockPos lamp : List.of(north.offset(-2, 3, 0), north.offset(2, 3, 0), north.offset(0, 4, 0))) {
            level.setBlock(
                    lamp,
                    ColonyKit.WALL_LAMP
                            .defaultBlockState()
                            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH),
                    Block.UPDATE_CLIENTS);
        }
        level.setBlock(
                floor.offset(0, 4, 4),
                ColonyKit.WALL_LAMP
                        .defaultBlockState()
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH),
                Block.UPDATE_CLIENTS);
    }

    /** A kit hall staged in layer 1 for an owner on a charter, before the kit is used. */
    public record Hall(ServerLevel level, BlockPos floor, MockPlayer mock, CharterId charter) {
        public ServerPlayer player() {
            return mock.player();
        }

        /** Uses the outpost builder on the floor block, as the kit does, and returns the outpost it recorded. */
        public Outpost build() {
            Optional<Component> refusal = OutpostBuilder.build(player(), level, floor, Direction.UP);
            if (refusal.isPresent()) {
                throw new IllegalStateException(
                        "the hall was refused: " + refusal.get().getString());
            }
            return OutpostData.get(level.getServer()).of(charter, 1).orElseThrow();
        }

        /** Puts the owner on the hall's floor, at its middle, on foot. */
        public void standIn() {
            mock.teleportTo(level, Vec3.atBottomCenterOf(floor.above()), 0f, 0f);
            player().setGameMode(GameType.SURVIVAL);
        }
    }

    /**
     * Carves the shaft bottom of a kit hall in layer 1 at column {@code x}, and founds a charter for a mock player who owns it. Call
     * from the test method; build with {@link Hall#build} once the chunk ticks.
     */
    public static Hall stageHall(GameTestHelper helper, int x, String name) {
        ServerLevel level = TestLayers.level(helper, 1);
        BlockPos floor = SettlingRooms.kitFloor(level, x);
        MockPlayer mock = MockPlayers.join(helper, name);
        MinecraftServer server = level.getServer();
        if (Charters.found(
                        server,
                        mock.player().getUUID(),
                        "Suit Test " + UUID.randomUUID().toString().substring(0, 8))
                .isPresent()) {
            throw new IllegalStateException("founding the charter should succeed");
        }
        CharterId charter = Charters.charterOfOrThrow(server, mock.player().getUUID())
                .orElseThrow()
                .id();
        return new Hall(level, floor, mock, charter);
    }

    /**
     * Runs {@code body} on the first tick the hall's chunk ticks, with a fresh outpost record swapped in for its length, and then succeeds.
     * A body that waits for anything uses {@link #hallSequence} instead: the test is done once this succeeds, and a finished test drops its
     * sequences unrun.
     */
    public static void whenHallReady(GameTestHelper helper, Hall hall, Runnable body) {
        FarChunks.awaitEntityTicking(
                helper,
                hall.level(),
                hall.floor(),
                () -> withFreshOutposts(hall, () -> {
                    body.run();
                    helper.succeed();
                }));
    }

    /** A sequence that starts on the first tick the hall's chunk ticks. Call from the test method; the sequence owns the success. */
    public static GameTestSequence hallSequence(GameTestHelper helper, Hall hall) {
        return sequence(helper, hall.level(), hall.floor(), "hall");
    }

    /** Runs {@code body} with a fresh outpost record swapped in for its length, on this tick. */
    public static void withFreshOutposts(Hall hall, Runnable body) {
        WorldData.with(hall.level().getServer(), OutpostData.TYPE, new OutpostData(), body);
    }

    /** Saved outpost data holding {@code outposts}, as the world would load it. */
    public static OutpostData dataWith(Outpost... outposts) {
        ListTag list = new ListTag();
        for (Outpost outpost : outposts) {
            CompoundTag tag = new CompoundTag();
            tag.put(
                    "charter",
                    CharterId.CODEC
                            .encodeStart(NbtOps.INSTANCE, outpost.charter())
                            .getOrThrow());
            tag.putInt("layer", outpost.layer());
            tag.put(
                    "dimension",
                    Level.RESOURCE_KEY_CODEC
                            .encodeStart(NbtOps.INSTANCE, outpost.dimension())
                            .getOrThrow());
            tag.put(
                    "centre",
                    BlockPos.CODEC
                            .encodeStart(NbtOps.INSTANCE, outpost.centre())
                            .getOrThrow());
            list.add(tag);
        }
        CompoundTag root = new CompoundTag();
        root.putInt("version", OutpostData.VERSION);
        root.put("outposts", list);
        return OutpostData.CODEC.parse(NbtOps.INSTANCE, root).getOrThrow();
    }
}
