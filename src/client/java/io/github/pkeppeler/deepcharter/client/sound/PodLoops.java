package io.github.pkeppeler.deepcharter.client.sound;

import io.github.pkeppeler.deepcharter.client.layer.SeamSwapEvents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodSeats;
import io.github.pkeppeler.deepcharter.pod.VoidBelow;
import io.github.pkeppeler.deepcharter.pod.VoidBelowTuning;
import io.github.pkeppeler.deepcharter.sound.DeepSound;
import io.github.pkeppeler.deepcharter.sound.SoundTuning;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The engine, drill and rotor loops of every pod with a pilot. A loop starts silent and rises, and one whose state ended falls to
 * silence before it stops, so a change of state crossfades. The local pilot's loops follow no position, since a sound at the pod's
 * last tick position pans and swells at the tick rate under a camera that moves every frame.
 */
public final class PodLoops {
    /** One loop slot of a pod: at most one loop of each plays at a time. */
    private enum Channel {
        /** Idle or driving; the drill replaces it while drilling. */
        ENGINE(PodLoops::engine),
        ROTOR(pod -> pod.flying() ? Optional.of(DeepSound.POD_ROTOR) : Optional.empty());

        private final Function<PodEntity, Optional<DeepSound>> sound;

        Channel(Function<PodEntity, Optional<DeepSound>> sound) {
            this.sound = sound;
        }

        /** The sound this channel should make for {@code pod} now: none when the pod has no pilot or no power. */
        Optional<DeepSound> soundOf(PodEntity pod) {
            return PodSeats.pilot(pod).isEmpty() || pod.stranded() ? Optional.empty() : sound.apply(pod);
        }
    }

    /** One loop: a pod, its channel, the sound, and whether it is heard from the cab (the local pilot's) or from where the pod is. */
    private record Key(int podId, Channel channel, DeepSound sound, boolean fromCab) {}

    /** Ticks a started loop may take to show up in the sound engine before it counts as declined. */
    private static final int START_GRACE_TICKS = 3;

    private static final Map<Key, Loop> LOOPS = new HashMap<>();
    /** Pod id to the ticks it still counts as moving: {@link SoundTuning#movingHoldTicks} after its last move. */
    private static final Map<Integer, Integer> MOVING = new HashMap<>();

    private static Map<Integer, Vec3> lastPositions = new HashMap<>();
    private static @Nullable ClientLevel trackedLevel;

