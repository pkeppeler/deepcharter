package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Inputs.JUMP;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.ore.GasHazard;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodTuning;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Server GameTests for #854: a gas blast, a hard landing and an overloaded lift each tell the pod's pilot their cause on the action bar and
 * play a sound at the pod, and say nothing when nothing hurt or blocked the pod. The mock pilot records the action bar and the sounds the
 * server plays to it.
 */
public class PodHazardCuesTest {
    private static final String GAS_KEY = "deepcharter.ore.gas_blast";
    private static final String LANDING_KEY = "deepcharter.pod.hard_landing";
    private static final String OVERLOAD_KEY = "deepcharter.pod.overloaded";

    private static final Identifier GAS_SOUND = id("pod.explosion");
    private static final Identifier LANDING_SOUND = id("pod.crash");
    private static final Identifier OVERLOAD_SOUND = id("cargo.full");

    private static final int FLOOR_Y = TestLayers.worldY(TestLayers.SURFACE, 1);
    private static final int FLOOR_RADIUS = 3;
    /** Layer 1 gas site: a bed of stone under an open room, away from every other test's. */
    private static final int GAS_X = 9800;

    private static final int GAS_Z = 9800;
    private static final int GAS_FLOOR = 14;
    private static final int GAS_RADIUS = 6;

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path);
    }

    private static long messages(MockPlayer pilot, String key) {
        return pilot.actionBarMessages().stream()
                .filter(message -> message.getContents() instanceof TranslatableContents contents
                        && contents.getKey().equals(key))
                .count();
    }

    private static long sounds(MockPlayer pilot, PodEntity pod, Identifier sound) {
        return pilot.soundsHeardFrom(pod).stream().filter(sound::equals).count();
    }

    /** The test fails unless the pilot has been told {@code key} {@code times} times and heard {@code sound} as often. */
    private static void expectCue(
            GameTestHelper helper,
            PodEntity pod,
            MockPlayer pilot,
            String key,
            Identifier sound,
            int times,
            String when) {
        if (messages(pilot, key) != times || sounds(pilot, pod, sound) != times) {
            throw failure(
                    helper,
                    "%s: the pilot should get '%s' %s times with the %s sound, the bar said %s and the pilot heard %s",
                    when,
                    key,
                    times,
                    sound,
                    pilot.actionBarMessages().stream().map(Component::getString).toList(),
                    pilot.soundsHeardFrom(pod));
        }
    }

    private static void expectQuiet(GameTestHelper helper, PodEntity pod, MockPlayer pilot, String when) {
        if (!pilot.actionBarMessages().isEmpty() || !pilot.soundsHeardFrom(pod).isEmpty()) {
            throw failure(
                    helper,
                    "%s: nothing hurt or blocked the pod, yet the bar said %s and the pilot heard %s",
                    when,
                    pilot.actionBarMessages().stream().map(Component::getString).toList(),
                    pilot.soundsHeardFrom(pod));
        }
    }

    // ---- gas ----

    /** A Mole with a seated pilot in layer 1, over a bed of stone; {@code vent} runs on the first tick. */
    private void gasSite(GameTestHelper helper, int xShift, Consumer<Gas> vent, Consumer<Gas> check, int settleTicks) {
        int x = GAS_X + xShift;
        ServerLevel level = TestLayers.level(helper, 1);
        RoomCarver.carve(
                level,
                x - GAS_RADIUS,
                x + GAS_RADIUS,
                GAS_FLOOR - 12,
                GAS_FLOOR - 1,
                GAS_Z - GAS_RADIUS,
                GAS_Z + GAS_RADIUS,
                Blocks.STONE);
        RoomCarver.carve(
                level,
                x - GAS_RADIUS,
                x + GAS_RADIUS,
                GAS_FLOOR,
                GAS_FLOOR + 10,
                GAS_Z - GAS_RADIUS,
                GAS_Z + GAS_RADIUS,
                Blocks.AIR);
        MockPlayer pilot = MockPlayers.join(helper, "Cue pilot gas " + xShift);
        pilot.player().setGameMode(GameType.SURVIVAL);
        pilot.teleportTo(level, new Vec3(x, GAS_FLOOR, GAS_Z), 0f, 0f);
        Gas[] gas = {null};
        FarChunks.awaitEntityTicking(helper, level, new BlockPos(x, GAS_FLOOR, GAS_Z), () -> {
            PodEntity pod = TestEntities.create(PodRegistry.POD, level);
            pod.setPos(x, GAS_FLOOR, GAS_Z);
            level.addFreshEntity(pod);
            if (!pilot.player().startRiding(pod)) {
                throw failure(helper, "the pilot could not mount the pod");
            }
            gas[0] = new Gas(level, pod, pilot, new BlockPos(x, GAS_FLOOR - 1, GAS_Z + 1), pod.hull());
        });
        int[] ticks = {0};
        TestTicks.each(helper, () -> {
            if (gas[0] == null) {
                return;
            }
            ticks[0]++;
            if (ticks[0] == 3) {
                vent.accept(gas[0]);
            }
            if (ticks[0] == 3 + settleTicks) {
                try {
                    check.accept(gas[0]);
                    helper.succeed();
                } finally {
                    pilot.leave();
                    gas[0].pod().discard();
                }
            }
        });
    }

    private record Gas(ServerLevel level, PodEntity pod, MockPlayer pilot, BlockPos pocket, float startHull) {}

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 40)
    public void aGasBlastTellsThePilotItWasGasAndSoundsAtThePod(GameTestHelper helper) {
        gasSite(
                helper,
                0,
                gas -> GasHazard.vent(gas.level(), gas.pocket()),
                gas -> {
                    if (gas.pod().hull() >= gas.startHull()) {
                        throw failure(
                                helper,
                                "the blast should cost hull, hull is %s",
                                gas.pod().hull());
                    }
                    expectCue(helper, gas.pod(), gas.pilot(), GAS_KEY, GAS_SOUND, 1, "after one blast");
                },
                5);
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 40)
    public void aGasBlastNamesTheHullItCost(GameTestHelper helper) {
        gasSite(
                helper,
                48,
                gas -> GasHazard.vent(gas.level(), gas.pocket()),
                gas -> {
                    Component said = gas.pilot().actionBarMessages().getFirst();
                    int lost = Math.round(gas.startHull() - gas.pod().hull());
                    if (!(said.getContents() instanceof TranslatableContents contents)
                            || contents.getArgs().length != 1
                            || !String.valueOf(lost).equals(String.valueOf(contents.getArgs()[0]))) {
                        throw failure(helper, "the cue should carry the %s hull lost, it is %s", lost, said);
                    }
                },
                5);
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 40)
    public void aBlastOutOfReachOfThePodSaysNothing(GameTestHelper helper) {
        gasSite(
                helper,
                96,
                gas -> GasHazard.vent(gas.level(), gas.pocket().offset(0, 0, 40)),
                gas -> {
                    if (gas.pod().hull() != gas.startHull()) {
                        throw failure(
                                helper,
                                "a far blast should not hurt, hull is %s",
                                gas.pod().hull());
                    }
                    expectQuiet(helper, gas.pod(), gas.pilot(), "a blast out of reach");
                },
                5);
    }

    // ---- surface rigs: hard landing and overload ----

    /** A pod standing {@code height} blocks over a small stone floor, with a mock pilot seated. */
    private record Rig(GameTestHelper helper, PodEntity pod, MockPlayer pilot) {
        static Rig build(GameTestHelper helper, double height) {
            for (int dx = 0; dx <= 2 * FLOOR_RADIUS; dx++) {
                for (int dz = 0; dz <= 2 * FLOOR_RADIUS; dz++) {
                    helper.setBlock(new BlockPos(dx, FLOOR_Y, dz), Blocks.STONE);
                }
            }
            PodEntity pod = helper.spawn(
                    PodRegistry.POD, new Vec3(FLOOR_RADIUS + 0.5, FLOOR_Y + 1 + height, FLOOR_RADIUS + 0.5));
            MockPlayer pilot = MockPlayers.join(helper, "Cue pilot " + height);
            pilot.teleportTo(helper.getLevel(), pod.position(), 0f, 0f);
            if (!pilot.player().startRiding(pod)) {
                throw failure(helper, "the pilot could not mount the pod");
            }
            return new Rig(helper, pod, pilot);
        }

        void finish() {
            pilot.leave();
            pod.discard();
            for (int dx = 0; dx <= 2 * FLOOR_RADIUS; dx++) {
                for (int dz = 0; dz <= 2 * FLOOR_RADIUS; dz++) {
                    helper.setBlock(new BlockPos(dx, FLOOR_Y, dz), Blocks.AIR);
                }
            }
        }
    }

    @GameTest(maxTicks = 100)
    public void aHardLandingTellsThePilotAndSoundsAtThePod(GameTestHelper helper) {
        Rig rig = Rig.build(helper, 12);
        helper.runAfterDelay(60, () -> {
            try {
                if (rig.pod.hull() >= rig.pod.maxHull()) {
                    throw failure(helper, "a fall of 12 blocks should cost hull, hull is %s", rig.pod.hull());
                }
                expectCue(helper, rig.pod, rig.pilot, LANDING_KEY, LANDING_SOUND, 1, "after one hard landing");
                helper.succeed();
            } finally {
                rig.finish();
            }
        });
    }

    @GameTest(maxTicks = 100)
    public void aSoftLandingSaysNothing(GameTestHelper helper) {
        Rig rig = Rig.build(helper, 2);
        helper.runAfterDelay(40, () -> {
            try {
                if (rig.pod.hull() != rig.pod.maxHull()) {
                    throw failure(helper, "a drop of 2 blocks should not cost hull, hull is %s", rig.pod.hull());
                }
                expectQuiet(helper, rig.pod, rig.pilot, "a soft landing");
                helper.succeed();
            } finally {
                rig.finish();
            }
        });
    }

    private static void overload(Rig rig) {
        rig.pod.setCargoMass(PodTuning.DEFAULT.movement().enginePower());
    }

    @GameTest(maxTicks = 60)
    public void jumpingWithAnOverloadedPodTellsThePilotOnceAndSounds(GameTestHelper helper) {
        Rig rig = Rig.build(helper, 0);
        overload(rig);
        helper.runAfterDelay(5, () -> rig.pilot.setInput(JUMP));
        helper.runAfterDelay(25, () -> {
            try {
                expectCue(
                        helper,
                        rig.pod,
                        rig.pilot,
                        OVERLOAD_KEY,
                        OVERLOAD_SOUND,
                        1,
                        "after one press held for 20 ticks");
                helper.succeed();
            } finally {
                rig.finish();
            }
        });
    }

    @GameTest(maxTicks = 130)
    public void aHeldJumpRepeatsTheOverloadCueEveryFortyTicks(GameTestHelper helper) {
        Rig rig = Rig.build(helper, 0);
        overload(rig);
        rig.pilot.setInput(JUMP);
        // Cues land at ticks 0, 40 and 80 of the hold, so 100 ticks hold three.
        helper.runAfterDelay(100, () -> {
            try {
                expectCue(
                        helper,
                        rig.pod,
                        rig.pilot,
                        OVERLOAD_KEY,
                        OVERLOAD_SOUND,
                        3,
                        "after a press held for 100 ticks");
                helper.succeed();
            } finally {
                rig.finish();
            }
        });
    }

    @GameTest(maxTicks = 80)
    public void eachNewPressOfAnOverloadedJumpCuesAgain(GameTestHelper helper) {
        Rig rig = Rig.build(helper, 0);
        overload(rig);
        rig.pilot.setInput(JUMP);
        helper.runAfterDelay(8, rig.pilot::releaseInput);
        helper.runAfterDelay(14, () -> rig.pilot.setInput(JUMP));
        helper.runAfterDelay(30, () -> {
            try {
                expectCue(helper, rig.pod, rig.pilot, OVERLOAD_KEY, OVERLOAD_SOUND, 2, "after two presses");
                helper.succeed();
            } finally {
                rig.finish();
            }
        });
    }

    @GameTest(maxTicks = 60)
    public void anOverloadedPodWithNoKeyPressedSaysNothing(GameTestHelper helper) {
        Rig rig = Rig.build(helper, 0);
        overload(rig);
        helper.runAfterDelay(30, () -> {
            try {
                expectQuiet(helper, rig.pod, rig.pilot, "an overloaded pod, no jump");
                helper.succeed();
            } finally {
                rig.finish();
            }
        });
    }

    @GameTest(maxTicks = 60)
    public void jumpingWithLiftToSpareSaysNothing(GameTestHelper helper) {
        Rig rig = Rig.build(helper, 0);
        rig.pod.setCargoMass(0f);
        double startY = rig.pod.getY();
        rig.pilot.setInput(JUMP);
        helper.runAfterDelay(30, () -> {
            try {
                if (rig.pod.getY() - startY < 1.0) {
                    throw failure(helper, "an empty pod should climb, rose %s", rig.pod.getY() - startY);
                }
                expectQuiet(helper, rig.pod, rig.pilot, "a pod with lift");
                helper.succeed();
            } finally {
                rig.finish();
            }
        });
    }

    @FormatMethod
    private static RuntimeException failure(
            GameTestHelper helper, @FormatString String format, @Nullable Object... args) {
        return helper.assertionException(Component.literal(String.format(format, args)));
    }
}
