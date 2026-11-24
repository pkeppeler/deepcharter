package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.SeamSite.AREA_CHUNKS;
import static io.github.pkeppeler.deepcharter.test.support.SeamSite.HOLD_HEIGHT;
import static io.github.pkeppeler.deepcharter.test.support.SeamSite.MAX_TICKS;

import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.layer.SeamChunkPayload;
import io.github.pkeppeler.deepcharter.layer.SeamDiscardPayload;
import io.github.pkeppeler.deepcharter.layer.SeamPreparePayload;
import io.github.pkeppeler.deepcharter.layer.SeamSender;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.SeamSite.Run;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.BiomeManager;

/**
 * Server GameTests of the data sent ahead of a crossing (#1185, ADR 0089) on the shipped layer_1 to layer_2 crossing: a rider whose
 * client has the seam channels is sent a prepare and then the arrival area's chunks, nearest the vehicle first and within a byte budget
 * a tick; a retreat discards, a recentre keeps what is still in the area, and a rider without the channels gets nothing. Each test has
 * its own site, a thousand blocks from the next, and none shares one with {@link SeamStreamingTest}.
 */
public class SeamDataAheadTest {
    /** The first site index of this class: {@link SeamStreamingTest} uses the ones below it. */
    private static final int FIRST_INDEX = 20;
    /** Ticks a negative case watches for a payload that must not come. */
    private static final int WATCH_TICKS = 40;

    /** A mock whose client announced the three seam channels the server sends. */
    private static MockPlayer joinWithChannels(GameTestHelper helper, String name) {
        MockPlayer mock = MockPlayers.join(helper, name);
        mock.announceChannel(SeamPreparePayload.TYPE);
        mock.announceChannel(SeamChunkPayload.TYPE);
        mock.announceChannel(SeamDiscardPayload.TYPE);
        return mock;
    }

    private static Map<ChunkPos, Integer> chunkCounts(MockPlayer mock) {
        Map<ChunkPos, Integer> counts = new HashMap<>();
        for (SeamChunkPayload payload : mock.payloadsOf(SeamChunkPayload.class)) {
            counts.merge(new ChunkPos(payload.chunk().x(), payload.chunk().z()), 1, Integer::sum);
        }
        return counts;
    }

