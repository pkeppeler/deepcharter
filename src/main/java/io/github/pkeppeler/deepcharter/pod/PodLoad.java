package io.github.pkeppeler.deepcharter.pod;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
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
 * pilot's pack, a towed pod, the lining's spoil and bricks. The limit is the engine's power: at or over it the rotor has no lift left. On the server the
 * extra mass is the listeners'; a client cannot run them, so the server copies it to {@link #EXTRA} at the end of each pod tick, and a client reads that.
 *
 * @param load  the pod's total mass, in the unit of engine power
 * @param limit the engine's power: the load at which the rotor lifts nothing
 */
public record PodLoad(float load, float limit) {
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
        return new PodLoad(pod.cargoMass() + extra, stats.enginePower());
    }

    /** Whether the rotor has no lift left: the load is at or over the limit. */
    public boolean overloaded() {
        return load >= limit;
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
