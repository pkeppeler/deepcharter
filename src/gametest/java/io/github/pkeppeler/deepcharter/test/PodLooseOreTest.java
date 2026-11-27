package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;
import static io.github.pkeppeler.deepcharter.test.support.Inputs.RIGHT;

import io.github.pkeppeler.deepcharter.fuel.FuelTuning;
import io.github.pkeppeler.deepcharter.ore.LooseOre;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.HardLanding;
import io.github.pkeppeler.deepcharter.pod.LooseOreTuning;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.pod.PodLoad;
import io.github.pkeppeler.deepcharter.pod.PodLooseOre;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Loose ore in a rider's pack (#1540, ADR 0110): it weighs double against the pod's lift, it slows the drill and the
 * steering in proportion to its mass, each ore adds a point to a hard landing's hull damage, and the pod reads it as
 * loose until the rider carries none. Every penalty is measured in the unit the player feels: lift in mass, the drill
 * in ticks for a point of hardness, steering in degrees turned, the landing in hull points.
 */
public class PodLooseOreTest {
    private static final int FLOOR_Y = TestLayers.worldY(TestLayers.SURFACE, 1);
    private static final int FLOOR_RADIUS = 2;
    private static final float TOLERANCE = 1e-3f;
    /** Ticks for the server's measure to reach the pod's stats. */
    private static final int SETTLE_TICKS = 3;
    /** Ticks of held D that a steering measure turns the pod for. */
    private static final int TURN_TICKS = 20;
    /** Ticks with no key held, enough for the turn rate (kept at 0.9 a tick) to die away to nothing. */
    private static final int COAST_TICKS = 80;
    /** The share of the turn the loose ore leaves, with a little for the turn rate's rounding. */
    private static final float TURN_TOLERANCE = 0.03f;

    private static final String FIRST_LINE_KEY = "deepcharter.pod.loose.first";

    /** A pod on a small stone floor with a mock pilot seated. */
    private record Rig(GameTestHelper helper, PodEntity pod, MockPlayer pilot) {
        static Rig build(GameTestHelper helper, String name) {
            floor(helper, Blocks.STONE);
            PodEntity pod =
                    helper.spawn(PodRegistry.POD, new Vec3(FLOOR_RADIUS + 0.5, FLOOR_Y + 1, FLOOR_RADIUS + 0.5));
            MockPlayer rider = MockPlayers.join(helper, name);
            rider.teleportTo(helper.getLevel(), pod.position(), 0f, 0f);
            if (!rider.player().startRiding(pod)) {
                rider.leave();
                throw failure(helper, "%s could not mount the pod", name);
            }
            return new Rig(helper, pod, rider);
        }

        void giveOre(OreType ore, int count) {
            for (int i = 0; i < count; i++) {
                pilot.player().getInventory().add(OreRegistry.stack(ore));
            }
        }

        void finish() {
            pilot.leave();
            pod.discard();
            floor(helper, Blocks.AIR);
        }
    }

    private static void floor(GameTestHelper helper, Block block) {
        for (int x = 0; x <= 2 * FLOOR_RADIUS; x++) {
            for (int z = 0; z <= 2 * FLOOR_RADIUS; z++) {
                helper.setBlock(new BlockPos(x, FLOOR_Y, z), block);
            }
        }
    }

    private static void near(GameTestHelper helper, String what, double expected, double actual) {
        expect(helper, Math.abs(expected - actual) <= TOLERANCE, "%s should be %s, got %s", what, expected, actual);
    }