    /** The positions of the arrival area of {@code centre}: a square of the tuned radius. */
    private static Set<ChunkPos> area(ChunkPos centre) {
        int radius = LayerTuning.DEFAULT.seamRadius();
        Set<ChunkPos> positions = new HashSet<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                positions.add(new ChunkPos(centre.x() + dx, centre.z() + dz));
            }
        }
        return positions;
    }

    private static int distanceSquared(ChunkPos centre, ChunkPos pos) {
        int dx = pos.x() - centre.x();
        int dz = pos.z() - centre.z();
        return dx * dx + dz * dz;
    }

    /**
     * A rider held inside the floor margin is sent one prepare naming layer 2, then each chunk of the arrival area once, in layer 2's
     * dimension, and the prepare comes before every chunk.
     */
    @GameTest(maxTicks = MAX_TICKS)
    public void preparePrecedesEveryArrivalChunkSentOnce(GameTestHelper helper) {
        Run run = Run.of(helper, FIRST_INDEX);
        MockPlayer mock = joinWithChannels(helper, "seam-data-prepare");
        run.stage(
                () -> {
                    run.site().carveShaft(run.site().x(), HOLD_HEIGHT + 2);
                    mock.teleportTo(run.site().one(), run.site().at(HOLD_HEIGHT), 0, 0);
                },
                ticks -> {
                    if (mock.payloadsOf(SeamChunkPayload.class).size() < AREA_CHUNKS) {
                        return;
                    }
                    List<SeamPreparePayload> prepares = mock.payloadsOf(SeamPreparePayload.class);
                    expect(helper, prepares.size() == 1, "expected one prepare, got %s", prepares);
                    SeamPreparePayload prepare = prepares.get(0);
                    expect(
                            helper,
                            prepare.dimension().equals(run.site().two().dimension())
                                    && prepare.centre().equals(run.site().centre())
                                    && prepare.seedHash()
                                            == BiomeManager.obfuscateSeed(
                                                    run.site().two().getSeed())
                                    && prepare.seaLevel() == run.site().two().getSeaLevel(),
                            "the prepare does not describe layer 2 under the vehicle: %s",
                            prepare);
                    List<CustomPacketPayload> all = mock.payloadsOf(CustomPacketPayload.class);
                    expect(
                            helper,
                            all.get(0) instanceof SeamPreparePayload,
                            "the first payload is %s, not the prepare",
                            all.get(0));
                    Map<ChunkPos, Integer> counts = chunkCounts(mock);
                    expect(
                            helper,
                            counts.keySet().equals(area(run.site().centre()))
                                    && counts.values().stream().allMatch(count -> count == 1),
                            "the chunks sent are not the arrival area once each: %s",
                            counts);
                    expect(
                            helper,
                            mock.payloadsOf(SeamChunkPayload.class).stream()
                                    .allMatch(payload -> payload.dimension()
                                            .equals(run.site().two().dimension())),
                            "a chunk payload names another dimension than layer 2");
                    expect(
                            helper,
                            mock.player().level() == run.site().one(),
                            "the mock left layer 1 while it was sent the area");
                    helper.succeed();
                });
    }

    /**
     * Each tick sends the nearest unsent chunks, and stops once the estimated bytes reach the tick's budget: the chunks of one tick but its
     * last add up to less than the budget. The chunks come in the order of their distance from the centre. The whole area takes more than a
     * tick.
     */
    @GameTest(maxTicks = MAX_TICKS)
    public void chunksGoNearestFirstWithinTheByteBudget(GameTestHelper helper) {
        Run run = Run.of(helper, FIRST_INDEX + 1);
        MockPlayer mock = joinWithChannels(helper, "seam-data-budget");
        List<Integer> perTick = new ArrayList<>();
        int[] seen = {0};
        run.stage(
                () -> {
                    run.site().carveShaft(run.site().x(), HOLD_HEIGHT + 2);
                    mock.teleportTo(run.site().one(), run.site().at(HOLD_HEIGHT), 0, 0);
                },
                ticks -> {
                    List<SeamChunkPayload> sent = mock.payloadsOf(SeamChunkPayload.class);
                    if (sent.size() > seen[0]) {
                        perTick.add(sent.size() - seen[0]);
                        seen[0] = sent.size();
                    }
                    if (sent.size() < AREA_CHUNKS) {
                        return;
                    }
                    int budget = LayerTuning.DEFAULT.seamBytesPerTick();
                    int index = 0;
                    for (int count : perTick) {
                        int bytes = 0;
                        for (int i = 0; i < count - 1; i++) {
                            bytes += SeamSender.estimatedBytes(
                                    sent.get(index + i).chunk());
                        }
                        expect(
                                helper,
                                bytes < budget,
                                "a tick sent %d chunks, %d estimated bytes before its last, over the budget %d",
                                count,
                                bytes,
                                budget);
                        index += count;
                    }
                    expect(
                            helper,
                            perTick.size() >= 2,
                            "the whole area went in one tick, so the budget bounded nothing: %s",
                            perTick);
                    List<ChunkPos> order = sent.stream()
                            .map(payload -> new ChunkPos(
                                    payload.chunk().x(), payload.chunk().z()))
                            .toList();
                    for (int i = 1; i < order.size(); i++) {
                        expect(
                                helper,
                                distanceSquared(run.site().centre(), order.get(i - 1))
                                        <= distanceSquared(run.site().centre(), order.get(i)),
                                "chunk %s was sent before the nearer %s",
                                order.get(i - 1),
                                order.get(i));
                    }
                    helper.succeed();
                });
    }

    /** A rider that climbs out of the zone before the area is whole is sent a discard for layer 2, and no chunk after it. */
    @GameTest(maxTicks = MAX_TICKS)
    public void aRetreatSendsDiscardAndStops(GameTestHelper helper) {
        Run run = Run.of(helper, FIRST_INDEX + 2);
        int outside = LayerTuning.DEFAULT.seamFloorMargin() + 16;
        MockPlayer mock = joinWithChannels(helper, "seam-data-retreat");
        int[] retreatedAt = {-1};
        int[] chunksAtDiscard = {-1};
        run.stage(
                List.of(
                        BlockPos.containing(run.site().at(HOLD_HEIGHT)),
                        BlockPos.containing(run.site().at(outside))),
                () -> {
                    run.site().carveShaft(run.site().x(), outside + 2);
                    mock.teleportTo(run.site().one(), run.site().at(HOLD_HEIGHT), 0, 0);
                },
                ticks -> {
                    if (retreatedAt[0] < 0) {
                        if (!mock.payloadsOf(SeamChunkPayload.class).isEmpty()) {
                            expect(
                                    helper,
                                    mock.payloadsOf(SeamChunkPayload.class).size() < AREA_CHUNKS,
                                    "the area was whole before the retreat");
                            mock.player().setPos(run.site().at(outside));
                            retreatedAt[0] = ticks;
                        }
                        return;
                    }
                    List<SeamDiscardPayload> discards = mock.payloadsOf(SeamDiscardPayload.class);
                    if (discards.isEmpty()) {
                        return;
                    }
                    if (chunksAtDiscard[0] < 0) {
                        expect(
                                helper,
                                discards.size() == 1
                                        && discards.get(0)
                                                .dimension()
                                                .equals(run.site().two().dimension()),
                                "expected one discard of layer 2, got %s",
                                discards);
                        chunksAtDiscard[0] =
                                mock.payloadsOf(SeamChunkPayload.class).size();
                        return;
                    }
                    expect(
                            helper,
                            mock.payloadsOf(SeamChunkPayload.class).size() == chunksAtDiscard[0],
                            "a chunk was sent after the discard");
                    if (ticks - retreatedAt[0] >= WATCH_TICKS) {
                        helper.succeed();
                    }
                });
    }

    /**
     * A vehicle that moves two chunks sideways recentres the area: the rider is sent a second prepare with the new centre, no discard, and
     * only the chunks of the new area it does not hold, so what lies in both areas is sent once.
     */
    @GameTest(maxTicks = MAX_TICKS)
    public void aRecentreKeepsSentChunksStillInTheArea(GameTestHelper helper) {
        Run run = Run.of(helper, FIRST_INDEX + 3);
        double movedX = run.site().x() + 32;
        ChunkPos moved =
                ChunkPos.containing(BlockPos.containing(movedX, 0, run.site().z()));
        MockPlayer mock = joinWithChannels(helper, "seam-data-recentre");
        int[] movedAt = {-1};
        run.stage(
                List.of(
                        BlockPos.containing(run.site().at(HOLD_HEIGHT)),
                        BlockPos.containing(
                                movedX,
                                run.site().floorY() + HOLD_HEIGHT,
                                run.site().z())),
                () -> {
                    run.site().carveShaft(run.site().x(), HOLD_HEIGHT + 2);
                    run.site().carveShaft(movedX, HOLD_HEIGHT + 2);
                    mock.teleportTo(run.site().one(), run.site().at(HOLD_HEIGHT), 0, 0);
                },
                ticks -> {
                    if (movedAt[0] < 0) {
                        if (mock.payloadsOf(SeamChunkPayload.class).size() >= AREA_CHUNKS) {
                            mock.player()
                                    .setPos(
                                            movedX,
                                            run.site().floorY() + HOLD_HEIGHT,
                                            run.site().z());
                            movedAt[0] = ticks;
                        }
                        return;
                    }
                    Set<ChunkPos> wanted = new HashSet<>(area(run.site().centre()));
                    wanted.addAll(area(moved));
                    if (chunkCounts(mock).size() < wanted.size()) {
                        return;
                    }
                    List<SeamPreparePayload> prepares = mock.payloadsOf(SeamPreparePayload.class);
                    expect(
                            helper,
                            prepares.size() == 2 && prepares.get(1).centre().equals(moved),
                            "expected a second prepare centred on %s, got %s",
                            moved,
                            prepares);
                    expect(helper, mock.payloadsOf(SeamDiscardPayload.class).isEmpty(), "a recentre sent a discard");
                    Map<ChunkPos, Integer> counts = chunkCounts(mock);
                    expect(
                            helper,
                            counts.keySet().equals(wanted)
                                    && counts.values().stream().allMatch(count -> count == 1),
                            "a chunk in both areas was sent twice, or one is missing: %s",
                            counts);
                    helper.succeed();
                });
    }

    /**
     * A client that announced none of the seam channels is sent no payload, however long it hangs over the floor, and the crossing then
     * sends it the whole arrival area the vanilla way, each chunk once.
     */
    @GameTest(maxTicks = MAX_TICKS)
    public void aClientWithoutTheChannelGetsNothingAndAFullResend(GameTestHelper helper) {
        Run run = Run.of(helper, FIRST_INDEX + 4);
        MockPlayer mock = MockPlayers.join(helper, "seam-data-plain");
        mock.setViewDistance(LayerTuning.DEFAULT.seamRadius() + 1);
        int[] watchedFrom = {-1};
        run.stage(
                () -> {
                    run.site().carveShaft(run.site().x(), HOLD_HEIGHT + 2);
                    mock.teleportTo(run.site().one(), run.site().at(HOLD_HEIGHT), 0, 0);
                },
                ticks -> {
                    if (watchedFrom[0] < 0) {
                        if (run.arrivalReady()) {
                            watchedFrom[0] = ticks;
                        }
                        return;
                    }
                    List<CustomPacketPayload> all = mock.payloadsOf(CustomPacketPayload.class);
                    expect(
                            helper,
                            all.stream()
                                    .noneMatch(payload -> payload instanceof SeamPreparePayload
                                            || payload instanceof SeamChunkPayload
                                            || payload instanceof SeamDiscardPayload),
                            "a client without the channels was sent seam payloads: %s",
                            all);
                    if (!TestLayers.isInDimensionOf(mock.player(), 2)) {
                        if (ticks - watchedFrom[0] >= WATCH_TICKS) {
                            mock.player()
                                    .setPos(
                                            run.site().x(),
                                            run.site().floorY() - 1,
                                            run.site().z());
                        }
                        return;
                    }
                    Set<ChunkPos> sent = mock.chunksSentIn(run.site().two().dimension());
                    if (!sent.containsAll(area(run.site().centre()))) {
                        return;
                    }
                    for (ChunkPos pos : area(run.site().centre())) {
                        expect(
                                helper,
                                mock.timesSentIn(run.site().two().dimension(), pos) == 1,
                                "the crossing sent %s %d times",
                                pos,
                                mock.timesSentIn(run.site().two().dimension(), pos));
                    }
                    helper.succeed();
                });
    }

    /**
     * Two riders of the same site stream independently: the one whose client has the channels gets the prepare and the area, the other
     * gets nothing, and neither's state touches the other's.
     */
    @GameTest(maxTicks = MAX_TICKS)
    public void twoRidersStreamIndependently(GameTestHelper helper) {
        Run run = Run.of(helper, FIRST_INDEX + 5);
        MockPlayer with = joinWithChannels(helper, "seam-data-with");
        MockPlayer without = MockPlayers.join(helper, "seam-data-without");
        run.stage(
                () -> {
                    run.site().carveShaft(run.site().x(), HOLD_HEIGHT + 2);
                    with.teleportTo(run.site().one(), run.site().at(HOLD_HEIGHT), 0, 0);
                    without.teleportTo(run.site().one(), run.site().at(HOLD_HEIGHT), 0, 0);
                },
                ticks -> {
                    if (with.payloadsOf(SeamChunkPayload.class).size() < AREA_CHUNKS) {
                        expect(
                                helper,
                                without.payloadsOf(CustomPacketPayload.class).stream()
                                        .noneMatch(payload -> payload instanceof SeamPreparePayload
                                                || payload instanceof SeamChunkPayload),
                                "the rider without the channels was sent seam payloads");
                        return;
                    }
                    expect(
                            helper,
                            chunkCounts(with).keySet().equals(area(run.site().centre()))
                                    && with.payloadsOf(SeamPreparePayload.class).size() == 1,
                            "the rider with the channels did not get the area once");
                    expect(
                            helper,
                            without.payloadsOf(SeamChunkPayload.class).isEmpty()
                                    && without.payloadsOf(SeamPreparePayload.class)
                                            .isEmpty(),
                            "the rider without the channels was sent seam payloads");
                    helper.succeed();
                });
    }

    /** The order the area is sent in is pure: nearest the centre first, each offset once, the same on every call. */
    @GameTest
    public void theOrderIsNearestFirst(GameTestHelper helper) {
        int radius = LayerTuning.DEFAULT.seamRadius();
        List<ChunkPos> offsets = SeamSender.offsets(radius);
        int side = 2 * radius + 1;
        expect(helper, offsets.size() == side * side, "expected %d offsets, got %d", side * side, offsets.size());
        expect(helper, new HashSet<>(offsets).size() == offsets.size(), "an offset comes twice: %s", offsets);
        expect(
                helper,
                offsets.get(0).equals(new ChunkPos(0, 0)),
                "the first offset is %s, not the centre",
                offsets.get(0));
        for (int i = 1; i < offsets.size(); i++) {
            expect(
                    helper,
                    distanceSquared(new ChunkPos(0, 0), offsets.get(i - 1))
                            <= distanceSquared(new ChunkPos(0, 0), offsets.get(i)),
                    "offset %s comes before the nearer %s",
                    offsets.get(i - 1),
                    offsets.get(i));
        }
        expect(helper, offsets.equals(SeamSender.offsets(radius)), "two calls gave two orders");
        helper.succeed();
    }
}
