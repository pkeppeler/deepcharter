package io.github.pkeppeler.deepcharter.pod;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
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
import net.minecraft.world.entity.player.Player;

/**
 * The server's seat assignment (#1088): who sits where in a pod, by UUID, in boarding order (the first is the pilot, see {@link PodSeat}). It is
 * synced to every client that tracks the pod, so a client reads a rider's role from it and not from the riders it has loaded: a rider whose chunk is
 * still queued is missing from the client's passenger list, but still holds its seat. UUIDs, not entity ids, so a rider the client has not
 * loaded can still be matched when it arrives. The server writes it at the end of a pod tick, only when it changed.
 *
 * <p>A rider the client sees but the list has not reached yet (a new pod entity before its first tick, or a boarding after the pod's tick) takes the
 * next seat after the listed ones, so nobody is without a role for a tick. The pilot's seat counts only when its rider is a player, as the
 * server's {@code getControllingPassenger} does; the list carries that flag for the riders the client has not loaded.
 *
 * <p>Not saved: it is derived from the passengers, which the pod's own save restores, and rewritten on the first tick. Unreadable (a newer build's
 * version on the wire) reads as no listed seats, so every visible rider falls back to its place in the passenger list and nothing throws.
 */
public final class PodSeats {
    public static final int VERSION = 1;

    /**
     * The seat assignment.
     *
     * @param riders        the UUIDs of the pod's riders in boarding order
     * @param pilotIsPlayer whether the first rider is a player, who is the only kind that pilots
     */
    public record State(List<UUID> riders, boolean pilotIsPlayer) {
        public static final State EMPTY = new State(List.of(), false);
        public static final StreamCodec<ByteBuf, State> STREAM = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs.list()),
                State::riders,
                ByteBufCodecs.BOOL,
                State::pilotIsPlayer,
                State::new);

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
     * The riders' UUIDs in seat order. On a client this is the server's synced assignment, then the visible riders it has not reached yet; on
     * the server it is the pod's passengers, which the assignment is written from.
     */
    static List<UUID> order(PodEntity pod) {
        List<UUID> seen = pod.getPassengers().stream().map(Entity::getUUID).toList();
        if (!pod.level().isClientSide()) {
            return seen;
        }
        List<UUID> order = new ArrayList<>(synced(pod).riders());
        for (UUID rider : seen) {
            if (!order.contains(rider)) {
                order.add(rider);
            }
        }
        return order;
    }

    /** The UUID of the pilot's seat holder, loaded on this side or not; empty when the first rider is not a player. */
    public static Optional<UUID> pilot(PodEntity pod) {
        if (!pod.level().isClientSide()) {
            return Optional.ofNullable(pod.getControllingPassenger()).map(Entity::getUUID);
        }
        State synced = synced(pod);
        if (!synced.riders().isEmpty()) {
            return synced.pilotIsPlayer() ? Optional.of(synced.riders().getFirst()) : Optional.empty();
        }
        // Nothing synced yet: the first visible rider pilots if it is a player.
        return Optional.ofNullable(pod.getFirstPassenger())
                .filter(Player.class::isInstance)
                .map(Entity::getUUID);
    }

    private static State synced(PodEntity pod) {
        return Versioned.readable(pod, STATE).orElse(State.EMPTY);
    }

    private static void afterTick(PodEntity pod) {
        Entity first = pod.getFirstPassenger();
        State now = new State(order(pod), first instanceof Player);
        Optional<State> read = Versioned.readable(pod, STATE);
        if (read.isPresent() && !read.get().equals(now)) {
            pod.setAttached(STATE, Versioned.of(now));
        }
    }
}
