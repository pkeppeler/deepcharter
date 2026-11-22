package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.sound.ambience.AmbienceRegistry;
import io.github.pkeppeler.deepcharter.sound.ambience.AmbienceTuning;
import io.github.pkeppeler.deepcharter.sound.ambience.HornSearch;
import io.github.pkeppeler.deepcharter.sound.ambience.SpotPicker;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.Optional;
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
    public void aHornIsFoundAtItsRangeAndNotABlockBeyond(GameTestHelper helper) {
        BlockPos horn = new BlockPos(40_008, Y, 40_008);
        ServerLevel level = arena(helper, horn);
        int range = AmbienceTuning.DEFAULT.hornRange();
        level.setBlock(horn, AmbienceRegistry.COMPANY_HORN.defaultBlockState(), 3);
        expect(
                helper,
                HornSearch.nearest(level, horn.offset(range, 0, 0), range).equals(Optional.of(horn)),
                "the horn was not found %s blocks away",
                range);
        expect(
                helper,
                HornSearch.nearest(level, horn.offset(range + 1, 0, 0), range).isEmpty(),
                "the horn was found %s blocks away",
                range + 1);
        expect(
                helper,
                HornSearch.nearest(level, horn.offset(0, 0, -range), range).equals(Optional.of(horn)),
                "the horn was not found %s blocks away along z",
                range);
        // The nearer of two wins.
        BlockPos nearer = horn.offset(-10, 3, 0);
        level.setBlock(nearer, AmbienceRegistry.COMPANY_HORN.defaultBlockState(), 3);
        expect(
                helper,
                HornSearch.nearest(level, horn.offset(-14, 3, 2), range).equals(Optional.of(nearer)),
                "the nearer horn was not the one found");
        level.setBlock(horn, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(nearer, Blocks.AIR.defaultBlockState(), 3);
        expect(helper, HornSearch.nearest(level, horn, range).isEmpty(), "a horn was found where none stands");
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
        expect(helper, HornSearch.nearest(level, nowhere, 24).isEmpty(), "a horn was found in an unloaded chunk");
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
