package io.github.pkeppeler.deepcharter.continuity;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * A person's Continuity Events (#1337), the player attachment {@link ContinuityRegistry#RECORD}: how many times the Continuity Office has
 * rebuilt them. Personal, saved with the player and kept across every death.
 *
 * @param events the deaths so far; the next one is number {@code events + 1}
 */
public record ContinuityRecord(int events) {
    public static final int VERSION = 1;
    /** A person the Office has never rebuilt. */
    public static final ContinuityRecord NONE = new ContinuityRecord(0);

    public ContinuityRecord {
        if (events < 0) {
            throw new IllegalArgumentException("a count of Continuity Events is never negative: " + events);
        }
    }

    /** The saved body. A negative count reads 0 (and is written back 0), and a missing one reads 0. */
    public static final MapCodec<ContinuityRecord> BODY = RecordCodecBuilder.mapCodec(instance -> instance.group(
                    Codec.INT.optionalFieldOf("events", 0).forGetter(ContinuityRecord::events))
            .apply(instance, events -> new ContinuityRecord(Math.max(0, events))));

    /** The record after one more Continuity Event. */
    public ContinuityRecord next() {
        return new ContinuityRecord(events + 1);
    }
}
