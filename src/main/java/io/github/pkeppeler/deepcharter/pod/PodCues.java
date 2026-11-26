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

/** Cues for what hurts or blocks a pod (#854, "no silent mechanics"): action bar line, sound and particles. Server only. */
public final class PodCues {
    // An ephemeral throttle, not saved state (m2-rules: pod state is an attachment): a reload may repeat one cue.
    /** Tick of the last cue per pod, only while its pilot holds an overloaded jump. Server thread only. */
    private static final Map<PodEntity, Long> LAST_CUE = new WeakHashMap<>();

    private PodCues() {}

    public static void init() {
        PodEvents.AFTER_TICK.register(PodEvents.OBSERVE, PodCues::overloaded);
    }

    /**
     * Damages the hull and, when the damage rounds to 1 or more, tells every seated player ({@code messageKey} with
     * the rounded damage) and sounds and shows it at the pod. Riders are read first: a lethal hit ejects them.
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
            LAST_CUE.remove(pod);
            return;
        }
        Input input = pilot.getLastClientInput();
        // The load at which PodMovement.tick's thrust no longer beats gravity, as the dump hint reads it.
        double limit = PodDump.climbLimit(pod);
        float extra = PodEvents.extraMass(pod);
        if (!input.jump() || pod.cargoMass() + extra < limit) {
            LAST_CUE.remove(pod);
            return;
        }
        long now = pod.level().getGameTime();
        Long last = LAST_CUE.get(pod);
        if (last != null && now - last < PodCuesTuning.DEFAULT.overloadRepeatTicks()) {
            return;
        }
        LAST_CUE.put(pod, now);
        // Dumping cargo only helps when the pod could climb without it: towing alone can hold it down.
        boolean dumpHelps = pod.cargoMass() > 0f && extra < limit;
        pilot.sendOverlayMessage(
                dumpHelps
                        ? Component.translatable("deepcharter.pod.overloaded", Component.keybind(PodDump.KEY))
                        : Component.translatable("deepcharter.pod.overloaded_load"));
        ((ServerLevel) pod.level()).playSound(null, pod, DeepSound.CARGO_FULL.event(), SoundSource.NEUTRAL, 1f, 1f);
    }
}
