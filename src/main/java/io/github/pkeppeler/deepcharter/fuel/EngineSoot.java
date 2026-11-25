package io.github.pkeppeler.deepcharter.fuel;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.handbook.ReadMarks;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.netty.buffer.ByteBuf;
import java.util.Optional;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * Soot in a pod's engine (#1280). Burning coal and charcoal leaves soot, and a fouled engine burns more fuel until the repair
 * station services it; pump fuel and biofuel burn clean. The state is a versioned attachment ({@link #STATE}, ADR 0097).
 *
 * <p>Refuelling with a solid fuel puts its litres, and the soot those litres will leave, in the tank's account; the pod's tick
 * burns litres off that account in proportion, so a tank of half coal and half pump fuel fouls half as fast. The burn is taken off
 * the solid fuel first, which makes the soot a pessimistic count for a mixed tank and exact for one filled with items.
 *
 * <p>The tick path reads the state with {@link Versioned#readable}: unreadable soot is no soot, and nothing writes over it.
 * {@link #service} is an explicit change and throws on an unreadable state, naming the attachment; its caller asks
 * {@link #isReadable} first.
 */
public final class EngineSoot {
    public static final int VERSION = 1;

    /** The read mark that records the one-time line the first time soot bites a pilot (the player's {@code ReadMarks}). */
    public static final Identifier FIRST_SOOT_MARK =
            Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "hint/first_soot");

    /**
     * Litres of soot in the engine, the litres of solid fuel in the tank that have not burned, and the litres of soot those
     * will leave when they do.
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

    private EngineSoot() {}

    static void init() {}

    private static State state(PodEntity pod) {
        return Versioned.readable(pod, STATE).orElse(State.CLEAN);
    }

    /** Litres of soot in the engine; 0 when the state cannot be read. Either side: the client reads the synced value. */
    public static float soot(PodEntity pod) {
        return state(pod).soot();
    }

    /** The soot past the grace, which is what burns extra fuel. */
    private static float sootPastGrace(float soot) {
        return Math.max(0f, soot - FuelTuning.DEFAULT.soot().graceLitres());
    }

    /** The extra share of fuel the engine burns for {@code soot}: 0 up to the grace, then the penalty a litre, to the cap. */
    public static float extraBurn(float soot) {
        FuelTuning.Soot tuning = FuelTuning.DEFAULT.soot();
        return Math.min(tuning.maxPenalty(), sootPastGrace(soot) * tuning.penaltyPerLitre());
    }

    /** What the engine burns now, as a multiple of what a clean engine burns: 1 up to the grace. */
    public static float burnMultiplier(PodEntity pod) {
        return 1f + extraBurn(soot(pod));
    }

    /**
     * Server only: {@code litres} of a fuel that leaves {@code sootPerLitre} of soot a litre went into the tank. A clean fuel
     * (0) changes nothing, and so does an unreadable state, which is kept as it is: the refuel is a callback path.
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
     * Server only, from the pod's tick: {@code litres} burned. The solid fuel in the tank burns first, and its soot comes off the
     * tank's account in proportion. Does nothing, and never throws, when nothing solid is in the tank or the state is unreadable.
     */
    public static void burned(PodEntity pod, float litres) {
        Optional<State> read = Versioned.readable(pod, STATE);
        if (read.isEmpty() || read.get().tankLitres() <= 0f || !(litres > 0f)) {
            return;
        }
        State state = read.get();
        float share = Math.min(1f, litres / state.tankLitres());
        float made = state.tankSoot() * share;
        float soot = state.soot() + made;
        boolean crossed = state.soot() <= FuelTuning.DEFAULT.soot().graceLitres()
                && soot > FuelTuning.DEFAULT.soot().graceLitres();
        pod.setAttached(
                STATE,
                Versioned.of(new State(soot, state.tankLitres() * (1f - share), state.tankSoot() * (1f - share))));
        if (crossed) {
            explainFirstSoot(pod);
        }
    }

    /** Dollars to service the engine now: the soot's litres at the tuned price, rounded up. */
    public static long serviceCost(PodEntity pod) {
        return (long) Math.ceil(soot(pod) * FuelTuning.DEFAULT.soot().servicePerLitre());
    }

    /** Server only: clears the engine's soot. What the tank's fuel will still leave is kept. Throws on an unreadable state. */
    public static void service(PodEntity pod) {
        Versioned.modifyOrThrow(pod, STATE, state -> new State(0f, state.tankLitres(), state.tankSoot()));
    }

    /** Says once per player, kept as a read mark, why the engine now burns more. Says nothing when the marks cannot be read. */
    private static void explainFirstSoot(PodEntity pod) {
        if (pod.getControllingPassenger() instanceof ServerPlayer pilot
                && ReadMarks.isReadable(pilot)
                && !ReadMarks.isRead(pilot, FIRST_SOOT_MARK)) {
            ReadMarks.mark(pilot, FIRST_SOOT_MARK);
            pilot.sendSystemMessage(Component.translatable("deepcharter.fuel.first_soot"));
        }
    }
}
