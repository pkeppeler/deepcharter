package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.test.support.ClientLight.Box;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.level.ChunkPos;

/**
 * A timeline of what the server and the client did with the light of a chunk: each hold {@code LightSync} was asked for, each chunk and
 * light packet the server sent, each chunk or light packet the client handled. The mixins of this test mod feed it
 * ({@code LightTraceMixins}); {@link LightMismatchEvidence} prints the part about the box when a client reads stale light. A test
 * {@link #clear}s it where the case it wants to explain begins, so it holds one case, and it holds at most {@value #CAP} events.
 */
public final class LightTrace {
    private static final int CAP = 20_000;
    private static final int PRINTED = 150;
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final ConcurrentLinkedDeque<Event> EVENTS = new ConcurrentLinkedDeque<>();
    private static final long START = System.nanoTime();

    private LightTrace() {}

    private record Event(long sequence, long millis, String side, String kind, int x, int z, String detail) {
        @Override
        public String toString() {
            return "#" + sequence + " +" + millis + "ms " + side + " " + kind + " [" + x + ", " + z + "]"
                    + (detail.isEmpty() ? "" : " " + detail);
        }
    }

    /** Notes one event. {@code side} is {@code SRV} or {@code CLI}. */
    public static void record(String side, String kind, int x, int z, String detail) {
        EVENTS.addLast(new Event(
                SEQUENCE.incrementAndGet(), (System.nanoTime() - START) / 1_000_000L, side, kind, x, z, detail));
        if (EVENTS.size() > CAP) {
            EVENTS.pollFirst();
        }
    }

    /** Forgets every event, at the start of the case a failure will be explained from. */
    public static void clear() {
        EVENTS.clear();
    }

    /** The events about the chunks of the box and the ring round it, oldest first, a repeat of one line counted once. */
    static String around(Box box) {
        ChunkPos low = ChunkPos.containing(box.min());
        ChunkPos high = ChunkPos.containing(box.max());
        List<String> lines = new ArrayList<>();
        String last = "";
        int repeats = 0;
        for (Event event : EVENTS) {
            if (event.x() < low.x() - 1
                    || event.x() > high.x() + 1
                    || event.z() < low.z() - 1
                    || event.z() > high.z() + 1) {
                continue;
            }
            String line =
                    event.side() + " " + event.kind() + " [" + event.x() + ", " + event.z() + "] " + event.detail();
            if (line.equals(last)) {
                repeats++;
                lines.set(lines.size() - 1, event + " (x" + (repeats + 1) + ")");
            } else {
                repeats = 0;
                last = line;
                lines.add(event.toString());
            }
        }
        String shown = String.join("\n      ", lines.subList(Math.max(0, lines.size() - PRINTED), lines.size()));
        return "\n    timeline of the box's chunks and ring (" + lines.size() + " lines, last " + PRINTED + "):\n      "
                + shown;
    }
}
