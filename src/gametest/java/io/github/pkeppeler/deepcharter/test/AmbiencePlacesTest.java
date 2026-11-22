package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.sound.ambience.AmbienceRegistry;
import io.github.pkeppeler.deepcharter.sound.ambience.AmbienceTuning;
import io.github.pkeppeler.deepcharter.sound.ambience.BlockSearch;
import io.github.pkeppeler.deepcharter.sound.ambience.ClockCaller;
import io.github.pkeppeler.deepcharter.sound.ambience.ClockHour;
import io.github.pkeppeler.deepcharter.sound.ambience.ClockTuning;
import io.github.pkeppeler.deepcharter.sound.ambience.PunchClock;
import io.github.pkeppeler.deepcharter.sound.ambience.SpotPicker;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.SplittableRandom;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

/**
 * Server GameTests for #866: where the ambience's fragments are heard from, on a server's level (the client asks the same two functions
 * of its own). A Company horn is found within its range and not a block beyond, a chunk that is not loaded holds none, and the place
 * picked for a fragment from the dark is 12 to 28 blocks from the listener's eyes and prefers rock between and darkness. Each test builds in
 * its own far corner of the overworld, at a height of open air, so the others cannot be in the way.
 */
public class AmbiencePlacesTest {
    /** A height of open air over the surface's flat ground. */
    private static final int Y = TestLayers.worldY(TestLayers.SURFACE, 200);

    private static final int RADIUS_CHUNKS = 3;

    /** Loads the 7 x 7 chunks round {@code centre}, held until the test ends, and returns the level. */
    private static ServerLevel arena(GameTestHelper helper, BlockPos centre) {
        ServerLevel level = helper.getLevel();
        for (int x = -RADIUS_CHUNKS; x <= RADIUS_CHUNKS; x++) {
            for (int z = -RADIUS_CHUNKS; z <= RADIUS_CHUNKS; z++) {
                int chunkX = (centre.getX() >> 4) + x;
                int chunkZ = (centre.getZ() >> 4) + z;
                FarChunks.force(helper, level, chunkX, chunkZ);
                level.getChunk(chunkX, chunkZ, ChunkStatus.FULL);
            }
        }
        return level;
    }

    @GameTest
    public void aClockIsFoundAtItsRangeAndNotABlockBeyondAndNeverMistakenForAHorn(GameTestHelper helper) {
        BlockPos clock = new BlockPos(40_208, Y, 40_208);
        ServerLevel level = arena(helper, clock);
        int range = ClockTuning.DEFAULT.range();
        level.setBlock(clock, AmbienceRegistry.COMPANY_CLOCK.defaultBlockState(), 3);
        expect(
                helper,
                BlockSearch.nearest(level, clock.offset(range, 0, 0), range, AmbienceRegistry.COMPANY_CLOCK)
                        .equals(Optional.of(clock)),
                "the clock was not found %s blocks away",
                range);
        expect(
                helper,
                BlockSearch.nearest(level, clock.offset(range + 1, 0, 0), range, AmbienceRegistry.COMPANY_CLOCK)
                        .isEmpty(),
                "the clock was found %s blocks away",
                range + 1);
        expect(
                helper,
                BlockSearch.nearest(level, clock, range, AmbienceRegistry.COMPANY_HORN)
                        .isEmpty(),
                "a clock was taken for a horn");
        level.setBlock(clock, AmbienceRegistry.COMPANY_HORN.defaultBlockState(), 3);
        expect(
                helper,
                BlockSearch.nearest(level, clock, range, AmbienceRegistry.COMPANY_CLOCK)
                        .isEmpty(),
                "a horn was taken for a clock");
        level.setBlock(clock, Blocks.AIR.defaultBlockState(), 3);
        helper.succeed();
    }