    @GameTest(maxTicks = 20)
    public void looseOreWeighsDoubleAndFuelAndTheBayStayAtOnce(GameTestHelper helper) {
        Rig rig = Rig.build(helper, "loose weigher");
        try {
            float factor = LooseOreTuning.DEFAULT.weightFactor();
            expect(helper, factor == 2f, "loose ore weighs double, the factor is %s", factor);
            rig.pod.cargo().tryAdd(rig.pod, OreRegistry.stack(OreType.GOLDIUM));
            rig.giveOre(OreType.IRONIUM, 3);
            rig.pilot.player().getInventory().add(new ItemStack(Items.COAL, 8));
            float coal = 8 * FuelTuning.DEFAULT.weight().coal();
            near(
                    helper,
                    "the extra mass of three loose ironium and some coal",
                    3 * OreType.IRONIUM.mass() * factor + coal,
                    PodEvents.extraMass(rig.pod));
            near(
                    helper,
                    "the load: the bay's goldium once, the loose ironium twice, the coal once",
                    OreType.GOLDIUM.mass() + 3 * OreType.IRONIUM.mass() * factor + coal,
                    PodLoad.of(rig.pod).load());
            rig.pilot.player().getInventory().clearContent();
            near(helper, "the extra mass of an empty pack", 0, PodEvents.extraMass(rig.pod));
            helper.succeed();
        } finally {
            rig.finish();
        }
    }

    @GameTest(maxTicks = 40)
    public void looseOreSlowsTheDrillByItsMassUpToTheCap(GameTestHelper helper) {
        Rig rig = Rig.build(helper, "loose driller");
        float before = PodStats.of(rig.pod).ticksPerHardness();
        rig.giveOre(OreType.GOLDIUM, 4);
        helper.runAfterDelay(SETTLE_TICKS, () -> {
            try {
                float mass = 4 * OreType.GOLDIUM.mass();
                float cramp = mass * LooseOreTuning.DEFAULT.crampPerMass();
                near(
                        helper,
                        "the drill's ticks for a point of hardness, with " + mass + " mass of loose ore",
                        before / (1f - cramp),
                        PodStats.of(rig.pod).ticksPerHardness());
                expect(
                        helper,
                        cramp > 0.1f && cramp < LooseOreTuning.DEFAULT.maxCramp(),
                        "four goldium should be a clear penalty under the cap, got %s",
                        cramp);
                rig.giveOre(OreType.GOLDIUM, 20);
            } catch (RuntimeException e) {
                rig.finish();
                throw e;
            }
        });
        helper.runAfterDelay(2 * SETTLE_TICKS + 2, () -> {
            try {
                near(
                        helper,
                        "the drill's ticks with more loose ore than the cap allows",
                        before / (1f - LooseOreTuning.DEFAULT.maxCramp()),
                        PodStats.of(rig.pod).ticksPerHardness());
                helper.succeed();
            } finally {
                rig.finish();
            }
        });
    }

    /**
     * The same pod turns for {@link #TURN_TICKS} ticks of D with an empty pack and then with four goldium in it: the
     * turn is the share that loose ore leaves.
     */
    @GameTest(maxTicks = TURN_TICKS * 2 + COAST_TICKS + SETTLE_TICKS + 10)
    public void looseOreSlowsTheSteeringByItsMass(GameTestHelper helper) {
        Rig rig = Rig.build(helper, "loose steerer");
        float[] unloaded = {0f};
        float[] start = {rig.pod.getYRot()};
        rig.pilot.setInput(RIGHT);
        helper.runAfterDelay(TURN_TICKS, () -> {
            unloaded[0] = Mth.wrapDegrees(rig.pod.getYRot() - start[0]);
            rig.pilot.setInput(Input.EMPTY);
        });
        helper.runAfterDelay(TURN_TICKS + COAST_TICKS, () -> rig.giveOre(OreType.GOLDIUM, 4));
        helper.runAfterDelay(TURN_TICKS + COAST_TICKS + SETTLE_TICKS, () -> {
            start[0] = rig.pod.getYRot();
            rig.pilot.setInput(RIGHT);
        });
        helper.runAfterDelay(2 * TURN_TICKS + COAST_TICKS + SETTLE_TICKS, () -> {
            try {
                float loaded = Mth.wrapDegrees(rig.pod.getYRot() - start[0]);
                float cramp = 4 * OreType.GOLDIUM.mass() * LooseOreTuning.DEFAULT.crampPerMass();
                expect(
                        helper,
                        unloaded[0] > 20f,
                        "an empty pack should turn well over 20 degrees, got %s",
                        unloaded[0]);
                expect(
                        helper,
                        Math.abs(loaded / unloaded[0] - (1f - cramp)) <= TURN_TOLERANCE,
                        "four goldium in the pack should leave %s of the turn: turned %s degrees, %s with an empty pack",
                        1f - cramp,
                        loaded,
                        unloaded[0]);
                helper.succeed();
            } finally {
                rig.finish();
            }
        });
    }

