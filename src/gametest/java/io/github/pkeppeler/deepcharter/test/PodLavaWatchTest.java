package io.github.pkeppeler.deepcharter.test;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.ore.SlagBrick;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.pod.PodLavaWatch;
import io.github.pkeppeler.deepcharter.pod.PodLavaWatchTuning;
import io.github.pkeppeler.deepcharter.pod.PodLining;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.UnreadableChecks;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server GameTests for the lava watch (#454), in layer 1. Each test has its own X. The pod stands on a bed of stone with its 2 x 2 footprint over
 * x - 1 to x and Z - 1 to Z, its feet at {@link #FLOOR} and its box 3 cells high, in a shaft of air that goes up from it, beside a one-block corridor that goes
 * east from it. The staged flow is the point: lava put where the liner cannot see it runs down the shaft, and the watch must speak before the hull burns.
 * The distances are pinned with flowing lava put in place directly (a level 7 cell, which stays about 30 ticks), so they are the same on every run.
 */
public class PodLavaWatchTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(PodLavaWatchTest.class);
    private static final AtomicInteger OWNERS = new AtomicInteger();
    private static final int Z = 5400;
    private static final int FLOOR = 14;
    /** Cells of the pod's box, from its feet up. */
    private static final int BOX = 3;
    /** Air cells above the pod's feet in the shaft. */
    private static final int SHAFT = 19;

    private static final int CORRIDOR = 14;
    private static final PodLavaWatchTuning TUNING = PodLavaWatchTuning.DEFAULT;
    /** Past two looks of the watch, so a reading is current. */
    private static final int SETTLE_TICKS = 2 * TUNING.scanEveryTicks() + 2;

    private static final int FLOW_TICKS = 1800;
    private static final int QUIET_TICKS = 400;
    private static final int BRICKS = 40;
    /** Long enough for the lava to fall from the top of the shaft onto the cap. */
    private static final int CAP_TICKS = 700;

    /** A bed of stone with a shaft of air in the pod's footprint, {@link #SHAFT} cells over its feet, and a corridor of {@link #CORRIDOR} cells east at its feet. */
    private static void site(ServerLevel level, int x) {
        RoomCarver.carve(level, x - 3, x + 20, FLOOR - 3, FLOOR + SHAFT + 3, Z - 3, Z + 3, Blocks.STONE);
        RoomCarver.carve(level, x - 1, x, FLOOR, FLOOR + SHAFT - 1, Z - 1, Z, Blocks.AIR);
        RoomCarver.carve(level, x + 1, x + CORRIDOR, FLOOR, FLOOR, Z, Z, Blocks.AIR);
    }

    /** The pod and its pilot, once the pod's chunk ticks. {@code pilot} is the mock player, seated in the pod when the test is piloted. */
    private record Staged(PodEntity pod, MockPlayer pilot) {}

    /** Called each tick after the pod is staged, with the ticks since; returns true when the test is done. */
    private interface Watch {
        boolean tick(Staged staged, int ticks);
    }

    /** Spawns the pod (with the pilot seated, or with none), runs {@code stage} on it, then {@code watch} each tick until it says it is done, and ends the test. */
    private static void run(
            GameTestHelper helper, ServerLevel level, int x, boolean piloted, Consumer<Staged> stage, Watch watch) {
        MockPlayer pilot = MockPlayers.join(helper, "Watch pilot " + OWNERS.incrementAndGet());
        pilot.player().setGameMode(GameType.SURVIVAL);
        pilot.teleportTo(level, new Vec3(x, FLOOR, Z), 0f, 0f);
        Staged[] staged = {null};
        int[] ticks = {0};
        FarChunks.awaitEntityTicking(helper, level, new BlockPos(x, FLOOR, Z), () -> {
            PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
            pod.setPos(x, FLOOR, Z);
            level.addFreshEntity(pod);
            if (piloted && !pilot.player().startRiding(pod)) {
                throw failure(helper, "the pilot could not mount the pod");
            }
            staged[0] = new Staged(pod, pilot);
            stage.accept(staged[0]);
        });
        helper.onEachTick(() -> {
            if (staged[0] != null && watch.tick(staged[0], ++ticks[0])) {
                staged[0].pod().discard();
                helper.succeed();
            }
        });
    }

    private static Optional<Integer> reading(PodEntity pod) {
        return PodLavaWatch.warning(pod).map(PodLavaWatch.State::blocksOff);
    }

    private static PodLavaWatch.State state(PodEntity pod) {
        return Versioned.readable(pod, PodLavaWatch.STATE).orElseThrow();
    }

    private static void flowing(ServerLevel level, BlockPos cell) {
        level.setBlock(cell, Blocks.LAVA.defaultBlockState().setValue(LiquidBlock.LEVEL, 7), 3);
    }

    // ---- the staged flow: lava from outside the liner's view, before the hull burns ----

    /** Lava flowing down the shaft from far above: the watch speaks while the lava is still some blocks off, and before the hull loses a point. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + FLOW_TICKS)
    public void lavaFlowingDownTheShaftFromOutsideTheLinersViewWarnsBeforeTheHullBurns(GameTestHelper helper) {
        int x = 7000;
        ServerLevel level = layer(helper);
        site(level, x);
        int[] warnedAt = {-1};
        int[] warnedBlocksOff = {0};
        float[] startHull = {0f};
        run(
                helper,
                level,
                x,
                true,
                staged -> {
                    startHull[0] = staged.pod().hull();
                    level.setBlock(new BlockPos(x, FLOOR + SHAFT - 1, Z), Blocks.LAVA.defaultBlockState(), 3);
                },
                (staged, ticks) -> {
                    PodEntity pod = staged.pod();
                    if (warnedAt[0] < 0 && reading(pod).isPresent()) {
                        warnedAt[0] = ticks;
                        warnedBlocksOff[0] = reading(pod).orElseThrow();
                        if (!state(pod).above()) {
                            throw failure(
                                    helper,
                                    "the lava falls down the shaft from above and the watch should say so, it reads %s",
                                    state(pod));
                        }
                    }
                    if (pod.hull() >= startHull[0]) {
                        return false;
                    }
                    if (warnedAt[0] < 0) {
                        throw failure(helper, "the hull burned at tick %s and the watch had said nothing", ticks);
                    }
                    if (warnedBlocksOff[0] < 2) {
                        throw failure(
                                helper,
                                "the watch spoke only when the lava was %s block off, no time to act",
                                warnedBlocksOff[0]);
                    }
                    LOGGER.info(
                            "[lava-watch] warned at tick {} with the lava {} blocks off, hull burned at tick {}: {} ticks to act",
                            warnedAt[0],
                            warnedBlocksOff[0],
                            ticks,
                            ticks - warnedAt[0]);
                    return true;
                });
    }

    /** A pilot, a shaft and no lava anywhere: the watch stays quiet. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + QUIET_TICKS + 100)
    public void aPodWithNoLavaAboutIsNotWarned(GameTestHelper helper) {
        int x = 7064;
        ServerLevel level = layer(helper);
        site(level, x);
        quietFor(helper, level, x, staged -> {}, () -> {});
    }

    /**
     * Lava runs down the shaft, but a plug of stone in it cuts the flow off from the pod: the lava is within the watch's reach as the crow flies, and joined
     * to nothing. The watch stays quiet and the hull stays whole, and the lava is shown above the plug.
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + QUIET_TICKS + 100)
    public void lavaSealedOffByARockPlugInTheShaftIsNotWarned(GameTestHelper helper) {
        int x = 7128;
        ServerLevel level = layer(helper);
        site(level, x);
        int plug = FLOOR + 8;
        RoomCarver.carve(level, x - 1, x, plug, plug, Z - 1, Z, Blocks.STONE);
        quietFor(
                helper,
                level,
                x,
                staged -> level.setBlock(new BlockPos(x, plug + 4, Z), Blocks.LAVA.defaultBlockState(), 3),
                () -> {
                    if (!level.getFluidState(new BlockPos(x, plug + 1, Z)).is(FluidTags.LAVA)) {
                        throw failure(
                                helper,
                                "the lava should have run down to the plug, so that the test shows a flow the watch does not warn of");
                    }
                });
    }

    /** Runs the pod with a pilot for {@link #QUIET_TICKS}, failing on any reading or any lost hull, then runs {@code after}. */
    private static void quietFor(
            GameTestHelper helper, ServerLevel level, int x, Consumer<Staged> stage, Runnable after) {
        float[] startHull = {0f};
        run(
                helper,
                level,
                x,
                true,
                staged -> {
                    startHull[0] = staged.pod().hull();
                    stage.accept(staged);
                },
                (staged, ticks) -> {
                    PodEntity pod = staged.pod();
                    if (reading(pod).isPresent() || pod.hull() < startHull[0]) {
                        throw failure(
                                helper,
                                "tick %s: the watch reads %s and the hull is %s of %s, and neither lava nor a warning was due",
                                ticks,
                                reading(pod),
                                pod.hull(),
                                startHull[0]);
                    }
                    if (ticks < QUIET_TICKS) {
                        return false;
                    }
                    after.run();
                    return true;
                });
    }

    // ---- how far the watch reaches, which way, and what stops it ----

    /**
     * Flowing lava put {@code dx} blocks east and {@code dy} up from the pod's feet (a brick {@code brickDx} east, or 0 for none): what the watch reads once it has had two
     * looks, as the blocks off and whether it is from above, or empty for nothing.
     */
    private void lavaAt(
            GameTestHelper helper,
            int x,
            int dx,
            int dy,
            int brickDx,
            Optional<Integer> expected,
            boolean expectedAbove,
            boolean piloted) {
        ServerLevel level = layer(helper);
        site(level, x);
        run(
                helper,
                level,
                x,
                piloted,
                staged -> {
                    if (brickDx > 0) {
                        level.setBlock(new BlockPos(x + brickDx, FLOOR, Z), SlagBrick.BLOCK.defaultBlockState(), 3);
                    }
                    flowing(level, new BlockPos(x + dx, FLOOR + dy, Z));
                },
                (staged, ticks) -> {
                    if (ticks < SETTLE_TICKS) {
                        return false;
                    }
                    Optional<Integer> read = reading(staged.pod());
                    boolean above = read.isPresent() && state(staged.pod()).above();
                    if (!read.equals(expected) || above != expectedAbove) {
                        throw failure(
                                helper,
                                "flowing lava %s east and %s up (brick at %s, pilot %s) should read %s%s, it reads %s%s",
                                dx,
                                dy,
                                brickDx,
                                piloted,
                                expected,
                                expectedAbove ? " from above" : "",
                                read,
                                above ? " from above" : "");
                    }
                    return true;
                });
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
    public void lavaAtTheEdgeOfTheReachIsRead(GameTestHelper helper) {
        lavaAt(helper, 7192, TUNING.reach(), 0, 0, Optional.of(TUNING.reach()), false, true);
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
    public void lavaOneBlockPastTheReachIsNotRead(GameTestHelper helper) {
        lavaAt(helper, 7256, TUNING.reach() + 1, 0, 0, Optional.empty(), false, true);
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
    public void lavaBesideTheBoxReadsOneBlockOff(GameTestHelper helper) {
        lavaAt(helper, 7320, 1, 0, 0, Optional.of(1), false, true);
    }

    /** Lining the corridor is the pilot's answer: the same lava with a brick between it and the pod is not read. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
    public void aBrickBetweenTheLavaAndThePodSilencesTheWarning(GameTestHelper helper) {
        lavaAt(helper, 7384, 5, 0, 2, Optional.empty(), false, true);
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
    public void aPodWithNoPilotIsNotWarned(GameTestHelper helper) {
        lavaAt(helper, 7448, 3, 0, 0, Optional.empty(), false, false);
    }

    /** Lava over the box in the shaft reads from above, 1 block off for the cell over the top of the box. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
    public void lavaOverTheBoxReadsFromAbove(GameTestHelper helper) {
        lavaAt(helper, 7512, 0, BOX, 0, Optional.of(1), true, true);
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
    public void lavaFiveBlocksUpTheShaftReadsFiveFromAbove(GameTestHelper helper) {
        lavaAt(helper, 7576, 0, BOX - 1 + 5, 0, Optional.of(5), true, true);
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
    public void lavaAtTheEdgeOfTheReachUpTheShaftIsRead(GameTestHelper helper) {
        lavaAt(helper, 7640, 0, BOX - 1 + TUNING.reach(), 0, Optional.of(TUNING.reach()), true, true);
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
    public void lavaOneBlockPastTheReachUpTheShaftIsNotRead(GameTestHelper helper) {
        lavaAt(helper, 7704, 0, BOX - 1 + TUNING.reach() + 1, 0, Optional.empty(), false, true);
    }

    // ---- only lava that comes nearer warns ----

    /**
     * A lavafall that has settled beyond the near band: it warns while it comes nearer, then falls quiet after the quiet looks, and stays quiet with the lava still
     * flowing there. A pod in a lava-rich cave is not warned for ever.
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + QUIET_TICKS + 200)
    public void lavaThatSettlesBeyondTheNearBandWarnsOnceThenFallsQuiet(GameTestHelper helper) {
        int x = 7768;
        ServerLevel level = layer(helper);
        site(level, x);
        boolean[] warned = {false};
        BlockPos source = new BlockPos(x + CORRIDOR, FLOOR, Z);
        run(
                helper,
                level,
                x,
                true,
                staged -> level.setBlock(source, Blocks.LAVA.defaultBlockState(), 3),
                (staged, ticks) -> {
                    if (reading(staged.pod()).isPresent()) {
                        warned[0] = true;
                    }
                    if (ticks < QUIET_TICKS) {
                        return false;
                    }
                    PodLavaWatch.State now = state(staged.pod());
                    if (now.nearest() <= TUNING.nearBand()) {
                        throw failure(
                                helper,
                                "the test needs lava that settles beyond the near band of %s blocks, the nearest is %s",
                                TUNING.nearBand(),
                                now.nearest());
                    }
                    if (!warned[0]) {
                        throw failure(helper, "the lava came nearer and the watch never warned, it reads %s", now);
                    }
                    if (!now.isQuiet()) {
                        throw failure(
                                helper,
                                "the lava settled %s blocks off and has not come nearer for %s looks, the watch should be quiet, it reads %s",
                                now.nearest(),
                                TUNING.quietScans(),
                                now);
                    }
                    if (!level.getFluidState(source.west()).is(FluidTags.LAVA)) {
                        throw failure(
                                helper,
                                "the lava should still be flowing in the corridor, so that the test shows a flow the watch has stopped warning of");
                    }
                    return true;
                });
    }

    /** Lava that settles within the near band keeps warning: it is close enough to matter whether it moves or not. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + QUIET_TICKS + 200)
    public void lavaThatSettlesWithinTheNearBandKeepsWarning(GameTestHelper helper) {
        int x = 7832;
        ServerLevel level = layer(helper);
        site(level, x);
        run(
                helper,
                level,
                x,
                true,
                staged -> level.setBlock(
                        new BlockPos(x + TUNING.nearBand(), FLOOR, Z), Blocks.LAVA.defaultBlockState(), 3),
                (staged, ticks) -> {
                    if (ticks < QUIET_TICKS) {
                        return false;
                    }
                    PodLavaWatch.State now = state(staged.pod());
                    if (now.nearest() > TUNING.nearBand() || now.still() < TUNING.quietScans()) {
                        throw failure(
                                helper,
                                "the test needs lava that has settled within the near band of %s blocks, the watch reads %s",
                                TUNING.nearBand(),
                                now);
                    }
                    if (now.isQuiet()) {
                        throw failure(
                                helper,
                                "lava %s blocks off, settled, is within the near band and should still warn, it reads %s",
                                now.nearest(),
                                now);
                    }
                    return true;
                });
    }

    // ---- the cap: the pilot's answer to lava from above ----

    /**
     * Lava falls down the shaft. The pilot lines by hand once the watch says the lava is above: the lid goes over the box first, one brick for each cell of the
     * bore (4 for the Mole), the warning goes out, the lava runs on above the lid, and the hull never burns.
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + FLOW_TICKS)
    public void handLiningCapsTheShaftAndSilencesLavaFromAbove(GameTestHelper helper) {
        capTheShaft(helper, 7896, false);
    }

    /**
     * As above, in an open cave: the row of the lid has open cells beside it all round (a wide breach). The lid is still 4 bricks, laid first, and no brick of the
     * ring goes in the lid's row.
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + FLOW_TICKS)
    public void handLiningInAnOpenCaveLaysOnlyTheLidInItsRow(GameTestHelper helper) {
        capTheShaft(helper, 7960, true);
    }

    private static void capTheShaft(GameTestHelper helper, int x, boolean openBeside) {
        ServerLevel level = layer(helper);
        site(level, x);
        int capY = FLOOR + BOX;
        if (openBeside) {
            RoomCarver.carve(level, x - 2, x + 1, capY, capY, Z - 2, Z + 1, Blocks.AIR);
        }
        float[] startHull = {0f};
        boolean[] lining = {false};
        int[] cappedAt = {-1};
        run(
                helper,
                level,
                x,
                true,
                staged -> {
                    startHull[0] = staged.pod().hull();
                    staged.pilot().player().getInventory().add(new ItemStack(SlagBrick.item(), BRICKS));
                    level.setBlock(new BlockPos(x, FLOOR + SHAFT - 1, Z), Blocks.LAVA.defaultBlockState(), 3);
                },
                (staged, ticks) -> {
                    PodEntity pod = staged.pod();
                    if (pod.hull() < startHull[0]) {
                        throw failure(
                                helper,
                                "the hull burned at tick %s, the cap should have stopped the lava (lining %s, capped at %s)",
                                ticks,
                                lining[0],
                                cappedAt[0]);
                    }
                    if (!lining[0]) {
                        if (reading(pod).isPresent() && state(pod).above()) {
                            PodLining.toggle(staged.pilot().player());
                            lining[0] = true;
                        }
                        return false;
                    }
                    boolean capped = true;
                    for (int cx = x - 1; cx <= x; cx++) {
                        for (int cz = Z - 1; cz <= Z; cz++) {
                            capped &= level.getBlockState(new BlockPos(cx, capY, cz))
                                    .is(SlagBrick.BLOCK);
                        }
                    }
                    if (cappedAt[0] < 0 && capped) {
                        cappedAt[0] = ticks;
                        for (int cx = x - 2; cx <= x + 1; cx++) {
                            for (int cz = Z - 2; cz <= Z + 1; cz++) {
                                boolean lid = cx >= x - 1 && cx <= x && cz >= Z - 1 && cz <= Z;
                                if (!lid
                                        && level.getBlockState(new BlockPos(cx, capY, cz))
                                                .is(SlagBrick.BLOCK)) {
                                    throw failure(helper, "a ring brick was laid in the lid's row at %s %s", cx, cz);
                                }
                            }
                        }
                        int used = BRICKS - carried(staged);
                        if (used != 4) {
                            throw failure(helper, "the cap over a 2 x 2 bore takes 4 bricks, the pilot used %s", used);
                        }
                    }
                    if (cappedAt[0] >= 0
                            && ticks > cappedAt[0] + SETTLE_TICKS
                            && reading(pod).isPresent()) {
                        throw failure(helper, "the shaft is capped and the watch still reads %s", state(pod));
                    }
                    if (cappedAt[0] < 0 || ticks < CAP_TICKS) {
                        return false;
                    }
                    if (!level.getFluidState(new BlockPos(x, capY + 1, Z)).is(FluidTags.LAVA)) {
                        throw failure(
                                helper,
                                "the lava should have run down onto the cap, so that the test shows lava that the cap holds off");
                    }
                    return true;
                });
    }

    private static int carried(Staged staged) {
        int count = 0;
        Inventory inventory = staged.pilot().player().getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).is(SlagBrick.item())) {
                count += inventory.getItem(slot).getCount();
            }
        }
        return count;
    }

    // ---- saving ----

    @GameTest
    public void theWatchsStateSurvivesASaveAndALoad(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
        PodLavaWatch.State state = new PodLavaWatch.State(7, true, 7, 2);
        pod.setAttached(PodLavaWatch.STATE, Versioned.of(state));
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        pod.saveWithoutId(output);
        PodEntity loaded = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
        loaded.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), output.buildResult()));
        if (!Versioned.readable(loaded, PodLavaWatch.STATE).orElseThrow().equals(state)) {
            throw failure(
                    helper,
                    "the watch's state must survive a save and a load, got %s",
                    Versioned.readable(loaded, PodLavaWatch.STATE));
        }
        helper.succeed();
    }

    @GameTest
    public void aWatchStateOfAnotherVersionIsKeptAndNeverThrows(GameTestHelper helper) {
        PodEntity pod = PodRegistry.POD.create(helper.getLevel(), EntitySpawnReason.COMMAND);
        UnreadableChecks.makeUnreadable(pod, PodLavaWatch.STATE);
        UnreadableChecks.assertNoThrow(
                helper,
                "pod lava watch",
                Map.of(
                        "the reading", () -> reading(pod),
                        "the tick", () -> PodEvents.AFTER_TICK.invoker().afterTick(pod)));
        if (!(pod.getAttached(PodLavaWatch.STATE) instanceof Versioned.Unreadable<PodLavaWatch.State>)) {
            throw failure(helper, "unreadable watch state is left as it was read");
        }
        helper.succeed();
    }

    @FormatMethod
    private static RuntimeException failure(GameTestHelper helper, @FormatString String format, Object... args) {
        return helper.assertionException(Component.literal(String.format(format, args)));
    }

    private static ServerLevel layer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel().getServer().getLevel(LayerChain.dimension(1));
        if (level == null) {
            throw failure(helper, "dimension %s did not load", LayerChain.dimension(1));
        }
        return level;
    }
}
