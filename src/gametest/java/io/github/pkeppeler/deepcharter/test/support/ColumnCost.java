package io.github.pkeppeler.deepcharter.test.support;

import java.lang.management.ManagementFactory;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * Measures what generating new columns costs in the test dimensions {@code deepcharter:<world>}: CPU, heap and wall time per column, over
 * rounds that alternate the order of the worlds, so that load on the machine falls on each world in turn. {@code SurfaceCostTest} (#240) and
 * {@code CampaignCostTest} (#742) run it with their own worlds and report their own ratios.
 */
public final class ColumnCost {
    /** Chunks to each side of the centre in the smoke case, which only checks that the worlds generate. */
    private static final int SMOKE_RADIUS = 1;

    /** Chunks to each side of the centre of a measured run, so a run is {@code (2 * 8 + 1)} squared chunks, which the readings call columns. */
    public static final int MEASURED_RADIUS = 8;

    private static final int WARM_UP_RADIUS = 4;
    /** Chunk rows between two regions, so that no two measurements share a column. */
    private static final int REGION_SPACING_CHUNKS = 64;

    private static final int COOL_DOWN_TICKS = 100;
    /** A region counts as unloaded when this few chunks of a world are left, such as those around a spawn. */
    private static final int UNLOADED_CHUNKS = 30;

    private ColumnCost() {}

    /** One run's reading, per column, and the blocks of height of the world it was taken in. */
    public record Measurement(double cpuMsPerColumn, double heapMb, double wallMsPerColumn, int height) {
        /** CPU per block of the column's height: what a taller world costs apart from being taller. */
        public double cpuMsPerBlock() {
            return cpuMsPerColumn / height;
        }
    }

    /**
     * Generates the columns of {@code worlds} and, for {@code rounds} above 0, hands the readings of each world (one per round) to
     * {@code report} and succeeds. With 0 rounds it is the smoke case: a few columns of each world, no heap readings, no waits. A measured
     * run first warms every world up, then runs each round over {@code worlds} (reversed on even rounds), and waits for every world in
     * {@code allWorlds} to hold nothing of the last region before it starts the next, because heap is read with the one before it gone.
     * Call it from the test method.
     */
    public static void run(
            GameTestHelper helper,
            List<String> worlds,
            List<String> allWorlds,
            int rounds,
            Consumer<Map<String, List<Measurement>>> report) {
        boolean smoke = rounds == 0;
        ArrayDeque<Run> queue = new ArrayDeque<>();
        if (smoke) {
            worlds.forEach(world -> queue.add(new Run(world, SMOKE_RADIUS, 0, false, true)));
        } else {
            worlds.forEach(world -> queue.add(new Run(world, WARM_UP_RADIUS, 0, false, false)));
            for (int round = 1; round <= rounds; round++) {
                for (String world : round % 2 == 1 ? worlds : worlds.reversed()) {
                    queue.add(new Run(world, MEASURED_RADIUS, round, true, false));
                }
            }
        }
        Map<String, List<Measurement>> results = new LinkedHashMap<>();
        @Nullable Run[] current = {null};
        int[] cooldown = {0};
        TestTicks.each(helper, () -> {
            if (cooldown[0] > 0) {
                cooldown[0]--;
                return;
            }
            if (current[0] == null && !smoke && !everyWorldIsUnloaded(helper, allWorlds)) {
                return;
            }
            if (current[0] == null) {
                if (queue.isEmpty()) {
                    return;
                }
                current[0] = queue.poll();
                current[0].begin(helper);
            }
            if (!current[0].isGenerated()) {
                return;
            }
            Measurement measurement = current[0].finish(helper);
            if (current[0].measured) {
                results.computeIfAbsent(current[0].world, world -> new ArrayList<>())
                        .add(measurement);
            }
            current[0] = null;
            cooldown[0] = smoke ? 0 : COOL_DOWN_TICKS;
            if (queue.isEmpty()) {
                if (rounds > 0) {
                    report.accept(results);
                }
                helper.succeed();
            }
        });
    }

    /** The median of each reading over {@code all}. */
    public static Measurement median(List<Measurement> all) {
        return new Measurement(
                medianOf(all.stream().mapToDouble(m -> m.cpuMsPerColumn).toArray()),
                medianOf(all.stream().mapToDouble(m -> m.heapMb).toArray()),
                medianOf(all.stream().mapToDouble(m -> m.wallMsPerColumn).toArray()),
                all.getFirst().height);
    }

    public static String format(double value) {
        return String.format("%.2f", value);
    }

    /** The columns of a measured run. */
    public static int measuredColumns() {
        return columnsOf(MEASURED_RADIUS);
    }

    private static boolean everyWorldIsUnloaded(GameTestHelper helper, List<String> allWorlds) {
        for (String world : allWorlds) {
            ServerLevel level = helper.getLevel()
                    .getServer()
                    .getLevel(ResourceKey.create(
                            Registries.DIMENSION, Identifier.fromNamespaceAndPath("deepcharter", world)));
            if (level != null && level.getChunkSource().getLoadedChunksCount() > UNLOADED_CHUNKS) {
                return false;
            }
        }
        return true;
    }

    private static double medianOf(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int middle = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
    }

    private static int columnsOf(int radius) {
        return (2 * radius + 1) * (2 * radius + 1);
    }

    private static long processCpuNanos() {
        return ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean())
                .getProcessCpuTime();
    }

    /** Heap in use after a collection, so that garbage of earlier work is not counted. */
    private static long usedHeap() {
        for (int i = 0; i < 3; i++) {
            System.gc();
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while measuring heap", e);
            }
        }
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    /** The columns of one world around a centre of its own: forced, then waited for tick by tick. */
    private static final class Run {
        private final String world;
        private final int radius;
        private final int round;
        private final boolean measured;
        private final boolean smoke;
        private final Arrival<ServerLevel> level = new Arrival<>("level", "begin() finds the dimension");
        private int centreChunk;
        private long heapBefore;
        private long cpuBefore;
        private long wallBefore;

        Run(String world, int radius, int round, boolean measured, boolean smoke) {
            this.world = world;
            this.radius = radius;
            this.round = round;
            this.measured = measured;
            this.smoke = smoke;
        }

        void begin(GameTestHelper helper) {
            ResourceKey<Level> key =
                    ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("deepcharter", world));
            ServerLevel found = helper.getLevel().getServer().getLevel(key);
            if (found == null) {
                throw helper.assertionException(Component.literal("no dimension " + key.identifier()));
            }
            level.set(found);
            centreChunk = (round + 1) * REGION_SPACING_CHUNKS;
            heapBefore = smoke ? 0 : usedHeap();
            cpuBefore = processCpuNanos();
            wallBefore = System.nanoTime();
            forEachChunk(chunk -> FarChunks.force(helper, found, chunk[0], chunk[1]));
        }

        boolean isGenerated() {
            boolean[] all = {true};
            forEachChunk(chunk -> all[0] &= level.get().getChunkSource().getChunkNow(chunk[0], chunk[1]) != null);
            return all[0];
        }

        Measurement finish(GameTestHelper helper) {
            double columns = columnsOf(radius);
            double cpuMs = (processCpuNanos() - cpuBefore) / 1e6;
            double wallMs = (System.nanoTime() - wallBefore) / 1e6;
            double heapMb = smoke ? 0 : (usedHeap() - heapBefore) / (1024.0 * 1024.0);
            forEachChunk(chunk -> FarChunks.release(helper, level.get(), chunk[0], chunk[1]));
            return new Measurement(
                    cpuMs / columns, heapMb, wallMs / columns, level.get().getHeight());
        }

        private void forEachChunk(Consumer<int[]> action) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    action.accept(new int[] {centreChunk + x, centreChunk + z});
                }
            }
        }
    }
}
