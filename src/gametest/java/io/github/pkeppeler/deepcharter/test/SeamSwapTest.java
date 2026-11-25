package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.SeamSite.HOLD_HEIGHT;
import static io.github.pkeppeler.deepcharter.test.support.SeamSite.MAX_TICKS;
import static io.github.pkeppeler.deepcharter.test.support.SeamSite.hold;

import io.github.pkeppeler.deepcharter.layer.BreachEvents;
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
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.SeamSite.Run;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TwoSeatPods;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.function.UnaryOperator;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
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

    /** The slice 3 swap radius and the send radius over the default; the rest are the default's. */
    private record Builder(int swapRadius, int radius) {
        Builder(LayerTuning base) {
            this(base.seamSwapRadius(), base.seamRadius());
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
                    d.handBreak());
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

    /**
     * The shipped swap radius is 3 chunks, and a swap radius beyond what is sendable, or below 1, is
     * refused.
     */
    @GameTest
    public void swapTuningIsValidated(GameTestHelper helper) {
        LayerTuning tuning = LayerTuning.DEFAULT;
        if (tuning.seamSwapRadius() != 3) {
            throw helper.assertionException("the swap tuning is not 3 chunks: " + tuning);
        }
        with(b -> new Builder(3, 4));
        expectRejected(helper, "a swap radius of 4, past the send radius 3", b -> new Builder(4, 4));
        expectRejected(helper, "a swap radius of 0", b -> new Builder(0, 4));
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

    /**
     * Every slice 3 payload reads back what it wrote, the prepare carries its generation, and a crossing flags a swap.
     */
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
        BreachPayload moved = BreachPayload.of(overworld, nether, 1, 2, false);
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
        ByteBuf bad = Unpooled.buffer();
        VarInt.write(bad, 99);
        expect(
                helper,
                SeamRefuseReason.CODEC.decode(bad) == SeamRefuseReason.BUILD_FAILED,
                "an id out of range did not decode as the first reason");
        helper.succeed();
    }

    /**
     * The first site index of this class: {@link SeamStreamingTest} and {@link SeamDataAheadTest} use the ones below
     * it.
     */
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

    /**
     * What the chunk check holds: every chunk within the radius of the area's centre sent, and the arrival chunk inside
     * it.
     */
    @GameTest
    public void theSwapAreaIsTheChunksAroundTheCentre(GameTestHelper helper) {
        ChunkPos centre = new ChunkPos(10, 10);
        int radius = LayerTuning.DEFAULT.seamSwapRadius();
        LongSet sent = new LongOpenHashSet();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                sent.add(ChunkPos.pack(10 + dx, 10 + dz));
            }
        }
        expect(helper, SeamSender.holdsSwapArea(sent::contains, centre, centre, radius), "a full area is not held");
        expect(
                helper,
                SeamSender.holdsSwapArea(sent::contains, centre, new ChunkPos(11, 9), radius),
                "an arrival a chunk off the centre is not held");
        expect(
                helper,
                !SeamSender.holdsSwapArea(sent::contains, centre, new ChunkPos(10 + radius + 1, 10), radius),
                "an arrival chunk outside the radius is held");
        sent.remove(ChunkPos.pack(10 + radius, 10 - radius));
        expect(
                helper,
                !SeamSender.holdsSwapArea(sent::contains, centre, centre, radius),
                "an area missing a corner is held");
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

    /** What a rider's client does before the crossing. */
    private enum Client {
        /** Has every channel and accepts the prepare. */
        ACCEPTS,
        /** Has every channel and refuses the prepare it was sent. */
        REFUSES,
        /** Has the channels of slice 2 and no swap channel. */
        NO_SWAP_CHANNEL
    }

    /** A crossing of a pod with riders, once settled: the pod as it was, and the riders. */
    private record Crossed(
            Run run, PodEntity pod, int podId, UUID podUuid, List<MockPlayer> riders, List<Integer> respawnsBefore) {
        /**
         * Respawns the server sent {@code rider} since the crossing began, not those of its first teleport into layer
         * 1.
         */
        int respawnsOf(MockPlayer rider) {
            return rider.respawns() - respawnsBefore.get(riders.indexOf(rider));
        }
    }

    /** The pod's velocity when its crossing was announced, by UUID: what the arrival left it. */
    private static final Map<UUID, Vec3> ARRIVAL_VELOCITY = new ConcurrentHashMap<>();

    static {
        BreachEvents.CROSSED.register((crossed, from, to, fromLayer, toLayer) -> {
            if (crossed instanceof PodEntity) {
                ARRIVAL_VELOCITY.put(crossed.getUUID(), crossed.getDeltaMovement());
            }
        });
    }

    /** Ticks after the riders arrive that a crossing waits, for the mock to read what the server sent. */
    private static final int SETTLE_TICKS = 3;

    /**
     * A pod with one rider for each of {@code clients}, held over the floor of layer 1 until each rider holds the area
     * and has answered as its client does, then dropped across the floor. {@code verify} runs {@link #SETTLE_TICKS}
     * ticks after every rider is in layer 2; the test passes when it returns.
     */
    private static void podCrossing(
            GameTestHelper helper, int index, String name, List<Client> clients, Consumer<Crossed> verify) {
        podCrossing(helper, index, name, clients, run -> {}, verify);
    }

    /** As above, with {@code beforeDrop} run on the tick the riders have answered, before the pod is dropped. */
    private static void podCrossing(
            GameTestHelper helper,
            int index,
            String name,
            List<Client> clients,
            Consumer<Run> beforeDrop,
            Consumer<Crossed> verify) {
        podCrossingUntil(helper, index, name, clients, beforeDrop, crossed -> {
            verify.accept(crossed);
            return true;
        });
    }

    /**
     * As above, with {@code verify} run on each tick from {@link #SETTLE_TICKS} after the arrival until it returns
     * true.
     */
    private static void podCrossingUntil(
            GameTestHelper helper,
            int index,
            String name,
            List<Client> clients,
            Consumer<Run> beforeDrop,
            Predicate<Crossed> verify) {
        podCrossingAt(
                helper, index, name, clients, beforeDrop, run -> run.site().x(), verify);
    }

    /** As above, with the pod dropped across the floor at the X that {@code dropX} gives, not over where it was held. */
    private static void podCrossingAt(
            GameTestHelper helper,
            int index,
            String name,
            List<Client> clients,
            Consumer<Run> beforeDrop,
            ToDoubleFunction<Run> dropX,
            Predicate<Crossed> verify) {
        Run run = Run.of(helper, index);
        List<MockPlayer> riders = new ArrayList<>();
        for (int i = 0; i < clients.size(); i++) {
            MockPlayer mock = MockPlayers.join(helper, name + i);
            mock.announceChannel(SeamPreparePayload.TYPE);
            mock.announceChannel(SeamChunkPayload.TYPE);
            mock.announceChannel(SeamDiscardPayload.TYPE);
            mock.announceChannel(BreachPayload.TYPE);
            if (clients.get(i) != Client.NO_SWAP_CHANNEL) {
                mock.announceChannel(SeamSwapPayload.TYPE);
            }
            mock.setViewDistance(SeamSender.SEND_RADIUS + 2);
            riders.add(mock);
        }
        PodEntity[] pods = new PodEntity[1];
        int[] phase = {0};
        int[] arrivedAt = {-1};
        List<Integer> respawnsBefore = new ArrayList<>();
        run.stage(
                () -> {
                    run.site().carveShaft(run.site().x(), HOLD_HEIGHT + 4);
                    PodEntity pod =
                            TestEntities.create(PodRegistry.POD, run.site().one());
                    TwoSeatPods.add(pod);
                    hold(run.site(), pod, HOLD_HEIGHT);
                    run.site().one().addFreshEntity(pod);
                    pods[0] = pod;
                    for (MockPlayer mock : riders) {
                        mock.teleportTo(run.site().one(), run.site().at(HOLD_HEIGHT), 0, 0);
                        expect(helper, mock.player().startRiding(pod, true, false), "a mock could not board the pod");
                    }
                },
                ticks -> {
                    PodEntity pod = pods[0];
                    if (phase[0] == 0) {
                        hold(run.site(), pod, HOLD_HEIGHT);
                        if (riders.stream()
                                .anyMatch(mock ->
                                        mock.payloadsOf(SeamChunkPayload.class).size() < SENT_CHUNKS)) {
                            return;
                        }
                        for (int i = 0; i < riders.size(); i++) {
                            MockPlayer mock = riders.get(i);
                            ResourceKey<Level> two = run.site().two().dimension();
                            if (clients.get(i) == Client.REFUSES) {
                                mock.send(new SeamRefusePayload(two, generationOf(mock), SeamRefuseReason.RELEASED));
                            } else {
                                mock.send(new SeamAcceptPayload(two, generationOf(mock)));
                            }
                        }
                        riders.forEach(mock -> respawnsBefore.add(mock.respawns()));
                        beforeDrop.accept(run);
                        phase[0] = 1;
                        return;
                    }
                    if (phase[0] == 1) {
                        pod.setPos(
                                dropX.applyAsDouble(run),
                                run.site().floorY() - 1,
                                run.site().z());
                        // The speed the arrival is sized for: a swap must not carry it into the new level.
                        pod.setDeltaMovement(0, -LayerTuning.DEFAULT.seamDesignFallSpeed(), 0);
                        phase[0] = 2;
                        return;
                    }
                    if (!riders.stream().allMatch(mock -> TestLayers.isInDimensionOf(mock.player(), 2))) {
                        return;
                    }
                    if (arrivedAt[0] < 0) {
                        arrivedAt[0] = ticks;
                    }
                    riders.forEach(MockPlayer::confirmDimensionChange);
                    if (ticks - arrivedAt[0] < SETTLE_TICKS) {
                        return;
                    }
                    if (!verify.test(new Crossed(run, pod, pod.getId(), pod.getUUID(), riders, respawnsBefore))) {
                        return;
                    }
                    TwoSeatPods.forget(pod);
                    Entity vehicle = riders.get(0).player().getVehicle();
                    riders.forEach(mock -> mock.player().stopRiding());
                    if (vehicle != null) {
                        vehicle.discard();
                    }
                    helper.succeed();
                });
    }

    /** The packets that name the pod, with the position of each in what the server sent, for the order of a swap. */
    private static int firstAfter(List<Object> sequence, int from, Predicate<Object> match) {
        for (int i = from + 1; i < sequence.size(); i++) {
            if (match.test(sequence.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Checks that the crossing was a swap for every rider: same pod, same ids, still mounted, no respawn, the swap
     * first.
     */
    private static void expectSwapped(GameTestHelper helper, Crossed crossed) {
        UUID uuid = crossed.podUuid();
        ServerLevel one = crossed.run().site().one();
        ServerLevel two = crossed.run().site().two();
        expect(
                helper,
                crossed.pod().level() == two,
                "the pod is in %s, not layer 2",
                crossed.pod().level().dimension());
        expect(helper, crossed.pod().getId() == crossed.podId(), "the pod's id changed");
        expect(helper, crossed.pod().getUUID().equals(uuid), "the pod's UUID changed");
        Vec3 velocity = ARRIVAL_VELOCITY.getOrDefault(uuid, new Vec3(Double.NaN, Double.NaN, Double.NaN));
        expect(
                helper,
                Vec3.ZERO.equals(velocity),
                "the swap left the pod the velocity %s, not none: the fallback's teleport zeroes it",
                velocity);
        expect(helper, one.getEntity(uuid) == null, "layer 1 still holds the pod");
        expect(helper, two.getEntity(uuid) == crossed.pod(), "layer 2 does not hold the very same pod by UUID");
        expect(helper, two.getEntity(crossed.podId()) == crossed.pod(), "layer 2 does not hold the pod by id");
        for (MockPlayer mock : crossed.riders()) {
            expect(
                    helper,
                    mock.player().getVehicle() == crossed.pod()
                            && crossed.pod().getPassengers().contains(mock.player()),
                    "a rider is not on the pod any more");
            expect(helper, crossed.respawnsOf(mock) == 0, "a rider was sent %d respawns", crossed.respawnsOf(mock));
            List<SeamSwapPayload> swaps = mock.payloadsOf(SeamSwapPayload.class);
            expect(helper, swaps.size() == 1, "a rider was sent %d swaps", swaps.size());
            expect(
                    helper,
                    swaps.get(0).rootId() == crossed.podId()
                            && swaps.get(0).rootUuid().equals(uuid)
                            && swaps.get(0).dimension().equals(two.dimension())
                            && swaps.get(0).carried().contains(crossed.podId())
                            && swaps.get(0).carried().contains(mock.player().getId()),
                    "the swap does not name the pod and its riders: %s",
                    swaps.get(0));
            List<Object> sequence = mock.sequence();
            int swapAt = firstAfter(sequence, -1, packet -> packet instanceof SeamSwapPayload);
            int removedAt = firstAfter(
                    sequence,
                    swapAt,
                    packet -> packet instanceof ClientboundRemoveEntitiesPacket remove
                            && remove.entityIds().contains(crossed.podId()));
            int addedAt = firstAfter(
                    sequence,
                    swapAt,
                    packet -> packet instanceof ClientboundAddEntityPacket add && add.getId() == crossed.podId());
            expect(
                    helper,
                    swapAt >= 0 && removedAt > swapAt && addedAt > swapAt,
                    "the swap must come before the old level's remove and the new level's add of the pod: swap %d, remove %d, add %d",
                    swapAt,
                    removedAt,
                    addedAt);
            expect(
                    helper,
                    firstAfter(sequence, swapAt, packet -> packet instanceof ClientboundForgetLevelChunkPacket) < 0,
                    "a chunk was forgotten after the swap");
            List<BreachPayload> breaches = mock.payloadsOf(BreachPayload.class);
            expect(
                    helper,
                    breaches.size() == 1
                            && breaches.get(0).swapped()
                            && !breaches.get(0).fades(),
                    "a swap must rumble and not fade: %s",
                    breaches);
        }
    }

    /**
     * Checks that the crossing was the teleport for every rider: a respawn each, a new pod with the old UUID, still
     * mounted.
     */
    private static void expectTeleported(GameTestHelper helper, Crossed crossed) {
        Entity vehicle =
                Objects.requireNonNull(crossed.riders().get(0).player().getVehicle(), "the rider has no vehicle");
        expect(
                helper,
                vehicle != crossed.pod() && vehicle.getId() != crossed.podId(),
                "the pod was kept: the fallback recreates it");
        expect(helper, vehicle.getUUID().equals(crossed.podUuid()), "the recreated pod has another UUID");
        expect(helper, vehicle.level() == crossed.run().site().two(), "the recreated pod is not in layer 2");
        for (MockPlayer mock : crossed.riders()) {
            expect(
                    helper,
                    mock.player().getVehicle() == vehicle
                            && vehicle.getPassengers().contains(mock.player()),
                    "a rider of the vehicle was left behind: the vehicle must never split");
            expect(
                    helper,
                    crossed.respawnsOf(mock) == 1,
                    "a rider was sent %d respawns, not 1",
                    crossed.respawnsOf(mock));
            expect(
                    helper,
                    mock.payloadsOf(SeamSwapPayload.class).isEmpty(),
                    "a rider was sent a swap on the fallback path");
            List<BreachPayload> breaches = mock.payloadsOf(BreachPayload.class);
            expect(
                    helper,
                    breaches.size() == 1
                            && !breaches.get(0).swapped()
                            && breaches.get(0).fades(),
                    "the fallback must fade: %s",
                    breaches);
        }
    }

    /**
     * One ready rider: the pod keeps its object, id and UUID, moves into layer 2 and the rider stays on it, with no
     * respawn.
     */
    @GameTest(maxTicks = MAX_TICKS)
    public void aReadyRiderSwapsAndThePodKeepsItsId(GameTestHelper helper) {
        podCrossing(helper, FIRST_INDEX + 10, "seam-swap-one", List.of(Client.ACCEPTS), crossed -> {
            expectSwapped(helper, crossed);
        });
    }

    /** Two ready riders of one pod swap together, each sent its own swap and neither respawned. */
    @GameTest(maxTicks = MAX_TICKS)
    public void twoReadyRidersSwapTogether(GameTestHelper helper) {
        podCrossing(helper, FIRST_INDEX + 11, "seam-swap-two", List.of(Client.ACCEPTS, Client.ACCEPTS), crossed -> {
            expectSwapped(helper, crossed);
        });
    }

    /** One of two riders refuses: the whole pod is teleported and both riders respawn and stay mounted. */
    @GameTest(maxTicks = MAX_TICKS)
    public void aRefusingRiderSendsTheWholePodThroughTheFallback(GameTestHelper helper) {
        podCrossing(helper, FIRST_INDEX + 12, "seam-swap-refuse", List.of(Client.ACCEPTS, Client.REFUSES), crossed -> {
            expectTeleported(helper, crossed);
        });
    }

    /** One of two riders has no swap channel: the whole pod is teleported. */
    @GameTest(maxTicks = MAX_TICKS)
    public void aRiderWithoutTheChannelFallsBack(GameTestHelper helper) {
        podCrossing(
                helper,
                FIRST_INDEX + 13,
                "seam-swap-nochannel",
                List.of(Client.NO_SWAP_CHANNEL, Client.ACCEPTS),
                crossed -> {
                    expectTeleported(helper, crossed);
                });
    }

    /**
     * The arrival is solid rock when the pod drops: the swap carves the pocket, sends the changed chunks ahead of the
     * swap as seam chunks, and the crossing does not send them again, so the held level has the pocket before the pod
     * is in it.
     */
    @GameTest(maxTicks = MAX_TICKS)
    public void aSwapIntoRockCarvesAndSendsThePocketAhead(GameTestHelper helper) {
        LayerTuning tuning = LayerTuning.DEFAULT;
        podCrossing(
                helper,
                FIRST_INDEX + 14,
                "seam-swap-rock",
                List.of(Client.ACCEPTS),
                run -> {
                    ServerLevel two = run.site().two();
                    int top = TestLayers.dimensionBand(two).maxY();
                    BlockPos bottom = BlockPos.containing(
                            run.site().x(),
                            top - tuning.pocketHeight() - 1,
                            run.site().z());
                    RoomCarver.carve(
                            two,
                            bottom.offset(-3, 0, -3),
                            bottom.offset(3, tuning.pocketHeight() + 1, 3),
                            Blocks.STONE.defaultBlockState(),
                            Block.UPDATE_ALL);
                },
                crossed -> {
                    expectSwapped(helper, crossed);
                    ServerLevel two = crossed.run().site().two();
                    ChunkPos arrival = crossed.run().site().centre();
                    BlockPos at = crossed.pod().blockPosition();
                    expect(
                            helper,
                            two.getBlockState(at).isAir(),
                            "the arrival is not carved: %s",
                            two.getBlockState(at));
                    MockPlayer mock = crossed.riders().get(0);
                    List<Object> sequence = mock.sequence();
                    int swapAt = firstAfter(sequence, -1, packet -> packet instanceof SeamSwapPayload);
                    long carvedAhead = sequence.subList(0, swapAt).stream()
                            .filter(packet -> packet instanceof SeamChunkPayload chunk
                                    && chunk.chunk().x() == arrival.x()
                                    && chunk.chunk().z() == arrival.z())
                            .count();
                    expect(
                            helper,
                            carvedAhead == 2,
                            "the arrival chunk was sent %d times before the swap, not 2",
                            carvedAhead);
                    expect(
                            helper,
                            firstAfter(
                                            sequence,
                                            swapAt,
                                            packet -> packet instanceof ClientboundLevelChunkWithLightPacket chunk
                                                    && chunk.x() == arrival.x()
                                                    && chunk.z() == arrival.z())
                                    < 0,
                            "the crossing sent the carved chunk again after the swap");
                });
    }

    /**
     * One of two swapped riders reports its swap failed: only that rider is respawned, the other and the pod are
     * untouched, and the rider is paired with the pod again and still rides it. A report that names another generation
     * does nothing.
     */
    @GameTest(maxTicks = MAX_TICKS)
    public void aFailedSwapRespawnsOnlyThatRider(GameTestHelper helper) {
        int[] calls = {0};
        podCrossingUntil(
                helper,
                FIRST_INDEX + 15,
                "seam-swap-failed",
                List.of(Client.ACCEPTS, Client.ACCEPTS),
                run -> {},
                crossed -> {
                    MockPlayer failing = crossed.riders().get(1);
                    MockPlayer fine = crossed.riders().get(0);
                    ResourceKey<Level> two = crossed.run().site().two().dimension();
                    int generation =
                            failing.payloadsOf(SeamSwapPayload.class).get(0).generation();
                    if (calls[0]++ == 0) {
                        expectSwapped(helper, crossed);
                        failing.send(new SeamRefusePayload(two, generation + 1, SeamRefuseReason.SWAP_FAILED));
                        return false;
                    }
                    if (calls[0] == 2) {
                        expect(
                                helper,
                                crossed.respawnsOf(failing) == 0,
                                "a report of another generation respawned the rider");
                        failing.send(new SeamRefusePayload(two, generation, SeamRefuseReason.SWAP_FAILED));
                        return false;
                    }
                    if (calls[0] < 2 + SETTLE_TICKS) {
                        return false;
                    }
                    // The pod pairs with the rider again once the pod's chunk has been sent to it, some ticks on.
                    List<Object> sequence = failing.sequence();
                    int swapAt = firstAfter(sequence, -1, packet -> packet instanceof SeamSwapPayload);
                    int respawnAt = firstAfter(sequence, swapAt, packet -> packet instanceof ClientboundRespawnPacket);
                    int pairedAt = respawnAt < 0
                            ? -1
                            : firstAfter(
                                    sequence,
                                    respawnAt,
                                    packet -> packet instanceof ClientboundAddEntityPacket add
                                            && add.getId() == crossed.podId());
                    if (pairedAt < 0) {
                        return false;
                    }
                    expect(
                            helper,
                            crossed.respawnsOf(failing) == 1,
                            "the failing rider was sent %d respawns",
                            crossed.respawnsOf(failing));
                    expect(helper, crossed.respawnsOf(fine) == 0, "the other rider was respawned");
                    expect(
                            helper,
                            crossed.pod().getId() == crossed.podId()
                                    && crossed.pod().level()
                                            == crossed.run().site().two(),
                            "the pod was moved again");
                    expect(
                            helper,
                            failing.player().getVehicle() == crossed.pod()
                                    && fine.player().getVehicle() == crossed.pod()
                                    && crossed.pod()
                                            .getPassengers()
                                            .containsAll(List.of(failing.player(), fine.player())),
                            "a rider is not on the pod any more");
                    return true;
                });
    }

    /** The Y a pod arrives at in layer 2: where {@code BreachService} puts the bottom of the pocket. */
    private static int arrivalY(Run run) {
        // level-height: the crossing's own arrival is the level's top less the pocket, whatever the layer
        return run.site().two().getMaxY() - LayerTuning.DEFAULT.pocketHeight();
    }

    /** Fills, in layer 2, the 7 by 7 columns round the arrival from {@code depth} blocks under it to the layer's top. */
    private static void fillArrival(Run run, int depth, BlockState fill) {
        int radius = 3;
        BlockPos at =
                BlockPos.containing(run.site().x(), arrivalY(run), run.site().z());
        RoomCarver.carve(
                run.site().two(),
                at.offset(-radius, -depth, -radius),
                // level-height: the box runs to the top of the level the arrival is measured from
                new BlockPos(at.getX() + radius, run.site().two().getMaxY(), at.getZ() + radius),
                fill,
                Block.UPDATE_ALL);
    }

    /** How many times the arrival chunk was sent as a seam chunk before the swap: once ahead, twice with a carve. */
    private static long arrivalChunkSentBeforeSwap(Crossed crossed) {
        ChunkPos arrival = crossed.run().site().centre();
        List<Object> sequence = crossed.riders().get(0).sequence();
        int swapAt = firstAfter(sequence, -1, packet -> packet instanceof SeamSwapPayload);
        return sequence.subList(0, swapAt).stream()
                .filter(packet -> packet instanceof SeamChunkPayload chunk
                        && chunk.chunk().x() == arrival.x()
                        && chunk.chunk().z() == arrival.z())
                .count();
    }

    /** Waits for the new level's add of the pod, which a loaded server may send some ticks after the swap, then checks. */
    private static boolean landed(GameTestHelper helper, Crossed crossed, long sentBeforeSwap) {
        List<Object> sequence = crossed.riders().get(0).sequence();
        int swapAt = firstAfter(sequence, -1, packet -> packet instanceof SeamSwapPayload);
        if (firstAfter(
                        sequence,
                        swapAt,
                        packet -> packet instanceof ClientboundAddEntityPacket add && add.getId() == crossed.podId())
                < 0) {
            return false;
        }
        expectLanded(helper, crossed, sentBeforeSwap);
        return true;
    }

    /** The pod swapped in and stands on the floor of the pocket the crossing carved, not at the bottom of a drop. */
    private static void expectLanded(GameTestHelper helper, Crossed crossed, long sentBeforeSwap) {
        expectSwapped(helper, crossed);
        PodEntity pod = crossed.pod();
        int arrivalY = arrivalY(crossed.run());
        expect(
                helper,
                arrivalChunkSentBeforeSwap(crossed) == sentBeforeSwap,
                "the arrival chunk was sent %d times before the swap, not %d",
                arrivalChunkSentBeforeSwap(crossed),
                sentBeforeSwap);
        expect(helper, pod.getY() >= arrivalY - 0.5, "the pod fell to %s from the arrival at %d", pod.getY(), arrivalY);
        expect(helper, !pod.isInLava() && !pod.isInWater(), "the pod arrived in a liquid at %s", pod.position());
        BlockState floor =
                crossed.run().site().two().getBlockState(BlockPos.containing(pod.getX(), arrivalY - 1, pod.getZ()));
        expect(helper, !floor.isAir() && floor.getFluidState().isEmpty(), "the pod has no floor: %s", floor);
    }

    /**
     * The arrival hangs over a cavern, with air for 6 blocks under it: the swap carves, so the pocket and its floor
     * are sent ahead, and the pod lands on the floor with no velocity, not at the bottom of the cavern at terminal
     * speed.
     */
    @GameTest(maxTicks = MAX_TICKS)
    public void aSwapOverACavernCarvesAFloorAndLands(GameTestHelper helper) {
        podCrossingUntil(
                helper,
                FIRST_INDEX + 16,
                "seam-swap-cavern",
                List.of(Client.ACCEPTS),
                run -> fillArrival(run, 6, Blocks.AIR.defaultBlockState()),
                crossed -> landed(helper, crossed, 2));
    }

    /** The arrival is a pool of lava: the swap carves it away, and the pod does not arrive in lava. */
    @GameTest(maxTicks = MAX_TICKS)
    public void aSwapIntoLavaCarvesItAwayAndLands(GameTestHelper helper) {
        podCrossingUntil(
                helper,
                FIRST_INDEX + 17,
                "seam-swap-lava",
                List.of(Client.ACCEPTS),
                run -> fillArrival(run, 3, Blocks.LAVA.defaultBlockState()),
                crossed -> landed(helper, crossed, 2));
    }

    /** The arrival is clear, dry and floored: the swap does not carve, and sends the arrival chunk once. */
    @GameTest(maxTicks = MAX_TICKS)
    public void aSwapOverAFloorIsNotCarved(GameTestHelper helper) {
        podCrossingUntil(
                helper,
                FIRST_INDEX + 18,
                "seam-swap-floor",
                List.of(Client.ACCEPTS),
                run -> {
                    fillArrival(run, 6, Blocks.AIR.defaultBlockState());
                    int y = arrivalY(run) - 1;
                    BlockPos at =
                            BlockPos.containing(run.site().x(), y, run.site().z());
                    RoomCarver.carve(
                            run.site().two(),
                            at.offset(-3, 0, -3),
                            at.offset(3, 0, 3),
                            Blocks.STONE.defaultBlockState(),
                            Block.UPDATE_ALL);
                },
                crossed -> landed(helper, crossed, 1));
    }

    /**
     * A chunk the carve changed is gone when it is to be sent again (it is made so): {@code resendCarved} says no, and
     * the whole pod goes through the teleport, the pocket carved for it.
     */
    @GameTest(maxTicks = MAX_TICKS)
    public void aChunkThatCannotBeSentAgainSendsTheWholePodThroughTheFallback(GameTestHelper helper) {
        podCrossing(
                helper,
                FIRST_INDEX + 19,
                "seam-swap-unsendable",
                List.of(Client.ACCEPTS, Client.ACCEPTS),
                run -> {
                    fillArrival(run, 1, Blocks.STONE.defaultBlockState());
                    SeamSender.setVanished(run.site().centre(), true);
                },
                crossed -> {
                    SeamSender.setVanished(crossed.run().site().centre(), false);
                    expectTeleported(helper, crossed);
                });
    }

    /**
     * The pod drops at the last column of the held area, where a chest forces the pocket out of it, into a chunk the
     * riders were not sent: the readiness is asked again of the carved pocket, and the whole pod goes through the
     * teleport.
     */
    @GameTest(maxTicks = MAX_TICKS)
    public void aPocketCarvedOutsideTheHeldAreaSendsTheWholePodThroughTheFallback(GameTestHelper helper) {
        int edge = LayerTuning.DEFAULT.seamSwapRadius();
        podCrossingAt(
                helper,
                FIRST_INDEX + 20,
                "seam-swap-outside",
                List.of(Client.ACCEPTS, Client.ACCEPTS),
                run -> {
                    // A block entity in the pocket moves it to the nearest column clear of it, 3 blocks out.
                    BlockPos chest = BlockPos.containing(
                            edgeX(run, edge), arrivalY(run), run.site().z());
                    run.site().two().setBlock(chest, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
                },
                run -> edgeX(run, edge),
                crossed -> {
                    expect(
                            helper,
                            crossed.riders()
                                            .get(0)
                                            .payloadsOf(SeamPreparePayload.class)
                                            .size()
                                    == 1,
                            "the area was moved before the crossing: the swap was never tried");
                    expectTeleported(helper, crossed);
                    return true;
                });
    }

    /** The X of the first block column of the chunk {@code chunks} out from the held area's centre, at -X. */
    private static double edgeX(Run run, int chunks) {
        return 16 * (run.site().centre().x() - chunks) + 0.5;
    }
}
