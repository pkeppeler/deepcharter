package io.github.pkeppeler.deepcharter.test;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import io.github.pkeppeler.deepcharter.layer.Depth;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodDrill;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodFuel;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodRockBite;
import io.github.pkeppeler.deepcharter.pod.PodRockBiteTuning;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.ScannerPods;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Server GameTests for the drill that bites Company rock (#526, ADR 0050): a pod whose drill works at tier {@code minTier} or more bores a Company rock slab slowly,
 * burning more fuel and heating the hull while it does; any other drill still refuses it. The chassis' tier cap applies, so a Mole never bites. Each test has its
 * own X in layer 1, and a plate of Company rock 5 x 5 under the pod, so the pod's footprint (2 x 2 or 3 x 3) is on rock whichever way it centres.
 */
public class CompanyRockBiteTest {
    private static final PodRockBiteTuning TUNING = PodRockBiteTuning.DEFAULT;
    private static final int Z = 5600;
    private static final int FLOOR = 60;
    private static final int ROOM = 8;
    private static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + 900;
    private static final Input SPRINT = new Input(false, false, false, false, false, false, true);

    /** The pilot and the room; {@link #pod} stays null until the far chunk ticks entities, so a tick callback must check it. */
    private static final class Bite {
        private final MockPlayer pilot;
        private final ServerLevel level;
        private @Nullable PodEntity pod;

        private Bite(MockPlayer pilot, ServerLevel level) {
            this.pilot = pilot;
            this.level = level;
        }

        @Nullable
        PodEntity pod() {
            return pod;
        }

        MockPlayer pilot() {
            return pilot;
        }

        ServerLevel level() {
            return level;
        }
    }

    /** Builds the room and the plate, seats a pilot in a pod of {@code type} with a drill of {@code drillTier}, and holds sprint. */
    private static Bite rig(GameTestHelper helper, int x, EntityType<PodEntity> type, int drillTier, int radiatorTier) {
        ServerLevel level = TestLayers.level(helper.getLevel().getServer(), 1);
        RoomCarver.carve(level, x - ROOM, x + ROOM, FLOOR - 8, FLOOR - 1, Z - ROOM, Z + ROOM, Blocks.STONE);
        RoomCarver.carve(level, x - ROOM, x + ROOM, FLOOR, FLOOR + 10, Z - ROOM, Z + ROOM, Blocks.AIR);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                level.setBlock(
                        new BlockPos(x + dx, FLOOR - 1, Z + dz), HazardBlocks.COMPANY_ROCK.defaultBlockState(), 2);
            }
        }
        MockPlayer pilot = MockPlayers.join(helper, "bite-" + x);
        Vec3 at = new Vec3(x + 0.5, FLOOR, Z + 0.5);
        pilot.teleportTo(level, at, 0f, 0f);
        Bite bite = new Bite(pilot, level);
        FarChunks.awaitEntityTicking(helper, level, BlockPos.containing(at), () -> {
            PodEntity pod = TestEntities.create(type, level);
            pod.setPos(at);
            level.addFreshEntity(pod);
            pilot.player().startRiding(pod);
            if (drillTier > 0) {
                ScannerPods.fit(helper.getLevel().getServer(), pilot.player(), pod, ComponentTrack.DRILL, drillTier);
            }
            if (radiatorTier > 0) {
                ScannerPods.fit(
                        helper.getLevel().getServer(), pilot.player(), pod, ComponentTrack.RADIATOR, radiatorTier);
            }
            pilot.setInput(SPRINT);
            bite.pod = pod;
        });
        return bite;
    }

    private static boolean bored(ServerLevel level, int x) {
        return level.getBlockState(new BlockPos(x, FLOOR - 1, Z)).isAir();
    }

    private static boolean toldOf(MockPlayer pilot, String key) {
        return pilot.actionBarMessages().stream()
                .anyMatch(message -> message.getContents() instanceof TranslatableContents translatable
                        && translatable.getKey().equals(key));
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aDrillAtTheBiteTierBoresCompanyRockSlowlyAndPaysInFuelAndHeat(GameTestHelper helper) {
        int x = 4400;
        Bite bite = rig(helper, x, PodRegistry.PROSPECTOR, TUNING.minTier(), 0);
        float[] start = new float[2];
        int[] startTick = {-1};
        TestTicks.each(helper, () -> {
            PodEntity pod = bite.pod();
            if (pod == null) {
                return;
            }
            if (startTick[0] < 0) {
                startTick[0] = pod.tickCount;
                start[0] = pod.fuel();
                start[1] = pod.hull();
            }
            if (!bored(bite.level(), x)) {
                return;
            }
            bite.pilot().releaseInput();
            PodStats stats = PodStats.of(pod);
            int ticks = pod.tickCount - startTick[0];
            int expected = PodRockBite.drillTicks(stats, Depth.feet(Depth.of(bite.level(), FLOOR - 1)));
            if (Math.abs(ticks - expected) > 4) {
                throw failure(helper, "the slab should take about %d ticks, it took %d", expected, ticks);
            }
            int stone = PodDrill.drillTicks(
                    stats, Blocks.STONE.defaultDestroyTime(), Depth.feet(Depth.of(bite.level(), FLOOR - 1)));
            if (expected < stone * 5) {
                throw failure(
                        helper,
                        "Company rock should bore much slower than stone (%d ticks), it takes %d",
                        stone,
                        expected);
            }
            double burn = start[0] - pod.fuel();
            double wanted =
                    PodFuel.drainPercentPerTick(stats, PodFuel.Activity.DRILLING) * TUNING.fuelMultiplier() * ticks;
            if (Math.abs(burn - wanted) > wanted * 0.1) {
                throw failure(helper, "the bite should burn %.3f of the tank, it burned %.3f", wanted, burn);
            }
            double heat = start[1] - pod.hull();
            double heatWanted = TUNING.heatHullPerSecond() * ticks / 20.0;
            if (Math.abs(heat - heatWanted) > 0.5) {
                throw failure(helper, "the bite should cost %.2f hull in heat, it cost %.2f", heatWanted, heat);
            }
            expectRockBeside(helper, bite.level(), x);
            pod.discard();
            helper.succeed();
        });
    }

    private static void expectRockBeside(GameTestHelper helper, ServerLevel level, int x) {
        if (!level.getBlockState(new BlockPos(x + 2, FLOOR - 1, Z)).is(HazardBlocks.UNDIGGABLE)) {
            throw failure(helper, "the bite is the pod's footprint only: the rock two blocks aside should stay");
        }
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aRadiatorCutsTheHeatOfTheBite(GameTestHelper helper) {
        int x = 4464;
        Bite bite = rig(helper, x, PodRegistry.PROSPECTOR, TUNING.minTier(), 3);
        float[] start = {-1};
        int[] startTick = {-1};
        TestTicks.each(helper, () -> {
            PodEntity pod = bite.pod();
            if (pod == null) {
                return;
            }
            if (startTick[0] < 0) {
                startTick[0] = pod.tickCount;
                start[0] = pod.hull();
            }
            if (!bored(bite.level(), x)) {
                return;
            }
            bite.pilot().releaseInput();
            float ratio = PodComponents.radiatorRatio(pod);
            if (ratio >= 1f) {
                throw failure(helper, "the radiator should count, its ratio is %s", ratio);
            }
            double heat = start[0] - pod.hull();
            double wanted = TUNING.heatHullPerSecond() * (pod.tickCount - startTick[0]) / 20.0 * ratio;
            if (Math.abs(heat - wanted) > 0.5) {
                throw failure(
                        helper,
                        "a radiator of ratio %.2f should cut the heat to %.2f hull, it cost %.2f",
                        ratio,
                        wanted,
                        heat);
            }
            pod.discard();
            helper.succeed();
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aDrillBelowTheBiteTierStillRefusesCompanyRock(GameTestHelper helper) {
        int x = 4528;
        Bite bite = rig(helper, x, PodRegistry.PROSPECTOR, TUNING.minTier() - 1, 0);
        refused(helper, bite, x, "deepcharter.ore.company_rock_refused");
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aMoleNeverBitesBecauseItsChassisCapsTheDrillBelowTheBiteTier(GameTestHelper helper) {
        int x = 4592;
        Bite bite = rig(helper, x, PodRegistry.POD, ComponentTrack.DRILL.maxTier(), 0);
        refused(helper, bite, x, "deepcharter.ore.company_rock_refused");
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aCutterOnAHullAtTheFloorRefusesAndSaysSo(GameTestHelper helper) {
        int x = 4656;
        Bite bite = rig(helper, x, PodRegistry.PROSPECTOR, TUNING.minTier(), 0);
        boolean[] hurt = {false};
        TestTicks.each(helper, () -> {
            PodEntity pod = bite.pod();
            if (pod != null && !hurt[0]) {
                pod.setHull(TUNING.hullFloor());
                hurt[0] = true;
            }
        });
        refused(helper, bite, x, "deepcharter.ore.company_rock_cutter_hot");
    }

    /** Waits 300 pod ticks with sprint held: the rock under the pod must stay, the pilot must hear {@code key}, and the hull and tank must not have moved for the bite. */
    private static void refused(GameTestHelper helper, Bite bite, int x, String key) {
        TestTicks.each(helper, () -> {
            PodEntity pod = bite.pod();
            if (pod == null || pod.tickCount < 300) {
                return;
            }
            if (bored(bite.level(), x)) {
                throw failure(helper, "the drill should refuse the Company rock, but the slab is bored");
            }
            if (!toldOf(bite.pilot(), key)) {
                throw failure(
                        helper,
                        "the pilot should be told '%s', the bar says %s",
                        key,
                        bite.pilot().actionBarMessages());
            }
            pod.discard();
            helper.succeed();
        });
    }

    // assertionException(String, Object...) leaves the placeholders unfilled in the report.
    @FormatMethod
    private static RuntimeException failure(
            GameTestHelper helper, @FormatString String format, @Nullable Object... args) {
        return helper.assertionException(Component.literal(String.format(format, args)));
    }
}
