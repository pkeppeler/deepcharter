package io.github.pkeppeler.deepcharter.test.support;

import com.google.common.base.Splitter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import javax.management.JMException;
import javax.management.ObjectName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The live heap by class at the start of each client test class, for a leak that grows by one world per class (the 2352-tall campaign
 * world: about 70 MB a class). The environment variable {@link #DIR_ENV} names a directory; when it is set, {@code ClientTestLog.start}
 * collects the heap (the {@code GC.class_histogram} diagnostic command, which runs a full collection first) and writes the largest 40
 * classes, and the rows of the types that pin a world and of every class of this mod, to {@code <dir>/<number>-<class>.txt}, and the live threads by name to {@code <number>-<class>-threads.txt}. Unset, nothing runs: a collection at every
 * class costs seconds. Diff the first and the last file to find the type that grows.
 */
public final class HeapHistogram {
    /** The directory the histograms go to; unset turns them off. CI sets it and uploads the directory with the GC log. */
    public static final String DIR_ENV = "DEEPCHARTER_CLIENT_HEAP_HISTOGRAM";

    private static final Logger LOGGER = LoggerFactory.getLogger("HeapHistogram");
    private static final int TOP = 40;
    /** Rows kept besides the top: the types that pin a whole world, whatever their rank, and every class of this mod. */
    private static final Pattern KEPT_TYPES = Pattern.compile(
            "\\s(net\\.minecraft\\.(client\\.multiplayer\\.ClientLevel|server\\.level\\.ServerLevel|server\\.MinecraftServer"
                + "|server\\.level\\.ChunkMap|world\\.level\\.chunk\\.LevelChunk|client\\.multiplayer\\.ClientChunkCache"
                + "|client\\.multiplayer\\.ClientPacketListener|server\\.level\\.ServerPlayer|world\\.level\\.chunk\\.ProtoChunk"
                + "|world\\.level\\.chunk\\.LevelChunkSection|client\\.Minecraft|server\\.dedicated\\.\\w+|server\\.network\\.\\w+|network\\.Connection)"
                + "|io\\.github\\.pkeppeler\\.deepcharter\\.[\\w.$]+)(?:\\s+\\([^)]*\\))?\\s*$");

    private static final Splitter ROW = Splitter.onPattern("\\s+");

    private static int count;

    private HeapHistogram() {}

    /**
     * The live instances of each class named in {@code classes} (binary names), after a full collection: a class with
     * none is counted as 0, so a caller checks the name exists. Always runs, whatever {@link #DIR_ENV} says, so a test
     * can assert on what a client or server holds.
     */
    public static Map<String, Long> liveCounts(Set<String> classes) {
        Map<String, Long> counts = new HashMap<>();
        classes.forEach(name -> counts.put(name, 0L));
        try {
            String histogram = gcClassHistogram();
            // A row is "num: instances bytes class (module)"; a class is named by its binary name.
            for (String line : histogram.lines().toList()) {
                List<String> row = ROW.splitToList(line.trim());
                if (row.size() >= 4 && classes.contains(row.get(3))) {
                    counts.put(row.get(3), Long.parseLong(row.get(1)));
                }
            }
        } catch (JMException failure) {
            throw new IllegalStateException("The class histogram could not be read", failure);
        }
        return counts;
    }

    private static String gcClassHistogram() throws JMException {
        return (String) ManagementFactory.getPlatformMBeanServer()
                .invoke(
                        new ObjectName("com.sun.management:type=DiagnosticCommand"),
                        "gcClassHistogram",
                        new Object[] {new String[0]},
                        new String[] {String[].class.getName()});
    }

    /** Writes the histogram of the heap as it stands before {@code test} starts, when {@link #DIR_ENV} is set. */
    public static synchronized void record(String test) {
        String dir = System.getenv(DIR_ENV);
        if (dir == null || dir.isBlank()) {
            return;
        }
        try {
            String histogram = gcClassHistogram();
            List<String> lines = histogram.lines().toList();
            List<String> kept = new ArrayList<>();
            // A header line and a rule, then one row per class (its module in brackets after the name), then a
            // "Total" row.
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                if (index < TOP + 2
                        || line.startsWith("Total")
                        || KEPT_TYPES.matcher(line).find()) {
                    kept.add(line);
                }
            }
            Path file = Path.of(dir).resolve(String.format("%02d-%s.txt", count++, test));
            Files.createDirectories(file.getParent());
            Files.write(file, kept, StandardCharsets.UTF_8);
            // The live threads by name, digits folded: a thread that outlives its server holds the server, and the file
            // shows it by its count.
            Map<String, Integer> threads = new TreeMap<>();
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                threads.merge(
                        thread.getName().replaceAll("\\d+", "#") + " (" + thread.getState() + ")", 1, Integer::sum);
            }
            Files.write(
                    file.resolveSibling(file.getFileName().toString().replace(".txt", "-threads.txt")),
                    threads.entrySet().stream()
                            .map(entry -> entry.getValue() + " " + entry.getKey())
                            .toList(),
                    StandardCharsets.UTF_8);
            String total = lines.stream()
                    .filter(line -> line.startsWith("Total"))
                    .findFirst()
                    .orElse("no total");
            LOGGER.info("[heap-histogram] before {}: {}", test, total.strip());
        } catch (JMException | IOException failure) {
            LOGGER.warn("[heap-histogram] before {} failed: {}", test, failure.toString());
        }
    }
}
