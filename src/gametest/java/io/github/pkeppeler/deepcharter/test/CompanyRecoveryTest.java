package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.fuel.ReserveTank;
import io.github.pkeppeler.deepcharter.ore.OreCargoMenu;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodTowing;
import io.github.pkeppeler.deepcharter.repair.CompanyRecovery;
import io.github.pkeppeler.deepcharter.repair.RecoveryTuning;
import io.github.pkeppeler.deepcharter.repair.RepairTuning;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.SecondSeat;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import io.github.pkeppeler.deepcharter.test.support.UnreadableChecks;
import io.github.pkeppeler.deepcharter.test.support.UseEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The Company recovery of a stranded pod (#963, ADR 0073). A pilot runs dry at the bottom of a shaft in layer 1 or 2, the pod strands by its
 * own fuel running out, and the crew winches it home after the delay for a fee. Most cases fast-forward the wait by moving the clock back, so a
 * test does not sit out a minute; one case waits the real {@link RecoveryTuning#delayTicks} out.
 */
public class CompanyRecoveryTest {
    private static final int Z = 12_000;
    private static final RecoveryTuning TUNING = RecoveryTuning.DEFAULT;
    private static final int HEIGHT_IN_LAYER = 40;
    /**
     * A batch of its own: the blocked landing floods the colony's pad and the world spawn, which every colony test of a
     * shared batch reads.
     */
    private static final String ALONE = "deepcharter-test:alone";

    private static final int LONG_RUN = FarChunks.AWAIT_BUDGET_TICKS + 4000;
    private static final int SHORT_RUN = FarChunks.AWAIT_BUDGET_TICKS + 400;

    /** One stranded pod: its pilot, its charter if it has one, and the pod once the far chunk ticks entities. */
    private static final class Scene {
        final MinecraftServer server;
        final ServerLevel layer;
        final BlockPos at;
        final MockPlayer pilot;
        final @Nullable Charter charter;

        @Nullable
        PodEntity pod;

        Scene(GameTestHelper helper, int x, int layerNumber, MockPlayer pilot, @Nullable Charter charter) {
            this.server = helper.getLevel().getServer();
            this.layer = TestLayers.level(server, layerNumber);
            this.at = BlockPos.containing(x, TestLayers.worldY(layerNumber, HEIGHT_IN_LAYER), Z);
            this.pilot = pilot;
            this.charter = charter;
        }

        /** The pod the pilot rides now (a haul across a dimension makes a new entity), or the first pod when nobody rides. */
        PodEntity current() {
            return pilot.player().getVehicle() instanceof PodEntity riding
                    ? riding
                    : Objects.requireNonNull(pod, "pod");
        }

        boolean arrived() {
            return pod != null;
        }

        long account() {
            return Charters.charterOfOrThrow(server, pilot.player().getUUID())
                    .orElseThrow()
                    .account();
        }

        int spilledOre() {
            int spilled = 0;
            for (ItemEntity drop : layer.getEntitiesOfClass(ItemEntity.class, new AABB(at).inflate(6))) {
                if (OreRegistry.typeOf(drop.getItem()).equals(Optional.of(OreType.IRONIUM))) {
                    spilled += drop.getItem().getCount();
                }
            }
            return spilled;
        }

        boolean hauled() {
            return arrived() && current().level() != layer;
        }
    }

    /** How a scene is built; the defaults are a pod owned by the pilot's charter, piloted, and stranded. */
    private static final class Plan {
        int layerNumber = 1;
        long balance = 0;
        boolean hasCharter = true;
        boolean owned = true;
        boolean mounted = true;
        boolean secondSeat;
        int ore;
        boolean fastForward = true;
        boolean strand = true;
        Consumer<PodEntity> mutate = pod -> {};
    }

    /** Builds the scene from the test method (it registers the tick body). */
    private static Scene scene(GameTestHelper helper, int x, Plan plan, MockPlayer @Nullable [] passenger) {
        MinecraftServer server = helper.getLevel().getServer();
        MockPlayer pilot = MockPlayers.join(helper, "recovery-" + x);
        Charter charter = null;
        if (plan.hasCharter) {
            Charters.found(
                            server,
                            pilot.player().getUUID(),
                            "Recovery " + UUID.randomUUID().toString().substring(0, 8))
                    .ifPresent(refusal -> {
                        throw helper.assertionException("founding the charter: %s", refusal);
                    });
            charter =
                    Charters.charterOfOrThrow(server, pilot.player().getUUID()).orElseThrow();
            if (plan.balance > 0) {
                Charters.deposit(server, charter.id(), plan.balance);
            }
        }
        Scene scene = new Scene(helper, x, plan.layerNumber, pilot, charter);
        if (passenger != null) {
            passenger[0] = MockPlayers.join(helper, "recovery-passenger-" + x);
        }
        TestTicks.each(helper, () -> {
            if (scene.pod == null || !plan.fastForward) {
                return;
            }
            PodEntity current = scene.current();
            if (current.level() == scene.layer && current.stranded()) {
                Versioned.readable(current, CompanyRecovery.STATE)
                        .filter(CompanyRecovery.State::waiting)
                        .filter(state -> current.level().getGameTime() - state.since() < TUNING.delayTicks())
                        .ifPresent(state -> Versioned.modifyOrThrow(
                                current,
                                CompanyRecovery.STATE,
                                old -> new CompanyRecovery.State(state.since() - TUNING.delayTicks())));
            }
        });
        FarChunks.awaitEntityTicking(helper, scene.layer, scene.at, () -> {
            pilot.teleportTo(scene.layer, Vec3.atCenterOf(scene.at), 0, 0);
            pilot.player().setGameMode(GameType.SURVIVAL);
            // A room for the pod: left in solid rock the pilot would suffocate over a long wait.
            RoomCarver.carve(
                    scene.layer,
                    scene.at.offset(-2, -2, -2),
                    scene.at.offset(2, -1, 2),
                    Blocks.STONE.defaultBlockState());
            RoomCarver.carve(
                    scene.layer, scene.at.offset(-2, 0, -2), scene.at.offset(2, 4, 2), Blocks.AIR.defaultBlockState());
            PodEntity created = TestEntities.create(PodRegistry.POD, scene.layer);
            created.setPos(scene.at.getX(), scene.at.getY(), scene.at.getZ());
            scene.layer.addFreshEntity(created);
            if (scene.charter != null && plan.owned) {
                PodComponents.register(created, scene.charter.id());
            }
            for (int i = 0; i < plan.ore; i++) {
                created.cargo().tryAdd(created, OreRegistry.stack(OreType.IRONIUM));
            }
            if (plan.secondSeat) {
                SecondSeat.add(created);
            }
            plan.mutate.accept(created);
            if (plan.mounted && !pilot.player().startRiding(created, true, false)) {
                throw helper.assertionException("the pilot could not board the pod");
            }
            if (passenger != null && passenger[0] != null) {
                passenger[0].teleportTo(scene.layer, Vec3.atCenterOf(scene.at), 0, 0);
                if (!passenger[0].player().startRiding(created, true, false)) {
                    throw helper.assertionException("the passenger could not board the pod");
                }
            }
            if (plan.strand) {
                // The last sliver of fuel: the pod burns it and strands itself, as a real tank runs dry.
                created.setFuel(0.01f);
            }
            scene.pod = created;
        });
        return scene;
    }

    private static boolean told(MockPlayer pilot, String key, Object... args) {
        String wanted = Component.translatable(key, args).getString();
        return pilot.actionBarMessages().stream()
                .anyMatch(message -> message.getString().equals(wanted));
    }

    private static void verify(GameTestHelper helper, boolean ok, String what, Object... args) {
        if (!ok) {
            throw helper.assertionException(what, args);
        }
    }

    private static boolean atTheColony(Scene scene) {
        return scene.current()
                .level()
                .dimension()
                .equals(scene.server.overworld().dimension());
    }

    private static boolean waiting(PodEntity pod) {
        return Versioned.readable(pod, CompanyRecovery.STATE)
                .map(CompanyRecovery.State::waiting)
                .orElse(false);
    }

    private static Scene run(GameTestHelper helper, int x, Plan plan) {
        return scene(helper, x, plan, null);
    }

    /** Waits for the hauled pod, then runs {@code check}. */
    private static void whenHauled(GameTestHelper helper, Scene scene, Consumer<PodEntity> check) {
        helper.succeedWhen(() -> {
            if (!scene.hauled()) {
                throw helper.assertionException(
                        "the pod has not been hauled out of its layer yet; the pilot was told %s",
                        scene.pilot.actionBarMessages().stream()
                                .map(Component::getString)
                                .toList());
            }
            check.accept(scene.current());
            verify(helper, !scene.current().stranded(), "the pod is still stranded at home");
        });
    }

    private static float hullLostBy(long unpaid) {
        return unpaid / (float) RepairTuning.DEFAULT.repairCostPerHp();
    }

    // The fee, the hull and the money

    @GameTest(maxTicks = LONG_RUN)
    public void aDryBrokePilotAloneAtTheBottomIsWinchedHomeAndTheHullPaysTheFee(GameTestHelper helper) {
        Scene scene = run(helper, 12_000, new Plan());
        whenHauled(helper, scene, home -> {
            long fee = TUNING.feeAt(1);
            verify(helper, scene.account() == 0, "no debt: the account stays at 0, is %d", scene.account());
            verify(
                    helper,
                    Math.abs(home.hull() - (home.maxHull() - hullLostBy(fee))) < 0.01f,
                    "the unpaid $%d costs %s hull at the repair station's price, the pod has %s of %s",
                    fee,
                    hullLostBy(fee),
                    home.hull(),
                    home.maxHull());
            verify(helper, home.fuel() > 0f, "the crew leaves a splash of fuel, the tank holds %s", home.fuel());
            verify(
                    helper,
                    atTheColony(scene),
                    "the pod is at the colony, not in %s",
                    home.level().dimension());
            verify(
                    helper,
                    told(scene.pilot, "message.deepcharter.repair.recovery_called", TUNING.delayTicks() / 20, fee),
                    "the pilot is told the crew is coming, and what it costs");
            verify(
                    helper,
                    told(scene.pilot, "message.deepcharter.repair.recovery_short", 0L, fee),
                    "the pilot is told what the crew took");
        });
    }

    @GameTest(maxTicks = LONG_RUN)
    public void aPilotWithMoneyPaysTheFeeInFullAndTheOreStaysBehindForHalfAnHour(GameTestHelper helper) {
        Plan plan = new Plan();
        plan.balance = 200;
        plan.ore = 2;
        Scene scene = run(helper, 12_100, plan);
        whenHauled(helper, scene, home -> {
            verify(
                    helper,
                    scene.account() == 200 - TUNING.feeAt(1),
                    "the fee comes off the account: $%d left",
                    scene.account());
            verify(helper, home.hull() == home.maxHull(), "a paid fee costs no hull, the pod has %s", home.hull());
            verify(helper, home.cargo().entries().isEmpty(), "nothing mined is winched home");
            verify(helper, scene.spilledOre() == 2, "the ore stays where the pod was: %d ironium", scene.spilledOre());
            List<ItemEntity> drops = scene.layer.getEntitiesOfClass(ItemEntity.class, new AABB(scene.at).inflate(6));
            verify(
                    helper,
                    !drops.isEmpty() && drops.stream().allMatch(drop -> drop.getAge() < 0),
                    "the spilled ore lies longer than vanilla's five minutes, ages %s",
                    drops.stream().map(ItemEntity::getAge).toList());
            verify(
                    helper,
                    told(scene.pilot, "message.deepcharter.repair.recovery_done", TUNING.feeAt(1)),
                    "the pilot is told the fee was paid in full");
        });
    }

    @GameTest(maxTicks = LONG_RUN)
    public void anAccountShortOfTheFeePaysWhatItHoldsAndTheHullPaysTheRest(GameTestHelper helper) {
        long balance = TUNING.feeAt(1) / 5;
        Plan plan = new Plan();
        plan.balance = balance;
        Scene scene = run(helper, 12_200, plan);
        whenHauled(helper, scene, home -> {
            verify(helper, scene.account() == 0, "the Company takes all $%d, $%d left", balance, scene.account());
            float wanted = home.maxHull() - hullLostBy(TUNING.feeAt(1) - balance);
            verify(helper, Math.abs(home.hull() - wanted) < 0.01f, "the hull should be %s, is %s", wanted, home.hull());
        });
    }

    @GameTest(maxTicks = LONG_RUN)
    public void aDeepStrandingCostsMoreAndTheHullNeverFallsUnderTheFloor(GameTestHelper helper) {
        Plan plan = new Plan();
        plan.layerNumber = 2;
        plan.mutate = pod -> pod.setHull(40f);
        Scene scene = run(helper, 12_250, plan);
        whenHauled(helper, scene, home -> {
            verify(helper, TUNING.feeAt(2) == 2 * TUNING.feeAt(1), "layer 2 costs twice layer 1");
            // $60 would take 60 hull from a pod with 40: the rescue stops at the floor and does not wreck it.
            verify(
                    helper,
                    home.hull() == TUNING.minHull() && home.hull() > 0f,
                    "the hull stops at the floor of %s, is %s",
                    TUNING.minHull(),
                    home.hull());
        });
    }

    @GameTest(maxTicks = LONG_RUN)
    public void anUnownedPodIsPaidForByThePilotsCharter(GameTestHelper helper) {
        Plan plan = new Plan();
        plan.owned = false;
        plan.balance = 100;
        Scene scene = run(helper, 12_300, plan);
        whenHauled(helper, scene, home -> {
            verify(helper, PodComponents.registration(home).isEmpty(), "the pod is unowned");
            verify(
                    helper,
                    scene.account() == 100 - TUNING.feeAt(1),
                    "the pilot's charter pays: $%d left",
                    scene.account());
            verify(helper, home.hull() == home.maxHull(), "a paid fee costs no hull");
        });
    }

    @GameTest(maxTicks = LONG_RUN)
    public void aPassengerIsHauledWithThePilot(GameTestHelper helper) {
        Plan plan = new Plan();
        plan.secondSeat = true;
        MockPlayer[] passenger = new MockPlayer[1];
        Scene scene = scene(helper, 12_350, plan, passenger);
        whenHauled(helper, scene, home -> {
            ServerPlayer second = passenger[0].player();
            verify(
                    helper,
                    second.level() == home.level() && second.position().distanceTo(home.position()) < 4.0,
                    "the passenger arrives with the pod, is at %s in %s",
                    second.position(),
                    second.level().dimension());
        });
    }

    @GameTest(maxTicks = LONG_RUN)
    public void aSeatedPilotCannotMoveTheBaysOreOutDuringTheWait(GameTestHelper helper) {
        Plan plan = new Plan();
        plan.ore = 2;
        plan.fastForward = false;
        Scene scene = run(helper, 12_380, plan);
        boolean[] tried = {false};
        helper.succeedWhen(() -> {
            if (!scene.arrived() || !waiting(scene.current())) {
                throw helper.assertionException("waiting for the clock to start");
            }
            ServerPlayer player = scene.pilot.player();
            OreCargoMenu.open(player, scene.current());
            tried[0] = true;
            ItemStack moved = player.containerMenu.quickMoveStack(player, 0);
            verify(helper, moved.isEmpty(), "shift-clicking the bay moved %s", moved);
            verify(helper, scene.current().cargo().count(OreType.IRONIUM) == 2, "the bay still holds its ore");
            verify(helper, tried[0], "the menu was tried");
        });
    }

    // Who the crew waits for

    private static void neverWaits(GameTestHelper helper, int x, Plan plan, @Nullable Consumer<Scene> later) {
        Scene scene = run(helper, x, plan);
        long[] since = {-1};
        helper.succeedWhen(() -> {
            if (!scene.arrived()) {
                throw helper.assertionException("waiting for the pod to arrive");
            }
            if (since[0] < 0) {
                since[0] = scene.layer.getGameTime();
            }
            if (scene.layer.getGameTime() - since[0] < 40) {
                throw helper.assertionException("waiting for the pod to tick");
            }
            if (later != null) {
                later.accept(scene);
            }
            PodEntity pod = scene.current();
            verify(helper, pod.stranded() || pod.hull() <= 0f, "the pod is stranded, or a wreck");
            verify(
                    helper,
                    !waiting(pod),
                    "the crew is not waiting for this pod: hull %s, stranded %s, pilot %s",
                    pod.hull(),
                    pod.stranded(),
                    String.valueOf(pod.getControllingPassenger()));
            // A wreck is the game's to move (it may be replaced); every other case leaves the pod where it is.
            verify(helper, later != null || (pod.level() == scene.layer && pod.isAlive()), "the pod stays where it is");
        });
    }

    @GameTest(maxTicks = SHORT_RUN)
    public void aWreckWaitsForNoCrew(GameTestHelper helper) {
        Plan plan = new Plan();
        plan.mutate = pod -> {};
        neverWaits(helper, 12_400, plan, scene -> {
            if (scene.current().hull() > 0f) {
                scene.current().setHull(0f);
            }
        });
    }

    @GameTest(maxTicks = SHORT_RUN)
    public void aTowedPodWaitsForNoCrew(GameTestHelper helper) {
        Plan plan = new Plan();
        plan.mutate = pod -> Versioned.modifyOrThrow(
                pod, PodTowing.STATE, old -> new PodTowing.State(Optional.of(UUID.randomUUID())));
        neverWaits(helper, 12_450, plan, null);
    }

    @GameTest(maxTicks = SHORT_RUN)
    public void aPodWithNoPilotWaitsForNoCrew(GameTestHelper helper) {
        Plan plan = new Plan();
        plan.mounted = false;
        neverWaits(helper, 12_500, plan, null);
    }

    @GameTest(maxTicks = LONG_RUN)
    public void aPilotWhoLeavesMidCountdownStopsTheClock(GameTestHelper helper) {
        Plan plan = new Plan();
        plan.fastForward = false;
        Scene scene = run(helper, 12_550, plan);
        boolean[] left = {false};
        TestTicks.each(helper, () -> {
            if (scene.arrived() && !left[0] && waiting(scene.current())) {
                left[0] = true;
                scene.pilot.player().stopRiding();
            }
        });
        helper.succeedWhen(() -> {
            if (!left[0] || scene.pod == null || scene.pod.tickCount < 5) {
                throw helper.assertionException("waiting for the pilot to leave");
            }
            verify(helper, !waiting(scene.pod), "the clock stops when the pilot leaves");
            verify(helper, scene.pod.level() == scene.layer, "the pod stays");
        });
    }

    // Refuelling, through the real paths

    @GameTest(maxTicks = LONG_RUN)
    public void coalFromThePilotCallsTheCrewOffThroughTheRealRefuel(GameTestHelper helper) {
        Plan plan = new Plan();
        plan.fastForward = false;
        Scene scene = run(helper, 12_600, plan);
        boolean[] refuelled = {false};
        TestTicks.each(helper, () -> {
            if (scene.arrived() && !refuelled[0] && waiting(scene.current())) {
                ServerPlayer player = scene.pilot.player();
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COAL, 3));
                UseEntity.on(player, scene.layer, InteractionHand.MAIN_HAND, scene.current());
                refuelled[0] = true;
            }
        });
        helper.succeedWhen(() -> {
            if (!refuelled[0] || scene.current().tickCount < 5) {
                throw helper.assertionException("waiting for the pod to strand and be fuelled");
            }
            PodEntity pod = scene.current();
            verify(helper, !pod.stranded() && pod.fuel() > 0f, "coal restarts the pod");
            verify(helper, !waiting(pod), "a pod with fuel again is not waiting for the crew");
            verify(helper, pod.level() == scene.layer, "the pod stays in its layer");
        });
    }

    @GameTest(maxTicks = LONG_RUN)
    public void aReserveTankCallsTheCrewOff(GameTestHelper helper) {
        Plan plan = new Plan();
        plan.fastForward = false;
        Scene scene = run(helper, 12_650, plan);
        boolean[] fitted = {false};
        TestTicks.each(helper, () -> {
            if (scene.arrived() && !fitted[0] && waiting(scene.current())) {
                ReserveTank.install(scene.current());
                fitted[0] = true;
            }
        });
        helper.succeedWhen(() -> {
            if (!fitted[0] || scene.current().tickCount < 5) {
                throw helper.assertionException("waiting for the pod to strand and be fitted");
            }
            verify(helper, !scene.current().stranded() && !waiting(scene.current()), "the reserve restarts the pod");
        });
    }

    // The real delay, and unreadable state

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 3000)
    public void theCrewComesAfterTheRealMinuteWithNoFastForward(GameTestHelper helper) {
        Plan plan = new Plan();
        plan.fastForward = false;
        Scene scene = run(helper, 12_700, plan);
        long[] startedAt = {-1};
        TestTicks.each(helper, () -> {
            if (scene.arrived() && startedAt[0] < 0 && waiting(scene.current())) {
                startedAt[0] = scene.layer.getGameTime();
            }
        });
        whenHauled(helper, scene, home -> {
            long waited = scene.server.overworld().getGameTime() - startedAt[0];
            verify(helper, startedAt[0] >= 0, "the clock started");
            verify(
                    helper,
                    waited >= TUNING.delayTicks() && waited < TUNING.delayTicks() + 100,
                    "the crew came %d ticks after the clock began, wanted about %d",
                    waited,
                    TUNING.delayTicks());
        });
    }

    @GameTest(maxTicks = LONG_RUN)
    public void anUnreadableClockNeverSwitchesTheSafetyNetOff(GameTestHelper helper) {
        Plan plan = new Plan();
        Scene scene = run(helper, 12_750, plan);
        boolean[] spoiled = {false};
        TestTicks.each(helper, () -> {
            if (scene.arrived() && !spoiled[0] && scene.current().stranded() && waiting(scene.current())) {
                // A newer build saved the clock: this build cannot read it, and must not throw or drop the pilot.
                UnreadableChecks.makeUnreadable(scene.current(), CompanyRecovery.STATE);
                Map<String, Runnable> paths = new HashMap<>();
                paths.put("after tick", () -> PodEvents.AFTER_TICK.invoker().afterTick(scene.current()));
                paths.put("hud seconds", () -> CompanyRecovery.secondsLeft(scene.current()));
                UnreadableChecks.makeUnreadable(scene.current(), CompanyRecovery.STATE);
                UnreadableChecks.assertNoThrow(helper, "company recovery", paths);
                UnreadableChecks.makeUnreadable(scene.current(), CompanyRecovery.STATE);
                spoiled[0] = true;
            }
        });
        whenHauled(helper, scene, home -> {
            verify(helper, spoiled[0], "the clock was made unreadable");
            verify(helper, scene.account() == 0, "no debt after an unreadable clock");
        });
    }

    // A blocked landing

    @GameTest(maxTicks = LONG_RUN, environment = ALONE)
    public void aBlockedColonyIsARefusalThatRestoresThePodAndRefundsTheFee(GameTestHelper helper) {
        Plan plan = new Plan();
        plan.balance = 100;
        Scene scene = run(helper, 12_800, plan);
        Map<BlockPos, BlockState> flooded = new HashMap<>();
        boolean[] restored = {false};
        Set<BlockPos> area = new HashSet<>();
        TestTicks.each(helper, () -> {
            if (!scene.arrived() || restored[0]) {
                return;
            }
            if (flooded.isEmpty()) {
                flood(scene, flooded);
            }
            if (told(scene.pilot, "message.deepcharter.repair.teleport_blocked")) {
                area.addAll(floodedArea(flooded));
                restore(scene.server, flooded, area);
                restored[0] = true;
            }
        });
        helper.succeedWhen(() -> {
            if (!restored[0]) {
                throw helper.assertionException("waiting for the crew to find the landing blocked");
            }
            if (waterLeft(scene.server, area)) {
                // restore drained the water beside the flooded layer; this waits for the flowing water beyond it, which
                // has no source left and decays, before the colony tests after this one read the pad.
                throw helper.assertionException("waiting for the water that ran from the flood to drain");
            }
            PodEntity pod = scene.current();
            verify(
                    helper,
                    pod.level() == scene.layer,
                    "a blocked landing leaves the pod where it is; it is at %s in %s, %d blocks flooded, the pilot was"
                            + " told %s",
                    pod.position(),
                    pod.level().dimension(),
                    flooded.size(),
                    scene.pilot.actionBarMessages().stream()
                            .map(Component::getString)
                            .toList());
            verify(helper, pod.stranded() && pod.fuel() < 1f, "the pod is stranded again, fuel %s", pod.fuel());
            verify(helper, pod.hull() == pod.maxHull(), "the hull is put back, %s", pod.hull());
            verify(helper, scene.account() == 100, "the fee is refunded: $%d", scene.account());
            verify(helper, waiting(pod), "the pilot waits another delay, and is not looped in one tick");
        });
    }

    /** Puts a layer of water on the ground round the colony's pad and the world spawn, so every landing there is wet. */
    private static void flood(Scene scene, Map<BlockPos, BlockState> flooded) {
        MinecraftServer server = scene.server;
        List<GlobalPos> places = new ArrayList<>();
        Colony.respawnPoint(server).ifPresent(places::add);
        places.add(GlobalPos.of(
                server.overworld().dimension(),
                server.overworld().getRespawnData().pos()));
        ServerLevel overworld = server.overworld();
        for (GlobalPos place : places) {
            // sync-load: both places are the colony's office, on the pad, which is pinned
            overworld.getChunk(place.pos());
            for (int dx = -12; dx <= 12; dx++) {
                for (int dz = -12; dz <= 12; dz++) {
                    BlockPos column = place.pos().offset(dx, 0, dz);
                    BlockPos top = overworld.getHeightmapPos(
                            net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column);
                    if (flooded.putIfAbsent(top, overworld.getBlockState(top)) == null) {
                        // room-carver: a layer of water laid on the test world's colony pad, put back by restore() as
                        // soon as the refusal is seen
                        overworld.setBlock(top, Blocks.WATER.defaultBlockState(), 3);
                    }
                }
            }
        }
    }

    /**
     * The blocks the flood can have reached: a block round each block of its layer, which the water makes sources
     * between. The two places it floods (the pad and the world spawn) can lie far apart, so this is not one box.
     */
    private static Set<BlockPos> floodedArea(Map<BlockPos, BlockState> flooded) {
        Set<BlockPos> area = new HashSet<>();
        for (BlockPos top : flooded.keySet()) {
            for (BlockPos pos : BlockPos.betweenClosed(top.offset(-2, -1, -2), top.offset(2, 2, 2))) {
                area.add(pos.immutable());
            }
        }
        return area;
    }

    /** Whether any water stands in {@code area} (see {@link #floodedArea}), flowing water included. */
    private static boolean waterLeft(MinecraftServer server, Set<BlockPos> area) {
        ServerLevel overworld = server.overworld();
        for (BlockPos pos : area) {
            if (overworld.getFluidState(pos).is(FluidTags.WATER)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Puts the flooded layer back, then clears the water that ran from it: water makes sources of its own between two
     * sources, which the layer's saved blocks do not name, and nothing else would take them away from the pad that the
     * colony tests read. The blocks are written for the clients only, so that no neighbour update starts the flow
     * again.
     */
    private static void restore(MinecraftServer server, Map<BlockPos, BlockState> flooded, Set<BlockPos> area) {
        ServerLevel overworld = server.overworld();
        flooded.forEach((pos, state) -> overworld.setBlock(pos, state, Block.UPDATE_CLIENTS));
        for (BlockPos pos : area) {
            if (overworld.getFluidState(pos).is(FluidTags.WATER)) {
                // room-carver: the water the flood left on the colony pad, not layer rock
                overworld.setBlock(pos.immutable(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
    }
}
