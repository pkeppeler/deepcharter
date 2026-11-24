package io.github.pkeppeler.deepcharter.outpost;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.SavedState;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * The visit ledger of every outpost (ADR 0079): how far its visits have been rolled, what the last look at it found, which visits wait
 * for its zone to load, and what the charter has not read yet. It is saved apart from {@link OutpostData}, so a ledger this build cannot
 * read never stops an outpost's terminals.
 *
 * <p>The saved form has a {@link #VERSION}. Data of another version loads as unreadable, is written back unchanged, and every use of it
 * throws: the sweep checks {@link #isReadable()} first.
 */
public final class OutpostVisits extends SavedData {
    public static final int VERSION = 1;
    /** The saved id of the data: never renamed, or every outpost starts a new ledger. */
    public static final Identifier ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "outpost_visits");

    /**
     * What the last look at a loaded outpost found, which is all the draws its visits are rolled from while it is not loaded.
     *
     * @param dark      some sampled spot of the hall had less block light than a figure needs to keep away
     * @param noise     how loud the outpost is: the terminals in its zone, until #1005 says more
     * @param fuelItems pod fuel items in the zone's storage
     * @param oreItems  ore items in the zone's storage
     * @param breakable blocks in the zone that a Sleeper's visit may break
     */
    public record Survey(boolean dark, int noise, int fuelItems, int oreItems, int breakable) {
        private static final Codec<Survey> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        Codec.BOOL.fieldOf("dark").forGetter(Survey::dark),
                        Codec.INT.fieldOf("noise").forGetter(Survey::noise),
                        Codec.INT.fieldOf("fuel_items").forGetter(Survey::fuelItems),
                        Codec.INT.fieldOf("ore_items").forGetter(Survey::oreItems),
                        Codec.INT.fieldOf("breakable").forGetter(Survey::breakable))
                .apply(instance, Survey::new));
    }

    /**
     * Visits rolled and not yet met in the world, as counts: what a visit takes is chosen when it is applied.
     *
     * @param retained the Retained's visits
     * @param sleepers the Sleeper's visits
     * @param recovery the Recovery's visits
     */
    public record Pending(int retained, int sleepers, int recovery) {
        public static final Pending NONE = new Pending(0, 0, 0);
        private static final Codec<Pending> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        Codec.INT.fieldOf("retained").forGetter(Pending::retained),
                        Codec.INT.fieldOf("sleepers").forGetter(Pending::sleepers),
                        Codec.INT.fieldOf("recovery").forGetter(Pending::recovery))
                .apply(instance, Pending::new));

        /** {@code more} added, each count held at {@code cap}. */
        public Pending plus(Pending more, int cap) {
            return new Pending(
                    (int) Math.min(cap, (long) retained + more.retained),
                    (int) Math.min(cap, (long) sleepers + more.sleepers),
                    (int) Math.min(cap, (long) recovery + more.recovery));
        }

        public boolean isEmpty() {
            return retained == 0 && sleepers == 0 && recovery == 0;
        }

        public int total() {
            return retained + sleepers + recovery;
        }
    }

    /**
     * What visits did that the charter has not read. The incident report shows it and clears it.
     *
     * @param visits visits met in the world
     * @param taken  items taken from storage, by item (fuel and ore)
     * @param broken items lost with a broken block, by item
     * @param heaps  salvage heaps a broken storage block left
     * @param causes why visits came, for the causes the report has a line for
     */
    public record Report(
            int visits,
            Map<Identifier, Integer> taken,
            Map<Identifier, Integer> broken,
            int heaps,
            Set<VisitCause> causes) {
        public static final Report NONE = new Report(0, Map.of(), Map.of(), 0, Set.of());
        private static final Codec<Map<Identifier, Integer>> COUNTS = Codec.unboundedMap(Identifier.CODEC, Codec.INT);
        private static final Codec<Report> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        Codec.INT.fieldOf("visits").forGetter(Report::visits),
                        COUNTS.fieldOf("taken").forGetter(Report::taken),
                        COUNTS.fieldOf("broken").forGetter(Report::broken),
                        Codec.INT.fieldOf("heaps").forGetter(Report::heaps),
                        VisitCause.CODEC
                                .listOf()
                                .xmap(Set::copyOf, causes -> List.copyOf(sorted(causes)))
                                .optionalFieldOf("causes", Set.of())
                                .forGetter(Report::causes))
                .apply(instance, Report::new));

        public Report {
            taken = Map.copyOf(taken);
            broken = Map.copyOf(broken);
            causes = Set.copyOf(causes);
        }

        /** The causes in the order of the enum, so a report reads and saves the same every time. */
        public List<VisitCause> sortedCauses() {
            return sorted(causes);
        }

        private static List<VisitCause> sorted(Set<VisitCause> causes) {
            return causes.stream().sorted().toList();
        }

        /** True when nothing happened that a report could say. */
        public boolean isEmpty() {
            return visits == 0 && taken.isEmpty() && broken.isEmpty() && heaps == 0 && causes.isEmpty();
        }

        /** This report and {@code more} as one, the counts of each item added (saturating). */
        public Report plus(Report more) {
            return new Report(
                    (int) Math.min(Integer.MAX_VALUE, (long) visits + more.visits),
                    merged(taken, more.taken),
                    merged(broken, more.broken),
                    (int) Math.min(Integer.MAX_VALUE, (long) heaps + more.heaps),
                    Stream.concat(causes.stream(), more.causes.stream()).collect(Collectors.toSet()));
        }

        private static Map<Identifier, Integer> merged(Map<Identifier, Integer> a, Map<Identifier, Integer> b) {
            Map<Identifier, Integer> sum = new LinkedHashMap<>(a);
            b.forEach((item, count) ->
                    sum.merge(item, count, (x, y) -> (int) Math.min(Integer.MAX_VALUE, (long) x + y)));
            return sum;
        }
    }

    /**
     * One outpost's ledger.
     *
     * @param charter   the charter that owns the outpost
     * @param layer     the outpost's layer
     * @param settledTo the window (game time over the window length) its visits are rolled up to, not counting that window
     * @param survey    what the last look at it found: empty until it has been loaded once since the ledger began
     * @param pending   visits rolled and not yet met in the world
     * @param unread    what visits did that the charter has not read
     */
    public record Ledger(
            CharterId charter, int layer, long settledTo, Optional<Survey> survey, Pending pending, Report unread) {
        private static final Codec<Ledger> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        CharterId.CODEC.fieldOf("charter").forGetter(Ledger::charter),
                        Codec.INT.fieldOf("layer").forGetter(Ledger::layer),
                        Codec.LONG.fieldOf("settled_to").forGetter(Ledger::settledTo),
                        Survey.CODEC.optionalFieldOf("survey").forGetter(Ledger::survey),
                        Pending.CODEC.fieldOf("pending").forGetter(Ledger::pending),
                        Report.CODEC.fieldOf("unread").forGetter(Ledger::unread))
                .apply(instance, Ledger::new));

        public Ledger withSettledTo(long window) {
            return new Ledger(charter, layer, window, survey, pending, unread);
        }

        public Ledger withSurvey(Survey found) {
            return new Ledger(charter, layer, settledTo, Optional.of(found), pending, unread);
        }

        public Ledger withPending(Pending waiting) {
            return new Ledger(charter, layer, settledTo, survey, waiting, unread);
        }

        public Ledger withUnread(Report report) {
            return new Ledger(charter, layer, settledTo, survey, pending, report);
        }
    }

    private record Key(CharterId charter, int layer) {}

    private static final Codec<Versioned<List<Ledger>>> VERSIONED_CODEC =
            Versioned.codec(VERSION, Ledger.CODEC.listOf().fieldOf("ledgers"));

    /** The codec of the saved data, which never fails to decode. */
    public static final Codec<OutpostVisits> CODEC = VERSIONED_CODEC.xmap(OutpostVisits::new, OutpostVisits::versioned);
    // Datafixer type: as for OutpostData.
    public static final SavedDataType<OutpostVisits> TYPE =
            new SavedDataType<>(ID, OutpostVisits::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final SavedState<Map<Key, Ledger>> state;

    public OutpostVisits() {
        this.state = SavedState.fresh(ID, VERSION, new LinkedHashMap<>());
    }

    private OutpostVisits(Versioned<List<Ledger>> loaded) {
        this.state = SavedState.load(ID, VERSION, loaded, ledgers -> {
            Map<Key, Ledger> byOutpost = new LinkedHashMap<>();
            ledgers.forEach(ledger -> byOutpost.put(new Key(ledger.charter(), ledger.layer()), ledger));
            return byOutpost;
        });
    }

    /** The world's ledgers. Call on the server thread. */
    public static OutpostVisits get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    private Versioned<List<Ledger>> versioned() {
        return state.versioned(ledgers -> new ArrayList<>(ledgers.values()));
    }

    /** False (logged once) when the saved data is of a version this build cannot read: every other method then throws. */
    public boolean isReadable() {
        return state.isReadable();
    }

    /** The outpost's ledger, if it has one. */
    public Optional<Ledger> find(CharterId charter, int layer) {
        return Optional.ofNullable(state.orThrow().get(new Key(charter, layer)));
    }

    /**
     * The outpost's ledger, begun at window {@code now} when it has none. A new ledger has no survey and has rolled nothing, so a visit
     * is never applied for the time before the ledger began.
     */
    public Ledger ledgerOf(CharterId charter, int layer, long now) {
        Map<Key, Ledger> ledgers = state.orThrow();
        Key key = new Key(charter, layer);
        Ledger found = ledgers.get(key);
        if (found != null) {
            return found;
        }
        Ledger begun = new Ledger(charter, layer, now, Optional.empty(), Pending.NONE, Report.NONE);
        ledgers.put(key, begun);
        setDirty();
        return begun;
    }

    /** Keeps {@code ledger} in place of the outpost's. */
    public void put(Ledger ledger) {
        Map<Key, Ledger> ledgers = state.orThrow();
        Key key = new Key(ledger.charter(), ledger.layer());
        if (!ledger.equals(ledgers.put(key, ledger))) {
            setDirty();
        }
    }
}
