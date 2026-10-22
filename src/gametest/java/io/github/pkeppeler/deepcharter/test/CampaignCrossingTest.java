package io.github.pkeppeler.deepcharter.test;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.handbook.HandbookProgress;
import io.github.pkeppeler.deepcharter.layer.BreachEvents;
import io.github.pkeppeler.deepcharter.layer.BreachPayload;
import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.Depth;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodTowing;
import io.github.pkeppeler.deepcharter.pod.TowTuning;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
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
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

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

    // assertionException(String, Object...) leaves the placeholders unfilled in the report.
    @FormatMethod
    private static RuntimeException failure(
            GameTestHelper helper, @FormatString String format, @Nullable Object... args) {
        return helper.assertionException(Component.literal(String.format(format, args)));
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

    @GameTest(dimension = SAMPLE, maxTicks = 1000)
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
                new Step(CampaignBands.UNBUILT.minY() + 100, "1>2", "2>1", "1>2"),
                new Step(layer1Floor + 100, "1>2", "2>1", "1>2", "2>1"),
                new Step(surfaceFloor + crust + 0.5, "1>2", "2>1", "1>2", "2>1", "1>0"),
                new Step(surfaceFloor + 1, "1>2", "2>1", "1>2", "2>1", "1>0"),
                new Step(surfaceFloor - 0.5, "1>2", "2>1", "1>2", "2>1", "1>0", "0>1"),
                new Step(surfaceFloor + 1, "1>2", "2>1", "1>2", "2>1", "1>0", "0>1"),
                new Step(surfaceFloor + 100, "1>2", "2>1", "1>2", "2>1", "1>0", "0>1", "1>0"));
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

    @GameTest(dimension = SAMPLE, maxTicks = 1000)
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

    @GameTest(dimension = SAMPLE, maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 600)
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

    @GameTest(dimension = SAMPLE)
    public void aCrossingInOneLevelSendsTheRumbleWithNoFade(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel campaign = helper.getLevel();
        ServerLevel overworld = server.overworld();
        BreachPayload inPlace = BreachPayload.of(campaign, campaign, 1, 2);
        BreachPayload moved = BreachPayload.of(overworld, campaign, 0, 1);
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

    @GameTest(dimension = SAMPLE)
    public void theBandTableReadsTheSameForEveryY(GameTestHelper helper) {
        List<String> seen = new ArrayList<>();
        for (int y : new int[] {319, -64, -65, -256, -257, -512, -513}) {
            seen.add(y + "="
                    + CampaignBands.bandAt(y)
                            .map(band -> String.valueOf(band.layer()))
                            .orElse("none"));
        }
        List<String> expected = List.of("319=0", "-64=0", "-65=1", "-256=1", "-257=2", "-512=2", "-513=none");
        if (!seen.equals(expected)) {
            throw failure(helper, "expected %s, got %s", expected, seen);
        }
        helper.succeed();
    }
}
