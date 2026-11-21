package io.github.pkeppeler.deepcharter.pod;

import io.github.pkeppeler.deepcharter.sound.DeepSound;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;

/**
 * The cues of the things that hurt or block a pod (#854, the "no silent mechanics" rule): a line on the action bar of every
 * player seated in the pod that names the cause, a sound at the pod and, where it fits, particles. A gas blast and a hard landing cue
 * each event ({@link #hurtHull}); an overloaded lift cues when the pilot presses jump and the rotor has no lift to give
 * ({@link #init}). Server only: the pod and its seats are the server's, and the clients get the action bar and the sound as packets.
 */
public final class PodCues {
    /** Where a held overloaded jump last cued, and that it is still held. */
    private record Press(long lastCue) {}

    // An ephemeral throttle, not saved state (m2-rules: pod state is an attachment): a reload may repeat one cue.
    /** Per pod, only while its pilot holds an overloaded jump; weak so a discarded pod leaves it. Server thread only. */
    private static final Map<PodEntity, Press> PRESSES = new WeakHashMap<>();

    private PodCues() {}

    public static void init() {
        PodEvents.AFTER_TICK.register(PodEvents.OBSERVE, PodCues::overloaded);
    }

    /**
     * Takes {@code damage} off the pod's hull and, when it rounds to at least 1, tells every player seated in the pod what hurt it (the line is
     * {@code messageKey} with the rounded damage), and sounds and shows it at the pod. The riders are read before the damage because a hit that
     * empties the hull wrecks the pod and ejects them; the line still reaches them. A hit that rounds to 0 costs hull and says nothing.
     *
     * @param burst the particles at the pod's middle, with their count
     */
    public static void hurtHull(
            PodEntity pod, float damage, String messageKey, DeepSound sound, ParticleOptions burst, int burstCount) {
        if (!(pod.level() instanceof ServerLevel level)) {
            throw new IllegalStateException("pod cues are the server's, this pod is on a client");
        }
        List<Entity> riders = List.copyOf(pod.getPassengers());
        pod.damageHull(damage);
        int shown = Math.round(damage);
        if (shown < 1) {
            return;
        }
        Component message = Component.translatable(messageKey, shown);
        for (Entity rider : riders) {
            if (rider instanceof ServerPlayer player) {
                player.sendOverlayMessage(message);
            }
        }
        level.playSound(null, pod, sound.event(), SoundSource.NEUTRAL, 1f, 1f);
        Vec3 middle = pod.getBoundingBox().getCenter();
        level.sendParticles(burst, middle.x, middle.y, middle.z, burstCount, 0.5, 0.5, 0.5, 0.02);
    }

    private static void overloaded(PodEntity pod) {
        if (!(pod.getControllingPassenger() instanceof ServerPlayer pilot) || !PodEvents.isPowered(pod)) {
            PRESSES.remove(pod);
            return;
        }
        Input input = pilot.getLastClientInput();
        // The same lift as PodMovement.tick: the engine power that cargo and PodEvents.extraMass leave.
        float usable = PodStats.of(pod).enginePower() - PodEvents.extraMass(pod);
        if (!input.jump() || usable - pod.cargoMass() > 0f) {
            PRESSES.remove(pod);
            return;
        }
        long now = pod.level().getGameTime();
        Press held = PRESSES.get(pod);
        if (held != null && now - held.lastCue() < PodCuesTuning.DEFAULT.overloadRepeatTicks()) {
            return;
        }
        PRESSES.put(pod, new Press(now));
        // Dumping cargo only helps when the engine could lift something without it: towing alone can take all the lift.
        boolean dumpHelps = pod.cargoMass() > 0f && usable > 0f;
        pilot.sendOverlayMessage(
                Component.translatable(dumpHelps ? "deepcharter.pod.overloaded" : "deepcharter.pod.overloaded_load"));
        ((ServerLevel) pod.level()).playSound(null, pod, DeepSound.CARGO_FULL.event(), SoundSource.NEUTRAL, 1f, 1f);
    }
}
