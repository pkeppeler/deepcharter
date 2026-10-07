package io.github.pkeppeler.deepcharter.client.pod;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodFuel;

/** Beeps for the pilot of a pod at or below the low-fuel threshold; faster when nearly dry. Silent once stranded (powered off). */
public final class LowFuelBeep {
	private static final int SLOW_INTERVAL_TICKS = 30;
	private static final int FAST_INTERVAL_TICKS = 10;
	private static final float URGENT_PERCENT = 6f;
	private static final float PITCH = 1.5f;

	private static int ticksSinceBeep;

	private LowFuelBeep() {
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(LowFuelBeep::tick);
	}

	private static void tick(Minecraft client) {
		if (client.player == null || !(client.player.getVehicle() instanceof PodEntity pod)
				|| pod.stranded() || !PodFuel.isLow(pod.fuel())) {
			ticksSinceBeep = SLOW_INTERVAL_TICKS;
			return;
		}
		int interval = pod.fuel() <= URGENT_PERCENT ? FAST_INTERVAL_TICKS : SLOW_INTERVAL_TICKS;
		if (++ticksSinceBeep >= interval) {
			ticksSinceBeep = 0;
			client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, PITCH));
		}
	}
}
