package io.github.pkeppeler.deepcharter.test.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * The arithmetic of the campaign cost gate (ADR 0086): each band's marginal CPU per filled row, against
 * {@code tall_layer}'s, on medians pooled over every round of {@value #RUNS} runs. {@code CampaignCostTest} measures the worlds and
 * asserts; {@code BandCostTest} checks this on made-up readings.
 */
public final class BandCost {
    /** A band's rows may cost at most this many times the reference's. */
    public static final double BAR = 1.15;

    /** The runs (JVMs) a pool needs before the gate judges it, so that no one run's level decides. */
    public static final int RUNS = 3;

    /** The rounds of each world a pool needs before the gate judges it. */
    public static final int ROUNDS = 18;

    private BandCost() {}

    /** One round's reading of one world: the run it came from, the world's blocks of height and its CPU ms per column. */
    public record Reading(String run, String world, int height, double cpuMsPerColumn) {
        String line() {
            return String.join("\t", run, world, Integer.toString(height), Double.toString(cpuMsPerColumn));
        }

        static Reading parse(String line) {
            String[] fields = line.split("\t", -1);
            if (fields.length != 4) {
                throw new IllegalArgumentException("a pool line is run, world, height and CPU, tab-separated: " + line);
            }
            return new Reading(fields[0], fields[1], Integer.parseInt(fields[2]), Double.parseDouble(fields[3]));
        }
    }

    /** A band, and the world that is the campaign world cut to that band's rows. */
    public record Window(String band, String world) {}

    /** A band's marginal CPU ms per filled row, and that over the reference's. */
    public record Ratio(String band, double marginal, double ratio) {
        public boolean over() {
            return ratio > BAR;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%s %.3fx", band, ratio);
        }
    }

    /** The readings of every pooled run; each statistic is over all of them, never over one run's. */
    public record Pool(List<Reading> readings) {
        public Pool {
            readings = List.copyOf(readings);
        }

        /** The pool in {@code file}, one reading a line; empty when there is no file yet. */
        public static Pool read(Path file) throws IOException {
            if (!Files.exists(file)) {
                return new Pool(List.of());
            }
            return new Pool(Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                    .filter(line -> !line.isBlank())
                    .map(Reading::parse)
                    .toList());
        }

        public static void append(Path file, List<Reading> readings) throws IOException {
            Files.write(
                    file,
                    readings.stream().map(Reading::line).toList(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        }

        public boolean has(String world) {
            return rounds(world) > 0;
        }

        /** The median CPU ms per column of every round of {@code world}, whatever run it came from. */
        public double median(String world) {
            return ColumnCost.medianOf(
                    of(world).mapToDouble(Reading::cpuMsPerColumn).toArray());
        }

        public int height(String world) {
            return of(world)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("no reading of " + world))
                    .height();
        }

        public int rounds(String world) {
            return (int) of(world).count();
        }

        public int runs(String world) {
            return (int) of(world).map(Reading::run).distinct().count();
        }

        /**
         * CPU ms per column for each row {@code world} has over {@code floor}: the difference of their pooled medians over the
         * difference of their heights. The floor is a thin world of the same generator, so what a column costs whatever its height
         * (the chunk, the structure starts, the forcing) drops out.
         */
        public double marginalPerRow(String world, String floor) {
            return (median(world) - median(floor)) / (height(world) - height(floor));
        }

        /** True when every one of {@code worlds} has {@value #ROUNDS} rounds from {@value #RUNS} runs. */
        public boolean judged(Collection<String> worlds) {
            return worlds.stream().allMatch(world -> runs(world) >= RUNS && rounds(world) >= ROUNDS);
        }

        private Stream<Reading> of(String world) {
            return readings.stream().filter(reading -> reading.world().equals(world));
        }
    }

    /**
     * Each window's marginal cost per row over {@code floor}, and its ratio to {@code reference}'s over {@code referenceFloor}. Every
     * world named must be in the pool.
     */
    public static List<Ratio> ratios(
            Pool pool, List<Window> windows, String floor, String reference, String referenceFloor) {
        double perRow = pool.marginalPerRow(reference, referenceFloor);
        return windows.stream()
                .map(window -> {
                    double marginal = pool.marginalPerRow(window.world(), floor);
                    return new Ratio(window.band(), marginal, marginal / perRow);
                })
                .toList();
    }
}
