package io.github.pkeppeler.deepcharter.hangar;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.SavedState;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * What the hangar remembers, saved with the world: the founding Mole (the derelict placed once when the colony was built),
 * the charter that repaired it, and the pods each charter got from the hangar.
 *
 * <p>The saved form has a {@link #VERSION}. Data of another version loads as unreadable and is written back unchanged, and
 * every use of it throws, as for {@code ColonySite}. Callbacks go through {@link #readable}, which never throws.
 */
public final class HangarData extends SavedData {
    public static final int VERSION = 2;
    private static final int FIRST_VERSION = 1;
    private static final Identifier ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "hangar");

    /** The pods of one charter that the hangar knows. */
    public record Held(CharterId charter, List<UUID> pods) {
        private static final Codec<Held> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        CharterId.CODEC.fieldOf("charter").forGetter(Held::charter),
                        UUIDUtil.CODEC.listOf().fieldOf("pods").forGetter(Held::pods))
                .apply(instance, Held::new));

        public Held {
            pods = List.copyOf(pods);
        }
    }

    /** The catalysts of the Company's advance that one charter has used. */
    public record Advanced(CharterId charter, int spent) {
        private static final Codec<Advanced> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        CharterId.CODEC.fieldOf("charter").forGetter(Advanced::charter),
                        Codec.INT.fieldOf("spent").forGetter(Advanced::spent))
                .apply(instance, Advanced::new));
    }

    /**
     * A filed write-off.
     *
     * @param due   the game time at which it takes effect
     * @param payer the charter that paid for it, to be refunded if the wreck is restored first
     * @param paid  what it cost, in dollars
     */
    public record WriteOff(long due, CharterId payer, long paid) {
        private static final Codec<WriteOff> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        Codec.LONG.fieldOf("due").forGetter(WriteOff::due),
                        CharterId.CODEC.fieldOf("payer").forGetter(WriteOff::payer),
                        Codec.LONG.fieldOf("paid").forGetter(WriteOff::paid))
                .apply(instance, WriteOff::new));
    }

    /**
     * One player's crafted Mole: the one place a charter member has (ADR 0117). It stays until the pod is scrapped or
     * written off, wrecked or working.
     *
     * @param player   the member who holds the place
     * @param pod      the Mole that holds it
     * @param wrecked  the Mole is a wreck now, recorded from the wreck events, so a write-off can be checked without
     * loading the pod
     * @param writeOff the filed write-off, if there is one
     */
    public record Slot(UUID player, UUID pod, boolean wrecked, Optional<WriteOff> writeOff) {
        private static final Codec<Slot> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        UUIDUtil.CODEC.fieldOf("player").forGetter(Slot::player),
                        UUIDUtil.CODEC.fieldOf("pod").forGetter(Slot::pod),
                        Codec.BOOL.fieldOf("wrecked").forGetter(Slot::wrecked),
                        WriteOff.CODEC.optionalFieldOf("write_off").forGetter(Slot::writeOff))
                .apply(instance, Slot::new));

        /** The game time at which the filed write-off takes effect, if one is filed. */
        public Optional<Long> writeOffDue() {
            return writeOff.map(WriteOff::due);
        }

        Slot withWrecked(boolean now) {
            return new Slot(player, pod, now, writeOff);
        }

        Slot withWriteOff(Optional<WriteOff> now) {
            return new Slot(player, pod, wrecked, now);
        }
    }

    /**
     * The hangar's saved state.
     *
     * @param derelict the founding Mole's pod, once placed: the hangar places it once, so a world that has one never gets a second
     * @param founder  the charter whose last part repaired the founding Mole
     * @param founded  the founding Mole belongs to {@code founder}: it is registered and restored
     * @param held     every pod registered to each charter, recorded when it is registered ({@code PodEvents.REGISTERED}) or, for a pod registered earlier, when it first loads
     * @param advanced the catalysts of the Company's advance that each charter has used. Absent from data saved before the advance
     *                 (it reads as nothing used), so the version stays 1.
     * @param moles    the crafted Mole of each player who has one. Absent from data saved before the one-Mole rule (it
     * reads as
     *                 none): a Mole of an older world holds no place, except the founding Mole ({@code
     * legacyFoundingPlace}).
     * @param legacyFoundingPlace true for data saved before version 2, whose founding Mole has no place on record yet:
     * it is given to
     *                 the founding charter's Director when it loads, once (see {@code Hangar})
     * @param condemned the pods of written-off wrecks that were not loaded when the write-off took effect: each is
     * destroyed when it
     *                 loads. Absent from older data (none).
     */
    public record State(
            Optional<UUID> derelict,
            Optional<CharterId> founder,
            boolean founded,
            List<Held> held,
            List<Advanced> advanced,
            List<Slot> moles,
            List<UUID> condemned,
            boolean legacyFoundingPlace) {
        private static final MapCodec<State> BODY = RecordCodecBuilder.mapCodec(instance -> instance.group(
                        UUIDUtil.CODEC.optionalFieldOf("derelict").forGetter(State::derelict),
                        CharterId.CODEC.optionalFieldOf("founder").forGetter(State::founder),
                        Codec.BOOL.fieldOf("founded").forGetter(State::founded),
                        Held.CODEC.listOf().fieldOf("held").forGetter(State::held),
                        Advanced.CODEC
                                .listOf()
                                .optionalFieldOf("advanced", List.of())
                                .forGetter(State::advanced),
                        Slot.CODEC.listOf().optionalFieldOf("moles", List.of()).forGetter(State::moles),
                        UUIDUtil.CODEC
                                .listOf()
                                .optionalFieldOf("condemned", List.of())
                                .forGetter(State::condemned),
                        // Absent in version 1, which gave the founding Mole no place: true there.
                        Codec.BOOL
                                .optionalFieldOf("legacy_founding_place", true)
                                .forGetter(State::legacyFoundingPlace))
                .apply(instance, State::new));
        static final State EMPTY =
                new State(Optional.empty(), Optional.empty(), false, List.of(), List.of(), List.of(), List.of(), false);

        public State {
            held = List.copyOf(held);
            advanced = List.copyOf(advanced);
            moles = List.copyOf(moles);
            condemned = List.copyOf(condemned);
        }

        private State withDerelict(UUID pod) {
            return new State(Optional.of(pod), founder, founded, held, advanced, moles, condemned, legacyFoundingPlace);
        }

        private State withFounder(CharterId charter) {
            return new State(
                    derelict, Optional.of(charter), founded, held, advanced, moles, condemned, legacyFoundingPlace);
        }

        private State withFounded() {
            return new State(derelict, founder, true, held, advanced, moles, condemned, legacyFoundingPlace);
        }

        private State withHeld(List<Held> now) {
            return new State(derelict, founder, founded, now, advanced, moles, condemned, legacyFoundingPlace);
        }

        private State withAdvanced(List<Advanced> now) {
            return new State(derelict, founder, founded, held, now, moles, condemned, legacyFoundingPlace);
        }

        private State withMoles(List<Slot> now) {
            return new State(derelict, founder, founded, held, advanced, now, condemned, legacyFoundingPlace);
        }

        private State withFoundingPlaceSettled() {
            return new State(derelict, founder, founded, held, advanced, moles, condemned, false);
        }

        private State withCondemned(List<UUID> now) {
            return new State(derelict, founder, founded, held, advanced, moles, now, legacyFoundingPlace);
        }
    }

    private static final Codec<Versioned<State>> VERSIONED_CODEC =
            Versioned.codec(VERSION, State.BODY, Map.of(FIRST_VERSION, State.BODY));
    /** The codec of the saved data, which never fails to decode. */
    public static final Codec<HangarData> CODEC = VERSIONED_CODEC.xmap(HangarData::new, HangarData::versioned);
    // Datafixer type: as for CharterData (ADR 0007), vanilla's fixers find nothing of theirs in a file that carries our
    // own version.
    public static final SavedDataType<HangarData> TYPE =
            new SavedDataType<>(ID, HangarData::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final SavedState<State> saved;

    public HangarData() {
        this.saved = SavedState.fresh(ID, VERSION, State.EMPTY);
    }

    private HangarData(Versioned<State> loaded) {
        this.saved = SavedState.load(ID, VERSION, loaded, state -> state);
    }

    /** The world's hangar data. Call on the server thread. */
    public static HangarData get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    /** The world's hangar data if it is readable; otherwise empty, after logging that once per data. Never throws. */
    public static Optional<HangarData> readable(MinecraftServer server) {
        return Optional.of(get(server)).filter(HangarData::isReadable);
    }

    private Versioned<State> versioned() {
        return saved.versioned(state -> state);
    }

    /** False (logged once) when the saved data is of a version this build cannot read: every other method then throws. */
    public boolean isReadable() {
        return saved.isReadable();
    }

    public State state() {
        return saved.orThrow();
    }

    private void change(UnaryOperator<State> change) {
        saved.set(change.apply(state()));
        setDirty();
    }

    /** Records the founding Mole. It is placed once: a second call throws. */
    void placeDerelict(UUID pod) {
        if (state().derelict().isPresent()) {
            throw new IllegalStateException("the founding Mole is placed already");
        }
        change(old -> old.withDerelict(pod));
    }

    /** Records the charter that repaired the founding Mole. It is the first one: a second call throws. */
    void setFounder(CharterId charter) {
        if (state().founder().isPresent()) {
            throw new IllegalStateException("the founding Mole has a founder already");
        }
        change(old -> old.withFounder(charter));
    }

    /** Records that the founding Mole is registered to its founder and works. */
    void markFounded() {
        change(old -> old.withFounded());
    }

    /** Records a pod as the charter's. Recording it twice changes nothing. */
    void hold(CharterId charter, UUID pod) {
        if (held(charter).contains(pod)) {
            return;
        }
        change(old -> {
            List<Held> held = new ArrayList<>(old.held());
            Set<UUID> pods = new LinkedHashSet<>(held(old, charter));
            pods.add(pod);
            held.removeIf(entry -> entry.charter().equals(charter));
            held.add(new Held(charter, List.copyOf(pods)));
            return old.withHeld(held);
        });
    }

    /** The catalysts of the Company's advance that the charter has used so far. */
    public int advanceSpent(CharterId charter) {
        return state().advanced().stream()
                .filter(entry -> entry.charter().equals(charter))
                .mapToInt(Advanced::spent)
                .sum();
    }

    /** The catalysts of an advance of {@code advance} that the charter has not used: never below 0. */
    public int advanceLeft(CharterId charter, int advance) {
        return Math.max(0, advance - advanceSpent(charter));
    }

    /** Records that the charter used {@code catalysts} more of the Company's advance. */
    public void spendAdvance(CharterId charter, int catalysts) {
        if (catalysts < 0) {
            throw new IllegalArgumentException("cannot spend " + catalysts + " catalysts of an advance");
        }
        change(old -> {
            List<Advanced> advanced = new ArrayList<>(old.advanced());
            int spent = advanceSpent(charter) + catalysts;
            advanced.removeIf(entry -> entry.charter().equals(charter));
            advanced.add(new Advanced(charter, spent));
            return old.withAdvanced(advanced);
        });
    }

    /** The crafted Mole of {@code player}, if they have one. */
    public Optional<Slot> slotOf(UUID player) {
        return state().moles().stream()
                .filter(slot -> slot.player().equals(player))
                .findFirst();
    }

    /** The place that {@code pod} holds, if it holds one. */
    public Optional<Slot> slotHolding(UUID pod) {
        return state().moles().stream().filter(slot -> slot.pod().equals(pod)).findFirst();
    }

    /** Every place with a filed write-off, so the tick can settle the ones that are due. */
    List<Slot> writeOffs() {
        return state().moles().stream()
                .filter(slot -> slot.writeOff().isPresent())
                .toList();
    }

    /**
     * Gives {@code player} their one place, held by {@code pod}. A player with a place already, or a pod that holds
     * one, throws.
     */
    void claim(UUID player, UUID pod, boolean wrecked) {
        if (slotOf(player).isPresent() || slotHolding(pod).isPresent()) {
            throw new IllegalStateException("player " + player + " or pod " + pod + " holds a Mole place already");
        }
        change(old -> {
            List<Slot> moles = new ArrayList<>(old.moles());
            moles.add(new Slot(player, pod, wrecked, Optional.empty()));
            return old.withMoles(moles);
        });
    }

    /**
     * Records that the pod holding a place is a wreck, or is not any more. A pod that holds no place changes nothing.
     */
    void markWrecked(UUID pod, boolean wrecked) {
        changeSlot(pod, slot -> slot.withWrecked(wrecked));
    }

    /** Files a write-off. A pod that holds no place throws. */
    void fileWriteOff(UUID pod, WriteOff writeOff) {
        if (slotHolding(pod).isEmpty()) {
            throw new IllegalStateException("pod " + pod + " holds no Mole place to write off");
        }
        changeSlot(pod, slot -> slot.withWriteOff(Optional.of(writeOff)));
    }

    /** Takes back a filed write-off: it never takes effect. A pod with none changes nothing. */
    void cancelWriteOff(UUID pod) {
        changeSlot(pod, slot -> slot.withWriteOff(Optional.empty()));
    }

    /** Records that the founding Mole's place is settled, so the migration of an older world runs once. */
    void settleFoundingPlace() {
        if (state().legacyFoundingPlace()) {
            change(State::withFoundingPlaceSettled);
        }
    }

    /** Frees the place that {@code pod} holds. A pod that holds none changes nothing. */
    void release(UUID pod) {
        if (slotHolding(pod).isEmpty()) {
            return;
        }
        change(old -> old.withMoles(
                old.moles().stream().filter(slot -> !slot.pod().equals(pod)).toList()));
    }

    private void changeSlot(UUID pod, UnaryOperator<Slot> change) {
        if (slotHolding(pod).isEmpty()) {
            return;
        }
        change(old -> old.withMoles(old.moles().stream()
                .map(slot -> slot.pod().equals(pod) ? change.apply(slot) : slot)
                .toList()));
    }

    /** Marks a written-off pod that was not loaded: it is destroyed when it loads. */
    void condemn(UUID pod) {
        if (isCondemned(pod)) {
            return;
        }
        change(old -> {
            List<UUID> condemned = new ArrayList<>(old.condemned());
            condemned.add(pod);
            return old.withCondemned(condemned);
        });
    }

    boolean isCondemned(UUID pod) {
        return state().condemned().contains(pod);
    }

    /** Forgets a condemned pod once it is destroyed. */
    void forgetCondemned(UUID pod) {
        if (isCondemned(pod)) {
            change(old -> old.withCondemned(
                    old.condemned().stream().filter(id -> !id.equals(pod)).toList()));
        }
    }

    /** The pods recorded as the charter's. */
    public Set<UUID> held(CharterId charter) {
        return held(state(), charter);
    }

    private static Set<UUID> held(State from, CharterId charter) {
        return from.held().stream()
                .filter(entry -> entry.charter().equals(charter))
                .flatMap(entry -> entry.pods().stream())
                .collect(Collectors.toUnmodifiableSet());
    }
}
