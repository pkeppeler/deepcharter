package io.github.pkeppeler.deepcharter.fuel;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.handbook.ReadMarks;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.pod.PodTuning;
import io.netty.buffer.ByteBuf;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * Soot in a pod's engine (#1280). Burning solid fuel leaves soot, and the engine pays for it until the repair station
 * services it. The state is a versioned attachment ({@link #STATE}, ADR 0097).
 *
 * <p>The engine in four steps, from {@link FuelTuning.Soot}: clean up to the grace; then it loses top speed and climb
 * speed and burns more fuel, in a straight line to the limp limit; at the limit it is in <b>limp mode</b>, with little
 * output and a high burn, which never stops it, so a pod deep down can always climb home. The colony is where limp
 * ends: a pod in limp mode cannot launch (drill) from the surface until it is serviced ({@link #launchRefused}).
 *
 * <p>Refuelling with a solid fuel puts its litres, and the soot those litres will leave, in the tank's account; the
 * burn takes litres off that account in proportion. The account is clamped to the litres really in the tank when it is
 * read, so fuel that came from elsewhere (a pump, a reserve tank, a recovery) is never counted as solid. The burn is
 * taken off the solid fuel first, which counts a mixed tank pessimistically.
 *
 * <p>The tick path writes nothing until {@link #QUANTUM} litres have burned: the burn is held in a transient map (lost
 * when the pod unloads, at most a quantum of soot), so the attachment and its sync change a few times a minute and not
 * on every tick. It reads the state with {@link Versioned#readable}: unreadable soot is no soot, and nothing writes
 * over it. {@link #service} is an explicit change and throws on an unreadable state; its caller asks {@link
 * #isReadable} first.
 */
public final class EngineSoot {
    public static final int VERSION = 1;

    /** Litres of fuel burned on solid fuel before the attachment is written and synced. */
    public static final float QUANTUM = 0.1f;

    /**
     * The read mark that records the one-time line the first time soot bites a pilot (the player's {@code ReadMarks}).
     */
    public static final Identifier FIRST_SOOT_MARK =
            Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "hint/first_soot");

    /**
     * Litres of soot in the engine, the litres of solid fuel in the tank that have not burned, and the litres of soot
     * those will leave when they do.
     */
    public record State(float soot, float tankLitres, float tankSoot) {
        public static final State CLEAN = new State(0f, 0f, 0f);
        public static final MapCodec<State> BODY = RecordCodecBuilder.mapCodec(instance -> instance.group(
                        Codec.FLOAT.fieldOf("soot").forGetter(State::soot),
                        Codec.FLOAT.fieldOf("tank_litres").forGetter(State::tankLitres),
                        Codec.FLOAT.fieldOf("tank_soot").forGetter(State::tankSoot))
                .apply(instance, State::new));
        public static final StreamCodec<ByteBuf, State> STREAM = StreamCodec.composite(
                ByteBufCodecs.FLOAT,
                State::soot,
                ByteBufCodecs.FLOAT,
                State::tankLitres,
                ByteBufCodecs.FLOAT,
                State::tankSoot,
                State::new);

        public State {
            // A bad saved number must not become a negative burn, a NaN fouling or a free service.
            soot = clean(soot);
            tankLitres = clean(tankLitres);
            tankSoot = clean(tankSoot);
        }

        private static float clean(float litres) {
            return litres >= 0f && Float.isFinite(litres) ? litres : 0f;
        }
    }

    public static final AttachmentType<Versioned<State>> STATE = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "engine_soot"),
            builder -> builder.persistent(Versioned.codec(VERSION, State.BODY))
                    .initializer(() -> Versioned.of(State.CLEAN))
                    .syncWith(Versioned.streamCodec(VERSION, State.STREAM), AttachmentSyncPredicate.all()));

    /** Litres burned on solid fuel and not yet written, for each pod. Server thread only. */
    private static final Map<PodEntity, Float> UNWRITTEN = new WeakHashMap<>();

    private EngineSoot() {}

    static void init() {
        // Output is a stat, so the speed, the climb and every reader of PodStats (the client's smoothing included)
        // see it.
        PodStats.MODIFY.register((pod, stats) -> {
            float output = output(soot(pod));
            return output >= 1f
                    ? stats
                    : stats.withHorizontalSpeed(stats.horizontalSpeed() * output)
                            .withMaxClimbSpeed(stats.maxClimbSpeed() * output);
        });
    }

    private static State state(PodEntity pod) {
        return Versioned.readable(pod, STATE).orElse(State.CLEAN);
    }

    /**
     * Litres of soot in the engine; 0 when the state cannot be read. Either side: the client reads the synced value.
     */
    public static float soot(PodEntity pod) {
        return state(pod).soot();
    }

    private static float wear(float soot) {
        FuelTuning.Soot tuning = FuelTuning.DEFAULT.soot();
        return Math.min(1f, Math.max(0f, (soot - tuning.graceLitres()) / (tuning.limpLitres() - tuning.graceLitres())));
    }

    /** Whether {@code soot} litres put the engine in limp mode. */
    public static boolean isLimp(float soot) {
        return soot >= FuelTuning.DEFAULT.soot().limpLitres();
    }

    /** The share of top speed and climb speed the engine has left at {@code soot} litres: 1 up to the grace. */
    public static float output(float soot) {
        FuelTuning.Soot tuning = FuelTuning.DEFAULT.soot();
        return isLimp(soot) ? tuning.limpOutput() : 1f - tuning.lossAtLimit() * wear(soot);
    }

    /** How many times the clean burn the engine burns at {@code soot} litres: 1 up to the grace. */
    public static float burn(float soot) {
        FuelTuning.Soot tuning = FuelTuning.DEFAULT.soot();
        return isLimp(soot) ? tuning.limpBurn() : 1f + tuning.extraBurnAtLimit() * wear(soot);
    }

    /** What this pod's engine burns now, as a multiple of what a clean engine burns. */
    public static float burnMultiplier(PodEntity pod) {
        return burn(soot(pod));
    }

    /** Whether the engine is in limp mode. */
    public static boolean isLimp(PodEntity pod) {
        return isLimp(soot(pod));
    }

    /**
     * Whether the pod may not launch: it is in limp mode and at the colony (the surface band), where the drill stops
     * with a line until the engine is serviced. Below the surface a limp pod drills, drives and climbs as it can.
     */
    public static boolean launchRefused(PodEntity pod) {
        return isLimp(pod)
                && LayerChain.bandAt(pod.level(), pod.getBlockY())
                        .map(band -> band.layer() == LayerChain.SURFACE)
                        .orElse(false);
    }

    /**
     * Server only: {@code litres} of a fuel that leaves {@code sootPerLitre} of soot a litre went into the tank. A
     * clean fuel (0) changes nothing, and so does an unreadable state, which is kept as it is: the refuel is a callback
     * path.
     */
    public static void refuelled(PodEntity pod, float litres, float sootPerLitre) {
        Optional<State> read = Versioned.readable(pod, STATE);
        if (read.isEmpty() || !(litres > 0f) || !(sootPerLitre > 0f)) {
            return;
        }
        State state = read.get();
        pod.setAttached(
                STATE,
                Versioned.of(new State(
                        state.soot(), state.tankLitres() + litres, state.tankSoot() + litres * sootPerLitre)));
    }

    /** Whether the engine's soot can be read and changed: false for saved data of another version. */
    public static boolean isReadable(PodEntity pod) {
        return Versioned.readable(pod, STATE).isPresent();
    }

    /**
     * Server only, from the pod's tick, after the fuel is taken off the tank: {@code litres} burned. They are held
     * until {@link #QUANTUM} have burned, then written. Does nothing, and never throws, when nothing solid is in the
     * tank or the state is unreadable.
     */
    public static void burned(PodEntity pod, float litres) {
        Optional<State> read = Versioned.readable(pod, STATE);
        if (read.isEmpty() || read.get().tankLitres() <= 0f || !(litres > 0f)) {
            return;
        }
        float held = UNWRITTEN.merge(pod, litres, Float::sum);
        if (held >= QUANTUM) {
            UNWRITTEN.remove(pod);
            write(pod, read.get(), held);
        }
    }

    /**
     * Server only: writes what the pod has burned and not written. A quantum is the most that a pod saved without it
     * loses.
     */
    public static void flush(PodEntity pod) {
        Float held = UNWRITTEN.remove(pod);
        Optional<State> read = Versioned.readable(pod, STATE);
        if (held != null && read.isPresent()) {
            write(pod, read.get(), held);
        }
    }

    private static void write(PodEntity pod, State state, float litres) {
        if (state.tankLitres() <= 0f) {
            return;
        }
        // The litres in the tank before this burn: those left, and those burned since the last write. The account of
        // solid fuel can be no more, whatever a pump, a reserve tank or a recovery did to the tank meanwhile.
        float inTank = pod.fuel()
                        / PodTuning.DEFAULT.shell().fullFuel()
                        * PodStats.of(pod).tankLitres()
                + litres;
        float account = Math.min(state.tankLitres(), inTank);
        float accountSoot = state.tankSoot() * account / state.tankLitres();
        float share = Math.min(1f, litres / account);
        float soot = state.soot() + accountSoot * share;
        float grace = FuelTuning.DEFAULT.soot().graceLitres();
        pod.setAttached(STATE, Versioned.of(new State(soot, account * (1f - share), accountSoot * (1f - share))));
        if (state.soot() <= grace && soot > grace) {
            explainFirstSoot(pod);
        }
    }

    /** Dollars to service the engine now: the soot's litres at the tuned price, rounded up. */
    public static long serviceCost(PodEntity pod) {
        return (long) Math.ceil(soot(pod) * FuelTuning.DEFAULT.soot().servicePerLitre());
    }

    /**
     * What a service paid from {@code account} dollars does: all the soot for {@link #serviceCost} when the account
     * pays for that ({@code all}), or else as many whole litres as it pays for at the tuned price.
     */
    public record ServiceQuote(int litres, long cost, boolean all) {}

    /**
     * The service {@code account} dollars buy now: empty for a clean engine, or when the account cannot pay for a
     * litre. Either side: the repair screen shows it from the synced soot and the charter's balance.
     */
    public static Optional<ServiceQuote> quote(PodEntity pod, long account) {
        float soot = soot(pod);
        if (!(soot > 0f)) {
            return Optional.empty();
        }
        long full = serviceCost(pod);
        if (account >= full) {
            return Optional.of(new ServiceQuote(Math.round(soot), full, true));
        }
        long price = FuelTuning.DEFAULT.soot().servicePerLitre();
        int litres = (int) Math.min((long) soot, Math.max(0L, account) / price);
        return litres < 1 ? Optional.empty() : Optional.of(new ServiceQuote(litres, litres * price, false));
    }

    /**
     * Server only: clears the engine's soot. What the tank's fuel will still leave is kept. Throws on an unreadable
     * state.
     */
    public static void service(PodEntity pod) {
        flush(pod);
        Versioned.modifyOrThrow(pod, STATE, state -> new State(0f, state.tankLitres(), state.tankSoot()));
    }

    /** Server only: clears {@code litres} of the engine's soot, a part service. Throws on an unreadable state. */
    public static void service(PodEntity pod, int litres) {
        flush(pod);
        Versioned.modifyOrThrow(
                pod,
                STATE,
                state -> new State(Math.max(0f, state.soot() - litres), state.tankLitres(), state.tankSoot()));
    }

    /**
     * Server only, when a flue scrape at the colony ends: brings the soot down to {@link
     * FuelTuning.Soot#scrapeLeavesLitres}, never up. A tick path, so an unreadable state is left as it is and the
     * answer is false.
     */
    public static boolean scrape(PodEntity pod) {
        flush(pod);
        Optional<State> read = Versioned.readable(pod, STATE);
        if (read.isEmpty()) {
            return false;
        }
        State state = read.get();
        float left = Math.min(state.soot(), FuelTuning.DEFAULT.soot().scrapeLeavesLitres());
        pod.setAttached(STATE, Versioned.of(new State(left, state.tankLitres(), state.tankSoot())));
        return true;
    }

    /**
     * Says once per player, kept as a read mark, why the engine now loses output. Says nothing when the marks cannot be
     * read.
     */
    private static void explainFirstSoot(PodEntity pod) {
        if (pod.getControllingPassenger() instanceof ServerPlayer pilot
                && ReadMarks.isReadable(pilot)
                && !ReadMarks.isRead(pilot, FIRST_SOOT_MARK)) {
            ReadMarks.mark(pilot, FIRST_SOOT_MARK);
            pilot.sendSystemMessage(Component.translatable("deepcharter.fuel.first_soot"));
        }
    }
}
