package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.outpost.OutpostSettling;
import io.github.pkeppeler.deepcharter.outpost.OutpostVisits.Ledger;
import io.github.pkeppeler.deepcharter.outpost.SettlingTuning;
import io.github.pkeppeler.deepcharter.test.support.CampaignEnvironments;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.SettlingRooms;
import io.github.pkeppeler.deepcharter.test.support.SettlingRooms.Room;
import io.github.pkeppeler.deepcharter.test.support.UnloadedChunk;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Items;

/**
 * #1004: an outpost whose zone is not loaded keeps its visits pending, never loads a chunk to meet them, and meets them once the zone
 * loads again. A real unload: the chunk stops being forced and the test waits until it is gone ({@link UnloadedChunk}).
 */
public class OutpostSettlingReloadTest {
    private static final int CYCLE_TICKS = 2 * FarChunks.AWAIT_BUDGET_TICKS + 400;

    @GameTest(environment = CampaignEnvironments.B, maxTicks = CYCLE_TICKS)
    public void anUnloadedZoneKeepsItsVisitsPendingAndMeetsThemAfterItReloads(GameTestHelper helper) {
        Room room = SettlingRooms.stage(helper, 12, SettlingRooms.nobody());
        SettlingRooms.stock(room, Items.COAL, 64);
        SettlingTuning tuning = SettlingRooms.SURE_RETAINED;
        Ledger[] ledger = {null};
        BlockPos far = room.floor();
        UnloadedChunk.cycle(
                helper,
                room.level(),
                far,
                new UnloadedChunk.Stages(
                        () -> {
                            expect(
                                    helper,
                                    OutpostSettling.isLoaded(room.level(), room.zone()),
                                    "the zone should be loaded while the chunk is forced");
                            // A look while loaded: it surveys what the outpost has to lose and meets nothing.
                            ledger[0] = OutpostSettling.settle(
                                    room.level(), room.outpost(), room.ledger(), SettlingRooms.after(0), tuning);
                            expect(
                                    helper,
                                    ledger[0].survey().isPresent()
                                            && ledger[0].pending().isEmpty(),
                                    "the survey is kept: %s",
                                    ledger[0]);
                        },
                        () -> {
                            expect(
                                    helper,
                                    !OutpostSettling.isLoaded(room.level(), room.zone()),
                                    "the zone should be unloaded now");
                            ledger[0] = OutpostSettling.settle(
                                    room.level(), room.outpost(), ledger[0], SettlingRooms.after(3), tuning);
                            expect(
                                    helper,
                                    ledger[0].pending().retained() == 3
                                            && ledger[0].unread().isEmpty(),
                                    "3 visits rolled from the kept survey wait, none is met: %s",
                                    ledger[0]);
                            expect(
                                    helper,
                                    ledger[0].settledTo() == 3,
                                    "the windows are settled all the same: %s",
                                    ledger[0]);
                            expect(
                                    helper,
                                    !OutpostSettling.isLoaded(room.level(), room.zone()),
                                    "settling must never load a chunk of the zone");
                        },
                        () -> OutpostSettling.isLoaded(room.level(), room.zone())
                                && room.level().getBlockEntity(room.barrel()) != null,
                        () -> "the outpost's zone",
                        () -> {
                            // Nothing new has passed: the pending visits are met, from the reloaded barrel.
                            Ledger met = OutpostSettling.settle(
                                    room.level(), room.outpost(), ledger[0], SettlingRooms.after(3), tuning);
                            expect(helper, met.pending().isEmpty(), "the visits should be met now: %s", met);
                            expect(
                                    helper,
                                    met.unread().visits() == 3
                                            && met.unread()
                                                    .taken()
                                                    .equals(Map.of(BuiltInRegistries.ITEM.getKey(Items.COAL), 37)),
                                    "3 visits took 37 coal: %s",
                                    met.unread());
                            int left = SettlingRooms.count(room.storage(), Items.COAL);
                            expect(
                                    helper,
                                    left == 27,
                                    "27 of 64 coal should be left in the reloaded barrel, %s are",
                                    left);
                        }));
    }
}
