package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.test.support.TestSites;
import io.github.pkeppeler.deepcharter.test.support.TestSites.Site;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * Server GameTests for {@link TestSites} (#1182): the table has no overlap and no stale owner, and staging outside a
 * claim fails.
 */
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

    /**
     * This class claims nothing, so the guards that {@code RoomCarver.carve} and {@code FarChunks.force} call refuse a
     * place far from the spawn square.
     */
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

    /** A stack, innermost first, of a support helper called by a shared helper of this package that a test called. */
    @GameTest
    public void aHelperOfTheTestPackageIsTransparentAndTheOutermostTestClassOwns(GameTestHelper helper) {
        List<Class<?>> stack = List.of(TestSites.class, ChassisChecks.class, OddChassisTest.class, Thread.class);
        expect(
                helper,
                TestSites.ownerOf(stack).equals(Optional.of("OddChassisTest")),
                "the owner of %s is %s, not the test that called the helper",
                stack,
                TestSites.ownerOf(stack));
        expect(
                helper,
                TestSites.ownerOf(List.of(TestSites.class, Thread.class)).isEmpty(),
                "a stack with no test frame has an owner");
        helper.succeed();
    }

    @GameTest
    public void aCallNoTestOwnsFailsInsteadOfPassingSilently(GameTestHelper helper) {
        var level = helper.getLevel();
        int far = 900_000;
        List<Class<?>> noTest = List.of(TestSites.class, Thread.class);
        try {
            TestSites.guardCarve(level, new BlockPos(far, 0, far), new BlockPos(far + 2, 2, far + 2), noTest);
            throw failure(helper, "a carve no test owns passed");
        } catch (IllegalStateException refused) {
            expect(
                    helper,
                    String.valueOf(refused.getMessage()).contains("could not be attributed to a test class")
                            && String.valueOf(refused.getMessage()).contains(String.valueOf(far - 1)),
                    "the refusal does not name the place and the missing test: %s",
                    refused.getMessage());
        }
        try {
            TestSites.guardChunk(level, far / 16, far / 16, noTest);
            throw failure(helper, "a force no test owns passed");
        } catch (IllegalStateException refused) {
            expect(
                    helper,
                    String.valueOf(refused.getMessage()).contains("could not be attributed to a test class"),
                    "the refusal does not say the call has no test: %s",
                    refused.getMessage());
        }
        TestSites.guardCarve(level, new BlockPos(0, 0, 0), new BlockPos(2, 2, 2), noTest);
        helper.succeed();
    }

    @GameTest
    public void aCallbackAScheduledTestWrapsIsAttributedToItsTest(GameTestHelper helper) {
        var level = helper.getLevel();
        int far = 900_000;
        String[] refusal = {"none"};
        Runnable later = TestSites.attributed(() -> {
            try {
                TestSites.guardChunk(level, far / 16, far / 16, List.of(TestSites.class, Thread.class));
            } catch (IllegalStateException refused) {
                refusal[0] = String.valueOf(refused.getMessage());
            }
        });
        later.run();
        expect(
                helper,
                String.valueOf(refusal[0]).contains("TestSitesTest forces"),
                "a wrapped callback with no test frame under it is not attributed to the test that wrapped it: %s",
                refusal[0]);
        helper.succeed();
    }

    /**
     * {@code ColonyTest} reads the terrain at the surface column 9500, 9500, outside the spawn square, so it claims it
     * rather than being exempt.
     */
    @GameTest
    public void colonyTestClaimsTheSurfaceColumnItReads(GameTestHelper helper) {
        expect(
                helper,
                TestSites.sites().stream()
                        .anyMatch(site -> site.owner().equals("ColonyTest")
                                && site.layer() == 0
                                && site.x0() <= 9500
                                && 9500 <= site.x1()
                                && site.z0() <= 9500
                                && 9500 <= site.z1()),
                "ColonyTest reads the surface at 9500, 9500 and claims no box around it in TestSiteTable");
        helper.succeed();
    }

    /**
     * A claim for "the unbuilt depth" or "the last layer" names a symbol, not a number, so building a layer moves it
     * (#1182).
     */
    @GameTest
    public void aSymbolicClaimResolvesToTheCurrentLayerCount(GameTestHelper helper) {
        expect(
                helper,
                TestSites.LAST == CampaignBands.lastLayer() && TestSites.UNBUILT == CampaignBands.lastLayer() + 1,
                "LAST is %s and UNBUILT %s, with %s layers built",
                TestSites.LAST,
                TestSites.UNBUILT,
                CampaignBands.lastLayer());
        expect(
                helper,
                TestSites.sites().stream()
                        .anyMatch(site ->
                                site.owner().equals("SuitTimerTest") && site.layer() == CampaignBands.lastLayer() + 1),
                "SuitTimerTest does not claim the layer below the last built one");
        expect(
                helper,
                TestSites.sites().stream()
                        .anyMatch(site ->
                                site.owner().equals("BandReadersTest") && site.layer() == CampaignBands.lastLayer()),
                "BandReadersTest does not claim the last built layer");
        helper.succeed();
    }
}
