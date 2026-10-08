package io.github.pkeppeler.deepcharter.test.support;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import io.github.pkeppeler.deepcharter.charter.CharterData;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.handbook.HandbookProgressData;
import io.github.pkeppeler.deepcharter.handbook.NotesData;
import io.github.pkeppeler.deepcharter.hangar.HangarData;
import io.github.pkeppeler.deepcharter.market.WorkOrderData;
import io.github.pkeppeler.deepcharter.pod.PodLightLedger;
import io.github.pkeppeler.deepcharter.pod.Serials;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.transmission.TransmissionData;

/**
 * Fails the server GameTest run when a test leaves the world's saved data swapped across a tick boundary.
 *
 * <p>GameTests in a batch interleave only between ticks. A test that swaps a world-global record
 * ({@code WorldData.with(server, TYPE, fresh, body)}) and puts the world's back in a {@code finally} on the same tick, with no wait in
 * between, is invisible to every other test. A swap that outlives its tick is seen by the tests that run in it, and makes the
 * suite depend on the order. No test needs one: a test that must change a record over many ticks sets it from a tick listener
 * that restores it in the same tick (see the poll test in {@code HandbookChaptersOneToFiveTest}).
 *
 * <p>At the start of every tick this compares each record with the one the previous tick started with. Only the dedicated GameTest
 * server is watched: the client suite's integrated server is not.
 */
public final class WorldDataGuard implements ModInitializer {
	private static final List<SavedDataType<?>> WATCHED = List.of(CharterData.TYPE, ColonySite.TYPE, HandbookProgressData.TYPE,
			NotesData.TYPE, HangarData.TYPE, WorkOrderData.TYPE, PodLightLedger.TYPE, Serials.TYPE, RepairState.TYPE, TransmissionData.TYPE);
	private static final Map<SavedDataType<?>, SavedData> SEEN = new HashMap<>();

	@Override
	public void onInitialize() {
		ServerTickEvents.START_SERVER_TICK.register(WorldDataGuard::check);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			SEEN.clear();
			WorldData.forgetSwappers();
		});
	}

	private static void check(MinecraftServer server) {
		if (!server.isDedicatedServer()) {
			return;
		}
		for (SavedDataType<?> type : WATCHED) {
			SavedData now = server.getDataStorage().computeIfAbsent(type);
			SavedData before = SEEN.put(type, now);
			if (before != null && before != now) {
				throw new IllegalStateException("The world's " + type.id() + " was swapped across a tick; last swapped by "
						+ WorldData.lastSwapper(type).orElse("a test that does not use WorldData") + ". Swap through WorldData, which restores it in a finally"
						+ " on the tick that swapped it.");
			}
		}
	}
}
