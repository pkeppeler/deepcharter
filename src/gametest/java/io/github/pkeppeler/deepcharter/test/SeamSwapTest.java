package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.SeamSite.HOLD_HEIGHT;
import static io.github.pkeppeler.deepcharter.test.support.SeamSite.MAX_TICKS;

import io.github.pkeppeler.deepcharter.layer.BreachPayload;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.layer.SeamAcceptPayload;
import io.github.pkeppeler.deepcharter.layer.SeamChunkPayload;
import io.github.pkeppeler.deepcharter.layer.SeamDiscardPayload;
import io.github.pkeppeler.deepcharter.layer.SeamPreparePayload;
import io.github.pkeppeler.deepcharter.layer.SeamRefusePayload;
import io.github.pkeppeler.deepcharter.layer.SeamRefuseReason;
import io.github.pkeppeler.deepcharter.layer.SeamSender;
import io.github.pkeppeler.deepcharter.layer.SeamSwapPayload;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.SeamSite.Run;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.UnaryOperator;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.phys.Vec3;

/**
 * Server GameTests of the seamless swap (#1229, ADR 0089, slice 3): the tuning that bounds it, the readiness of a
 * vehicle's riders, the swap itself and its recovery.
 */
public class SeamSwapTest {
    /** {@link LayerTuning#DEFAULT} with one component changed, through the canonical constructor. */
    private static LayerTuning with(UnaryOperator<Builder> change) {
        return change.apply(new Builder(LayerTuning.DEFAULT)).build();
    }

    /** The two slice 3 components over the default; the rest are the default's. */
    private record Builder(int swapRadius, int flawlessFrames, int radius) {
        Builder(LayerTuning base) {
            this(base.seamSwapRadius(), base.seamFlawlessFrames(), base.seamRadius());
        }

        LayerTuning build() {
            LayerTuning d = LayerTuning.DEFAULT;
            return new LayerTuning(
                    d.seaLevel(),
                    d.feetPerBlock(),
                    d.crustThickness(),
                    d.pocketRadius(),
                    d.pocketHeight(),
                    d.structureSpacing(),
                    d.structureFloorMargin(),
                    d.structureCeilingMargin(),
                    d.lavaHullPerSecond(),
                    d.lavaCueTicks(),
                    d.grainPulseTicks(),
                    d.grainSqueezeHull(),
                    d.grainSqueezeDamage(),
                    d.grainSkateHeatPerSecond(),
                    d.grainDecoyChance(),
                    d.glowcapLight(),
                    d.seamLeadPerRow(),
                    d.seamFloorMargin(),
                    d.seamSpeedWindow(),
                    d.seamDesignFallSpeed(),
                    radius,
                    d.seamRefreshTicks(),
                    d.seamTicketTimeout(),
                    d.seamRecentreChunks(),
                    d.seamBytesPerTick(),
                    d.seamMissedWindowTicks(),
                    swapRadius,
                    flawlessFrames);
        }
    }

