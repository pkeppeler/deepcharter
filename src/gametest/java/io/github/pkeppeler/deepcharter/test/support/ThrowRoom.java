package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.repair.RepairRegistry;
import io.github.pkeppeler.deepcharter.repair.ThrownKitEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * What the thrown kit's tests share (#985): a sealed stone room in layer 1 whose floor is at {@link #FLOOR}, a person on foot at its south end who
 * faces north-to-south along +z, and the cells round {@code (x, FLOOR, Z)}. Each test has its own {@code x}. The room is dark: layer 1 holds no light.
 */
public final class ThrowRoom {
    public static final int Z = 5400;
    public static final int FLOOR = TestLayers.worldY(1, 60);
    /** Half the room's width and length: a flat throw (about 12 blocks) and a lob both land inside it. */
    public static final int HALF = 24;
    /** How far south of {@code (x, Z)} the thrower stands. */
    public static final int THROWER_BACK = 11;

    private static final int CHUNK = 16;
    /** How far either side of the room's middle line {@link #flare()} looks. */
    private static final int FLARE_REACH = 8;

    private final ServerLevel level;
    private final int x;
    private final MockPlayer thrower;
    private int ticks;

    private ThrowRoom(ServerLevel level, int x, MockPlayer thrower) {
        this.level = level;
        this.x = x;
        this.thrower = thrower;
    }

    /**
     * Carves the room, joins a person on foot (survival) and, once the room's chunk ticks, runs {@code each} every tick with the room, after
     * {@code first} has run once. Both may throw to fail the test.
     */
    public static void start(GameTestHelper helper, int x, Consumer<ThrowRoom> first, Consumer<ThrowRoom> each) {
        ServerLevel level = TestLayers.level(helper, 1);
        RoomCarver.carve(level, x - HALF, x + HALF, FLOOR - 8, FLOOR - 1, Z - HALF, Z + HALF, Blocks.STONE);
        RoomCarver.carve(level, x - HALF, x + HALF, FLOOR, FLOOR + 10, Z - HALF, Z + HALF, Blocks.AIR);
        MockPlayer thrower = MockPlayers.join(helper, "thrower-" + x);
        thrower.player().setGameMode(GameType.SURVIVAL);
        ThrowRoom room = new ThrowRoom(level, x, thrower);
        thrower.teleportTo(level, room.throwingSpot(), 0f, 0f);
        boolean[] ready = {false};
        // A flying item stops in a chunk that does not tick, so every chunk of the room's column must.
        List<BlockPos> column = new ArrayList<>();
        for (int z = Z - HALF; z <= Z + HALF; z += CHUNK) {
            column.add(new BlockPos(x, FLOOR, z));
        }
        int[] waiting = {column.size()};
        FarChunks.awaitEntityTicking(helper, level, column, index -> {
            if (--waiting[0] == 0) {
                first.accept(room);
                ready[0] = true;
            }
        });
        TestTicks.each(helper, () -> {
            if (ready[0]) {
                room.ticks++;
                each.accept(room);
            }
        });
    }

    public ServerLevel level() {
        return level;
    }

    public MockPlayer thrower() {
        return thrower;
    }

    /** Ticks since the room was ready, 1 on the first tick of {@code each}. */
    public int ticks() {
        return ticks;
    }

    public Vec3 throwingSpot() {
        return new Vec3(x + 0.5, FLOOR, Z - THROWER_BACK + 0.5);
    }

    /** The cell {@code (dx, dy, dz)} from {@code (x, FLOOR, Z)}. */
    public BlockPos cell(int dx, int dy, int dz) {
        return new BlockPos(x + dx, FLOOR + dy, Z + dz);
    }

    /** The cell with a flare in the strip the throws land in, or null when none burns there. */
    public @Nullable BlockPos flare() {
        for (int z = -HALF; z <= HALF; z++) {
            for (int dx = -FLARE_REACH; dx <= FLARE_REACH; dx++) {
                BlockPos cell = cell(dx, 0, z);
                if (level.getBlockState(cell).is(RepairRegistry.FLARE_LIGHT)) {
                    return cell;
                }
            }
        }
        return null;
    }

    /** Whether a thrown kit is in the room and has come to rest. */
    public boolean kitLanded() {
        List<ThrownKitEntity> kits = kits();
        return !kits.isEmpty() && kits.getFirst().landed();
    }

    /** The thrown kit in the room, in flight or landed. */
    public List<ThrownKitEntity> kits() {
        // Every test's room is its own 49 blocks wide, so what is within it is this test's.
        return new ArrayList<>(level.getEntities(RepairRegistry.THROWN_KIT, kit -> Math.abs(kit.getX() - x) <= HALF));
    }
}