    @GameTest(maxTicks = 20)
    public void eachLooseOreAddsAFixedAmountToAHardLanding(GameTestHelper helper) {
        Rig rig = Rig.build(helper, "loose lander");
        try {
            PodStats stats = PodStats.of(rig.pod);
            double sink = stats.hardLandingSpeed() + 0.2;
            float bare = land(helper, rig.pod, sink);
            expect(
                    helper,
                    bare >= HardLanding.hullDamage(stats, sink, 1f) - TOLERANCE,
                    "the hard landing should cost the hull at least %s, cost %s",
                    HardLanding.hullDamage(stats, sink, 1f),
                    bare);
            rig.giveOre(OreType.GOLDIUM, 3);
            float rattled = land(helper, rig.pod, sink);
            near(
                    helper,
                    "the extra hull three loose ore cost on the same landing",
                    3 * LooseOreTuning.DEFAULT.rattlePerOre(),
                    rattled - bare);
            rig.pilot.player().getInventory().clearContent();
            near(helper, "the hull lost again with an empty pack", bare, land(helper, rig.pod, sink));
            helper.succeed();
        } finally {
            rig.finish();
        }
    }

    /** Lands {@code pod} at {@code sink} blocks a tick, and returns the hull it lost. */
    private static float land(GameTestHelper helper, PodEntity pod, double sink) {
        float before = pod.hull();
        pod.setDeltaMovement(0, -sink, 0);
        pod.causeFallDamage(5, 1f, helper.getLevel().damageSources().fall());
        return before - pod.hull();
    }

    @GameTest(maxTicks = 40)
    public void thePodReadsLooseOreWhileARiderCarriesItAndTellsThemOnce(GameTestHelper helper) {
        Rig rig = Rig.build(helper, "loose reader");
        helper.runAfterDelay(SETTLE_TICKS, () -> {
            expect(helper, !PodLooseOre.of(rig.pod).any(), "an empty pack reads no loose ore");
            expect(helper, firstLines(rig) == 0, "an empty pack says nothing");
            rig.giveOre(OreType.IRONIUM, 2);
        });
        helper.runAfterDelay(2 * SETTLE_TICKS, () -> {
            LooseOre loose = PodLooseOre.of(rig.pod);
            expect(
                    helper,
                    loose.ore() == 2 && Math.abs(loose.mass() - 2 * OreType.IRONIUM.mass()) <= TOLERANCE,
                    "two ironium in the pack read as loose ore, read %s",
                    loose);
            expect(helper, firstLines(rig) == 1, "the first loose ore says why once, said %s times", firstLines(rig));
            rig.pilot.player().getInventory().clearContent();
        });
        helper.runAfterDelay(3 * SETTLE_TICKS, () -> {
            expect(helper, !PodLooseOre.of(rig.pod).any(), "an emptied pack reads no loose ore");
            rig.giveOre(OreType.IRONIUM, 1);
        });
        helper.runAfterDelay(4 * SETTLE_TICKS, () -> {
            try {
                expect(
                        helper,
                        PodLooseOre.of(rig.pod).any() && firstLines(rig) == 1,
                        "a second load reads loose again and says nothing more, said %s times",
                        firstLines(rig));
                helper.succeed();
            } finally {
                rig.finish();
            }
        });
    }

    private static long firstLines(Rig rig) {
        return rig.pilot.chatMessages().stream()
                .filter(message -> message.getContents() instanceof TranslatableContents contents
                        && contents.getKey().equals(FIRST_LINE_KEY))
                .count();
    }
}
