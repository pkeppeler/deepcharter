package io.github.pkeppeler.deepcharter.creature;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerChain.Band;
import io.github.pkeppeler.deepcharter.layer.Zones;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.sound.DeepSound;
import io.github.pkeppeler.deepcharter.sound.SoundPackets;
import io.netty.buffer.ByteBuf;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;

/**
 * The alarm (#973, CREATURES.md section 2): killing a Drifter rings the alarm of its zone for a few minutes ({@link DrifterTuning.Alarm}). A
 * zone is a third of a layer by height ({@link Zones#index}), so no zone names are needed. The alarm and the pod's readout of it are held in
 * memory only: a restart forgets a few minutes of alarm, which costs the player nothing they could keep (mechanics.md).
 */
public final class DrifterAlarm {
    public static final int VERSION = 1;
    /** Ticks in a second, for the line the pilot reads. */
    private static final int TICKS_PER_SECOND = 20;

    /**
     * A third of a layer of a level: the key of one alarm.
     *
     * @param dimension the level
     * @param layer     the layer of the band, from 1
     * @param index     0 for the top third, 2 for the bottom
     */
    public record Zone(ResourceKey<Level> dimension, int layer, int index) {
        /** The zone at {@code y} in {@code level}; empty on the surface and in unbuilt depth. */
        public static Optional<Zone> at(Level level, int y) {
            if (level.dimensionTypeRegistration().unwrapKey().isEmpty()) {
                return Optional.empty();
            }
            Optional<Band> band = LayerChain.layerAt(level, y);
            if (band.isEmpty() || y < band.get().minY() || y > band.get().maxY()) {
                return Optional.empty();
            }
            return Optional.of(new Zone(
                    level.dimension(),
                    band.get().layer(),
                    Zones.index(band.get().minY(), band.get().height(), y)));
        }
    }

    /**
     * What the pilot's readout shows.
     *
     * @param seconds whole seconds the alarm still rings, 0 while it is quiet
     */
    public record State(int seconds) {
        public static final State QUIET = new State(0);
        public static final StreamCodec<ByteBuf, State> STREAM = ByteBufCodecs.VAR_INT.map(State::new, State::seconds);

        public State {
            if (seconds < 0) {
                throw new IllegalArgumentException("the alarm's seconds are not negative, got " + seconds);
            }
        }

        public boolean isQuiet() {
            return seconds == 0;
        }
    }

    /** Session only: no {@code persistent}, so a pod saved and loaded again is quiet. */
    public static final AttachmentType<Versioned<State>> STATE = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "pod_drifter_alarm"),
            builder -> builder.initializer(() -> Versioned.of(State.QUIET))
                    .syncWith(Versioned.streamCodec(VERSION, State.STREAM), AttachmentSyncPredicate.all()));

    /** Game time at which each zone's alarm stops. Server thread only; a new server starts with none. */
    private static final Map<Zone, Long> RINGS_UNTIL = new HashMap<>();

    private DrifterAlarm() {}

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> RINGS_UNTIL.clear());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> RINGS_UNTIL.clear());
        ServerTickEvents.END_LEVEL_TICK.register(DrifterAlarm::tick);
        // A pod with no one aboard shows no alarm: the next rider is not told a number from a minute ago.
        PodEvents.AFTER_TICK.register(pod -> {
            if (pod.getPassengers().isEmpty()) {
                show(pod, State.QUIET);
            }
        });
    }

    /**
     * Starts the alarm of the zone at {@code y}, or starts it again if it rings: the length never stacks. When it was quiet, a person on foot in
     * the zone is told once (a pilot's readout says it). Nothing happens outside a zone.
     */
    public static void raise(ServerLevel level, int y) {
        Optional<Zone> zone = Zone.at(level, y);
        if (zone.isEmpty()) {
            return;
        }
        boolean wasQuiet = !isRinging(level, y);
        RINGS_UNTIL.put(
                zone.get(), level.getGameTime() + DrifterTuning.DEFAULT.alarm().ticks());
        if (wasQuiet) {
            for (ServerPlayer person : level.players()) {
                if (!(person.getVehicle() instanceof PodEntity)
                        && Zone.at(level, person.getBlockY()).equals(zone)) {
                    person.sendOverlayMessage(Component.translatable("creature.deepcharter.alarm_foot"));
                }
            }
        }
    }

    /** Ticks the alarm of the zone at {@code y} still rings: 0 when it is quiet. */
    public static long ticksLeft(Level level, int y) {
        return Zone.at(level, y)
                .map(zone -> Math.max(0L, RINGS_UNTIL.getOrDefault(zone, 0L) - level.getGameTime()))
                .orElse(0L);
    }

    public static boolean isRinging(Level level, int y) {
        return ticksLeft(level, y) > 0;
    }

    /** What the pod's readout warns of now, or empty while the alarm is quiet or the state is unreadable. Works on both sides, from the synced state. */
    public static Optional<State> warning(PodEntity pod) {
        return Versioned.readable(pod, STATE).filter(state -> !state.isQuiet());
    }

    /** Ends alarms that ran out, beats the drone for each person in a ringing zone, and keeps the pilots' readouts true. */
    private static void tick(ServerLevel level) {
        long now = level.getGameTime();
        Iterator<Map.Entry<Zone, Long>> rings = RINGS_UNTIL.entrySet().iterator();
        while (rings.hasNext()) {
            if (rings.next().getValue() <= now) {
                rings.remove();
            }
        }
        if (now % TICKS_PER_SECOND != 0) {
            return;
        }
        boolean beat = now % DrifterTuning.DEFAULT.alarm().droneTicks() == 0;
        for (ServerPlayer player : level.players()) {
            long left = ticksLeft(level, player.getBlockY());
            if (left > 0 && beat) {
                SoundPackets.playTo(player, DeepSound.CREATURE_ALARM_DRONE, SoundSource.HOSTILE);
            }
            if (player.getVehicle() instanceof PodEntity pod) {
                show(pod, new State((int) Math.ceilDiv(left, TICKS_PER_SECOND)));
            }
        }
    }

    private static void show(PodEntity pod, State now) {
        if (Versioned.readable(pod, STATE).filter(now::equals).isEmpty()) {
            pod.setAttached(STATE, Versioned.of(now));
        }
    }
}
