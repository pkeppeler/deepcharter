package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The arithmetic of the campaign cost gate (ADR 0086): each band's marginal CPU per filled row, against {@code tall_layer}'s, on
 * medians pooled over every round of {@value #RUNS} runs. {@code CampaignCostTest} measures the worlds and asserts;
 * {@code BandCostTest} checks this on made-up readings.
 */
public final class BandCost {
    /** A band's rows may cost at most this many times the reference's. */
    public static final double BAR = 1.15;

    /** The runs (JVMs) a pool needs before the gate judges it, so that no one run's level decides. */
    public static final int RUNS = 3;

    /** The rounds of each world a pool needs before the gate judges it. */
    public static final int ROUNDS = 18;

    /**
     * One-sided 90% Student t for 1 to 9 degrees of freedom: a pool of 3 runs has 2. From 10 degrees of freedom (11 runs) the t is that
     * of 10, 1.372, which is a little high for more runs and so errs towards passing.
     */
    private static final double[] T90 = {3.078, 1.886, 1.638, 1.533, 1.476, 1.440, 1.415, 1.397, 1.383};

    private static final double T90_LIMIT = 1.372;

    private BandCost() {}

    /**
     * The least the runs' mean can be, with 90% confidence one-sided: the mean less the t of the runs' own scatter over the square root of
     * their number. A band is over only when this is over its bar, so a pool whose runs disagree cannot fail it (detection odds: ADR 0086).
     */
    public static double lowerBound(double[] perRun) {
        int n = perRun.length;
        if (n == 0 || Arrays.stream(perRun).anyMatch(value -> !Double.isFinite(value))) {
            throw new IllegalStateException(
                    "a run's ratio is not a finite number (its reference marginal is zero or less?): "
                            + Arrays.toString(perRun));
        }
        double mean = Arrays.stream(perRun).average().orElseThrow();
        if (n < 2) {
            return mean;
        }
        double squares = Arrays.stream(perRun)
                .map(value -> (value - mean) * (value - mean))
                .sum();
        double t = n - 2 < T90.length ? T90[n - 2] : T90_LIMIT;
        return mean - t * Math.sqrt(squares / (n - 1) / n);
    }

    /**
     * One round's reading of one world: the run it came from, the tree it measured, the columns of a round, the world's blocks of
     * height and its CPU ms per column.
     */
    public record Reading(String run, String tree, int columns, String world, int height, double cpuMsPerColumn) {
        String line() {
            return String.join(
                    "\t",
                    run,
                    tree,
                    Integer.toString(columns),
                    world,
                    Integer.toString(height),
                    Double.toString(cpuMsPerColumn));
        }

        static Reading parse(String line) {
            String[] fields = line.split("\t", -1);
            if (fields.length != 6) {
                throw new IllegalArgumentException(
                        "a pool line is run, tree, columns, world, height and CPU, tab-separated: " + line);
            }
            return new Reading(
                    fields[0],
                    fields[1],
                    Integer.parseInt(fields[2]),
                    fields[3],
                    Integer.parseInt(fields[4]),
                    Double.parseDouble(fields[5]));
        }
    }

    /** A band, the world that is the campaign world cut to its rows, and that world cut to its bottom rows. */
    public record Window(CampaignBands.Band band, String world, String floor) {}

    /**
     * A band that was over the bar when the gate was written, held to its own {@code measured} ratio plus the bar's 15% until
     * {@code issue} brings it under and deletes this. A band without one, every new band, is held to {@link #BAR}.
     */
    public record Ratchet(CampaignBands.Band band, double measured, int issue) {
        public double ceiling() {
            return measured * BAR;
        }
    }

    /**
     * A window's marginal CPU ms per filled row, that over the reference's (pooled), the {@link #lowerBound} of the runs' own ratios, and
     * the ratchet that holds it, if any. The band is over when {@code bound} is over its bar.
     */
    public record Ratio(Window window, double marginal, double ratio, double bound, Optional<Ratchet> ratchet) {
        /** The most the ratio may be. */
        public double bar() {
            return ratchet.map(Ratchet::ceiling).orElse(BAR);
        }

        public boolean over() {
            return bound > bar();
        }

        @Override
        public String toString() {
            return String.format(
                            Locale.ROOT,
                            "%s %.3fx (at least %.3fx) of %.3fx",
                            window.band().name(),
                            ratio,
                            bound,
                            bar())
                    + ratchet.map(held -> " (a ratchet, #" + held.issue() + ")").orElse("");
        }
    }

    /**
     * The readings of every pooled run; each statistic is over all of them, never over one run's. A pool holds one tree, one size of
     * round and one height per world: readings of another are not the same measurement.
     */
    public record Pool(List<Reading> readings) {
        public Pool {
            List<Reading> all = List.copyOf(readings);
            only(all.stream(), Reading::tree, "trees");
            only(all.stream(), Reading::columns, "sizes of round");
            all.stream()
                    .map(Reading::world)
                    .distinct()
                    .forEach(world -> only(
                            all.stream().filter(reading -> reading.world().equals(world)),
                            Reading::height,
                            "heights of " + world));
            readings = all;
        }

        private static void only(Stream<Reading> readings, Function<Reading, Object> field, String what) {
            Set<Object> values = readings.map(field).collect(Collectors.toSet());
            if (values.size() > 1) {
                throw new IllegalArgumentException("the pool mixes " + what + ": " + values);
            }
        }

        /** The pool in {@code file}, one reading a line; empty when there is no file yet. */
        public static Pool read(Path file) throws IOException {
            if (!Files.exists(file)) {
                return new Pool(List.of());
            }
            List<Reading> readings = Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                    .filter(line -> !line.isBlank())
                    .map(Reading::parse)
                    .toList();
            try {
                return new Pool(readings);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(
                        file + " is no one pool (" + e.getMessage() + "): delete it and start the pool again", e);
            }
        }

        public static void append(Path file, List<Reading> readings) throws IOException {
            Files.write(
                    file,
                    readings.stream().map(Reading::line).toList(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        }

        /** The median CPU ms per column of every round of {@code world} that {@code run} took. */
        public double median(String world, String run) {
            return ColumnCost.medianOf(of(world)
                    .filter(reading -> reading.run().equals(run))
                    .mapToDouble(Reading::cpuMsPerColumn)
                    .toArray());
        }

        /** {@link #marginalPerRow(String, String)} on one run's rounds alone. */
        public double marginalPerRow(String world, String floor, String run) {
            return (median(world, run) - median(floor, run)) / (height(world) - height(floor));
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

        public Set<String> runs(String world) {
            return of(world).map(Reading::run).collect(Collectors.toSet());
        }

        /**
         * CPU ms per column for each row {@code world} has over {@code floor}: the difference of their pooled medians over the
         * difference of their heights. The floor is the world cut to its bottom rows, so what a column costs whatever its height
         * (the chunk, the structure starts, the forcing) drops out, and the floor's rows are the world's own.
         */
        public double marginalPerRow(String world, String floor) {
            return (median(world) - median(floor)) / (height(world) - height(floor));
        }

        /**
         * True when every one of {@code worlds} comes from the same {@value #RUNS} or more runs, with {@value #ROUNDS} or more rounds,
         * and every run has as many rounds of each: a window is never judged against a floor or reference from other runs, and no run
         * weighs more than another.
         */
        public boolean judged(Collection<String> worlds) {
            Set<String> runs = worlds.stream().findFirst().map(this::runs).orElse(Set.of());
            Map<String, Long> perRun = readings.stream()
                    .filter(reading -> worlds.contains(reading.world()))
                    .collect(Collectors.groupingBy(
                            reading -> reading.run() + "\t" + reading.world(), Collectors.counting()));
            return runs.size() >= RUNS
                    && worlds.stream().allMatch(world -> runs(world).equals(runs) && rounds(world) >= ROUNDS)
                    && perRun.values().stream().distinct().count() == 1;
        }

        private Stream<Reading> of(String world) {
            return readings.stream().filter(reading -> reading.world().equals(world));
        }
    }

    /**
     * Each window's marginal cost per row over its floor, and its ratio to {@code reference}'s over {@code referenceFloor}, held to its
     * band's ratchet or else {@link #BAR}. Every world named must be in the pool.
     */
    public static List<Ratio> ratios(
            Pool pool, List<Window> windows, List<Ratchet> ratchets, String reference, String referenceFloor) {
        double perRow = pool.marginalPerRow(reference, referenceFloor);
        if (!(perRow > 0)) {
            throw new IllegalStateException(
                    "the reference " + reference + " has no cost per row over " + referenceFloor + ": " + perRow);
        }
        return windows.stream()
                .map(window -> {
                    double marginal = pool.marginalPerRow(window.world(), window.floor());
                    // Only a run that read all four worlds has a ratio of its own; with none, the bound is the pooled
                    // ratio.
                    double[] perRun = pool.runs(window.world()).stream()
                            .filter(run -> Stream.of(window.floor(), reference, referenceFloor)
                                    .allMatch(world -> pool.runs(world).contains(run)))
                            .mapToDouble(run -> perRunRatio(pool, window, reference, referenceFloor, run))
                            .toArray();
                    Optional<Ratchet> ratchet = ratchets.stream()
                            .filter(held -> held.band().equals(window.band()))
                            .findFirst();
                    double bound = perRun.length == 0 ? marginal / perRow : lowerBound(perRun);
                    return new Ratio(window, marginal, marginal / perRow, bound, ratchet);
                })
                .toList();
    }

    private static double perRunRatio(Pool pool, Window window, String reference, String referenceFloor, String run) {
        double referencePerRow = pool.marginalPerRow(reference, referenceFloor, run);
        if (!(referencePerRow > 0)) {
            throw new IllegalStateException("run " + run + " has no cost per row for " + reference + " over "
                    + referenceFloor + ": " + referencePerRow);
        }
        return pool.marginalPerRow(window.world(), window.floor(), run) / referencePerRow;
    }
}