    @GameTest
    public void thePunchClockSpeaksOnlyNearAClockWithTheWorldsHourKnownAndItsTimerWaitsForTheVisitor(
            GameTestHelper helper) {
        BlockPos clock = new BlockPos(40_408, Y, 40_408);
        ServerLevel level = arena(helper, clock);
        int range = ClockTuning.DEFAULT.range();
        level.setBlock(clock, AmbienceRegistry.COMPANY_CLOCK.defaultBlockState(), 3);
        Optional<BlockPos> near =
                BlockSearch.nearest(level, clock.offset(3, 0, 0), range, AmbienceRegistry.COMPANY_CLOCK);
        Optional<BlockPos> away =
                BlockSearch.nearest(level, clock.offset(range + 5, 0, 0), range, AmbienceRegistry.COMPANY_CLOCK);
        OptionalInt hour = ClockHour.of(level);
        expect(helper, near.isPresent() && away.isEmpty(), "the clock was %s near and %s away", near, away);
        expect(
                helper,
                hour.isPresent() && hour.getAsInt() >= 1 && hour.getAsInt() <= 12,
                "the world's hour is %s",
                hour);
        ClockCaller caller = new ClockCaller(ClockTuning.DEFAULT, new SplittableRandom(1));
        // Away from the clock, or with no hour, for far longer than any gap: nothing is called and the timer does not
        // run.
        for (int tick = 0; tick < 500_000; tick++) {
            expect(helper, PunchClock.tick(caller, away, hour, false).isEmpty(), "called away from the clock");
            expect(
                    helper,
                    PunchClock.tick(caller, near, OptionalInt.empty(), false).isEmpty(),
                    "called with no hour");
        }
        // Then near: the first call is not before the floor, comes from the clock and strikes the world's hour or
        // another.
        for (int tick = 0; tick < ClockTuning.DEFAULT.firstMinTicks() - 1; tick++) {
            expect(
                    helper,
                    PunchClock.tick(caller, near, hour, false).isEmpty(),
                    "called %s ticks after arriving",
                    tick);
        }
        Optional<PunchClock.Strike> strike = Optional.empty();
        for (int tick = 0; tick < ClockTuning.DEFAULT.gapMaxTicks() && strike.isEmpty(); tick++) {
            strike = PunchClock.tick(caller, near, hour, false);
        }
        expect(helper, strike.isPresent(), "no call within the longest gap");
        expect(
                helper,
                strike.get().at().equals(clock),
                "the call came from %s",
                strike.get().at());
        expect(helper, !strike.get().call().chimes().isEmpty(), "the call strikes nothing");
        level.setBlock(clock, Blocks.AIR.defaultBlockState(), 3);
        helper.succeed();
    }

