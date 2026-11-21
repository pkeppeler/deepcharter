package io.github.pkeppeler.deepcharter.pod;

import io.github.pkeppeler.deepcharter.sound.DeepSound;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Input;

/**
 * The cues of the things that hurt or block a pod (#854, the "no silent mechanics" rule): a line on the action bar of every
 * player seated in the pod that names the cause, a sound at the pod and, where it fits, particles. A gas blast and a hard landing cue
 * each event ({@link #hullHit}); an overloaded lift cues when the pilot presses jump and the rotor has no lift to give
 * ({@link #init}). Server only: the pod and its seats are the server's, and the clients get the action bar and the sound as packets.
 */
public final class PodCues {
    /** Where a held overloaded jump last cued, and that it is still held. */
    private record Press(long lastCue) {}

    /** Per pod, only while its pilot holds an overloaded jump; weak so a discarded pod leaves it. Server thread only. */
    private static final Map<PodEntity, Press> PRESSES = new WeakHashMap<>();

    private PodCues() {}

    public static void init() {
        PodEvents.AFTER_TICK.register(PodEvents.OBSERVE, PodCues::overloaded);
    }

    /**
     * Tells every player seated in {@code pod} what hurt its hull, and sounds and shows it at the pod. Call it only when the hull took damage.
     *
     * @param burst the particles at the pod's middle, with their count
     */
    public static void hullHit(
            PodEntity pod, Component message, DeepSound sound, ParticleOptions burst, int burstCount) {
        if (!(pod.level() instanceof ServerLevel level)) {
            throw new IllegalStateException("pod cues are the server's, this pod is on a client");
        }
        for (var rider : pod.getPassengers()) {
            if (rider instanceof ServerPlayer player) {
                player.sendOverlayMessage(message);
            }
        }
        level.playSound(null, pod, sound.event(), SoundSource.NEUTRAL, 1f, 1f);
        var middle = pod.getBoundingBox().getCenter();
        level.sendParticles(burst, middle.x, middle.y, middle.z, burstCount, 0.5, 0.5, 0.5, 0.02);
    }

    private static void overloaded(PodEntity pod) {
        if (!(pod.getControllingPassenger() instanceof ServerPlayer pilot) || !PodEvents.isPowered(pod)) {
            PRESSES.remove(pod);
            return;
        }
        Input input = pilot.getLastClientInput();
        if (!input.jump() || PodMovement.lift(pod, PodStats.of(pod)) > 0f) {
            PRESSES.remove(pod);
            return;
        }
        long now = pod.level().getGameTime();
        Press held = PRESSES.get(pod);
        if (held != null && now - held.lastCue() < PodCuesTuning.DEFAULT.overloadRepeatTicks()) {
            return;
        }
        PRESSES.put(pod, new Press(now));
        pilot.sendOverlayMessage(Component.translatable("deepcharter.pod.overloaded"));
        ((ServerLevel) pod.level()).playSound(null, pod, DeepSound.CARGO_FULL.event(), SoundSource.NEUTRAL, 1f, 1f);
    }
}
