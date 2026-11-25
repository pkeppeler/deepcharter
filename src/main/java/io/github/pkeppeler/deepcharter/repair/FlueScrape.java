package io.github.pkeppeler.deepcharter.repair;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.fuel.EngineSoot;
import io.github.pkeppeler.deepcharter.fuel.FuelTuning;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.sound.DeepSound;
import io.github.pkeppeler.deepcharter.terminal.Terminals;
import io.netty.buffer.ByteBuf;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * The free flue scrape (#1280): the repair station's way out of limp mode for a pilot who cannot pay for a service.
 * A pod in limp mode at the colony cannot launch ({@link EngineSoot#launchRefused}), and a charter with no money
 * cannot pay to service it, so without this a first-tank pilot on coal would be stuck.
 *
 * <p>The player who starts it waits at the station for {@link FuelTuning.Soot#scrapeTicks}, with the pod parked there.
 * It then leaves {@link FuelTuning.Soot#scrapeLeavesLitres} of soot: under the limp limit, so the pod launches, and
 * still slow, so the paid service stays the way to a clean engine. It stops, clearing nothing, when that player walks
 * out of the station's reach or leaves the game, when the pod leaves its parking place, flies or drills, or when the
 * engine is no longer limp (a paid service came first). The server alone decides, on the pod's tick, as {@link
 * CompanyRecovery}'s wait does.
 *
 * <p>The state is session only (no {@code persistent}), as {@code DrifterAlarm}'s is: a scrape needs its player at
 * the station, so a reload ends it, and no save holds one. It is synced, so the station's row counts down.
 */
public final class FlueScrape {
    public static final int VERSION = 1;

    /** The game time the scrape began, or {@link #NONE}'s; the station it runs at; the player who waits there. */
    public record State(long since, BlockPos station, UUID scraper) {
        public static final State NONE = new State(Long.MIN_VALUE, BlockPos.ZERO, new UUID(0L, 0L));
        public static final StreamCodec<ByteBuf, State> STREAM = StreamCodec.composite(
                ByteBufCodecs.LONG,
                State::since,
                BlockPos.STREAM_CODEC,
                State::station,
                UUIDUtil.STREAM_CODEC,
                State::scraper,
                State::new);

        public boolean running() {
            return since != Long.MIN_VALUE;
        }
    }

    public static final AttachmentType<Versioned<State>> STATE = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "flue_scrape"),
            builder -> builder.initializer(() -> Versioned.of(State.NONE))
                    .syncWith(Versioned.streamCodec(VERSION, State.STREAM), AttachmentSyncPredicate.all()));

    private static final int TICKS_PER_SECOND = 20;

    private FlueScrape() {}

    static void init() {
        PodEvents.AFTER_TICK.register(FlueScrape::afterTick);
        // A wreck does not tick, so its scrape would stay set.
        PodEvents.HULL_DEPLETED.register(pod -> {
            if (running(pod)) {
                pod.setAttached(STATE, Versioned.of(State.NONE));
            }
        });
    }

    /** Whether a scrape runs on {@code pod}. Either side. */
    public static boolean running(PodEntity pod) {
        return Versioned.readable(pod, STATE).map(State::running).orElse(false);
    }

    /**
     * Whole seconds until the scrape ends, for the station's row: empty when none runs. Rounds up, so the row never
     * shows 0 while the scrape still runs.
     */
    public static Optional<Integer> secondsLeft(PodEntity pod) {
        return Versioned.readable(pod, STATE).filter(State::running).map(state -> {
            long left = state.since()
                    + FuelTuning.DEFAULT.soot().scrapeTicks()
                    - pod.level().getGameTime();
            return (int) Math.max(0L, (left + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND);
        });
    }

    /** Server only: {@code player} starts a scrape of {@code pod} at the station at {@code station}. */
    static void start(PodEntity pod, ServerPlayer player, BlockPos station) {
        pod.setAttached(
                STATE, Versioned.of(new State(pod.level().getGameTime(), station.immutable(), player.getUUID())));
    }

    private static void afterTick(PodEntity pod) {
        State state = Versioned.readable(pod, STATE).orElse(State.NONE);
        if (!state.running()) {
            return;
        }
        // A paid service came first, or the pod is a wreck: nothing left to scrape, and nothing to say.
        if (!EngineSoot.launchRefused(pod) || pod.hull() <= 0f) {
            stop(pod);
            return;
        }
        ServerPlayer scraper =
                ((ServerLevel) pod.level()).getServer().getPlayerList().getPlayer(state.scraper());
        if (scraper == null) {
            stop(pod);
            return;
        }
        boolean here =
                scraper.isAlive() && scraper.level() == pod.level() && Terminals.inReach(scraper, state.station());
        if (!here || !Terminals.isParkedAt(pod, state.station())) {
            stop(pod);
            scraper.sendOverlayMessage(Component.translatable(
                    here ? "message.deepcharter.repair.scrape_pod_left" : "message.deepcharter.repair.scrape_left"));
            return;
        }
        if (pod.level().getGameTime() - state.since()
                < FuelTuning.DEFAULT.soot().scrapeTicks()) {
            return;
        }
        stop(pod);
        if (EngineSoot.scrape(pod)) {
            Consumables.play(pod, DeepSound.REPAIR_NANOBOTS);
            scraper.sendOverlayMessage(
                    Component.translatable("message.deepcharter.repair.scrape_done", Math.round(EngineSoot.soot(pod))));
        }
    }

    private static void stop(PodEntity pod) {
        pod.setAttached(STATE, Versioned.of(State.NONE));
    }
}
