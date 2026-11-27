package io.github.pkeppeler.deepcharter.test.support;

import static io.github.pkeppeler.deepcharter.test.support.TestSites.LAST;
import static io.github.pkeppeler.deepcharter.test.support.TestSites.UNBUILT;
import static io.github.pkeppeler.deepcharter.test.support.TestSites.claim;
import static io.github.pkeppeler.deepcharter.test.support.TestSites.exempt;
import static io.github.pkeppeler.deepcharter.test.support.TestSites.row;

/**
 * The table of {@link TestSites}: every server test class that stages at a fixed place of the shared worlds, with the
 * block columns it takes in each layer (layer 0 is the surface). {@code LAST} is the last built layer and {@code
 * UNBUILT} the depth below it, resolved from {@code CampaignBands}, so a claim for either survives a new layer. A box
 * is one room with its shell ({@link RoomCarver} writes the shell one block outside the room) and whatever the test
 * moves or flies beyond it; a {@code row} is {@code count} boxes {@code step} columns apart. A test that only forces or
 * reads a chunk claims that chunk's 16 x 16 columns. Two owners never share a column of a layer: pick the next free
 * place and run {@code TestSitesTest}, which names the pair that does.
 */
final class TestSiteTable {
    private TestSiteTable() {}

    static void fill() {
        claim("ActiveStateTest", 0, -3536, -3521, 3536, 3551);

        row("AirCanisterTest", UNBUILT, 7805, 7815, 20, 3, 7395, 7405);

        // Each arena is the 7 x 7 chunks round its centre, at x = z.
        claim("AmbiencePlacesTest", 0, 40160, 40271, 40160, 40271);
        claim("AmbiencePlacesTest", 0, 40352, 40463, 40352, 40463);
        claim("AmbiencePlacesTest", 0, 40752, 40863, 40752, 40863);
        claim("AmbiencePlacesTest", 0, 40960, 41071, 40960, 41071);
        claim("AmbiencePlacesTest", 0, 41952, 42063, 41952, 42063);

        claim("BandFiguresTest", 0, 24059, 24069, 23995, 24005);
        claim("BandFiguresTest", 0, 24255, 24257, 23999, 24001);
        row("BandFiguresTest", 1, 23995, 24005, 128, 3, 23995, 24005);
        claim("BandFiguresTest", 2, 24027, 24037, 23995, 24005);
        claim("BandFiguresTest", 2, 24187, 24197, 23995, 24005);

        claim("BandReadersTest", 0, 23379, 23389, 22995, 23005);
        claim("BandReadersTest", 1, 23183, 23201, 22991, 23009);
        claim("BandReadersTest", 1, 23695, 23713, 22991, 23009);
        claim("BandReadersTest", 1, 23315, 23325, 22995, 23005);
        claim("BandReadersTest", 1, 22997, 23003, 22997, 23003);
        claim("BandReadersTest", 1, 23511, 23513, 22999, 23001);
        claim("BandReadersTest", 2, 23347, 23357, 22995, 23005);
        claim("BandReadersTest", LAST, 22997, 23003, 22997, 23003);

        row("BlastingChargeTest", 1, 2993, 3023, 64, 7, 5185, 5215);

        claim("BoltDriverPacingTest", 1, 3709, 3730, 7396, 7404);
        claim("BoltDriverPacingTest", 1, 4317, 4338, 7396, 7404);
        claim("BoltDriverPacingTest", 1, 3869, 3916, 7396, 7404);

        claim("BoltDriverTest", 1, 3197, 3218, 7396, 7404);
        claim("BoltDriverTest", 1, 3229, 3250, 7396, 7404);
        row("BoltDriverTest", 1, 3325, 3346, 32, 11, 7396, 7404);
        claim("BoltDriverTest", 1, 3965, 3986, 7396, 7404);
        row("BoltDriverTest", 1, 3997, 4018, 64, 4, 7396, 7404);
        claim("BoltDriverTest", 1, 4221, 4242, 7396, 7404);
        claim("BoltDriverTest", 1, 4253, 4274, 7396, 7404);
        claim("BoltDriverTest", 1, 4477, 4498, 7396, 7404);
        claim("BoltDriverTest", 1, 4509, 4530, 7396, 7404);
        claim("BoltDriverTest", 1, 3261, 3314, 7396, 7404);

        claim("BreachCrossingTest", 1, 1199, 1201, 1199, 1201);
        claim("BreachCrossingTest", 1, 1399, 1401, 1399, 1401);
        claim("BreachCrossingTest", 2, 1199, 1201, 1199, 1201);
        claim("BreachCrossingTest", 2, 1399, 1401, 1399, 1401);

        row("BreachCrustMoversTest", 1, 31040, 31056, 32, 10, 31072, 31088);
        claim("BreachCrustMoversTest", 2, 31302, 31306, 31078, 31082);
        row("BreachCrustMoversTest", 2, 31040, 31056, 32, 10, 31104, 31120);
        row("BreachCrustMoversTest", 3, 31040, 31056, 32, 10, 31136, 31152);
        claim("BreachCrustMoversTest", 3, 31302, 31306, 31110, 31114);
        claim("BreachCrustMoversTest", UNBUILT, 31302, 31306, 31142, 31146);

        claim("CampaignCrossingTest", 1, 21395, 21404, 21395, 21404);
        claim("CampaignCrossingTest", 2, 21395, 21404, 21395, 21404);

        claim("ColonyTest", 0, 9484, 9516, 9484, 9516);
        claim("ColonyTest", 0, 8910, 9090, 8910, 9090);

        row("CompanyRecoveryTest", 1, 11997, 12003, 100, 4, 11997, 12003);
        claim("CompanyRecoveryTest", 1, 12347, 12353, 11997, 12003);
        claim("CompanyRecoveryTest", 1, 12377, 12383, 11997, 12003);
        row("CompanyRecoveryTest", 1, 12397, 12403, 50, 9, 11997, 12003);
        claim("CompanyRecoveryTest", 2, 12247, 12253, 11997, 12003);

        row("CompanyRockBiteTest", 1, 4391, 4409, 64, 5, 5591, 5609);

        claim("CompanyRockLookTest", 1, 7499, 7501, 3007, 3009);
        claim("CompanyRockLookTest", 1, 3441, 3471, 5285, 5315);
        claim("CompanyRockLookTest", 1, 3517, 3523, 5361, 5367);
        claim("CompanyRockLookTest", 1, 3441, 3471, 5405, 5435);
        claim("CompanyRockLookTest", 1, 3441, 3471, 5525, 5555);
        claim("CompanyRockLookTest", 2, 7499, 7501, 3015, 3017);
        claim("CompanyRockLookTest", 1, 7520, 7567, 7520, 7567);
        claim("CompanyRockLookTest", 2, 7520, 7567, 7520, 7567);

        claim("DeepestPointTest", 1, 9095, 9104, 9095, 9104);

        claim("DimensionCrossingTest", 0, 30999, 31001, 30999, 31001);
        claim("DimensionCrossingTest", 0, 31099, 31101, 31099, 31101);
        claim("DimensionCrossingTest", 0, 31392, 31407, 31392, 31407);
        claim("DimensionCrossingTest", 0, 31596, 31604, 31596, 31604);
        claim("DimensionCrossingTest", 0, 31699, 31701, 31699, 31701);
        claim("DimensionCrossingTest", 0, 31799, 31801, 31799, 31801);
        claim("DimensionCrossingTest", 0, 31890, 31910, 31890, 31910);
        claim("DimensionCrossingTest", 0, 31998, 32002, 31998, 32002);
        claim("DimensionCrossingTest", 0, 32192, 32207, 32192, 32207);

        claim("DrifterSpawnTest", 1, 25007, 25057, 25008, 25024);
        claim("DrifterSpawnTest", 1, 25888, 25908, 25888, 25908);
        claim("DrifterSpawnTest", 2, 25007, 25057, 25008, 25024);
        claim("DrifterSpawnTest", 3, 25007, 25057, 25008, 25024);
        claim("DrifterSpawnTest", 2, 25488, 25503, 25488, 25503);
        claim("DrifterSpawnTest", 2, 25692, 25711, 25692, 25711);

        claim("DrifterTest", 0, 9216, 9232, 9216, 9232);
        claim("DrifterTest", 0, 9216, 9232, 9280, 9296);
        claim("DrifterTest", 0, 9216, 9232, 9344, 9360);
        claim("DrifterTest", 0, 9216, 9232, 9408, 9424);
        claim("DrifterTest", 0, 9216, 9232, 9472, 9488);
        claim("DrifterTest", 0, 9216, 9232, 9536, 9552);
        claim("DrifterTest", 0, 9216, 9232, 9600, 9616);
        claim("DrifterTest", 0, 9216, 9232, 9664, 9680);
        claim("DrifterTest", 0, 9216, 9232, 9728, 9744);
        claim("DrifterTest", 0, 9216, 9232, 9792, 9808);
        claim("DrifterTest", 0, 9216, 9232, 9856, 9872);
        claim("DrifterTest", 1, 25992, 26015, 25992, 26015);

        claim("EmptySurfaceTest", 0, 24995, 25005, 24995, 25005);
        claim("EmptySurfaceTest", 1, 25027, 25037, 24995, 25005);
        claim("EmptySurfaceTest", 2, 25059, 25069, 24995, 25005);

        claim("EngineSootPersistenceTest", 1, 34098, 34102, 34098, 34102);
        claim("EngineSootPersistenceTest", 2, 34098, 34102, 34098, 34102);

        claim("EntityQueryAuditTest", 0, 16000, 16015, 16000, 16015);
        claim("EntityQueryAuditTest", 0, 16192, 16207, 16192, 16207);
        claim("EntityQueryAuditTest", 0, 16288, 16303, 16288, 16303);
        claim("EntityQueryAuditTest", 0, 20000, 20015, 20000, 20015);
        claim("EntityQueryAuditTest", 0, 40000, 40015, 40000, 40015);

        claim("EntityTickingGateTest", 0, 95980, 96700, 95980, 96040);

        claim("FarChunksTest", 0, 64000, 64015, 64000, 64015);
        claim("FarChunksTest", 0, 64080, 64095, 64080, 64095);
        claim("FarChunksTest", 0, 64160, 64175, 64160, 64175);
        claim("FarChunksTest", 0, 64320, 64335, 64320, 64335);

        claim("FungalHollowsTest", 3, 368, 543, 368, 543);

        row("GrainedCrustTest", 1, 4591, 4609, 40, 11, 6191, 6209);
        row("GrainedCrustTest", 1, 5071, 5089, 40, 4, 6191, 6209);

        // One strip per layer: its rooms stand 20 to 100 columns apart along z 7700.
        claim("HandBreakTest", 1, 7696, 10414, 7695, 7705);
        claim("HandBreakTest", 2, 7816, 10633, 7695, 7705);
        claim("HandBreakTest", 3, 7836, 10416, 7695, 7705);

        claim("HandbookChaptersSixToNineTest", 0, 7200, 7215, 7200, 7215);
        claim("HandbookChaptersSixToNineTest", 1, 12992, 13007, 12992, 13007);
        claim("HandbookChaptersSixToNineTest", 2, 12992, 13007, 12992, 13007);

        claim("KellsShaftBoreTest", 1, 15536, 15551, 15488, 15503);
        claim("KellsShaftBoreTest", 1, 15952, 15967, 15488, 15503);

        claim("LamplessFigureTest", 0, 6000, 6015, 6000, 6015);
        claim("LamplessFigureTest", 0, 6000, 6015, 6064, 6079);
        claim("LamplessFigureTest", 0, 6000, 6015, 6128, 6143);
        claim("LamplessFigureTest", 0, 6000, 6015, 6192, 6207);
        claim("LamplessFigureTest", 0, 6000, 6015, 6320, 6335);
        claim("LamplessFigureTest", 0, 6000, 6015, 6448, 6463);
        claim("LamplessFigureTest", 0, 6000, 6015, 6512, 6527);
        claim("LamplessFigureTest", 0, 6000, 6015, 6576, 6591);
        claim("LamplessFigureTest", 1, 3760, 3775, 3616, 3695);

        row("LavaBoreTest", 1, 40006, 40009, 32, 3, 41606, 41609);

        claim("LayerConceptsTest", 1, 3007, 3112, 3007, 3029);
        claim("LayerConceptsTest", 2, 3135, 3240, 3007, 3029);
        claim("LayerConceptsTest", 2, 3263, 3368, 3007, 3029);

        claim("LayerDimensionsTest", 2, 299999, 300001, 299999, 300001);

        claim("LayerStructuresTest", 0, 44000, 44015, 44000, 44015);
        // The layer structure sites StructureSite places from the seed, forced in layers 1 and 2: both claimed.
        claim("LayerStructuresTest", 1, 1280, 1311, 1248, 1295);
        claim("LayerStructuresTest", 1, 1344, 1359, 1312, 1391);
        claim("LayerStructuresTest", 1, 1392, 1439, 1200, 1279);
        claim("LayerStructuresTest", 1, 1472, 1487, 1472, 1487);
        claim("LayerStructuresTest", 1, 1504, 1519, 1344, 1359);
        claim("LayerStructuresTest", 2, 1280, 1311, 1248, 1295);
        claim("LayerStructuresTest", 2, 1344, 1359, 1312, 1391);
        claim("LayerStructuresTest", 2, 1392, 1439, 1200, 1279);
        claim("LayerStructuresTest", 2, 1472, 1487, 1472, 1487);
        claim("LayerStructuresTest", 2, 1504, 1519, 1344, 1359);
        // Sites StructureSite picks by seed lie anywhere in their 384-block cell: a cell is claimed whole, but for
        // the boxes other tests claim in it.
        claim("LayerStructuresTest", 1, 1152, 1535, 1152, 1198);
        claim("LayerStructuresTest", 1, 1152, 1535, 1202, 1398);
        claim("LayerStructuresTest", 1, 1152, 1535, 1402, 1535);
        claim("LayerStructuresTest", 1, 1152, 1398, 1399, 1401);
        claim("LayerStructuresTest", 1, 1402, 1535, 1399, 1401);
        claim("LayerStructuresTest", 1, 1152, 1198, 1199, 1201);
        claim("LayerStructuresTest", 1, 1202, 1535, 1199, 1201);
        claim("LayerStructuresTest", 1, 2304, 2687, 2304, 2397);
        claim("LayerStructuresTest", 1, 2304, 2687, 2403, 2498);
        claim("LayerStructuresTest", 1, 2304, 2687, 2502, 2598);
        claim("LayerStructuresTest", 1, 2304, 2687, 2602, 2687);
        claim("LayerStructuresTest", 1, 2304, 2598, 2599, 2601);
        claim("LayerStructuresTest", 1, 2602, 2687, 2599, 2601);
        claim("LayerStructuresTest", 1, 2304, 2498, 2499, 2501);
        claim("LayerStructuresTest", 1, 2502, 2687, 2499, 2501);
        claim("LayerStructuresTest", 1, 2304, 2397, 2398, 2402);
        claim("LayerStructuresTest", 1, 2403, 2497, 2398, 2402);
        claim("LayerStructuresTest", 1, 2503, 2547, 2398, 2402);
        claim("LayerStructuresTest", 1, 2553, 2597, 2398, 2402);
        claim("LayerStructuresTest", 1, 2603, 2647, 2398, 2402);
        claim("LayerStructuresTest", 1, 2653, 2687, 2398, 2402);
        claim("LayerStructuresTest", 1, 2688, 3071, 2688, 2994);
        claim("LayerStructuresTest", 1, 2688, 3071, 3006, 3006);
        claim("LayerStructuresTest", 1, 2688, 2994, 2995, 3005);
        claim("LayerStructuresTest", 1, 3007, 3058, 2995, 3005);
        claim("LayerStructuresTest", 1, 3071, 3071, 2995, 3005);
        claim("LayerStructuresTest", 1, 2688, 3071, 3030, 3071);
        claim("LayerStructuresTest", 1, 2688, 3006, 3007, 3029);
        claim("LayerStructuresTest", 1, 3072, 3455, 3072, 3194);
        claim("LayerStructuresTest", 1, 3072, 3455, 3206, 3455);
        claim("LayerStructuresTest", 1, 3072, 3194, 3195, 3205);
        claim("LayerStructuresTest", 1, 3207, 3258, 3195, 3205);
        claim("LayerStructuresTest", 1, 3271, 3322, 3195, 3205);
        claim("LayerStructuresTest", 1, 3335, 3455, 3195, 3205);
        claim("LayerStructuresTest", 1, 3456, 3839, 3456, 3497);
        claim("LayerStructuresTest", 1, 3456, 3839, 3503, 3535);
        claim("LayerStructuresTest", 1, 3456, 3839, 3552, 3615);
        claim("LayerStructuresTest", 1, 3456, 3679, 3536, 3551);
        claim("LayerStructuresTest", 1, 3696, 3839, 3536, 3551);
        claim("LayerStructuresTest", 1, 3456, 3497, 3498, 3502);
        claim("LayerStructuresTest", 1, 3503, 3839, 3498, 3502);
        claim("LayerStructuresTest", 1, 3456, 3839, 3696, 3697);
        claim("LayerStructuresTest", 1, 3456, 3839, 3703, 3797);
        claim("LayerStructuresTest", 1, 3456, 3839, 3803, 3839);
        claim("LayerStructuresTest", 1, 3456, 3797, 3798, 3802);
        claim("LayerStructuresTest", 1, 3803, 3839, 3798, 3802);
        claim("LayerStructuresTest", 1, 3456, 3697, 3698, 3702);
        claim("LayerStructuresTest", 1, 3703, 3839, 3698, 3702);
        claim("LayerStructuresTest", 1, 3456, 3759, 3616, 3695);
        claim("LayerStructuresTest", 1, 3776, 3839, 3616, 3695);
        claim("LayerStructuresTest", 1, 3840, 4223, 3840, 4223);
        claim("LayerStructuresTest", 1, 4224, 4607, 4224, 4607);
        claim("LayerStructuresTest", 1, 11520, 11903, 11520, 11903);
        claim("LayerStructuresTest", 1, 11904, 12287, 11904, 11996);
        claim("LayerStructuresTest", 1, 11904, 12287, 12004, 12287);
        claim("LayerStructuresTest", 1, 11904, 11996, 11997, 12003);
        claim("LayerStructuresTest", 1, 12004, 12096, 11997, 12003);
        claim("LayerStructuresTest", 1, 12104, 12196, 11997, 12003);
        claim("LayerStructuresTest", 1, 12204, 12287, 11997, 12003);
        claim("LayerStructuresTest", 1, 12288, 12671, 12288, 12671);
        claim("LayerStructuresTest", 1, 12672, 13055, 12672, 12991);
        claim("LayerStructuresTest", 1, 12672, 13055, 13008, 13055);
        claim("LayerStructuresTest", 1, 12672, 12991, 12992, 13007);
        claim("LayerStructuresTest", 1, 13008, 13055, 12992, 13007);
        claim("LayerStructuresTest", 1, 13056, 13439, 13056, 13439);
        claim("LayerStructuresTest", 1, 13440, 13823, 13440, 13823);
        claim("LayerStructuresTest", 2, 1152, 1535, 1152, 1198);
        claim("LayerStructuresTest", 2, 1152, 1535, 1202, 1398);
        claim("LayerStructuresTest", 2, 1152, 1535, 1402, 1535);
        claim("LayerStructuresTest", 2, 1152, 1398, 1399, 1401);
        claim("LayerStructuresTest", 2, 1402, 1535, 1399, 1401);
        claim("LayerStructuresTest", 2, 1152, 1198, 1199, 1201);
        claim("LayerStructuresTest", 2, 1202, 1535, 1199, 1201);
        claim("LayerStructuresTest", 2, 2304, 2687, 2304, 2399);
        claim("LayerStructuresTest", 2, 2304, 2687, 2416, 2687);
        claim("LayerStructuresTest", 2, 2304, 2399, 2400, 2415);
        claim("LayerStructuresTest", 2, 2416, 2687, 2400, 2415);
        claim("LayerStructuresTest", 2, 2688, 3071, 2688, 3071);
        claim("LayerStructuresTest", 2, 3072, 3455, 3072, 3455);
        claim("LayerStructuresTest", 2, 3456, 3839, 3456, 3839);
        claim("LayerStructuresTest", 2, 3840, 4223, 3840, 4223);
        claim("LayerStructuresTest", 2, 4224, 4607, 4224, 4607);
        claim("LayerStructuresTest", 2, 11520, 11903, 11520, 11903);
        claim("LayerStructuresTest", 2, 11904, 12287, 11904, 11996);
        claim("LayerStructuresTest", 2, 11904, 12287, 12004, 12287);
        claim("LayerStructuresTest", 2, 11904, 12246, 11997, 12003);
        claim("LayerStructuresTest", 2, 12254, 12287, 11997, 12003);
        claim("LayerStructuresTest", 2, 12288, 12671, 12288, 12671);
        claim("LayerStructuresTest", 2, 12672, 13055, 12672, 12991);
        claim("LayerStructuresTest", 2, 12672, 13055, 13008, 13055);
        claim("LayerStructuresTest", 2, 12672, 12991, 12992, 13007);
        claim("LayerStructuresTest", 2, 13008, 13055, 12992, 13007);
        claim("LayerStructuresTest", 2, 13056, 13439, 13056, 13439);
        claim("LayerStructuresTest", 2, 13440, 13823, 13440, 13823);

        claim("LayerTerrainTest", 2, 1795, 1804, 1795, 1804);
        claim("LayerTerrainTest", 1, 896, 975, 896, 975);
        claim("LayerTerrainTest", 2, 896, 975, 896, 975);
        claim("LayerTerrainTest", 1, 700, 747, 700, 747);
        claim("LayerTerrainTest", 2, 700, 747, 700, 747);
        claim("LayerTerrainTest", 1, 1695, 1705, 1695, 1705);
        claim("LayerTerrainTest", 2, 1695, 1705, 1695, 1705);
        claim("LayerTerrainTest", 1, 1745, 1755, 1745, 1755);
        claim("LayerTerrainTest", 2, 1745, 1755, 1745, 1755);

        // Chunks only, so the layer is inferred.
        claim("LightSyncRingHoldTest", 1, -36800, -36641, 40000, 40159);
        row("LightSyncRingHoldTest", 1, -36048, -35937, 320, 3, 39968, 40047);

        row("LightSyncSafetyNetTest", 1, -40048, -39937, 320, 3, 39968, 40047);
        claim("LightSyncSafetyNetTest", 1, -38720, -38705, 40000, 40015);
        claim("LightSyncSafetyNetTest", 1, -38688, -38673, 40000, 40015);
        claim("LightSyncSafetyNetTest", 1, -38080, -38065, 40000, 40015);
        claim("LightSyncSafetyNetTest", 1, -38048, -38033, 40000, 40015);

        claim("LightWatchReportTest", 0, -34410, -33700, 39990, 40030);

        claim("M2SliceTest", 1, 9296, 9311, 9296, 9311);
        claim("M2SliceTest", 2, 9296, 9311, 9296, 9311);

        row("OddChassisTest", 1, 8992, 9008, 48, 11, 8992, 9008);
        claim("OddChassisTest", 1, 8992, 9488, 8992, 9008);

        row("OreCargoTest", 1, 3195, 3206, 64, 3, 3195, 3205);

        claim("OreHostTest", 1, 7399, 7408, 3007, 3009);
        claim("OreHostTest", 2, 7399, 7408, 3015, 3017);
        claim("OreHostTest", 3, 7399, 7408, 3023, 3025);
        claim("OreHostTest", 1, 7040, 7055, 7040, 7055);

        claim("OrePlacementTest", 1, 7088, 7103, 2992, 3007);
        claim("OrePlacementTest", 1, 7792, 7807, 2992, 3007);
        claim("OrePlacementTest", 1, 8995, 9005, 2995, 3005);
        row("OrePlacementTest", 1, 9095, 9105, 32, 3, 2995, 3005);
        claim("OrePlacementTest", 1, 6992, 7007, 2992, 3007);
        claim("OrePlacementTest", 1, 7192, 7207, 2992, 3007);
        claim("OrePlacementTest", 2, 7192, 7207, 2992, 3007);
        claim("OrePlacementTest", 1, 7492, 7507, 2992, 3006);
        claim("OrePlacementTest", 1, 6400, 6495, 6400, 6495);
        claim("OrePlacementTest", 2, 6400, 6495, 6400, 6495);

        claim("OutpostHabitabilityTest", 1, 6391, 6409, 9491, 9509);
        claim("OutpostHabitabilityTest", 1, 6451, 6469, 9491, 9509);
        claim("OutpostHabitabilityTest", UNBUILT, 6515, 6525, 7395, 7405);

        claim("OutpostLeakTest", 1, 6631, 6649, 9491, 9509);
        claim("OutpostLeakTest", 1, 6947, 6965, 9491, 9509);
        claim("OutpostLeakTest", UNBUILT, 6575, 6585, 7395, 7405);

        claim("OutpostRiskLineTest", 1, 6755, 6773, 9491, 9509);
        claim("OutpostRiskLineTest", 1, 6883, 6901, 9491, 9509);
        claim("OutpostRiskLineTest", UNBUILT, 6823, 6833, 7395, 7405);

        claim("OutpostSettlingReloadTest", 1, 10353, 10383, 9585, 9615);

        claim("OutpostSettlingSweepTest", 1, 9691, 9709, 9491, 9509);

        claim("OutpostSettlingTest", 1, 9291, 9309, 9491, 9509);
        row("OutpostSettlingTest", 1, 9649, 9679, 64, 10, 9585, 9615);

        row("OutpostTest", 1, 7991, 8009, 100, 4, 7191, 7209);
        claim("OutpostTest", 1, 8331, 8349, 7191, 7209);
        claim("OutpostTest", 1, 8391, 8409, 7191, 7209);
        claim("OutpostTest", 1, 8491, 8509, 7191, 7209);
        row("OutpostTest", 1, 8691, 8709, 100, 3, 7191, 7209);
        row("OutpostTest", 1, 9091, 9109, 100, 3, 7191, 7209);
        row("OutpostTest", 1, 9591, 9609, 100, 6, 7191, 7209);
        claim("OutpostTest", 1, 10291, 10309, 7191, 7209);
        claim("OutpostTest", 1, 10391, 10409, 7191, 7209);
        claim("OutpostTest", 1, 10191, 10211, 7191, 7209);
        claim("OutpostTest", 1, 10691, 10711, 7191, 7209);
        claim("OutpostTest", 1, 8991, 9019, 7191, 7209);
        claim("OutpostTest", 2, 8498, 8503, 7198, 7203);

        claim("OvergrowthTest", 1, 4990, 5900, 6590, 6610);
        claim("OvergrowthVeneerTest", 1, 4990, 5740, 6890, 6910);
        claim("PodAttachmentTest", 1, 1998, 2002, 1998, 2002);
        claim("PodAttachmentTest", 2, 1998, 2002, 1998, 2002);

        claim("PodBraceTest", 1, 4095, 4105, 3795, 3805);

        claim("PodDrillTest", 0, 3123, 3134, 2995, 3005);
        claim("PodDrillTest", 0, 3187, 3198, 2995, 3005);
        claim("PodDrillTest", 1, 3947, 3974, 2987, 3013);
        claim("PodDrillTest", 1, 3251, 3260, 2995, 3004);
        claim("PodDrillTest", 1, 3315, 3324, 2995, 3004);
        claim("PodDrillTest", 1, 2995, 3006, 2995, 3005);
        claim("PodDrillTest", 1, 3059, 3070, 2995, 3005);
        claim("PodDrillTest", 1, 3379, 3390, 2995, 3005);
        claim("PodDrillTest", 1, 3443, 3454, 2995, 3005);
        row("PodDrillTest", 1, 3635, 3646, 64, 5, 2995, 3005);
        row("PodDrillTest", 1, 4019, 4030, 64, 3, 2995, 3005);
        claim("PodDrillTest", 1, 3509, 3515, 2997, 3003);
        claim("PodDrillTest", 2, 3509, 3515, 2997, 3003);
        claim("PodDrillTest", LAST, 3573, 3579, 2997, 3003);
        claim("PodDrillTest", UNBUILT, 3123, 3134, 2995, 3005);
        claim("PodDrillTest", UNBUILT, 3187, 3198, 2995, 3005);
        row("PodDrillTest", 1, 9295, 9306, 64, 5, 2995, 3005);

        claim("PodDriveStepTest", 1, 4259, 4270, 2995, 3005);
        claim("PodDriveStepTest", 1, 4259, 4270, 3011, 3021);
        claim("PodDriveStepTest", 1, 4259, 4270, 3027, 3037);
        claim("PodDriveStepTest", 1, 4259, 4270, 3043, 3053);
        claim("PodDriveStepTest", 1, 4259, 4270, 3059, 3069);
        claim("PodDriveStepTest", 1, 4259, 4270, 3075, 3085);
        claim("PodDriveStepTest", 1, 4259, 4270, 3091, 3101);
        claim("PodDriveStepTest", 1, 4259, 4270, 3107, 3117);
        claim("PodDriveStepTest", 1, 4259, 4270, 3123, 3133);
        claim("PodDriveStepTest", 1, 4259, 4270, 3139, 3149);
        claim("PodDriveStepTest", 1, 4259, 4270, 3155, 3165);
        claim("PodDriveStepTest", 1, 4259, 4270, 3171, 3181);
        claim("PodDriveStepTest", 1, 4259, 4270, 3187, 3197);
        claim("PodDriveStepTest", 1, 4259, 4270, 3203, 3213);
        claim("PodDriveStepTest", 1, 4259, 4270, 3219, 3229);
        claim("PodDriveStepTest", 1, 4259, 4270, 3235, 3245);
        claim("PodDriveStepTest", 1, 4259, 4270, 3251, 3261);
        claim("PodDriveStepTest", 1, 4259, 4270, 3267, 3277);
        claim("PodDriveStepTest", 1, 4259, 4270, 3283, 3293);
        claim("PodDriveStepTest", 1, 4259, 4270, 3299, 3309);

        claim("PodFuelTripTest", 1, 7396, 7410, 7396, 7404);
        claim("PodFuelTripTest", 1, 7426, 7440, 7396, 7404);

        claim("PodHardLandingTest", 1, 2398, 2402, 2398, 2402);
        row("PodHardLandingTest", 1, 2498, 2502, 50, 5, 2398, 2402);

        row("PodHazardCuesTest", 1, 9793, 9807, 48, 4, 9793, 9807);

        row("PodLavaWatchTest", 1, 6996, 7021, 64, 16, 5696, 5704);

        claim("PodLightsTest", 0, 3040, 3055, 3040, 3055);
        claim("PodLightsTest", 0, 5600, 5615, 5600, 5615);
        claim("PodLightsTest", 1, 3498, 3502, 3498, 3502);

        row("PodLinerTest", 1, 5199, 5210, 48, 23, 3411, 3421);

        row("PodLiningDrillTest", 1, 3695, 3706, 64, 4, 3295, 3305);

        row("PodNearLethalLandingTest", 1, 22000, 22014, 64, 6, 22000, 22008);

        row("PodSonarTest", 1, 6973, 7027, 64, 19, 6973, 7027);
        claim("PodSonarTest", 1, 8189, 8243, 6973, 7027);
        claim("PodSonarTest", 1, 8273, 8361, 6973, 7027);
        claim("PodSonarTest", 1, 8573, 8627, 6973, 7027);
        claim("PodSonarTest", 1, 8637, 8691, 6973, 7027);
        claim("PodSonarTest", 1, 8701, 8773, 6973, 7027);
        claim("PodSonarTest", 1, 9073, 9127, 6973, 7027);
        claim("PodSonarTest", 1, 9137, 9225, 6973, 7027);

        row("PodSounderTest", 1, 5993, 6007, 48, 21, 3593, 3607);

        claim("PodSprintTest", 1, 18990, 19999, 18990, 19999);

        claim("PodStatsTest", 1, 3996, 4009, 2996, 3005);
        claim("PodStatsTest", 1, 4061, 4067, 2997, 3003);
        claim("PodStatsTest", 1, 4125, 4131, 2997, 3003);
        claim("PodStatsTest", 2, 4061, 4067, 2997, 3003);

        claim("ProspectorChassisTest", 1, 3394, 3406, 2994, 3006);
        claim("ProspectorChassisTest", 1, 3698, 3702, 3698, 3702);
        claim("ProspectorChassisTest", 1, 3798, 3802, 3798, 3802);
        claim("ProspectorChassisTest", 1, 3680, 3695, 3536, 3551);

        claim("RepairStationTest", 1, 27392, 27407, 27392, 27407);
        claim("RepairStationTest", 1, 27488, 27503, 27488, 27503);
        claim("RepairStationTest", 1, 27600, 27615, 27600, 27615);
        claim("RepairStationTest", 1, 27696, 27711, 27696, 27711);
        claim("RepairStationTest", 2, 27392, 27407, 27392, 27407);
        claim("RepairStationTest", 2, 27488, 27503, 27488, 27503);
        claim("RepairStationTest", 2, 27600, 27615, 27600, 27615);
        claim("RepairStationTest", 2, 27696, 27711, 27696, 27711);

        claim("RetainedOnFootTest", 0, 7424, 7439, 7424, 7439);
        claim("RetainedOnFootTest", 0, 7424, 7439, 7488, 7503);
        claim("RetainedOnFootTest", 0, 7424, 7439, 7552, 7567);
        claim("RetainedOnFootTest", 0, 7424, 7439, 7616, 7631);
        claim("RetainedOnFootTest", 0, 7424, 7439, 7680, 7695);
        claim("RetainedOnFootTest", 0, 7424, 7439, 7744, 7759);
        claim("RetainedOnFootTest", 0, 7424, 7439, 7808, 7823);
        claim("RetainedOnFootTest", 0, 7424, 7439, 7872, 7887);
        claim("RetainedOnFootTest", 0, 7424, 7439, 7936, 7951);
        claim("RetainedOnFootTest", 0, 7424, 7439, 8000, 8015);
        claim("RetainedOnFootTest", 0, 7424, 7439, 8064, 8079);
        claim("RetainedOnFootTest", 0, 7424, 7439, 8128, 8143);
        claim("RetainedOnFootTest", 0, 7424, 7439, 8192, 8207);
        claim("RetainedOnFootTest", 0, 7424, 7439, 8256, 8271);
        claim("RetainedOnFootTest", 0, 7424, 7439, 8320, 8335);
        claim("RetainedOnFootTest", 0, 7424, 7439, 8384, 8399);

        claim("RetainedTest", 0, 6400, 6415, 6400, 6415);
        claim("RetainedTest", 0, 6400, 6415, 6464, 6479);
        claim("RetainedTest", 0, 6400, 6415, 6592, 6607);
        claim("RetainedTest", 0, 6400, 6415, 6656, 6671);
        claim("RetainedTest", 0, 6400, 6415, 6720, 6735);
        claim("RetainedTest", 0, 6400, 6415, 6784, 6799);
        claim("RetainedTest", 0, 6400, 6415, 6848, 6863);
        claim("RetainedTest", 0, 6400, 6415, 6912, 6927);
        claim("RetainedTest", 0, 6400, 6415, 6976, 6991);
        claim("RetainedTest", 0, 6400, 6415, 7040, 7055);
        claim("RetainedTest", 0, 6400, 6415, 7104, 7119);
        claim("RetainedTest", 0, 6400, 6415, 7232, 7247);
        claim("RetainedTest", 0, 6400, 6415, 7296, 7311);
        claim("RetainedTest", 0, 6400, 6415, 7360, 7375);
        claim("RetainedTest", 0, 6400, 6415, 7424, 7439);
        claim("RetainedTest", 1, 7204, 7212, 7204, 7212);
        claim("RetainedTest", 2, 7204, 7212, 7204, 7212);
        claim("RetainedTest", 2, 7268, 7276, 7204, 7212);
        claim("RetainedTest", 1, 6368, 6399, 6592, 6607);
        claim("RetainedTest", 1, 6368, 6399, 6784, 6799);

        claim("RoomCarverSendTest", 1, 26403, 26411, 26403, 26411);
        claim("RoomCarverSendTest", 1, 26803, 26811, 26803, 26811);
        claim("RoomCarverSendTest", 2, 26601, 26615, 26601, 26615);

        claim("SealedRoomArrivalLightTest", 1, -60016, -59985, 59984, 60015);

        // The campaign level's chunk (2688, 2500) at layer 1, forced for a mock the seam test holds there (index 3).
        claim("SeamStreamingTest", 1, 43008, 43023, 40000, 40015);

        row("ShapedChargeTest", 1, 4001, 4031, 64, 6, 5385, 5415);
        claim("ShapedChargeTest", 1, 4400, 4415, 5392, 5407);

        row("ShippedChassisTest", 1, 10992, 11008, 48, 10, 8992, 9008);
        row("ShippedChassisTest", 1, 11992, 12008, 48, 10, 8992, 9008);

        claim("StructureLightBorderTest", 1, 15456, 15503, 15456, 15519);
        claim("StructureLightBorderTest", 1, 18128, 18175, 15472, 15535);
        claim("StructureLightBorderTest", 1, 16576, 16623, 15552, 15615);
        claim("StructureLightBorderTest", 1, 41488, 41551, 15584, 15631);

        row("SuitPacingTest", UNBUILT, 7395, 7405, 64, 3, 7395, 7405);

        claim("SuitTimerTest", 0, 6395, 6405, 7395, 7405);
        claim("SuitTimerTest", 1, 6443, 6453, 7395, 7405);
        claim("SuitTimerTest", 2, 6491, 6501, 7395, 7405);
        row("SuitTimerTest", UNBUILT, 6595, 6605, 100, 7, 7395, 7405);

        row("SuitTracksTest", UNBUILT, 7595, 7605, 50, 4, 7395, 7405);

        claim("SurfaceBreachTest", 0, 2499, 2501, 2499, 2501);
        claim("SurfaceBreachTest", 0, 2599, 2601, 2599, 2601);
        claim("SurfaceBreachTest", 1, 2499, 2501, 2499, 2501);
        claim("SurfaceBreachTest", 1, 2599, 2601, 2599, 2601);

        claim("SurfaceTerrainTest", 0, -1040, 1040, -1040, 1040);

        claim("SurfaceWritesTest", 0, 16001590, 16001625, 16001590, 16001625);

        row("TallChassisTest", 1, 9992, 10008, 48, 11, 8992, 9008);

        claim("TallyTest", 0, 6800, 6815, 6800, 6815);
        claim("TallyTest", 0, 6800, 6815, 6864, 6879);
        claim("TallyTest", 0, 6800, 6815, 6928, 6943);
        claim("TallyTest", 0, 6800, 6815, 6992, 7007);
        claim("TallyTest", 0, 6800, 6815, 7056, 7071);
        claim("TallyTest", 0, 6800, 6815, 7120, 7135);
        claim("TallyTest", 0, 6800, 6815, 7184, 7199);
        claim("TallyTest", 0, 6800, 6815, 7248, 7263);
        claim("TallyTest", 0, 6800, 6815, 7376, 7391);
        claim("TallyTest", 0, 6800, 6815, 7440, 7455);
        claim("TallyTest", 0, 6800, 6815, 7504, 7519);
        claim("TallyTest", 0, 6800, 6815, 7568, 7583);
        claim("TallyTest", 0, 6800, 6815, 7632, 7647);
        claim("TallyTest", 0, 6800, 6815, 7696, 7711);
        claim("TallyTest", 0, 6800, 6815, 7760, 7775);
        claim("TallyTest", 0, 52000, 52015, 52000, 52015);
        claim("TallyTest", 1, 6800, 6815, 7824, 7839);
        claim("TallyTest", 1, 6800, 6815, 7888, 7903);

        claim("TeleportArrivalTest", 0, 13984, 14031, -16, 31);

        row("TetherTest", 1, 16999, 17051, 64, 14, 16999, 17027);
        claim("TetherTest", 1, 17888, 17903, 17008, 17023);

        claim("ThrownKitPacingTest", 1, 7751, 7801, 5375, 5425);
        claim("ThrownKitPacingTest", 1, 7815, 7865, 5375, 5425);
        claim("ThrownKitPacingTest", 1, 8391, 8441, 5375, 5425);

        row("ThrownKitTest", 1, 6983, 7033, 64, 12, 5375, 5425);
        row("ThrownKitTest", 1, 7879, 7929, 64, 8, 5375, 5425);

        claim("TowingTest", 1, 2098, 2102, 2098, 2102);
        claim("TowingTest", 1, 2198, 2202, 2198, 2202);
        claim("TowingTest", 1, 14999, 15041, 15007, 15019);
        claim("TowingTest", 1, 15099, 15141, 15007, 15019);
        row("TowingTest", 1, 15299, 15341, 100, 5, 15007, 15019);
        claim("TowingTest", 1, 15149, 15269, 15007, 15019);
        row("TowingTest", 1, 15294, 15328, 128, 3, 15102, 15120);
        claim("TowingTest", 2, 2098, 2102, 2098, 2102);
        claim("TowingTest", 2, 2198, 2202, 2198, 2202);

        claim("VoidBelowTest", 1, 9096, 9104, 9146, 9154);
        claim("VoidBelowTest", 2, 9096, 9104, 9146, 9154);
        claim("VoidBelowTest", 1, 9196, 9204, 9146, 9154);
        claim("VoidBelowTest", 2, 9196, 9204, 9146, 9154);
        claim("VoidBelowTest", 1, 9296, 9304, 9146, 9154);
        claim("VoidBelowTest", 2, 9296, 9304, 9146, 9154);
        claim("VoidBelowTest", 1, 9396, 9404, 9146, 9154);
        claim("VoidBelowTest", 2, 9396, 9404, 9146, 9154);
        claim("VoidBelowTest", 1, 9496, 9504, 9146, 9154);
        claim("VoidBelowTest", 2, 9496, 9504, 9146, 9154);
        claim("VoidBelowTest", 1, 9596, 9604, 9146, 9154);
        claim("VoidBelowTest", 2, 9596, 9604, 9146, 9154);
        claim("VoidBelowTest", 1, 9696, 9704, 9146, 9154);
        claim("VoidBelowTest", 2, 9696, 9704, 9146, 9154);

        claim("WorkOrdersTest", 0, 14000, 14015, 14000, 14015);
        claim("WorkOrdersTest", 0, 14992, 15007, 14992, 15007);

        claim("WreckTest", 2, 2400, 2415, 2400, 2415);
        claim("WreckTest", 1, 4597, 4603, 2997, 3003);
        claim("TransmissionsTest", 1, 28368, 28447, 28368, 28447);
        claim("TransmissionsTest", 2, 28368, 28447, 28368, 28447);
        claim("TransmissionsTest", 2, 28576, 28639, 28576, 28639);

        String spawn =
                "stages inside the spawn square, which every test world shares (the colony, the content surveys)";
        exempt("CampaignAtmosphereTest", spawn);
        exempt("CampaignContentTest", spawn);
        exempt("ColonyInteriorLightTest", spawn);
        exempt("ColonyPhonographTest", spawn);
        exempt("ColonyPlacementTest", spawn);
        exempt("FoundingMoleHangarTest", spawn);
        exempt("GrainedCrustDecoyTest", spawn);
        exempt("HandbookChaptersOneToFiveTest", spawn);
        exempt("LayerAtmosphereTest", spawn);
        exempt("PencilPathsTest", spawn);
        exempt("SalvageBootstrapTest", spawn);
        String structure =
                "stages at its own GameTest structure, which the runner places at a different spot of the world per run";
        exempt("FlueScrapeTest", structure);
        exempt("GiantFungusTest", structure);
        exempt("PhonographTest", structure);
        exempt("PodSyncTest", structure);
        exempt("StalkCollapseTest", structure);
        String ownDimension =
                "stages only in deepcharter:campaign_full, a dimension of its own that the guards do not check";
        exempt("CampaignTerrainTest", ownDimension);
        exempt("ClaimFeaturesTest", ownDimension);
        exempt("TheClaimTest", ownDimension);
        exempt("ContinuityEventTest", "stages only in the Nether, which the guards do not check");
        exempt(
                "ObtainableItemsTest",
                "only reads the layer structure sites the world generates, the cells LayerStructuresTest claims");
        String layerDimensions =
                "stages only in the layer dimensions, which the guards do not check; SeamSite's index keeps"
                        + " the seam tests' sites apart";
        exempt("SeamAscentTest", layerDimensions);
        exempt("SeamSwapTest", layerDimensions);
        exempt(
                "CampaignCostTest",
                "measures a world of its own, deepcharter:campaign_layer_1, which no other test stages in");
    }
}
