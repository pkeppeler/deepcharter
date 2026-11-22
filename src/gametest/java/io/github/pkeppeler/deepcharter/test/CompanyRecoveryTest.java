package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.repair.CompanyRecovery;
import io.github.pkeppeler.deepcharter.repair.RecoveryTuning;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;

/**
 * The Company recovery of a stranded pod (#963). A solo pilot runs dry at the bottom of a shaft in layer 1, the pod strands by its own
 * fuel running out, and the crew winches it home after the delay for a fee. The wait is fast-forwarded by moving the clock back, so a
 * test does not sit out a minute; the clock itself, and the wait's start, are the real ones.
 */
public class CompanyRecoveryTest {
    private static final int Z = 12_000;
    private static final int LAYER = 1;
    private static final int PILOT_Y = TestLayers.worldY(LAYER, 40);
    private static final RecoveryTuning TUNING = RecoveryTuning.DEFAULT;

    /** What a case reads once the pod is home. */
    private record Home(PodEntity pod, ServerLevel level, long account, int spilledOre) {}

    /**
     * Strands a new pod in layer 1 with a pilot aboard and {@code balance} dollars in the owner charter's account, by letting its last
     * sliver of fuel burn, then waits for the recovery and gives {@code check} the state at home. {@code ore} ironium ride in the bay.
     */
    private static void strand(GameTestHelper helper, int x, long balance, int ore, Consumer<Home> check) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel layer = TestLayers.level(server, LAYER);
        MockPlayer pilot = MockPlayers.join(helper, "recovery-" + x);
        Charters.found(
                        server,
                        pilot.player().getUUID(),
                        "Recovery " + UUID.randomUUID().toString().substring(0, 8))
                .ifPresent(refusal -> {
                    throw helper.assertionException("founding the charter: %s", refusal);
                });
        Charter charter =
                Charters.charterOfOrThrow(server, pilot.player().getUUID()).orElseThrow();
        if (balance > 0) {
            Charters.deposit(server, charter.id(), balance);
        }
        BlockPos at = BlockPos.containing(x, PILOT_Y, Z);
        PodEntity[] pod = new PodEntity[1];
        TestTicks.each(helper, () -> {
            // The pod is a new entity after the crossing: read the pilot's vehicle, not the first pod.
            if (pod[0] == null || !(pilot.player().getVehicle() instanceof PodEntity current)) {
                return;
            }
            if (current.level() == layer && current.stranded()) {
                Versioned.readable(current, CompanyRecovery.STATE)
                        .filter(CompanyRecovery.State::waiting)
                        .filter(state -> current.level().getGameTime() - state.since() < TUNING.delayTicks())
                        .ifPresent(state -> Versioned.modifyOrThrow(
                                current,
                                CompanyRecovery.STATE,
                                old -> new CompanyRecovery.State(state.since() - TUNING.delayTicks())));
            }
        });
        FarChunks.awaitEntityTicking(helper, layer, at, () -> {
            pilot.teleportTo(layer, TestLayers.vec(LAYER, x, PILOT_Y, Z), 0, 0);
            pilot.player().setGameMode(GameType.SURVIVAL);
            PodEntity created = TestEntities.create(PodRegistry.POD, layer);
            created.setPos(x, TestLayers.worldY(LAYER, PILOT_Y), Z);
            layer.addFreshEntity(created);
            PodComponents.register(created, charter.id());
            for (int i = 0; i < ore; i++) {
                created.cargo().tryAdd(created, OreRegistry.stack(OreType.IRONIUM));
            }
            if (!pilot.player().startRiding(created, true, false)) {
                throw helper.assertionException("the pilot could not board the pod");
            }
            // The last sliver of fuel: the pod burns it and strands itself, as a real tank runs dry.
            created.setFuel(0.01f);
            pod[0] = created;
        });
        helper.succeedWhen(() -> {
            if (pod[0] == null) {
                throw helper.assertionException("waiting for the chunk");
            }
            ServerPlayer player = pilot.player();
            if (!(player.getVehicle() instanceof PodEntity home) || home.level() == layer) {
                throw helper.assertionException(
                        "the pod has not been hauled out of layer 1 yet; the pilot was told %s",
                        pilot.actionBarMessages().stream()
                                .map(Component::getString)
                                .toList());
            }
            int spilled = 0;
            for (ItemEntity drop : layer.getEntitiesOfClass(ItemEntity.class, new AABB(at).inflate(6))) {
                if (OreRegistry.typeOf(drop.getItem()).equals(Optional.of(OreType.IRONIUM))) {
                    spilled += drop.getItem().getCount();
                }
            }
            long account = Charters.charterOfOrThrow(server, player.getUUID())
                    .orElseThrow()
                    .account();
            check.accept(new Home(home, (ServerLevel) home.level(), account, spilled));
            if (home.stranded()) {
                throw helper.assertionException("the pod is still stranded at home");
            }
        });
    }

    /** The player's wording of what the pilot was told, by its key and arguments. */
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

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 4000)
    public void aDryBrokePilotAloneAtTheBottomIsWinchedHomeAndTheHullPaysTheFee(GameTestHelper helper) {
        strand(helper, 12_000, 0, 0, home -> {
            verify(helper, home.account() == 0, "no debt: the account stays at 0, is %d", home.account());
            float scraped = TUNING.scrapeHull();
            verify(
                    helper,
                    Math.abs(home.pod().hull() - (home.pod().maxHull() - scraped)) < 0.01f,
                    "the unpaid fee costs %s hull, the pod has %s of %s",
                    scraped,
                    home.pod().hull(),
                    home.pod().maxHull());
            verify(
                    helper,
                    home.pod().fuel() > 0f,
                    "the crew leaves a splash of fuel, the tank holds %s",
                    home.pod().fuel());
            verify(
                    helper,
                    home.level()
                            .dimension()
                            .equals(helper.getLevel().getServer().overworld().dimension()),
                    "the pod is at the colony, not in %s",
                    home.level().dimension());
        });
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 4000)
    public void aPilotWithMoneyPaysTheFeeInFullAndLeavesTheOreBehind(GameTestHelper helper) {
        strand(helper, 12_100, 200, 2, home -> {
            verify(
                    helper,
                    home.account() == 200 - TUNING.fee(),
                    "the fee comes off the account: $%d left",
                    home.account());
            verify(
                    helper,
                    home.pod().hull() == home.pod().maxHull(),
                    "a paid fee costs no hull, the pod has %s",
                    home.pod().hull());
            verify(helper, home.pod().cargo().entries().isEmpty(), "nothing mined is winched home");
            verify(helper, home.spilledOre() == 2, "the ore stays where the pod was: %d ironium", home.spilledOre());
        });
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 4000)
    public void anAccountShortOfTheFeePaysWhatItHoldsAndTheHullPaysTheRest(GameTestHelper helper) {
        long balance = TUNING.fee() / 5;
        strand(helper, 12_200, balance, 0, home -> {
            verify(
                    helper,
                    home.account() == 0,
                    "the Company takes all $%d the account held, $%d left",
                    balance,
                    home.account());
            float unpaidShare = 1f - (float) balance / TUNING.fee();
            float wanted = home.pod().maxHull() - TUNING.scrapeHull() * unpaidShare;
            verify(
                    helper,
                    Math.abs(home.pod().hull() - wanted) < 0.01f,
                    "the unpaid %s of the fee takes hull to %s, the pod has %s",
                    unpaidShare,
                    wanted,
                    home.pod().hull());
        });
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 200)
    public void aPilotWhoRefuelsInTheWaitCallsTheCrewOffAndKeepsTheOre(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel layer = TestLayers.level(server, LAYER);
        int x = 12_300;
        MockPlayer pilot = MockPlayers.join(helper, "recovery-refuel");
        BlockPos at = BlockPos.containing(x, PILOT_Y, Z);
        PodEntity[] pod = new PodEntity[1];
        long[] startedAt = {-1};
        boolean[] refuelled = {false};
        TestTicks.each(helper, () -> {
            if (pod[0] == null) {
                return;
            }
            var state = Versioned.readable(pod[0], CompanyRecovery.STATE).orElseThrow();
            if (startedAt[0] < 0 && state.waiting()) {
                startedAt[0] = pod[0].level().getGameTime();
                // Wave the crew off: the pod has fuel again.
                pod[0].setFuel(50f);
                pod[0].setStranded(false);
                refuelled[0] = true;
            }
        });
        FarChunks.awaitEntityTicking(helper, layer, at, () -> {
            pilot.teleportTo(layer, TestLayers.vec(LAYER, x, PILOT_Y, Z), 0, 0);
            pilot.player().setGameMode(GameType.SURVIVAL);
            PodEntity created = TestEntities.create(PodRegistry.POD, layer);
            created.setPos(x, TestLayers.worldY(LAYER, PILOT_Y), Z);
            layer.addFreshEntity(created);
            if (!pilot.player().startRiding(created, true, false)) {
                throw helper.assertionException("the pilot could not board the pod");
            }
            created.setFuel(0.01f);
            pod[0] = created;
        });
        helper.succeedWhen(() -> {
            if (!refuelled[0]) {
                throw helper.assertionException("waiting for the pod to strand and the clock to start");
            }
            verify(
                    helper,
                    !Versioned.readable(pod[0], CompanyRecovery.STATE)
                            .orElseThrow()
                            .waiting(),
                    "a pod with fuel again is not waiting for the crew");
            verify(helper, pod[0].level() == layer && pod[0].isAlive(), "the pod stays in layer 1");
            verify(
                    helper,
                    told(pilot, "message.deepcharter.repair.recovery_called", TUNING.delayTicks() / 20, TUNING.fee()),
                    "the pilot is told the crew is coming, and what it costs");
        });
    }
}
