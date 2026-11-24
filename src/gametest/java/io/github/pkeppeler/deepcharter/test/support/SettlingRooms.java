package io.github.pkeppeler.deepcharter.test.support;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.layer.RoomSeal;
import io.github.pkeppeler.deepcharter.outpost.OutpostData.Outpost;
import io.github.pkeppeler.deepcharter.outpost.OutpostSettling;
import io.github.pkeppeler.deepcharter.outpost.OutpostVisits.Ledger;
import io.github.pkeppeler.deepcharter.outpost.OutpostVisits.Pending;
import io.github.pkeppeler.deepcharter.outpost.OutpostVisits.Report;
import io.github.pkeppeler.deepcharter.outpost.SettlingTuning;
import io.github.pkeppeler.deepcharter.outpost.ShellTuning;
import io.github.pkeppeler.deepcharter.outpost.SettlingTuning.LayerRates;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * The stage of the away-settling tests (#1004): a dark, hall-sized room in the rock of layer 1 with a barrel in it, and the outpost that
 * owns it, with no hall built and nothing recorded in the world's data. {@link OutpostSettling#settle} takes the outpost and its ledger
 * as arguments, so a test settles with a synthetic game time and never touches the world's clock.
 */
public final class SettlingRooms {
    private static final int FIRST_X = 9600;
    private static final int Z = 9600;
    private static final int KIT_Z = 9500;
    /** Rock is carved this far out from the middle, past the zone's edge (the hall's shell plus the reach). */
    private static final int ROCK_RADIUS = 14;

    private static final int HALL_RADIUS = 4;
    private static final int HALL_HEIGHT = 5;
    /** Rock is carved this high over the floor: the hall, its ceiling and a shaft of {@link #SHAFT_HEIGHT} over it, with a roof. */
    private static final int ROCK_HEIGHT = HALL_HEIGHT + 1 + ShellTuning.DEFAULT.openSpan() + 2;
    /** The shaft of {@link #openToOutside}: one block more than a closed pocket may span, so that the hall's air is outside. */
    private static final int SHAFT_HEIGHT = ShellTuning.DEFAULT.openSpan() + 1;
    /** How many columns apart two rooms are: more than a zone and its reach. */
    private static final int SPACING = 64;

    /** One window of game time, in ticks. */
    public static final int WINDOW = SettlingTuning.DEFAULT.windowTicks();

    /** Layer 1 with one visit of the Retained for certain in every window, and nothing else: a visit count a test can state exactly. */
    public static final SettlingTuning SURE_RETAINED =
            tuning(SettlingTuning.DEFAULT.nearBlocks(), new LayerRates(20, 0, 0));

    /** As {@link #SURE_RETAINED} for the Recovery. */
    public static final SettlingTuning SURE_RECOVERY =
            tuning(SettlingTuning.DEFAULT.nearBlocks(), new LayerRates(0, 0, 20));

    /** A room, the outpost that owns it, and the barrel in its corner. */
    public record Room(ServerLevel level, Outpost outpost, BlockPos barrel) {
        public BlockPos floor() {
            return outpost.centre();
        }

        /** The barrel's items, which a test reads and fills. */
        public Container storage() {
            return containerAt(level, barrel);
        }

        public BoundingBox zone() {
            return OutpostSettling.zone(outpost, SettlingTuning.DEFAULT);
        }

        /** A ledger begun at window 0 of game time: nothing rolled, nothing surveyed. */
        public Ledger ledger() {
            return new Ledger(outpost.charter(), outpost.layer(), 0, Optional.empty(), Pending.NONE, Report.NONE);
        }
    }

    private SettlingRooms() {}

    /** The default tuning with {@code nearBlocks} and with {@code layer} as the rates of layer 1, the only layer. */
    public static SettlingTuning tuning(int nearBlocks, LayerRates layer) {
        SettlingTuning base = SettlingTuning.DEFAULT;
        return new SettlingTuning(
                base.windowTicks(),
                base.sweepIntervalTicks(),
                nearBlocks,
                base.reachOut(),
                base.reachUp(),
                base.reachDown(),
                base.maxWindowsPerSweep(),
                base.pendingCap(),
                base.fuelShare(),
                base.maxFuelItemsPerVisit(),
                base.maxOrePerVisit(),
                base.maxBlocksPerVisit(),
                List.of(layer));
    }

    /** The game time at which {@code windows} whole windows have passed. */
    public static long after(int windows) {
        return (long) windows * WINDOW;
    }

    /**
     * As {@link #stageSealed}, with the hall open to the outside ({@link #openToOutside}): the room a visit can reach, which is what a
     * test of what visits do needs.
     */
    public static Room stage(GameTestHelper helper, int slot, CharterId charter) {
        Room room = stageSealed(helper, slot, charter);
        openToOutside(room);
        return room;
    }

    /**
     * Carves room number {@code slot} in layer 1 (its rock, a sealed dark hall, a barrel) for an outpost of {@code charter}. Nothing a visit
     * can reach is in it: the hall's air is a room, so the Retained find no storage. Call from the test method; the room's chunks load as it
     * is carved, and {@link #requireLoaded} waits until they are all there.
     */
    public static Room stageSealed(GameTestHelper helper, int slot, CharterId charter) {
        ServerLevel level = TestLayers.level(helper, 1);
        int x = FIRST_X + slot * SPACING;
        BlockPos floor =
                new BlockPos(x, TestLayers.band(level, 1).minY() + LayerTuning.DEFAULT.crustThickness() - 1, Z);
        RoomCarver.carve(
                level,
                x - ROCK_RADIUS,
                x + ROCK_RADIUS,
                floor.getY() + 1,
                floor.getY() + ROCK_HEIGHT,
                Z - ROCK_RADIUS,
                Z + ROCK_RADIUS,
                Blocks.STONE);
        BlockPos from = floor.offset(-HALL_RADIUS, 1, -HALL_RADIUS);
        BlockPos to = floor.offset(HALL_RADIUS, HALL_HEIGHT, HALL_RADIUS);
        RoomSeal.seal(level, from, to);
        RoomCarver.carve(level, from, to, Blocks.AIR.defaultBlockState());
        BlockPos barrel = floor.offset(3, 1, 3);
        level.setBlock(barrel, Blocks.BARREL.defaultBlockState(), 3);
        return new Room(level, new Outpost(charter, 1, level.dimension(), floor), barrel);
    }

    /**
     * Lines the room's walls and ceiling with {@code wall}, over the rock they were cut in, and puts the barrel back. Call from the test
     * method, before {@link #openToOutside} and before the room's chunks are awaited.
     */
    public static void lineShell(Room room, BlockState wall) {
        BlockPos floor = room.floor();
        RoomCarver.carve(
                room.level(),
                floor.offset(-HALL_RADIUS - 1, 1, -HALL_RADIUS - 1),
                floor.offset(HALL_RADIUS + 1, HALL_HEIGHT + 1, HALL_RADIUS + 1),
                wall);
        RoomCarver.carve(
                room.level(),
                floor.offset(-HALL_RADIUS, 1, -HALL_RADIUS),
                floor.offset(HALL_RADIUS, HALL_HEIGHT, HALL_RADIUS),
                Blocks.AIR.defaultBlockState());
        room.level().setBlock(room.barrel(), Blocks.BARREL.defaultBlockState(), 3);
    }

    /**
     * Cuts a 1 by 1 shaft up from the middle of the room's ceiling, one block taller than a closed pocket may span, so that the hall's air
     * joins an outside (ADR 0092). The room's rock is carved to hold it, and {@link RoomCarver} seals its shell before it cuts. Call from
     * the test method, as {@link #stageSealed} is.
     */
    public static void openToOutside(Room room) {
        BlockPos from = room.floor().offset(0, HALL_HEIGHT + 1, 0);
        RoomCarver.carve(room.level(), from, from.above(SHAFT_HEIGHT - 1), Blocks.AIR.defaultBlockState());
    }

    /**
     * The clicked floor block of a shaft bottom in layer 1, in solid stone with a 2 by 2 shaft up from it, where the outpost kit
     * works, at column {@code x}. Call from the test method.
     */
    public static BlockPos kitFloor(ServerLevel level, int x) {
        BlockPos floor =
                new BlockPos(x, TestLayers.band(level, 1).minY() + LayerTuning.DEFAULT.crustThickness() - 1, KIT_Z);
        RoomCarver.carve(level, x - 8, x + 8, floor.getY() + 1, floor.getY() + 16, KIT_Z - 8, KIT_Z + 8, Blocks.STONE);
        RoomCarver.carve(
                level,
                new BlockPos(x, floor.getY() + 1, KIT_Z),
                new BlockPos(x + 1, floor.getY() + 16, KIT_Z + 1),
                Blocks.AIR.defaultBlockState());
        return floor;
    }

    /** A charter id no charter holds, for an outpost that needs no charter. */
    public static CharterId nobody() {
        return new CharterId(UUID.randomUUID());
    }

    /** The container block entity at {@code pos}, which the test has put there. */
    public static Container containerAt(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof Container container)) {
            throw new IllegalStateException("no container at " + pos);
        }
        return container;
    }

    /** Throws until every chunk of the room's zone is loaded: for a sequence's {@code thenWaitUntil}. */
    public static void requireLoaded(GameTestHelper helper, Room room) {
        expect(
                helper,
                OutpostSettling.isLoaded(room.level(), room.zone()),
                "waiting for the zone %s to load",
                room.zone());
    }

    /** Puts {@code count} of {@code item} in the barrel, from its first slot. */
    public static void stock(Room room, Item item, int count) {
        Container storage = room.storage();
        int left = count;
        for (int slot = 0; slot < storage.getContainerSize() && left > 0; slot++) {
            int stack = Math.min(left, item.getDefaultMaxStackSize());
            storage.setItem(slot, new ItemStack(item, stack));
            left -= stack;
        }
        if (left != 0) {
            throw new IllegalStateException("the barrel holds only " + (count - left) + " of the " + count + " items");
        }
    }

    /** How many of {@code item} a container holds. */
    public static int count(Container storage, Item item) {
        int total = 0;
        for (int slot = 0; slot < storage.getContainerSize(); slot++) {
            if (storage.getItem(slot).is(item)) {
                total += storage.getItem(slot).getCount();
            }
        }
        return total;
    }
}