    @GameTest
    public void aHornIsFoundAtItsRangeAndNotABlockBeyond(GameTestHelper helper) {
        BlockPos horn = new BlockPos(40_008, Y, 40_008);
        ServerLevel level = arena(helper, horn);
        int range = AmbienceTuning.DEFAULT.hornRange();
        level.setBlock(horn, AmbienceRegistry.COMPANY_HORN.defaultBlockState(), 3);
        expect(
                helper,
                BlockSearch.nearest(level, horn.offset(range, 0, 0), range, AmbienceRegistry.COMPANY_HORN)
                        .equals(Optional.of(horn)),
                "the horn was not found %s blocks away",
                range);
        expect(
                helper,
                BlockSearch.nearest(level, horn.offset(range + 1, 0, 0), range, AmbienceRegistry.COMPANY_HORN)
                        .isEmpty(),
                "the horn was found %s blocks away",
                range + 1);
        expect(
                helper,
                BlockSearch.nearest(level, horn.offset(0, 0, -range), range, AmbienceRegistry.COMPANY_HORN)
                        .equals(Optional.of(horn)),
                "the horn was not found %s blocks away along z",
                range);
        // The nearer of two wins.
        BlockPos nearer = horn.offset(-10, 3, 0);
        level.setBlock(nearer, AmbienceRegistry.COMPANY_HORN.defaultBlockState(), 3);
        expect(
                helper,
                BlockSearch.nearest(level, horn.offset(-14, 3, 2), range, AmbienceRegistry.COMPANY_HORN)
                        .equals(Optional.of(nearer)),
                "the nearer horn was not the one found");
        level.setBlock(horn, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(nearer, Blocks.AIR.defaultBlockState(), 3);
        expect(
                helper,
                BlockSearch.nearest(level, horn, range, AmbienceRegistry.COMPANY_HORN)
                        .isEmpty(),
                "a horn was found where none stands");
        helper.succeed();
    }

    @GameTest
    public void aChunkThatIsNotLoadedHoldsNoHornAndNoPlace(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos nowhere = new BlockPos(-9_000_000, Y, 9_000_000);
        expect(
                helper,
                level.getChunk(nowhere.getX() >> 4, nowhere.getZ() >> 4, ChunkStatus.FULL, false) == null,
                "the chunk at %s is loaded",
                nowhere);
        expect(
                helper,
                BlockSearch.nearest(level, nowhere, 24, AmbienceRegistry.COMPANY_HORN)
                        .isEmpty(),
                "a horn was found in an unloaded chunk");
        expect(
                helper,
                SpotPicker.pick(level, Vec3.atCenterOf(nowhere), AmbienceTuning.DEFAULT, new SplittableRandom(1))
                        .isEmpty(),
                "a place was picked in unloaded chunks");
        helper.succeed();
    }

    @GameTest
    public void thePlaceForAFragmentIsTwelveToTwentyEightBlocksFromTheEyes(GameTestHelper helper) {
        BlockPos centre = new BlockPos(41_008, Y, 41_008);
        ServerLevel level = arena(helper, centre);
        Vec3 eyes = Vec3.atCenterOf(centre);
        AmbienceTuning tuning = AmbienceTuning.DEFAULT;
        SplittableRandom random = new SplittableRandom(5);
        for (int i = 0; i < 300; i++) {
            Vec3 at = SpotPicker.pick(level, eyes, tuning, random).orElseThrow();
            double flat = Math.hypot(at.x - eyes.x, at.z - eyes.z);
            expect(
                    helper,
                    flat >= tuning.distanceMin() - 1e-6 && flat <= tuning.distanceMax() + 1e-6,
                    "place %s is %s blocks from the eyes",
                    i,
                    flat);
            expect(
                    helper,
                    Math.abs(at.y - eyes.y) <= 4 + 1e-6,
                    "place %s is %s blocks above or below",
                    i,
                    at.y - eyes.y);
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 200)
    public void aPlaceBehindRockIsPreferredAndSoIsADarkOne(GameTestHelper helper) {
        BlockPos centre = new BlockPos(42_008, Y, 42_008);
        ServerLevel level = arena(helper, centre);
        Vec3 eyes = Vec3.atCenterOf(centre);
        // A wall of stone 6 blocks east, which hides the place 15 blocks east of the eyes; 15 blocks south is open and
        // dark; 15 blocks
        // west stands in the glare of a lamp.
        for (int y = Y - 5; y <= Y + 6; y++) {
            for (int z = -6; z <= 6; z++) {
                level.setBlock(centre.offset(6, y - Y, z), Blocks.STONE.defaultBlockState(), 3);
            }
        }
        level.setBlock(
                centre.offset(-15, 0, 0), Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 15), 3);
        Vec3 behind = eyes.add(15, 0, 0);
        Vec3 open = eyes.add(0, 0, 15);
        Vec3 lit = eyes.add(-14, 0, 0);
        helper.succeedWhen(() -> {
            int behindScore = SpotPicker.score(level, eyes, behind);
            int openScore = SpotPicker.score(level, eyes, open);
            int litScore = SpotPicker.score(level, eyes, lit);
            expect(helper, behindScore > openScore, "behind rock scores %s, in the open %s", behindScore, openScore);
            expect(helper, openScore > litScore, "dark scores %s, by a lamp %s", openScore, litScore);
        });
    }
}