    private static void expectRejected(GameTestHelper helper, String what, UnaryOperator<Builder> change) {
        try {
            with(change);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw helper.assertionException(what + " should be refused");
    }

    /** The shipped swap tuning is 3 chunks and 2 frames, and a swap radius beyond what is sendable, or below 1, is refused. */
    @GameTest
    public void swapTuningIsValidated(GameTestHelper helper) {
        LayerTuning tuning = LayerTuning.DEFAULT;
        if (tuning.seamSwapRadius() != 3 || tuning.seamFlawlessFrames() != 2) {
            throw helper.assertionException("the swap tuning is not 3 chunks and 2 frames: " + tuning);
        }
        with(b -> new Builder(3, 0, 4));
        expectRejected(helper, "a swap radius of 4, past the send radius 3", b -> new Builder(4, 2, 4));
        expectRejected(helper, "a swap radius of 0", b -> new Builder(0, 2, 4));
        expectRejected(helper, "a negative Flawless Frames count", b -> new Builder(3, -1, 4));
        helper.succeed();
    }

    /** Writes {@code value} with {@code codec} and reads it back. */
    private static <T> T roundTrip(GameTestHelper helper, StreamCodec<RegistryFriendlyByteBuf, T> codec, T value) {
        RegistryFriendlyByteBuf buf =
                new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        codec.encode(buf, value);
        T read = codec.decode(buf);
        expect(helper, buf.readableBytes() == 0, "%s left %d bytes unread", value, buf.readableBytes());
        return read;
    }

    /** Every slice 3 payload reads back what it wrote, the prepare carries its generation, and a crossing flags a swap. */
    @GameTest
    public void swapPayloadsRoundTrip(GameTestHelper helper) {
        SeamPreparePayload prepare = new SeamPreparePayload(
                Level.OVERWORLD, BuiltinDimensionTypes.OVERWORLD, 7L, 63, false, false, new ChunkPos(3, -4), 5);
        SeamSwapPayload swap = new SeamSwapPayload(
                Level.NETHER, 5, 123, UUID.randomUUID(), new Vec3(1.5, 200.25, -3.5), List.of(123, 124, 9000));
        SeamAcceptPayload accept = new SeamAcceptPayload(Level.END, 300);
        expect(helper, roundTrip(helper, SeamPreparePayload.CODEC, prepare).equals(prepare), "prepare");
        expect(helper, roundTrip(helper, SeamSwapPayload.CODEC, swap).equals(swap), "swap");
        expect(helper, roundTrip(helper, SeamAcceptPayload.CODEC, accept).equals(accept), "accept");
        for (SeamRefuseReason reason : SeamRefuseReason.values()) {
            SeamRefusePayload refuse = new SeamRefusePayload(Level.NETHER, 2, reason);
            expect(helper, roundTrip(helper, SeamRefusePayload.CODEC, refuse).equals(refuse), "refuse %s", reason);
        }
        ServerLevel overworld = helper.getLevel().getServer().overworld();
        ServerLevel nether =
                Objects.requireNonNull(helper.getLevel().getServer().getLevel(Level.NETHER));
        BreachPayload swapped = BreachPayload.of(overworld, nether, 1, 2, true);
        expect(helper, roundTrip(helper, BreachPayload.CODEC, swapped).equals(swapped), "breach");
        expect(helper, swapped.swapped() && !swapped.fades(), "a swap rumbles and does not fade: %s", swapped);
        BreachPayload moved = BreachPayload.of(overworld, nether, 1, 2);
        expect(helper, !moved.swapped() && moved.fades(), "a teleport keeps fading: %s", moved);
        helper.succeed();
    }

    /** The refusal reasons and their wire ids are pinned: a reorder or an insertion changes what an old client says. */
    @GameTest
    public void refuseReasonsArePinned(GameTestHelper helper) {
        expect(
                helper,
                List.of(SeamRefuseReason.values())
                        .equals(List.of(
                                SeamRefuseReason.BUILD_FAILED,
                                SeamRefuseReason.RELEASED,
                                SeamRefuseReason.SWAP_FAILED)),
                "the refusal reasons are %s",
                List.of(SeamRefuseReason.values()));
        expect(
                helper,
                SeamRefuseReason.BUILD_FAILED.ordinal() == 0
                        && SeamRefuseReason.RELEASED.ordinal() == 1
                        && SeamRefuseReason.SWAP_FAILED.ordinal() == 2,
                "the wire ids moved");
        helper.succeed();
    }

    /** The first site index of this class: {@link SeamStreamingTest} and {@link SeamDataAheadTest} use the ones below it. */
    private static final int FIRST_INDEX = 40;

    /** Chunks the server sends ahead: the square of the send radius. */
    private static final int SENT_CHUNKS = (2 * SeamSender.SEND_RADIUS + 1) * (2 * SeamSender.SEND_RADIUS + 1);

    /** A mock whose client announced the channels the swap needs: the three it is sent and the swap. */
    private static MockPlayer joinWithChannels(GameTestHelper helper, String name) {
        MockPlayer mock = MockPlayers.join(helper, name);
        mock.announceChannel(SeamPreparePayload.TYPE);
        mock.announceChannel(SeamChunkPayload.TYPE);
        mock.announceChannel(SeamDiscardPayload.TYPE);
        mock.announceChannel(SeamSwapPayload.TYPE);
        return mock;
    }

    /** The generation of the latest prepare the mock was sent. */
    private static int generationOf(MockPlayer mock) {
        List<SeamPreparePayload> prepares = mock.payloadsOf(SeamPreparePayload.class);
        return prepares.get(prepares.size() - 1).generation();
    }

    /** Whether the server would swap {@code mock} into layer 2 under {@code run}'s site. */
    private static boolean ready(Run run, MockPlayer mock) {
        return SeamSender.readyToSwap(
                mock.player(), run.site().two(), run.site().centre());
    }

    /** What the chunk check holds: every chunk within the radius of the centre sent, and the arrival chunk inside it. */
    @GameTest
    public void theSwapAreaIsTheChunksAroundTheArrival(GameTestHelper helper) {
        ChunkPos centre = new ChunkPos(10, 10);
        int radius = LayerTuning.DEFAULT.seamSwapRadius();
        LongSet sent = new LongOpenHashSet();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                sent.add(ChunkPos.pack(10 + dx, 10 + dz));
            }
        }
        expect(helper, SeamSender.holdsSwapArea(sent, centre, centre, radius), "a full area is not held");
        expect(
                helper,
                !SeamSender.holdsSwapArea(sent, centre, new ChunkPos(10 + radius + 1, 10), radius),
                "an arrival chunk outside the radius is held");
        sent.remove(ChunkPos.pack(10 + radius, 10 - radius));
        expect(helper, !SeamSender.holdsSwapArea(sent, centre, centre, radius), "an area missing a corner is held");
        sent.add(ChunkPos.pack(10 + radius, 10 - radius));
        sent.remove(centre.pack());
        expect(helper, !SeamSender.holdsSwapArea(sent, centre, centre, radius), "an area missing the arrival is held");
        helper.succeed();
    }

    /** A rider over the floor holds the area, and is ready only from its accept of the current prepare to a refusal. */
    @GameTest(maxTicks = MAX_TICKS)
    public void aRiderIsReadyOnlyFromItsAcceptToItsRefusal(GameTestHelper helper) {
        Run run = Run.of(helper, FIRST_INDEX);
        MockPlayer mock = joinWithChannels(helper, "seam-swap-ready");
        run.stage(
                () -> {
                    run.site().carveShaft(run.site().x(), HOLD_HEIGHT + 2);
                    mock.teleportTo(run.site().one(), run.site().at(HOLD_HEIGHT), 0, 0);
                },
                ticks -> {
                    if (mock.payloadsOf(SeamChunkPayload.class).size() < SENT_CHUNKS) {
                        return;
                    }
                    ResourceKey<Level> two = run.site().two().dimension();
                    int generation = generationOf(mock);
                    expect(helper, !ready(run, mock), "ready before the client accepted");
                    mock.send(new SeamAcceptPayload(two, generation));
                    expect(helper, ready(run, mock), "not ready after an accept of the current generation");
                    mock.send(new SeamRefusePayload(two, generation, SeamRefuseReason.RELEASED));
                    expect(helper, !ready(run, mock), "ready after the client refused");
                    mock.send(new SeamAcceptPayload(two, generation));
                    expect(helper, ready(run, mock), "not ready after a second accept");
                    helper.succeed();
                });
    }

    /** An accept names the prepare it answers: one of an old generation, or of another dimension, is not an accept. */
    @GameTest(maxTicks = MAX_TICKS)
    public void anAcceptOfAnOldGenerationIsNotReady(GameTestHelper helper) {
        Run run = Run.of(helper, FIRST_INDEX + 1);
        double movedX = run.site().x() + 32;
        ChunkPos moved =
                ChunkPos.containing(BlockPos.containing(movedX, 0, run.site().z()));
        MockPlayer mock = joinWithChannels(helper, "seam-swap-old-generation");
        boolean[] movedOn = {false};
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
                    ResourceKey<Level> two = run.site().two().dimension();
                    if (!movedOn[0]) {
                        if (mock.payloadsOf(SeamChunkPayload.class).size() >= SENT_CHUNKS) {
                            mock.send(new SeamAcceptPayload(two, generationOf(mock)));
                            expect(helper, ready(run, mock), "not ready after the first accept");
                            mock.player()
                                    .setPos(
                                            movedX,
                                            run.site().floorY() + HOLD_HEIGHT,
                                            run.site().z());
                            movedOn[0] = true;
                        }
                        return;
                    }
                    if (mock.payloadsOf(SeamPreparePayload.class).size() < 2
                            || chunksOfArea(mock, moved) < SENT_CHUNKS) {
                        return;
                    }
                    int old = generationOf(mock) - 1;
                    expect(helper, generationOf(mock) == old + 1 && old >= 1, "the generation did not advance");
                    ChunkPos arrival = moved;
                    expect(
                            helper,
                            !SeamSender.readyToSwap(mock.player(), run.site().two(), arrival),
                            "ready on the accept of the old prepare");
                    mock.send(new SeamAcceptPayload(two, old));
                    expect(
                            helper,
                            !SeamSender.readyToSwap(mock.player(), run.site().two(), arrival),
                            "ready after an accept of the old generation");
                    mock.send(new SeamAcceptPayload(Level.NETHER, generationOf(mock)));
                    expect(
                            helper,
                            !SeamSender.readyToSwap(mock.player(), run.site().two(), arrival),
                            "ready after an accept of another dimension");
                    mock.send(new SeamAcceptPayload(two, generationOf(mock)));
                    expect(
                            helper,
                            SeamSender.readyToSwap(mock.player(), run.site().two(), arrival),
                            "not ready after an accept of the new generation");
                    helper.succeed();
                });
    }

    /** Chunks of the area centred on {@code centre} the mock was sent. */
    private static long chunksOfArea(MockPlayer mock, ChunkPos centre) {
        int radius = SeamSender.SEND_RADIUS;
        return mock.payloadsOf(SeamChunkPayload.class).stream()
                .map(payload -> payload.chunk())
                .filter(chunk ->
                        Math.abs(chunk.x() - centre.x()) <= radius && Math.abs(chunk.z() - centre.z()) <= radius)
                .map(chunk -> new ChunkPos(chunk.x(), chunk.z()))
                .distinct()
                .count();
    }

    /** A rider whose client has no swap channel is never ready, though it holds the whole area and accepted. */
    @GameTest(maxTicks = MAX_TICKS)
    public void aRiderWithoutTheChannelIsNotReady(GameTestHelper helper) {
        Run run = Run.of(helper, FIRST_INDEX + 2);
        MockPlayer mock = MockPlayers.join(helper, "seam-swap-no-channel");
        mock.announceChannel(SeamPreparePayload.TYPE);
        mock.announceChannel(SeamChunkPayload.TYPE);
        mock.announceChannel(SeamDiscardPayload.TYPE);
        run.stage(
                () -> {
                    run.site().carveShaft(run.site().x(), HOLD_HEIGHT + 2);
                    mock.teleportTo(run.site().one(), run.site().at(HOLD_HEIGHT), 0, 0);
                },
                ticks -> {
                    if (mock.payloadsOf(SeamChunkPayload.class).size() < SENT_CHUNKS) {
                        return;
                    }
                    mock.send(new SeamAcceptPayload(run.site().two().dimension(), generationOf(mock)));
                    expect(helper, !ready(run, mock), "ready without the swap channel");
                    helper.succeed();
                });
    }

    /** A rider far from the floor has no seam, and a mock that never joined an approach is not ready. */
    @GameTest(maxTicks = MAX_TICKS)
    public void aRiderWithNoSeamIsNotReady(GameTestHelper helper) {
        Run run = Run.of(helper, FIRST_INDEX + 3);
        MockPlayer mock = joinWithChannels(helper, "seam-swap-no-seam");
        int outside = LayerTuning.DEFAULT.seamFloorMargin() + 16;
        run.stage(
                List.of(BlockPos.containing(run.site().at(outside))),
                () -> {
                    run.site().carveShaft(run.site().x(), outside + 2);
                    mock.teleportTo(run.site().one(), run.site().at(outside), 0, 0);
                },
                ticks -> {
                    mock.send(new SeamAcceptPayload(run.site().two().dimension(), 1));
                    expect(helper, !ready(run, mock), "ready with no approach");
                    if (ticks >= 20) {
                        helper.succeed();
                    }
                });
    }
}
