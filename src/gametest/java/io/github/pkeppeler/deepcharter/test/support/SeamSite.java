package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.layer.LayerRegistry;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.test.mixin.ServerChunkCacheAccess;
import java.util.List;
import java.util.function.IntConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The site of a seam test (ADR 0089): a column of the shipped layer 1 with the arrival area of layer 2 under it, a thousand blocks from
 * the next test's, and the wait for the area. Shared by {@code SeamStreamingTest} and {@code SeamDataAheadTest}.
 */
public final class SeamSite {
    /** Chunks in the arrival area: a square of {@code 2 * radius + 1} on a side. */
    public static final int AREA_CHUNKS =
            (2 * LayerTuning.DEFAULT.seamRadius() + 1) * (2 * LayerTuning.DEFAULT.seamRadius() + 1);
    /** Blocks above the floor of layer 1 where a test holds its vehicle: inside the floor margin, so it always streams. */
    public static final int HOLD_HEIGHT = 100;
    /** Blocks above the floor where a falling vehicle starts, a drop of a few ticks. */
    public static final int DROP_HEIGHT = 8;
    /** Half the width of the air shaft cut above the floor: wide enough for a pod. */
    public static final int SHAFT_HALF_WIDTH = 2;
    /** The test's own ticks beside its chunk wait: a drop, a crossing, and the run of the ticket's timeout when the chunk work is not in the way. */
    public static final int WORK_TICKS = 400;

    public static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + WORK_TICKS;
    /** Blocks between the X of two tests, so their arrival areas stay apart. */
    public static final int SITE_SPACING = 1000;
    /** A chunk's middle: a shaft there stays inside one chunk. */
    public static final double CHUNK_MIDDLE = 8.5;

    public static final double FIRST_SITE = 40_000 + CHUNK_MIDDLE;

    /** Where a test stands: a column of layer 1, and the layer under it. */
    public record Site(ServerLevel one, ServerLevel two, double x, double z) {
        public static Site of(GameTestHelper helper, int index) {
            MinecraftServer server = helper.getLevel().getServer();
            return new Site(
                    TestLayers.layerDimension(server, 1),
                    TestLayers.layerDimension(server, 2),
                    FIRST_SITE + (double) SITE_SPACING * index,
                    FIRST_SITE);
        }

        public int floorY() {
            return TestLayers.dimensionBand(one).minY();
        }

        public Vec3 at(int heightAboveFloor) {
            return new Vec3(x, floorY() + heightAboveFloor, z);
        }

        public ChunkPos centre() {
            return ChunkPos.containing(BlockPos.containing(x, 0, z));
        }

        /** An air shaft from the floor of layer 1 up to {@code topHeight}, so a mock or a pod in it is not in rock and can fall out. */
        public void carveShaft(double shaftX, int topHeight) {
            BlockPos column = BlockPos.containing(shaftX, floorY(), z);
            RoomCarver.carve(
                    one,
                    column.offset(-SHAFT_HALF_WIDTH, 0, -SHAFT_HALF_WIDTH),
                    column.offset(SHAFT_HALF_WIDTH, topHeight, SHAFT_HALF_WIDTH),
                    Blocks.AIR.defaultBlockState(),
                    Block.UPDATE_ALL);
        }
    }

    /** One test's site, and the wall-clock limit of its wait for the arrival area. */
    public record Run(GameTestHelper helper, Site site, FarChunks.Deadline deadline) {
        public static Run of(GameTestHelper helper, int index) {
            return new Run(helper, Site.of(helper, index), FarChunks.deadline());
        }

        /** Whether the whole arrival area is loaded under a ticket; otherwise sleeps a little and, past the limit, fails naming what is missing. */
        public boolean arrivalReady() {
            int loaded = loadedAround(site.two(), site.centre());
            boolean ready = loaded == AREA_CHUNKS
                    && !arrivalTickets(site.two(), site.centre()).isEmpty();
            deadline.await(
                    helper,
                    site.two(),
                    ready,
                    () -> "the arrival area of " + site.centre() + " has " + loaded + " of " + AREA_CHUNKS
                            + " chunks loaded and tickets " + arrivalTickets(site.two(), site.centre()));
            return ready;
        }

        /**
         * Waits until every position of {@code ticking} in layer 1 ticks entities, then runs {@code setup} once and, from the next tick,
         * {@code step} with the ticks since. {@code step} ends the test itself.
         */
        public void stage(List<BlockPos> ticking, Runnable setup, IntConsumer step) {
            boolean[] ready = {false};
            long[] placedAt = {-1};
            FarChunks.awaitAllEntityTicking(helper, site.one(), ticking, () -> ready[0] = true);
            TestTicks.each(helper, () -> {
                if (placedAt[0] < 0) {
                    if (ready[0]) {
                        setup.run();
                        placedAt[0] = helper.getTick();
                    }
                    return;
                }
                step.accept((int) (helper.getTick() - placedAt[0]));
            });
        }

        public void stage(Runnable setup, IntConsumer step) {
            stage(List.of(BlockPos.containing(site.at(HOLD_HEIGHT))), setup, step);
        }
    }

    /** How many chunks of the arrival area around {@code centre} are loaded in {@code level}. */
    public static int loadedAround(ServerLevel level, ChunkPos centre) {
        int radius = LayerTuning.DEFAULT.seamRadius();
        int loaded = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (level.getChunkSource().getChunkNow(centre.x() + dx, centre.z() + dz) != null) {
                    loaded++;
                }
            }
        }
        return loaded;
    }

    /** The arrival tickets vanilla holds on {@code chunk} of {@code level}, read from its ticket storage. */
    public static List<Ticket> arrivalTickets(ServerLevel level, ChunkPos chunk) {
        return ((ServerChunkCacheAccess) level.getChunkSource())
                .deepcharter$ticketStorage().getTickets(chunk.pack()).stream()
                        .filter(ticket -> ticket.getType() == LayerRegistry.SEAM_ARRIVAL)
                        .toList();
    }

    /** Lowers a vehicle that is still in layer 1 by a block, a mock's fall: it has no client gravity. */
    public static void fall(Site site, Entity vehicle) {
        if (vehicle.level() == site.one()) {
            vehicle.setPos(vehicle.getX(), vehicle.getY() - 1, vehicle.getZ());
        }
    }

    /** A vehicle at {@code height} above layer 1's floor holds there: zero speed, so a pod does not fall of its own weight. */
    public static void hold(Site site, PodEntity pod, int height) {
        pod.setPos(site.at(height));
        pod.setDeltaMovement(Vec3.ZERO);
    }

    private SeamSite() {}
}
