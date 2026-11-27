package io.github.pkeppeler.deepcharter.pod;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.handbook.ReadMarks;
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
import net.minecraft.world.entity.player.Input;

/**
 * Pod sprint (#1276): the pilot's sprint key held with W drives the pod {@link PodSteeringTuning#sprintBoost} times
 * faster, on the ground and in the air, and burns {@link PodSteeringTuning#sprintBurn} times the moving fuel. It never
 * reaches the drill: sprint with no direction key still bores down ({@link PodDrill}), and a pod that drills, or stands
 * in a bore as wide as itself, cruises. S, a bare A or D and a passenger's keys do not boost.
 *
 * <p>The server decides at the end of each tick from the pilot's input, so the boost starts and ends a tick after the
 * keys. It reaches the drive as a stat ({@link PodStats#MODIFY}), so it multiplies with the engine tier and with soot.
 * The state is session only (no {@code persistent}), as {@code FlueScrape}'s is, because the next tick decides it
 * again; it is synced so the pilot's readout and the client's glide see it.
 */
public final class PodSprint {
    public static final int VERSION = 1;

    /** The read mark of the one-time line on a pilot's first sprint. */
    private static final Identifier FIRST_SPRINT_MARK =
            Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "hint/first_pod_sprint");

    /** Whether the pod runs on its sprint boost this tick. */
    public record State(boolean boosting) {
        public static final State CRUISE = new State(false);
        public static final StreamCodec<ByteBuf, State> STREAM = ByteBufCodecs.BOOL.map(State::new, State::boosting);
    }

    public static final AttachmentType<Versioned<State>> STATE = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "pod_sprint"),
            builder -> builder.initializer(() -> Versioned.of(State.CRUISE))
                    .syncWith(Versioned.streamCodec(VERSION, State.STREAM), AttachmentSyncPredicate.all()));

    private PodSprint() {}

    public static void init() {
        PodEvents.AFTER_TICK.register(PodSprint::afterTick);
        // A wreck does not tick, so its boost would stay set.
        PodEvents.HULL_DEPLETED.register(PodSprint::stop);
        PodStats.MODIFY.register((pod, stats) -> boosting(pod)
                ? stats.withHorizontalSpeed(stats.horizontalSpeed() * PodSteeringTuning.DEFAULT.sprintBoost())
                : stats);
    }

    /** Whether the pod runs on its sprint boost. Either side: the client reads the synced value. */
    public static boolean boosting(PodEntity pod) {
        return Versioned.readable(pod, STATE).map(State::boosting).orElse(false);
    }

    /** The share the boost adds to the drive speed, in percent. */
    public static int boostPercent() {
        return Math.round((PodSteeringTuning.DEFAULT.sprintBoost() - 1f) * 100);
    }

    /** The share the boost adds to the fuel a block driven costs, in percent: the burn a second over the speed. */
    static int burnPerBlockPercent() {
        PodSteeringTuning tuning = PodSteeringTuning.DEFAULT;
        return Math.round((tuning.sprintBurn() / tuning.sprintBoost() - 1f) * 100);
    }

    /**
     * Whether the pilot's {@code input} boosts the pod: sprint with W and not S, while the pod neither drills nor
     * stands in a bore as wide as itself, where the drill sets the pace.
     */
    private static boolean wants(PodEntity pod, Input input) {
        return input.sprint() && input.forward() && !input.backward() && !pod.drilling() && !PodSteering.inBore(pod);
    }

    /** Sets the boost from the powered pod's pilot, and tells them once, on their first boost. */
    private static void afterTick(PodEntity pod) {
        ServerPlayer pilot = PodEvents.isPowered(pod) && pod.getControllingPassenger() instanceof ServerPlayer player
                ? player
                : null;
        boolean boosting = pilot != null && wants(pod, pilot.getLastClientInput());
        record(pod, boosting);
        if (pilot != null && boosting) {
            tellOnce(pilot);
        }
    }

    /** Server only: ends the boost now, as the drill does on its first tick. */
    static void stop(PodEntity pod) {
        record(pod, false);
    }

    private static void record(PodEntity pod, boolean boosting) {
        Optional<State> read = Versioned.readable(pod, STATE);
        if (read.isPresent() && read.get().boosting() != boosting) {
            pod.setAttached(STATE, Versioned.of(new State(boosting)));
        }
    }

    private static void tellOnce(ServerPlayer pilot) {
        if (!ReadMarks.isReadable(pilot) || ReadMarks.isRead(pilot, FIRST_SPRINT_MARK)) {
            return;
        }
        ReadMarks.mark(pilot, FIRST_SPRINT_MARK);
        pilot.sendSystemMessage(Component.translatable(
                "deepcharter.pod.first_sprint",
                Component.keybind("key.sprint"),
                Component.keybind("key.forward"),
                boostPercent(),
                burnPerBlockPercent()));
    }
}
