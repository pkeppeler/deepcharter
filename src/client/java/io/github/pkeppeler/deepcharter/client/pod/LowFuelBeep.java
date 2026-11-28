package io.github.pkeppeler.deepcharter.client.pod;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodFuel;
import io.github.pkeppeler.deepcharter.pod.PodSeat;
import io.github.pkeppeler.deepcharter.pod.PodTuning;
import io.github.pkeppeler.deepcharter.sound.DeepSound;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;

/**
 * Sounds the cockpit alarm for the pilot of a pod at or below the low-fuel threshold; the harsher, faster klaxon when
 * nearly dry. Silent once stranded (powered off).
 */
public final class LowFuelBeep {
    private static final int SLOW_INTERVAL_TICKS = 30;
    private static final int FAST_INTERVAL_TICKS = 10;

    private static int ticksSinceBeep;

    private LowFuelBeep() {}

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(LowFuelBeep::tick);
    }

    private static void tick(Minecraft client) {
        if (client.player == null
                || !(client.player.getVehicle() instanceof PodEntity pod)
                || !PodSeat.showsPodStatus(pod, client.player)
                || pod.stranded()
                || !PodFuel.isLow(pod.fuel())) {
            ticksSinceBeep = SLOW_INTERVAL_TICKS;
            return;
        }
        boolean urgent = pod.fuel() <= PodTuning.DEFAULT.fuel().urgentFuelPercent();
        if (++ticksSinceBeep >= (urgent ? FAST_INTERVAL_TICKS : SLOW_INTERVAL_TICKS)) {
            ticksSinceBeep = 0;
            DeepSound alarm = urgent ? DeepSound.ALARM_FUEL_CRITICAL : DeepSound.FUEL_LOW;
            client.getSoundManager().play(SimpleSoundInstance.forUI(alarm.event(), 1.0f));
        }
    }
}
