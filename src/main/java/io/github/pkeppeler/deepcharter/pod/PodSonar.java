package io.github.pkeppeler.deepcharter.pod;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.sound.DeepSound;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * The navigator's active sonar (#988, SPEC section 8). The player in the Prospector's second seat pings: the pod pays power, the ping returns
 * echoes around the pod as contacts of a {@link SonarKind}, and its noise is an event others answer ({@link SonarEvents}). The echoes live in the
 * pod's {@link State}, synced, so the navigator's client draws them; they fade after {@link SonarTuning#lifetimeTicks()}. A robot navigator
 * (SPEC section 8) calls {@link #ping(PodEntity)} itself.
 */
public final class PodSonar {
    public static final int VERSION = 1;
    /** The ping is heard through the hull: loud, so that a crew knows each one went out. A volume above 1 carries farther. */
    private static final float PING_VOLUME = 2f;

    /** Why a ping did or did not go out. A refusal says so to the player ({@link #message}). */
    public enum Result {
        PINGED(""),
        NOT_NAVIGATOR("deepcharter.pod.sonar.not_navigator"),
        NO_POWER("deepcharter.pod.sonar.no_power"),
        LOW_POWER("deepcharter.pod.sonar.low_power"),
        COOLING_DOWN("deepcharter.pod.sonar.cooling_down"),
        UNREADABLE("deepcharter.pod.sonar.unreadable");

        /** The lang key of the refusal; empty for the ping that went out. */
        private final String key;

        Result(String key) {
            this.key = key;
        }

        /** What a refusal tells the player; the ping that went out says nothing. */
        Optional<Component> message() {
            return key.isEmpty() ? Optional.empty() : Optional.of(Component.translatable(key));
        }
    }

    /**
     * The pod's sonar picture.
     *
     * @param readyAt  the game time from which the pod can ping again
     * @param pingedAt the game time of the ping that returned {@code echoes}
     * @param echoes   the contacts of that ping, nearest kind first, until they fade
     * @param marks    the targets the navigator marked on the pilot's HUD, oldest first, until they lapse
     * @param markReadyAt the game time from which the pod takes another mark
     */
    public record State(long readyAt, long pingedAt, List<SonarEcho> echoes, List<SonarMark> marks, long markReadyAt) {
        public static final State EMPTY = new State(0L, 0L, List.of(), List.of(), 0L);
        public static final MapCodec<State> BODY = RecordCodecBuilder.mapCodec(instance -> instance.group(
                        Codec.LONG.fieldOf("readyAt").forGetter(State::readyAt),
                        Codec.LONG.fieldOf("pingedAt").forGetter(State::pingedAt),
                        SonarEcho.CODEC.listOf().fieldOf("echoes").forGetter(State::echoes),
                        SonarMark.CODEC.listOf().fieldOf("marks").forGetter(State::marks),
                        Codec.LONG.fieldOf("markReadyAt").forGetter(State::markReadyAt))
                .apply(instance, State::new));
        public static final StreamCodec<ByteBuf, State> STREAM = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG,
                State::readyAt,
                ByteBufCodecs.VAR_LONG,
                State::pingedAt,
                SonarEcho.STREAM.apply(ByteBufCodecs.list()),
                State::echoes,
                SonarMark.STREAM.apply(ByteBufCodecs.list()),
                State::marks,
                ByteBufCodecs.VAR_LONG,
                State::markReadyAt,
                State::new);

        public State {
            echoes = List.copyOf(echoes);
            marks = List.copyOf(marks);
        }

        State withEchoes(long pingedAt, long readyAt, List<SonarEcho> echoes) {
            return new State(readyAt, pingedAt, echoes, marks, markReadyAt);
        }

        State withMarks(List<SonarMark> marks, long markReadyAt) {
            return new State(readyAt, pingedAt, echoes, marks, markReadyAt);
        }

        /** The marks that still hold at game time {@code now}. */
        public List<SonarMark> activeMarks(long now) {
            return marks.stream()
                    .filter(mark -> now < mark.markedAt() + SonarTuning.DEFAULT.markLifetimeTicks())
                    .toList();
        }

        /** The echoes the navigator can still read at game time {@code now}: none once they have faded. */
        public List<SonarEcho> shown(long now) {
            return now < pingedAt + SonarTuning.DEFAULT.lifetimeTicks() ? echoes : List.of();
        }

        /** How much of the echoes' life is left at game time {@code now}, from 1 (just pinged) down to 0 (gone). */
        public float strength(long now) {
            return Math.clamp(1f - (float) (now - pingedAt) / SonarTuning.DEFAULT.lifetimeTicks(), 0f, 1f);
        }

        /** The share of the wait for the next ping that is over at game time {@code now}, 0 to 1. */
        public float charged(long now) {
            long wait = SonarTuning.DEFAULT.cooldownTicks();
            return wait == 0 ? 1f : Math.clamp(1f - (float) (readyAt - now) / wait, 0f, 1f);
        }
    }

    public static final AttachmentType<Versioned<State>> STATE = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "pod_sonar"),
            builder -> builder.persistent(Versioned.codec(VERSION, State.BODY))
                    .initializer(() -> Versioned.of(State.EMPTY))
                    .syncWith(Versioned.streamCodec(VERSION, State.STREAM), AttachmentSyncPredicate.all()));

    private PodSonar() {}

    public static void init() {
        SonarPingPayload.register();
        SonarMarkPayload.register();
        SonarEchoes.init();
        SonarLure.init();
        PodEvents.AFTER_TICK.register(PodSonar::afterTick);
    }

    /** The pod's sonar picture, or empty when it is unreadable (logged once). Never throws. Works on both sides, from the synced state. */
    public static Optional<State> reading(PodEntity pod) {
        return Versioned.readable(pod, STATE);
    }

    /**
     * The navigator pressed the ping key: pings from the pod they sit in, and says why when it does not. A player who is not in a pod's navigator
     * seat is ignored with a word, never trusted: the pilot cannot ping.
     */
    public static Result pingBy(ServerPlayer player) {
        Result result = player.getVehicle() instanceof PodEntity pod && PodSeat.isNavigator(pod, player)
                ? ping(pod)
                : Result.NOT_NAVIGATOR;
        result.message().ifPresent(player::sendOverlayMessage);
        return result;
    }

    /**
     * Server only. Pings from {@code pod}: it needs power, a tank that can pay and stay above its reserve, and the wait since the last ping to be
     * over. Then it pays, sets the pod's echoes, and fires {@link SonarEvents#PINGED}. It asks nothing of a seat, so a robot navigator can call it.
     */
    public static Result ping(PodEntity pod) {
        Optional<State> read = reading(pod);
        if (read.isEmpty()) {
            return Result.UNREADABLE;
        }
        if (!(pod.level() instanceof ServerLevel level)) {
            throw new IllegalStateException("a ping is made on the server");
        }
        SonarTuning tuning = SonarTuning.DEFAULT;
        if (!PodEvents.isPowered(pod)) {
            return Result.NO_POWER;
        }
        if (level.getGameTime() < read.get().readyAt()) {
            return Result.COOLING_DOWN;
        }
        float reserve = PodTuning.DEFAULT.fuel().urgentFuelPercent();
        if (pod.fuel() - costPercent(pod) < reserve) {
            return Result.LOW_POWER;
        }
        PodFuel.drain(pod, tuning.powerLitres(), reserve);
        SonarPing ping = new SonarPing(pod, level, pod.position(), tuning.rangeBlocks(), tuning.noiseBlocks());
        long now = level.getGameTime();
        pod.setAttached(STATE, Versioned.of(read.get().withEchoes(now, now + tuning.cooldownTicks(), collect(ping))));
        level.playSound(
                null,
                pod.getX(),
                pod.getY(),
                pod.getZ(),
                DeepSound.SCANNER_PING.event(),
                SoundSource.NEUTRAL,
                PING_VOLUME,
                1f);
        SonarEvents.PINGED.invoker().onPinged(ping);
        return Result.PINGED;
    }

    /** What a ping takes from the pod's tank, in percent of it. */
    public static float costPercent(PodEntity pod) {
        return SonarTuning.DEFAULT.powerLitres()
                / PodStats.of(pod).tankLitres()
                * PodTuning.DEFAULT.shell().fullFuel();
    }

    private static List<SonarEcho> collect(SonarPing ping) {
        Set<SonarEcho> unique = new LinkedHashSet<>();
        SonarEvents.COLLECT.invoker().collect(ping, (kind, pos) -> unique.add(new SonarEcho(kind, pos)));
        List<SonarEcho> found = new ArrayList<>(unique);
        Vec3 origin = ping.origin();
        found.sort(Comparator.comparingInt((SonarEcho echo) -> echo.kind().rank())
                .thenComparingDouble(echo -> echo.pos().distToCenterSqr(origin)));
        return found.size() > SonarTuning.DEFAULT.maxEchoes()
                ? found.subList(0, SonarTuning.DEFAULT.maxEchoes())
                : found;
    }

    private static void afterTick(PodEntity pod) {
        Optional<State> read = reading(pod);
        if (read.isEmpty()) {
            return;
        }
        State state = read.get();
        if (state.echoes().isEmpty() && state.marks().isEmpty()) {
            return;
        }
        long now = pod.level().getGameTime();
        boolean echoesFaded = !state.echoes().isEmpty() && state.shown(now).isEmpty();
        boolean marksLapsed = state.activeMarks(now).size() != state.marks().size();
        if (echoesFaded || marksLapsed) {
            pod.setAttached(
                    STATE,
                    Versioned.of(new State(
                            state.readyAt(),
                            state.pingedAt(),
                            echoesFaded ? List.of() : state.echoes(),
                            state.activeMarks(now),
                            state.markReadyAt())));
        }
    }

    /** Why a mark was or was not set. A refusal says so to the player. */
    public enum MarkResult {
        MARKED(""),
        UNMARKED(""),
        NOT_NAVIGATOR("deepcharter.pod.sonar.not_navigator"),
        NO_ECHO("deepcharter.pod.sonar.no_echo"),
        /** Too soon after the last mark: refused with no word and no write. */
        TOO_FAST(""),
        UNREADABLE("deepcharter.pod.sonar.unreadable");

        private final String key;

        MarkResult(String key) {
            this.key = key;
        }

        Optional<Component> message() {
            return key.isEmpty() ? Optional.empty() : Optional.of(Component.translatable(key));
        }
    }

    /**
     * The navigator marked the echo at {@code pos}: it goes on the pilot's HUD as a mark, or comes off it if it was marked. Only an echo the pod's
     * picture still shows can be marked (the client names the echo it aims at; the server does not take its word for one that is not there). A pod
     * holds {@link SonarTuning#maxMarks()}; one more drops the oldest. A player who is not in the navigator seat is told so and nothing changes.
     */
    public static MarkResult markBy(ServerPlayer player, BlockPos pos) {
        MarkResult result = player.getVehicle() instanceof PodEntity pod && PodSeat.isNavigator(pod, player)
                ? mark(pod, pos)
                : MarkResult.NOT_NAVIGATOR;
        result.message().ifPresent(player::sendOverlayMessage);
        return result;
    }

    /** Server only. Marks the echo at {@code pos} on {@code pod}, or unmarks it; see {@link #markBy}. It asks nothing of a seat. */
    public static MarkResult mark(PodEntity pod, BlockPos pos) {
        Optional<State> read = reading(pod);
        if (read.isEmpty()) {
            return MarkResult.UNREADABLE;
        }
        long now = pod.level().getGameTime();
        State state = read.get();
        if (now < state.markReadyAt()) {
            return MarkResult.TOO_FAST;
        }
        long next = now + SonarTuning.DEFAULT.markCooldownTicks();
        List<SonarMark> marks = new ArrayList<>(state.activeMarks(now));
        if (marks.removeIf(mark -> mark.pos().equals(pos))) {
            pod.setAttached(STATE, Versioned.of(state.withMarks(marks, next)));
            return MarkResult.UNMARKED;
        }
        Optional<SonarEcho> echo =
                state.shown(now).stream().filter(e -> e.pos().equals(pos)).findFirst();
        if (echo.isEmpty()) {
            return MarkResult.NO_ECHO;
        }
        marks.add(new SonarMark(echo.get().kind(), pos, now));
        while (marks.size() > SonarTuning.DEFAULT.maxMarks()) {
            marks.removeFirst();
        }
        pod.setAttached(STATE, Versioned.of(state.withMarks(marks, next)));
        return MarkResult.MARKED;
    }
}