    private PodLoops() {}

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(PodLoops::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset(null));
        // A swap keeps the pods and everything playing: the loops follow the pod into the new level.
        SeamSwapEvents.SWAPPED.register((from, to) -> trackedLevel = to);
    }

    /** Ends every loop and forgets every pod: the pods of the old level are not the pods of {@code level}. */
    private static void reset(@Nullable ClientLevel level) {
        LOOPS.values().forEach(Loop::end);
        LOOPS.clear();
        MOVING.clear();
        lastPositions = new HashMap<>();
        trackedLevel = level;
    }

    private static Optional<DeepSound> engine(PodEntity pod) {
        if (pod.drilling()) {
            return Optional.of(
                    pod.drillDirection() == Direction.DOWN
                            ? DeepSound.POD_ENGINE_DRILL_DOWN
                            : DeepSound.POD_ENGINE_DRILL_SIDE);
        }
        return Optional.of(MOVING.containsKey(pod.getId()) ? DeepSound.POD_ENGINE_DRIVE : DeepSound.POD_ENGINE_IDLE);
    }

    /** Records which pods moved since the last client tick. A pod's own old position is not that: a remote pod is lerped. */
    private static void trackMovement(ClientLevel level) {
        double speed = SoundTuning.DEFAULT.movingSpeed();
        Map<Integer, Vec3> positions = new HashMap<>();
        MOVING.replaceAll((id, ticks) -> ticks - 1);
        MOVING.values().removeIf(ticks -> ticks <= 0);
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof PodEntity pod) {
                Vec3 last = lastPositions.get(pod.getId());
                if (last != null && last.distanceToSqr(pod.position()) > speed * speed) {
                    MOVING.put(pod.getId(), SoundTuning.DEFAULT.movingHoldTicks());
                }
                positions.put(pod.getId(), pod.position());
            }
        }
        lastPositions = positions;
    }

    private static void tick(Minecraft client) {
        // A loop the engine declined to start (a pod out of range) never ticks and would hold its key for ever.
        LOOPS.values().forEach(loop -> loop.endIfNeverStarted(client.getSoundManager()));
        LOOPS.values().removeIf(Loop::isStopped);
        ClientLevel level = client.level;
        if (level != trackedLevel) {
            reset(level);
        }
        if (level == null) {
            return;
        }
        trackMovement(level);
        for (Entity entity : level.entitiesForRendering()) {
            // A pod the client has not ticked yet may be dropped again at once.
            if (entity instanceof PodEntity pod && !pod.isRemoved() && pod.tickCount > 0) {
                for (Channel channel : Channel.values()) {
                    // A loop of a state the pod has left is not ended here: it falls to silence and stops itself.
                    channel.soundOf(pod).ifPresent(wanted -> {
                        Key key = new Key(pod.getId(), channel, wanted, isCab(pod));
                        if (!LOOPS.containsKey(key)) {
                            Loop loop = new Loop(pod, key);
                            LOOPS.put(key, loop);
                            client.getSoundManager().play(loop);
                        }
                    });
                }
            }
        }
    }

    /** Whether the player at this client pilots {@code pod}. */
    private static boolean isCab(PodEntity pod) {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null
                && PodSeats.pilot(pod).filter(player.getUUID()::equals).isPresent();
    }

    /** Follows its pod, fades in while the pod is in the loop's state and out when it leaves it, and stops once it is silent or its pod is gone. */
    private static final class Loop extends AbstractTickableSoundInstance {
        private final PodEntity pod;
        private final Key key;
        private final float fadeStep = 1f / SoundTuning.DEFAULT.engineFadeTicks();
        private int ageTicks;

        private Loop(PodEntity pod, Key key) {
            super(key.sound().event(), SoundSource.NEUTRAL, RandomSource.create());
            this.pod = pod;
            this.key = key;
            this.looping = true;
            this.volume = 0f;
            if (key.fromCab()) {
                this.relative = true;
                this.attenuation = SoundInstance.Attenuation.NONE;
            }
            follow();
        }

        /** A loop starts at volume 0 and rises; vanilla would drop a silent sound without playing it. */
        @Override
        public boolean canStartSilent() {
            return true;
        }

        @Override
        public void tick() {
            if (pod.isRemoved() || pod.level() != Minecraft.getInstance().level) {
                stop();
                return;
            }
            boolean wanted = key.channel().soundOf(pod).equals(Optional.of(key.sound())) && isCab(pod) == key.fromCab();
            volume = Mth.clamp(volume + (wanted ? fadeStep : -fadeStep), 0f, 1f);
            // The drill sounds hollow over the last rows before it breaks into a drop that lands hard (#1387).
            if (key.sound() == DeepSound.POD_ENGINE_DRILL_DOWN) {
                pitch = VoidBelow.hollow(pod) ? VoidBelowTuning.DEFAULT.hollowPitch() : 1f;
            }
            if (!wanted && volume == 0f) {
                stop();
                return;
            }
            follow();
        }

        private void follow() {
            if (!key.fromCab()) {
                x = pod.getX();
                y = pod.getY();
                z = pod.getZ();
            }
        }

        /** Ends a loop that is not playing {@link #START_GRACE_TICKS} client ticks after it was handed to the sound manager. */
        private void endIfNeverStarted(SoundManager sounds) {
            if (++ageTicks > START_GRACE_TICKS && !isStopped() && !sounds.isActive(this)) {
                stop();
            }
        }

        /** {@code stop()} is protected: the outer class ends loops through this. */
        private void end() {
            stop();
        }
    }
}
