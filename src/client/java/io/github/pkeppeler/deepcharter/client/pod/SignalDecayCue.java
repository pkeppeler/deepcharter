package io.github.pkeppeler.deepcharter.client.pod;

import io.github.pkeppeler.deepcharter.client.theme.CockpitLook;
import io.github.pkeppeler.deepcharter.client.theme.CockpitLook.DecayLook;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodSeat;
import io.github.pkeppeler.deepcharter.pod.SignalDecay;
import io.github.pkeppeler.deepcharter.pod.SignalDecay.Instrument;
import io.github.pkeppeler.deepcharter.sound.DeepSound;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;

/**
 * What the signal decay (#502) sounds like to the pilot, which is the one thing the decay does not take away: a crackle on the line that comes faster the deeper the pod
 * is, a heartbeat when the cab goes dark, and, while the decay is on and the hull is low, the hull alarm. A pilot who has lost every readout still hears that the hull is
 * going and that something is on the line.
 */
public final class SignalDecayCue {
    private static final float QUIET = 0.4f;
    private static final float LOW_PITCH = 0.7f;
    private static final float HIGH_PITCH = 1.1f;

    private static int sinceCrackle;
    private static int sinceAlarm;
    private static boolean dark;

    private SignalDecayCue() {}

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(SignalDecayCue::tick);
    }

    private static void tick(Minecraft client) {
        if (client.player == null
                || client.level == null
                || !(client.player.getVehicle() instanceof PodEntity pod)
                || !PodSeat.showsPodStatus(pod, client.player)) {
            reset();
            return;
        }
        double intensity = SignalDecay.intensity(pod);
        if (intensity <= 0) {
            reset();
            return;
        }
        CockpitLook look = CockpitLook.current();
        DecayLook decay = look.decay();
        long seed = pod.getUUID().getLeastSignificantBits();
        if (intensity >= 1 && !dark) {
            client.getSoundManager().play(SimpleSoundInstance.forUI(DeepSound.POD_SIGNAL_LOST.event(), 1.0f));
        }
        dark = intensity >= 1;
        double interval = decay.cueSlowTicks() + (decay.cueFastTicks() - decay.cueSlowTicks()) * intensity;
        // The gap varies from crackle to crackle, the same way for every pilot at one tick: from half to one and a half
        // intervals.
        if (++sinceCrackle
                >= interval * (0.5 + SignalDecay.jump(Instrument.SCANNER, seed, client.level.getGameTime()))) {
            sinceCrackle = 0;
            client.getSoundManager()
                    .play(SimpleSoundInstance.forUI(
                            DeepSound.POD_SIGNAL_CRACKLE.event(),
                            (float) (LOW_PITCH + (HIGH_PITCH - LOW_PITCH) * intensity),
                            (float) (QUIET + (1 - QUIET) * intensity)));
        }
        if (pod.maxHull() > 0
                && pod.hull() / pod.maxHull() <= look.hullLowFraction()
                && ++sinceAlarm >= decay.alarmTicks()) {
            sinceAlarm = 0;
            client.getSoundManager().play(SimpleSoundInstance.forUI(DeepSound.ALARM_HULL_CRITICAL.event(), 1.0f));
        }
    }

    private static void reset() {
        sinceCrackle = 0;
        sinceAlarm = 0;
        dark = false;
    }
}
