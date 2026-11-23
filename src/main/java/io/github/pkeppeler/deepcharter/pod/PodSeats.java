package io.github.pkeppeler.deepcharter.pod;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;

/**
 * The server's seat assignment (#1088): who sits where in a pod, by UUID, in boarding order (the first is the pilot, see {@link PodSeat}). It is
 * synced to every client that tracks the pod, so a client reads a rider's role from it and not from the riders it has loaded: a rider whose chunk is
 * still queued is missing from the client's passenger list, but still holds its seat. UUIDs, not entity ids, so a rider the client has not
 * loaded can still be matched when it arrives. The server rewrites it at the end of each pod tick.
 *
 * <p>Not saved: it is derived from the passengers, which the pod's own save restores, and rewritten on the first tick. Unreadable (a newer build's
 * version on the wire) reads as no seats, so a client shows no sonar and no pod status rather than throwing.
 */
public final class PodSeats {
    public static final int VERSION = 1;

    /**
     * The seat assignment.
     *
     * @param riders the UUIDs of the pod's riders in boarding order
     */
    public record State(List<UUID> riders) {
        public static final State EMPTY = new State(List.of());
        public static final MapCodec<State> BODY = RecordCodecBuilder.mapCodec(instance -> instance.group(
                        UUIDUtil.CODEC.listOf().fieldOf("riders").forGetter(State::riders))
                .apply(instance, State::new));
        public static final StreamCodec<ByteBuf, State> STREAM =
                UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs.list()).map(State::new, State::riders);

        public State {
            riders = List.copyOf(riders);
        }
    }

    /** Synced, not persistent: see the class comment. */
    public static final AttachmentType<Versioned<State>> STATE = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "pod_seats"),
            builder -> builder.initializer(() -> Versioned.of(State.EMPTY))
                    .syncWith(Versioned.streamCodec(VERSION, State.STREAM), AttachmentSyncPredicate.all()));

    private PodSeats() {}

    public static void init() {
        PodEvents.AFTER_TICK.register(PodEvents.OBSERVE, PodSeats::afterTick);
    }

    /**
     * The riders' UUIDs in seat order. On a client this is the server's synced assignment; on the server it is the pod's passengers, which
     * the assignment is written from.
     */
    static List<UUID> order(PodEntity pod) {
        if (pod.level().isClientSide()) {
            return Versioned.readable(pod, STATE).map(State::riders).orElse(List.of());
        }
        return pod.getPassengers().stream().map(Entity::getUUID).toList();
    }

    /** The UUID of the pilot's seat holder, loaded on this side or not. */
    public static Optional<UUID> pilot(PodEntity pod) {
        return order(pod).stream().findFirst();
    }

    private static void afterTick(PodEntity pod) {
        State now = new State(order(pod));
        Optional<State> read = Versioned.readable(pod, STATE);
        if (read.isPresent() && !read.get().equals(now)) {
            pod.setAttached(STATE, Versioned.of(now));
        }
    }
}
