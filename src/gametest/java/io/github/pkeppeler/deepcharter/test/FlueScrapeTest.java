package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.fuel.EngineSoot;
import io.github.pkeppeler.deepcharter.fuel.FuelTuning;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.repair.FlueScrape;
import io.github.pkeppeler.deepcharter.repair.RepairStation;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalRefusal;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.Terminals;
import io.github.pkeppeler.deepcharter.test.support.Failures;
import io.github.pkeppeler.deepcharter.test.support.Inputs;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.TestCharters;
import io.github.pkeppeler.deepcharter.test.support.WorldData;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Server GameTests for the free flue scrape (#1280): the way out of limp mode at the colony for a charter with no
 * money. The bootstrap case is a first-tank pilot with $0 whose coal-fed pod went limp: the paid service clears
 * nothing, the scrape takes its time and leaves the engine under the limit, and the pod then drills. A scrape is
 * refused for an engine that is not limp, and one cut short clears nothing.
 *
 * <p>The station needs a repaired chain, a world-global record swapped only for one tick ({@link WorldData}), so each
 * test presses its buttons on its first tick and then only watches the pod's own ticks, which read no such record.
 * Whether the pod is held reads it, so a later check swaps it in again for that tick ({@link #held}).
 */
public class FlueScrapeTest {
    private static final int TICKS = FuelTuning.DEFAULT.soot().scrapeTicks();
    private static final float LEAVES = FuelTuning.DEFAULT.soot().scrapeLeavesLitres();
    private static final float COAL_LIMP = 13f;
    private static final BlockPos FLOOR = new BlockPos(4, 1, 2);
    private static final CompoundTag NO_ARGS = new CompoundTag();

    /** A repaired station, the pilot standing in its reach on a charter that owns the pod parked over a stone floor. */
    private record Scene(BlockPos station, MockPlayer pilot, CharterId charter, PodEntity pod) {}

    /**
     * Builds the scene, the pod's soot at {@code soot} and the account at {@code account} dollars, and runs {@code
     * presses}, the buttons pressed, while the station's chain is repaired.
     */
    private static Scene withScene(
            GameTestHelper helper, String name, float soot, long account, Consumer<Scene> presses) {
        MinecraftServer server = helper.getLevel().getServer();
        for (int x = FLOOR.getX() - 1; x <= FLOOR.getX() + 1; x++) {
            for (int z = FLOOR.getZ() - 1; z <= FLOOR.getZ() + 1; z++) {
                helper.setBlock(new BlockPos(x, FLOOR.getY(), z), Blocks.STONE);
            }
        }
        BlockPos relative = new BlockPos(1, 2, 2);
        helper.setBlock(relative, TerminalTypes.REPAIR_STATION.block().defaultBlockState());
        BlockPos station = helper.absolutePos(relative);
        MockPlayer pilot = MockPlayers.join(helper, name);
        pilot.player().setGameMode(GameType.SURVIVAL);
        CharterId charter = TestCharters.found(helper, server, pilot.player());
        pilot.teleportTo(helper.getLevel(), Vec3.atBottomCenterOf(station.south(2)), 0, 0);
        PodEntity pod = helper.spawn(PodRegistry.POD, new Vec3(FLOOR.getX() + 0.5, 2, FLOOR.getZ() + 0.5));
        PodComponents.register(pod, charter);
        pod.setAttached(EngineSoot.STATE, Versioned.of(new EngineSoot.State(soot, 0f, 0f)));
        expect(
                helper,
                account == 0 || Charters.deposit(server, charter, account).isEmpty(),
                "funding the account should succeed");
        Scene scene = new Scene(station, pilot, charter, pod);
        WorldData.with(server, RepairState.TYPE, repaired(helper), () -> presses.accept(scene));
        return scene;
    }

    /** A fresh repair state with the chain repaired up to the repair station. */
    private static RepairState repaired(GameTestHelper helper) {
        RepairState fresh = new RepairState();
        for (TerminalType type : List.of(
                TerminalTypes.FUEL_PUMP,
                TerminalTypes.ORE_PROCESSOR,
                TerminalTypes.UPGRADE_TERMINAL,
                TerminalTypes.REPAIR_STATION)) {
            for (var part : type.parts()) {
                fresh.insert(type, part).ifPresent(refusal -> {
                    throw Failures.failure(helper, "repairing %s: %s", type.id(), refusal);
                });
            }
        }
        return fresh;
    }

    /** Whether the pod is held at the colony, asked within this tick with the station repaired. */
    private static boolean held(GameTestHelper helper, PodEntity pod) {
        boolean[] held = new boolean[1];
        WorldData.with(
                helper.getLevel().getServer(),
                RepairState.TYPE,
                repaired(helper),
                () -> held[0] = EngineSoot.launchRefused(pod));
        return held[0];
    }

    private static Optional<TerminalRefusal> press(Scene scene, Identifier action) {
        return Terminals.act(scene.pilot().player(), scene.station(), action, NO_ARGS);
    }

    private static long account(GameTestHelper helper, Scene scene) {
        return Charters.findOrThrow(helper.getLevel().getServer(), scene.charter())
                .orElseThrow()
                .account();
    }

    private static boolean heard(MockPlayer pilot, String words) {
        return pilot.actionBarMessages().stream()
                .anyMatch(line -> line.getString().toLowerCase(Locale.ROOT).contains(words));
    }

    @GameTest(maxTicks = 1600)
    public void aBrokeFirstTankPilotAtLimpScrapesTheFlueForFreeAndThePodDrills(GameTestHelper helper) {
        Scene scene = withScene(helper, "Scraper", COAL_LIMP, 0, pressed -> {
            PodEntity pod = pressed.pod();
            pod.setFuel(100f);
            expect(helper, EngineSoot.launchRefused(pod), "a limp pod at the colony should be held");
            expect(
                    helper,
                    press(pressed, RepairStation.SERVICE_ENGINE).equals(Optional.of(TerminalRefusal.ACTION_REFUSED)),
                    "a service from $0 clears nothing and is refused");
            expect(helper, EngineSoot.soot(pod) == COAL_LIMP, "the refused service left %s L", EngineSoot.soot(pod));
            expect(
                    helper,
                    press(pressed, RepairStation.SCRAPE_FLUE).isEmpty(),
                    "the free scrape should start for a limp pod");
            expect(
                    helper,
                    press(pressed, RepairStation.SCRAPE_FLUE).equals(Optional.of(TerminalRefusal.ACTION_REFUSED)),
                    "a second scrape while one runs is refused");
        });
        PodEntity pod = scene.pod();
        helper.runAfterDelay(TICKS - 20, () -> {
            expect(helper, FlueScrape.running(pod), "a second before the end the scrape still runs");
            expect(helper, EngineSoot.soot(pod) == COAL_LIMP, "the scrape cleared early: %s L", EngineSoot.soot(pod));
            expect(helper, held(helper, pod), "the pod launched before the scrape ended");
        });
        helper.runAfterDelay(TICKS + 20, () -> {
            expect(helper, !FlueScrape.running(pod), "the scrape should have ended");
            expect(
                    helper,
                    EngineSoot.soot(pod) == LEAVES,
                    "the scrape should leave %s L, left %s",
                    LEAVES,
                    EngineSoot.soot(pod));
            expect(
                    helper,
                    !EngineSoot.isLimp(pod) && EngineSoot.output(EngineSoot.soot(pod)) < 1f,
                    "a scraped engine is out of limp mode and still slow");
            expect(
                    helper,
                    account(helper, scene) == 0,
                    "the scrape is free, the account is $%s",
                    account(helper, scene));
            expect(
                    helper,
                    heard(scene.pilot(), "scraped"),
                    "the pilot should be told, heard %s",
                    scene.pilot().actionBarMessages());
            expect(helper, scene.pilot().player().startRiding(pod), "the pilot should board the pod");
            scene.pilot().setInput(Inputs.SPRINT);
        });
        helper.runAfterDelay(TICKS + 100, () -> {
            try {
                expect(
                        helper,
                        pod.drilling() || !helper.getBlockState(FLOOR).is(Blocks.STONE),
                        "the scraped pod should drill from the colony");
                helper.succeed();
            } finally {
                scene.pilot().leave();
                pod.discard();
            }
        });
    }

    @GameTest
    public void aScrapeIsRefusedForAnEngineThatIsNotLimp(GameTestHelper helper) {
        for (float soot : new float[] {0f, LEAVES}) {
            withScene(helper, "Clean" + Math.round(soot), soot, 0, pressed -> {
                try {
                    expect(
                            helper,
                            press(pressed, RepairStation.SCRAPE_FLUE)
                                    .equals(Optional.of(TerminalRefusal.ACTION_REFUSED)),
                            "a scrape of %s L should be refused",
                            soot);
                    expect(helper, !FlueScrape.running(pressed.pod()), "no scrape should run at %s L", soot);
                } finally {
                    pressed.pilot().leave();
                    pressed.pod().discard();
                }
            });
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 1600)
    public void aScrapeCutShortByThePilotWalkingAwayClearsNothing(GameTestHelper helper) {
        Scene scene = withScene(
                helper,
                "Walker",
                COAL_LIMP,
                0,
                pressed ->
                        expect(helper, press(pressed, RepairStation.SCRAPE_FLUE).isEmpty(), "the scrape should start"));
        helper.runAfterDelay(
                100,
                () -> scene.pilot()
                        .teleportTo(
                                helper.getLevel(),
                                Vec3.atBottomCenterOf(scene.station().south(8)),
                                0,
                                0));
        helper.runAfterDelay(110, () -> {
            expect(helper, !FlueScrape.running(scene.pod()), "walking away should stop the scrape");
            expect(
                    helper,
                    heard(scene.pilot(), "stopped"),
                    "the pilot should be told, heard %s",
                    scene.pilot().actionBarMessages());
        });
        helper.runAfterDelay(TICKS + 20, () -> {
            try {
                expect(
                        helper,
                        EngineSoot.soot(scene.pod()) == COAL_LIMP,
                        "a scrape cut short should clear nothing, left %s L",
                        EngineSoot.soot(scene.pod()));
                expect(helper, held(helper, scene.pod()), "the pod is still held");
                helper.succeed();
            } finally {
                scene.pilot().leave();
                scene.pod().discard();
            }
        });
    }

    @GameTest(maxTicks = 1600)
    public void aScrapeCutShortByThePodLeavingTheStationClearsNothing(GameTestHelper helper) {
        Scene scene = withScene(
                helper,
                "Parker",
                COAL_LIMP,
                0,
                pressed ->
                        expect(helper, press(pressed, RepairStation.SCRAPE_FLUE).isEmpty(), "the scrape should start"));
        helper.runAfterDelay(100, () -> {
            PodEntity pod = scene.pod();
            pod.setPos(pod.position().add(6, 0, 0));
        });
        helper.runAfterDelay(110, () -> {
            expect(helper, !FlueScrape.running(scene.pod()), "a pod that leaves the station stops the scrape");
            expect(
                    helper,
                    heard(scene.pilot(), "stopped"),
                    "the pilot should be told, heard %s",
                    scene.pilot().actionBarMessages());
        });
        helper.runAfterDelay(TICKS + 20, () -> {
            try {
                expect(
                        helper,
                        EngineSoot.soot(scene.pod()) == COAL_LIMP,
                        "a scrape cut short should clear nothing, left %s L",
                        EngineSoot.soot(scene.pod()));
                helper.succeed();
            } finally {
                scene.pilot().leave();
                scene.pod().discard();
            }
        });
    }
}
