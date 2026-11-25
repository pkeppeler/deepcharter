package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.colony.ColonyBlocks;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.colony.ColonyTuning;
import io.github.pkeppeler.deepcharter.handbook.HandbookProgress;
import io.github.pkeppeler.deepcharter.layer.BreachEvents;
import io.github.pkeppeler.deepcharter.layer.BreachPayload;
import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.Depth;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodTowing;
import io.github.pkeppeler.deepcharter.pod.TowTuning;
import io.github.pkeppeler.deepcharter.test.support.CampaignEnvironments;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestColony;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import io.github.pkeppeler.deepcharter.transmission.TransmissionEvents;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Server GameTests for breach crossing inside the campaign world (#745, ADR 0061), in {@code deepcharter:campaign_sample}: the layers
 * are Y bands of one level, so a crossing is noticed as the root vehicle passes a band boundary and moves nobody. Columns 21000 to
 * 21999 are this class's.
 */
public class CampaignCrossingTest {
    private static final String SAMPLE = "deepcharter:campaign_sample";
    private static final int STEP_TICKS = 3;
    private static final int SETTLE_TICKS = 40;

    /** The crossings seen for each entity, as {@code from>to} when both levels are the same and {@code from>to moved} otherwise. */
    private static final Map<UUID, List<String>> CROSSED = new ConcurrentHashMap<>();

    private static final Map<UUID, List<Identifier>> DELIVERED = new ConcurrentHashMap<>();

    static {
        BreachEvents.CROSSED.register((entity, from, to, fromLayer, toLayer) -> CROSSED.computeIfAbsent(
                        entity.getUUID(), uuid -> new CopyOnWriteArrayList<>())
                .add(fromLayer + ">" + toLayer + (from == to ? "" : " moved")));
        TransmissionEvents.DELIVERED.register((server, charter, player, transmission) -> DELIVERED
                .computeIfAbsent(player.getUUID(), uuid -> new CopyOnWriteArrayList<>())
                .add(transmission.id()));
    }

    /** Where the player is put, and every crossing that must have been seen once the player has stood there. */
    private record Step(double y, List<String> crossings) {
        Step(double y, String... crossings) {
            this(y, List.of(crossings));
        }
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("deepcharter", path);
    }

    private static List<String> crossings(Entity entity) {
        return CROSSED.getOrDefault(entity.getUUID(), List.of());
    }

    private static MockPlayer floater(GameTestHelper helper, String name, double x, double z, double y) {
        MockPlayer mock = MockPlayers.join(helper, name);
        mock.player().setNoGravity(true);
        mock.player().setPermanentlyInvulnerable(true);
        mock.teleportTo(helper.getLevel(), new Vec3(x, y, z), 0, 0);
        return mock;
    }

    @GameTest(environment = CampaignEnvironments.D, dimension = SAMPLE, maxTicks = 1000)
    public void eachDescentAndAscentRaisesOneEventAndJitterInTheCrustNone(GameTestHelper helper) {
        int layer1Floor = CampaignBands.LAYER_1.minY();
        int crust = CampaignBands.crustRows();
        int surfaceFloor = CampaignBands.SURFACE.minY();
        MockPlayer mock = floater(helper, "cross-steps", 21000.5, 21000.5, layer1Floor + 100);
        List<Step> steps = List.of(
                new Step(layer1Floor + 100),
                new Step(layer1Floor + 1),
                new Step(layer1Floor - 0.5, "1>2"),
                new Step(layer1Floor + 1, "1>2"),
                new Step(layer1Floor - 1, "1>2"),
                new Step(layer1Floor + crust - 0.5, "1>2"),
                new Step(layer1Floor + crust + 0.5, "1>2", "2>1"),
                new Step(layer1Floor + 2, "1>2", "2>1"),
                new Step(layer1Floor - 0.5, "1>2", "2>1", "1>2"),
                new Step(CampaignBands.UNBUILT.minY() + 100, "1>2", "2>1", "1>2", "2>3"),
                new Step(layer1Floor + 100, "1>2", "2>1", "1>2", "2>3", "3>2", "2>1"),
                new Step(surfaceFloor + crust + 0.5, "1>2", "2>1", "1>2", "2>3", "3>2", "2>1", "1>0"),
                new Step(surfaceFloor + 1, "1>2", "2>1", "1>2", "2>3", "3>2", "2>1", "1>0"),
                new Step(surfaceFloor - 0.5, "1>2", "2>1", "1>2", "2>3", "3>2", "2>1", "1>0", "0>1"),
                new Step(surfaceFloor + 1, "1>2", "2>1", "1>2", "2>3", "3>2", "2>1", "1>0", "0>1"),
                new Step(surfaceFloor + 100, "1>2", "2>1", "1>2", "2>3", "3>2", "2>1", "1>0", "0>1", "1>0"));
        TestTicks.each(helper, () -> {
            long tick = helper.getTick();
            int index = (int) (tick / STEP_TICKS);
            if (tick % STEP_TICKS == STEP_TICKS - 1 && index < steps.size()) {
                Step step = steps.get(index);
                List<String> expected = step.crossings();
                if (!crossings(mock.player()).equals(expected)) {
                    throw failure(
                            helper,
                            "with the player at y=%s (step %d) the crossings should be %s, were %s",
                            step.y(),
                            index,
                            expected,
                            crossings(mock.player()));
                }
                if (index == steps.size() - 1) {
                    helper.succeed();
                    return;
                }
                Step next = steps.get(index + 1);
                mock.teleportTo(helper.getLevel(), new Vec3(21000.5, next.y(), 21000.5), 0, 0);
            }
        });
    }

    @GameTest(environment = CampaignEnvironments.D, dimension = SAMPLE, maxTicks = 1000)
    public void aDescentSendsTheTransmissionOpensChapterEightPlaysTheHymnAndRaisesTheDeepestPoint(
            GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        int layer1Floor = CampaignBands.LAYER_1.minY();
        MockPlayer mock = floater(helper, "cross-reacts", 21200.5, 21200.5, layer1Floor + 50);
        ServerPlayer player = mock.player();
        player.setGameMode(GameType.SURVIVAL);
        if (Charters.found(
                        server,
                        player.getUUID(),
                        "Crossing " + UUID.randomUUID().toString().substring(0, 8))
                .isPresent()) {
            throw failure(helper, "founding a charter should succeed");
        }
        int depthBefore = Depth.of(helper.getLevel(), layer1Floor + 50);
        Identifier breachDirective = id("handbook/first_breach/breach_workings");
        helper.runAfterDelay(SETTLE_TICKS, () -> {
            if (!crossings(player).isEmpty()) {
                throw failure(helper, "nothing crossed yet, saw %s", crossings(player));
            }
            mock.teleportTo(helper.getLevel(), new Vec3(21200.5, layer1Floor - 2, 21200.5), 0, 0);
        });
        helper.succeedWhen(() -> {
            if (!crossings(player).equals(List.of("1>2"))) {
                throw failure(helper, "expected one descent from layer 1 to 2, saw %s", crossings(player));
            }
            List<Identifier> delivered = DELIVERED.getOrDefault(player.getUUID(), List.of());
            if (!delivered.contains(id("t05"))) {
                throw failure(helper, "the descent into layer 2 should send t05, sent %s", delivered);
            }
            Set<Identifier> done = HandbookProgress.completedFor(server, player.getUUID());
            if (!done.contains(breachDirective)) {
                throw failure(helper, "chapter 8's breach directive should be done, done: %s", done);
            }
            if (!mock.soundsHeard().contains(id("hymn.layer_2"))) {
                throw failure(helper, "the layer 2 hymn should play, heard %s", mock.soundsHeard());
            }
            int deepest = Charters.charterOfOrThrow(server, player.getUUID())
                    .orElseThrow()
                    .deepestPoint();
            if (deepest <= depthBefore) {
                throw failure(
                        helper, "the deepest point should be below the layer 1 depth %s, is %s", depthBefore, deepest);
            }
        });
    }

    @GameTest(environment = CampaignEnvironments.D, dimension = SAMPLE, maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 600)
    public void aTowedPodFollowsAcrossTheBoundaryAndIsNotCarried(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        double x = 21400.5;
        double z = 21400.5;
        int floor = CampaignBands.LAYER_1.minY() - 14;
        int start = CampaignBands.LAYER_1.minY() + 6;
        MockPlayer mock = floater(helper, "cross-tow", x, z, start);
        mock.player().setNoGravity(false);
        PodEntity[] pods = {null, null};
        FarChunks.awaitEntityTicking(helper, level, BlockPos.containing(x, start, z), () -> {
            // A shaft through the crust, with a floor under it for the pods to land on.
            RoomCarver.carve(
                    level, (int) x - 4, (int) x + 3, floor - 1, floor - 1, (int) z - 4, (int) z + 3, Blocks.STONE);
            RoomCarver.carve(level, (int) x - 4, (int) x + 3, floor, start + 8, (int) z - 4, (int) z + 3, Blocks.AIR);
            pods[0] = TestEntities.create(PodRegistry.POD, level);
            pods[0].setPos(x, start, z);
            level.addFreshEntity(pods[0]);
            pods[1] = TestEntities.create(PodRegistry.POD, level);
            pods[1].setPos(x, start + 3, z);
            level.addFreshEntity(pods[1]);
            PodTowing.attach(pods[0], pods[1]);
            if (!mock.player().startRiding(pods[0], true, false)) {
                throw failure(helper, "the mock could not board the tower");
            }
        });
        helper.succeedWhen(() -> {
            if (pods[0] == null) {
                throw failure(helper, "waiting for the shaft's chunk to tick entities");
            }
            PodEntity tower = pods[0];
            PodEntity towed = pods[1];
            if (!crossings(mock.player()).equals(List.of("1>2"))) {
                throw failure(
                        helper,
                        "the rider should cross once from layer 1 to 2, saw %s (tower at y=%s)",
                        crossings(mock.player()),
                        tower.getY());
            }
            if (towed.isRemoved() || level.getEntity(towed.getUUID()) != towed) {
                throw failure(helper, "the towed pod should be the same entity, not carried or recreated");
            }
            if (CROSSED.containsKey(towed.getUUID())) {
                throw failure(helper, "the towed pod was carried across: %s", crossings(towed));
            }
            if (towed.getY() >= CampaignBands.LAYER_1.minY()) {
                throw failure(helper, "the towed pod has not followed, it is at y=%s", towed.getY());
            }
            double gap = towed.position().distanceTo(tower.position());
            double trail = TowTuning.DEFAULT.trailDistance(tower, towed);
            if (gap > trail + 1.5) {
                throw failure(helper, "the towed pod trails %s from its tower, more than the rope %s", gap, trail);
            }
            if (!PodTowing.towerId(towed).equals(Optional.of(tower.getUUID()))) {
                throw failure(helper, "the cable is gone: %s", PodTowing.towerId(towed));
            }
        });
    }

    @GameTest(environment = CampaignEnvironments.D, dimension = SAMPLE, maxTicks = 400)
    public void aPlayerWhoLeavesTheLevelAndComesBackLowerIsSettledNotCrossed(GameTestHelper helper) {
        ServerLevel campaign = helper.getLevel();
        ServerLevel overworld = campaign.getServer().overworld();
        MockPlayer mock = floater(helper, "cross-leave", 21600.5, 21600.5, CampaignBands.LAYER_1.minY() + 50);
        helper.runAfterDelay(
                SETTLE_TICKS,
                () -> mock.teleportTo(
                        overworld,
                        new Vec3(
                                21600.5,
                                TestLayers.band(overworld, TestLayers.SURFACE).maxY() - 50,
                                21600.5),
                        0,
                        0));
        helper.runAfterDelay(
                SETTLE_TICKS + 10,
                () -> mock.teleportTo(campaign, new Vec3(21600.5, CampaignBands.LAYER_2.minY() + 100, 21600.5), 0, 0));
        helper.runAfterDelay(2 * SETTLE_TICKS + 10, () -> {
            if (!crossings(mock.player()).isEmpty()) {
                throw failure(
                        helper,
                        "coming back to the level at another depth is a first sighting, but it raised %s",
                        crossings(mock.player()));
            }
            helper.succeed();
        });
    }

    /**
     * A respawn that stays in the level cannot be driven here (the dead player respawns at the overworld spawn), so
     * the test moves the player to the surface as the respawn would and raises the same Fabric event.
     */
    @GameTest(environment = CampaignEnvironments.D, dimension = SAMPLE, maxTicks = 400)
    public void aPlayerWhoRespawnsAtTheSurfaceOfTheSameLevelRaisesNoAscents(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MockPlayer mock = floater(helper, "cross-respawn", 21800.5, 21800.5, CampaignBands.LAYER_2.minY() + 100);
        ServerPlayer player = mock.player();
        helper.runAfterDelay(SETTLE_TICKS, () -> {
            mock.teleportTo(level, new Vec3(21800.5, CampaignBands.SURFACE.minY() + 80, 21800.5), 0, 0);
            ServerPlayerEvents.AFTER_RESPAWN.invoker().afterRespawn(player, player, false);
        });
        helper.runAfterDelay(2 * SETTLE_TICKS, () -> {
            if (!crossings(player).isEmpty()) {
                throw failure(helper, "a respawn is a first sighting, but it raised %s", crossings(player));
            }
            mock.teleportTo(level, new Vec3(21800.5, CampaignBands.LAYER_1.minY() + 50, 21800.5), 0, 0);
        });
        helper.succeedWhen(() -> {
            if (helper.getTick() < 2 * SETTLE_TICKS + 5) {
                throw failure(helper, "waiting for the respawned player to be moved down");
            }
            if (!crossings(player).equals(List.of("0>1"))) {
                throw failure(helper, "after the respawn a descent should still be tracked, saw %s", crossings(player));
            }
        });
    }

    @GameTest(environment = CampaignEnvironments.D, dimension = SAMPLE)
    public void aCrossingInOneLevelSendsTheRumbleWithNoFade(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel campaign = helper.getLevel();
        ServerLevel overworld = server.overworld();
        BreachPayload inPlace = BreachPayload.of(campaign, campaign, 1, 2, false);
        BreachPayload moved = BreachPayload.of(overworld, campaign, 0, 1, false);
        if (inPlace.fades() || !moved.fades()) {
            throw failure(helper, "in place: %s, between levels: %s", inPlace, moved);
        }
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
        BreachPayload.CODEC.encode(buffer, inPlace);
        if (!BreachPayload.CODEC.decode(buffer).equals(inPlace)) {
            throw failure(helper, "the payload did not survive the wire");
        }
        helper.succeed();
    }

    /**
     * A crossing moves nobody (ADR 0061), so the old pocket (a carved room that skipped the Conduit's casing) is gone, and a pod that
     * crosses a band boundary in the Conduit's own column leaves the casing whole. Cycle-2 review of #818 found this untested.
     */
    @GameTest(environment = CampaignEnvironments.D, dimension = SAMPLE, maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 1000)
    public void aPodCrossingInPlaceAtTheConduitsColumnLeavesTheConduitWhole(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ColonySite.Placed colony = Colony.placed(level.getServer())
                .orElseThrow(() -> failure(helper, "the colony was not built when the server started"));
        BlockPos centre = TestColony.anchor(colony, ColonyAnchor.CONDUIT);
        int radius = ColonyTuning.DEFAULT.conduitRadius();
        int top = colony.groundY() + ColonyTuning.DEFAULT.conduitStack();
        int layer1Floor = CampaignBands.LAYER_1.minY();
        int surfaceFloor = CampaignBands.SURFACE.minY();
        // The corners of the casing name every chunk that holds a part of it.
        List<BlockPos> corners = List.of(
                centre.offset(-radius, 0, -radius).atY(layer1Floor + 8),
                centre.offset(-radius, 0, radius).atY(layer1Floor + 8),
                centre.offset(radius, 0, -radius).atY(layer1Floor + 8),
                centre.offset(radius, 0, radius).atY(layer1Floor + 8));
        MockPlayer mock = MockPlayers.join(helper, "cross-conduit");
        mock.player().setPermanentlyInvulnerable(true);
        PodEntity[] pods = {null};
        long[] startTick = {-1};
        int[] loaded = {0};
        FarChunks.awaitEntityTicking(helper, level, corners, index -> loaded[0]++);
        double x = centre.getX() + 0.5;
        double z = centre.getZ() + 0.5;
        // One crossing at each boundary between bands, down and up, with the pod in the Conduit's own column.
        List<Step> steps = List.of(
                new Step(layer1Floor + 100),
                new Step(layer1Floor - 0.5, "1>2"),
                new Step(layer1Floor + CampaignBands.crustRows() + 0.5, "1>2", "2>1"),
                new Step(surfaceFloor + CampaignBands.crustRows() + 0.5, "1>2", "2>1", "1>0"),
                new Step(surfaceFloor - 0.5, "1>2", "2>1", "1>0", "0>1"));
        TestTicks.each(helper, () -> {
            if (loaded[0] < corners.size()) {
                return;
            }
            if (pods[0] == null) {
                expectConduitWhole(helper, level, centre, radius, top, "before the crossings");
                pods[0] = TestEntities.create(PodRegistry.POD, level);
                pods[0].setNoGravity(true);
                pods[0].setPos(x, steps.get(0).y(), z);
                level.addFreshEntity(pods[0]);
                mock.teleportTo(level, new Vec3(x, steps.get(0).y(), z), 0, 0);
                if (!mock.player().startRiding(pods[0], true, false)) {
                    throw failure(helper, "the mock could not board the pod");
                }
                startTick[0] = helper.getTick();
                return;
            }
            long since = helper.getTick() - startTick[0];
            if (since == 1) {
                // The first tick after the pilot is placed sees the band the pilot joined in and the band it was put in
                // as one crossing.
                CROSSED.remove(mock.player().getUUID());
                return;
            }
            if (since % STEP_TICKS != STEP_TICKS - 1 || since / STEP_TICKS >= steps.size()) {
                return;
            }
            int index = (int) (since / STEP_TICKS);
            if (!crossings(mock.player()).equals(steps.get(index).crossings())) {
                throw failure(
                        helper,
                        "with the pod at y=%s (step %d) the crossings should be %s, were %s",
                        steps.get(index).y(),
                        index,
                        steps.get(index).crossings(),
                        crossings(mock.player()));
            }
            if (pods[0].level() != level || pods[0].getX() != x || pods[0].getZ() != z) {
                throw failure(
                        helper,
                        "the crossing moved the pod to %s in %s",
                        pods[0].position(),
                        pods[0].level().dimension().identifier());
            }
            if (index == steps.size() - 1) {
                expectConduitWhole(helper, level, centre, radius, top, "after the crossings");
                helper.succeed();
                return;
            }
            pods[0].setPos(x, steps.get(index + 1).y(), z);
        });
    }

    /** The casing runs unbroken from the floor of the deepest layer to the top of its stack, in the whole column of the square. */
    private static void expectConduitWhole(
            GameTestHelper helper, ServerLevel level, BlockPos centre, int radius, int top, String when) {
        for (int cx = centre.getX() - radius; cx <= centre.getX() + radius; cx++) {
            for (int cz = centre.getZ() - radius; cz <= centre.getZ() + radius; cz++) {
                for (int y = CampaignBands.LAYER_2.minY(); y <= top; y++) {
                    if (!level.getBlockState(new BlockPos(cx, y, cz)).is(ColonyBlocks.CONDUIT)) {
                        throw failure(helper, "%s: the Conduit has a gap at %s %s %s", when, cx, y, cz);
                    }
                }
            }
        }
    }

    @GameTest(environment = CampaignEnvironments.D, dimension = SAMPLE)
    public void theBandTableReadsTheSameForEveryY(GameTestHelper helper) {
        List<String> seen = new ArrayList<>();
        for (int y : new int[] {319, -64, -65, -256, -257, -512, -513, -768, -769}) {
            seen.add(y + "="
                    + CampaignBands.bandAt(y)
                            .map(band -> String.valueOf(band.layer()))
                            .orElse("none"));
        }
        List<String> expected =
                List.of("319=0", "-64=0", "-65=1", "-256=1", "-257=2", "-512=2", "-513=3", "-768=3", "-769=none");
        if (!seen.equals(expected)) {
            throw failure(helper, "expected %s, got %s", expected, seen);
        }
        helper.succeed();
    }
}
