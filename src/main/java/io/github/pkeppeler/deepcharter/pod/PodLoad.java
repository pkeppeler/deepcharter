package io.github.pkeppeler.deepcharter.pod;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.ore.OreEncumbrance;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.netty.buffer.ByteBuf;
import java.util.Optional;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

/**
 * What a pod weighs against what its rotor can lift (#1449): the one source of the pod's mass, which {@link PodMovement}, {@link PodCues} and the
 * cockpit's load gauge all read, so the physics and what the pilot sees cannot drift apart.
 *
 * <p>The load is the bay's cargo mass, read live from the bay, so ore that leaves it is gone the same tick, plus {@link PodEvents#extraMass}: the
 * riders' packs, a towed pod with its own load, the lining's spoil and bricks. The limit is the engine's power: at or over it the rotor has no lift left. The climb
 * limit is lower: the rotor's thrust scales with the lift left ({@link PodMovement}), so once it no longer beats gravity the pod cannot climb, though
 * it still drives. That is what the cockpit calls OVERLOADED. On the server the extra mass is the listeners'; a client cannot run them, so the server
 * copies it to {@link #EXTRA} at the end of each pod tick, and a client reads that.
 *
 * @param load       the pod's total mass, in the unit of engine power
 * @param limit      the engine's power: the load at which the rotor lifts nothing
 * @param climbLimit the load at and over which the rotor cannot climb: {@code limit * (1 - gravity / thrust)}, half the engine for a stock Mole
 */
public record PodLoad(float load, float limit, float climbLimit) {
    public static final int VERSION = 1;

    /**
     * The extra mass as the server last worked it out, for the client. Not saved: the server writes it again on the pod's first tick. Unreadable (a
     * newer build's version on the wire) reads as no extra mass, so the gauge shows the bay alone and nothing throws.
     *
     * @param mass {@link PodEvents#extraMass}, never negative
     */
    public record Extra(float mass) {
        public static final Extra NONE = new Extra(0f);
        public static final StreamCodec<ByteBuf, Extra> STREAM =
                StreamCodec.composite(ByteBufCodecs.FLOAT, Extra::mass, Extra::new);

        public Extra {
            // NaN fails >= 0, so the wire refuses it too.
            if (!(mass >= 0f)) {
                throw new IllegalArgumentException("a pod's extra mass is never negative, got " + mass);
            }
        }
    }

    /** Synced, not persistent: see {@link Extra}. */
    public static final AttachmentType<Versioned<Extra>> EXTRA = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "pod_extra_mass"),
            builder -> builder.initializer(() -> Versioned.of(Extra.NONE))
                    .syncWith(Versioned.streamCodec(VERSION, Extra.STREAM), AttachmentSyncPredicate.all()));

    static void init() {
        PodEvents.AFTER_TICK.register(PodEvents.OBSERVE, PodLoad::share);
    }

    /** The pod's load now, on either side. */
    public static PodLoad of(PodEntity pod) {
        return of(pod, PodStats.of(pod));
    }

    /** The pod's load now, against the engine of {@code stats}: the snapshot the pod's tick already took. */
    public static PodLoad of(PodEntity pod, PodStats stats) {
        float extra = pod.level().isClientSide()
                ? Versioned.readable(pod, EXTRA).orElse(Extra.NONE).mass()
                : PodEvents.extraMass(pod);
        return new PodLoad(pod.cargoMass() + extra, stats.enginePower(), climbLimit(pod, stats));
    }

    /** The climb limit of {@code stats} under the gravity {@code pod} feels. */
    private static float climbLimit(PodEntity pod, PodStats stats) {
        return climbLimit(stats, pod.gravityPull(PodTuning.DEFAULT.movement().gravity()));
    }

    /**
     * The load at which the held rotor's thrust just pays {@code gravity}: thrust is {@code thrustAcceleration * lift / limit}, so it climbs while the lift
     * share is over {@code gravity / thrustAcceleration}. A rotor with no thrust at all is held by something other than weight (the lining stops it):
     * then only zero lift counts, as before.
     */
    private static float climbLimit(PodStats stats, double gravity) {
        if (stats.thrustAcceleration() <= 0f) {
            return stats.enginePower();
        }
        double share = Math.max(0.0, 1.0 - gravity / stats.thrustAcceleration());
        return (float) (stats.enginePower() * share);
    }

    /**
     * What an engine part of {@code tier} lifts (#1541): the climb limit of a stock pod that works that engine, under the pod's own
     * gravity. It runs the same {@link PodComponents#withPart part} and the same climb limit a fitted pod's {@link #of} does, so the
     * figure on a part and the pod's LOAD gauge cannot drift apart. The chassis does not change it: it only caps the tier a part
     * works at ({@link Chassis#tierCap}), and neither the thrust nor the gravity depend on it.
     */
    public static float engineLift(int tier) {
        PodStats fitted = PodComponents.withPart(PodStats.base(), ComponentTrack.ENGINE, tier);
        return climbLimit(fitted, PodTuning.DEFAULT.movement().gravity());
    }

    /** {@link #engineLift} as the player reads it: written as {@link OreEncumbrance#shown} writes an ore's mass, to a tenth. */
    public static String shownEngineLift(int tier) {
        return OreEncumbrance.shown(OreEncumbrance.tenths(engineLift(tier)));
    }

    /** Whether the held rotor cannot climb: the load is at or over the climb limit. The cockpit's OVERLOADED. */
    public boolean cannotClimb() {
        return load >= climbLimit;
    }

    /** The engine power the load leaves for the rotor, never below 0. */
    public float lift() {
        return Math.max(0f, limit - load);
    }

    private static void share(PodEntity pod) {
        Optional<Extra> read = Versioned.readable(pod, EXTRA);
        Extra now = new Extra(PodEvents.extraMass(pod));
        if (read.isPresent() && !read.get().equals(now)) {
            pod.setAttached(EXTRA, Versioned.of(now));
        }
    }
}
