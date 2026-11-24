package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.test.support.TestSites;
import io.github.pkeppeler.deepcharter.test.support.TestSites.Site;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;

/** Server GameTests for {@link TestSites} (#1182): the table has no overlap and no stale owner, and staging outside a claim fails. */
public class TestSitesTest {
    private static final String TEST_PACKAGE = "io.github.pkeppeler.deepcharter.test.";

    @GameTest
    public void noTwoOwnersClaimTheSameColumnOfALayer(GameTestHelper helper) {
        List<String> overlaps = TestSites.overlaps();
        expect(
                helper,
                overlaps.isEmpty(),
                "tests stage on the same columns of one layer; move the newer one in TestSiteTable and the test:\n  %s",
                String.join("\n  ", overlaps));
        helper.succeed();
    }

    @GameTest
    public void everyOwnerIsATestClassThatStillExists(GameTestHelper helper) {
        for (String owner : TestSites.owners()) {
            try {
                Class.forName(TEST_PACKAGE + owner, false, TestSitesTest.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                throw failure(
                        helper,
                        "TestSiteTable names %s, which is not a class of %s: renamed or deleted?",
                        owner,
                        TEST_PACKAGE);
            }
        }
        helper.succeed();
    }

    @GameTest
    public void aSharedColumnOfOneLayerIsAnOverlapAndNothingElseIs(GameTestHelper helper) {
        Site base = new Site("A", 1, 100, 110, 100, 110);
        expect(
                helper,
                TestSites.overlapsIn(List.of(base, new Site("B", 1, 110, 120, 110, 120)))
                                .size()
                        == 1,
                "two owners that share the column 110, 110 of layer 1 are not an overlap");
        expect(
                helper,
                TestSites.overlapsIn(List.of(base, new Site("B", 2, 100, 110, 100, 110)))
                        .isEmpty(),
                "boxes of two layers are an overlap");
        expect(
                helper,
                TestSites.overlapsIn(List.of(base, new Site("B", 1, 111, 120, 100, 110)))
                        .isEmpty(),
                "boxes one column apart are an overlap");
        expect(
                helper,
                TestSites.overlapsIn(List.of(base, new Site("A", 1, 105, 120, 105, 120)))
                        .isEmpty(),
                "two boxes of one owner are an overlap");
        helper.succeed();
    }

    /** This class claims nothing, so the guards that {@code RoomCarver.carve} and {@code FarChunks.force} call refuse a place far from the spawn square. */
    @GameTest
    public void stagingOutsideTheClaimsFails(GameTestHelper helper) {
        var level = helper.getLevel();
        int far = 900_000;
        try {
            TestSites.guardCarve(level, new BlockPos(far, 0, far), new BlockPos(far + 2, 2, far + 2));
            throw failure(helper, "a carve outside the claims was not refused");
        } catch (IllegalStateException refused) {
            expect(
                    helper,
                    String.valueOf(refused.getMessage()).contains("TestSitesTest carves"),
                    "the refusal does not name the owner: %s",
                    refused.getMessage());
        }
        try {
            TestSites.guardChunk(level, far / 16, far / 16);
            throw failure(helper, "a force outside the claims was not refused");
        } catch (IllegalStateException refused) {
            expect(
                    helper,
                    String.valueOf(refused.getMessage()).contains("TestSitesTest forces"),
                    "the refusal does not name the owner: %s",
                    refused.getMessage());
        }
        helper.succeed();
    }
}
