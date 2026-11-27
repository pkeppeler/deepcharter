package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.pod.PodFuelSlot;
import io.github.pkeppeler.deepcharter.pod.PodMenu;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodTuning;
import io.github.pkeppeler.deepcharter.test.support.LogCapture;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.SettlingRooms;
import io.github.pkeppeler.deepcharter.test.support.SlotClicks;
import io.github.pkeppeler.deepcharter.test.support.UnreadableChecks;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

/**
 * Server GameTests for the pod's fuel slot (#1270, ADR 0102): its live stack is dropped when the pod leaves the level,
 * its stack survives a save and a reload, and a future version reads as an empty slot that takes nothing and is
 * written back unchanged.
 */
public class PodFuelSlotTest {
    private static final String ATTACHMENTS_KEY = "fabric:attachments";

    private static PodEntity reload(GameTestHelper helper, PodEntity pod) {
        ServerLevel level = helper.getLevel();
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        pod.saveWithoutId(output);
        pod.discard();
        Entity loaded = EntityType.create(
                        PodRegistry.POD,
                        TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), output.buildResult()),
                        level,
                        EntitySpawnReason.LOAD)
                .orElseThrow(() -> failure(helper, "the saved pod did not load"));
        return (PodEntity) loaded;
    }

    private static CompoundTag savedSlot(GameTestHelper helper, PodEntity pod) {
        TagValueOutput output = TagValueOutput.createWithContext(
                ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
        pod.saveWithoutId(output);
        return output.buildResult()
                .getCompound(ATTACHMENTS_KEY)
                .orElseThrow(() -> failure(helper, "the pod saved no attachments"))
                .getCompound(PodFuelSlot.STATE.identifier().toString())
                .orElseThrow(() -> failure(helper, "the pod saved no fuel slot"));
    }

    @GameTest
    public void aPodsLiveFuelSlotGoesWhenThePodIsDiscardedOrUnloaded(GameTestHelper helper) {
        List<String> problems = new ArrayList<>();
        for (Entity.RemovalReason reason :
                List.of(Entity.RemovalReason.DISCARDED, Entity.RemovalReason.UNLOADED_TO_CHUNK)) {
            PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
            try {
                PodFuelSlot.container(pod);
                boolean before = PodFuelSlot.hasLiveSlot(pod);
                pod.remove(reason);
                if (!before || PodFuelSlot.hasLiveSlot(pod)) {
                    problems.add(reason + ": live before " + before + ", after " + PodFuelSlot.hasLiveSlot(pod));
                }
            } finally {
                pod.discard();
            }
        }
        expect(helper, problems.isEmpty(), "a pod that leaves the level must not stay pinned: %s", problems);
        helper.succeed();
    }

    @GameTest
    public void theFuelSlotsStackSurvivesASaveAndAReload(GameTestHelper helper) {
        PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
        PodEntity loaded = null;
        try {
            // A full tank, so nothing is fed while the stack waits.
            pod.setFuel(PodTuning.DEFAULT.shell().fullFuel());
            PodFuelSlot.container(pod).setItem(0, new ItemStack(Items.COAL, 5));
            loaded = reload(helper, pod);
            ItemStack read = PodFuelSlot.container(loaded).getItem(0);
            expect(
                    helper,
                    read.is(Items.COAL) && read.getCount() == 5 && PodFuelSlot.isReadable(loaded),
                    "the fuel slot should reload with its 5 coal, holds %s",
                    read);
            helper.succeed();
        } finally {
            pod.discard();
            if (loaded != null) {
                loaded.discard();
            }
        }
    }

    @GameTest
    public void aFutureFuelSlotReadsEmptyTakesNothingAndIsWrittenBackUnchanged(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        MockPlayer mock = MockPlayers.join(
                helper, "FuelSlot" + UUID.randomUUID().toString().substring(0, 6));
        PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
        LogCapture log = LogCapture.start(PodFuelSlot.STATE.identifier().toString());
        try {
            ServerPlayer player = mock.player();
            player.setGameMode(GameType.SURVIVAL);
            expect(
                    helper,
                    Charters.found(
                                    server,
                                    player.getUUID(),
                                    "Slot " + UUID.randomUUID().toString().substring(0, 8))
                            .isEmpty(),
                    "founding a charter should succeed");
            CharterId charter = Charters.charterOfOrThrow(server, player.getUUID())
                    .orElseThrow()
                    .id();
            PodComponents.register(pod, charter);
            mock.teleportTo((ServerLevel) pod.level(), pod.position().add(2, 0, 0), 0f, 0f);
            pod.setFuel(0f);
            UnreadableChecks.makeUnreadable(pod, PodFuelSlot.STATE);
            Map<String, Runnable> paths = new LinkedHashMap<>();
            paths.put("tick", pod::tick);
            paths.put("extra mass", () -> PodEvents.extraMass(pod));
            paths.put("shift-click", () -> {
                // 2 coal on each of the two visits.
                player.getInventory().add(new ItemStack(Items.COAL, 2));
                Optional<Component> refusal = PodMenu.open(player, pod);
                if (refusal.isPresent() || !(player.containerMenu instanceof PodMenu menu)) {
                    throw failure(helper, "the pod screen should open, was refused: %s", refusal);
                }
                menu.clicked(SlotClicks.inventorySlot(menu, Items.COAL).index, 0, ContainerInput.QUICK_MOVE, player);
                menu.clicked(menu.fuelSlot().index, 0, ContainerInput.PICKUP, player);
                player.closeContainer();
            });
            UnreadableChecks.assertNoThrow(helper, "pod fuel slot", paths);
            int held = SettlingRooms.count(player.getInventory(), Items.COAL);
            int inHold = 0;
            for (int i = 0; i < pod.cargo().reach(); i++) {
                inHold += pod.cargo().stackAt(i).is(Items.COAL)
                        ? pod.cargo().stackAt(i).getCount()
                        : 0;
            }
            expect(
                    helper,
                    held + inHold == 4
                            && PodFuelSlot.container(pod).getItem(0).isEmpty()
                            && pod.fuel() == 0f
                            && !PodFuelSlot.isReadable(pod),
                    "an unreadable slot shows nothing and takes nothing: %s held, %s in the hold, slot %s, fuel %s",
                    held,
                    inHold,
                    PodFuelSlot.container(pod).getItem(0),
                    pod.fuel());
            expect(
                    helper,
                    UnreadableChecks.futureData().equals(savedSlot(helper, pod)),
                    "the unreadable fuel slot must be written back unchanged: %s",
                    savedSlot(helper, pod));
            expect(helper, log.errors().size() == 1, "the unreadable slot should be logged once: %s", log.errors());
            helper.succeed();
        } finally {
            mock.leave();
            pod.discard();
        }
    }
}
