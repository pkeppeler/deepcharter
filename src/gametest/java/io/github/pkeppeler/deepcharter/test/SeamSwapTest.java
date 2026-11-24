package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.layer.BreachPayload;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.layer.SeamAcceptPayload;
import io.github.pkeppeler.deepcharter.layer.SeamPreparePayload;
import io.github.pkeppeler.deepcharter.layer.SeamRefusePayload;
import io.github.pkeppeler.deepcharter.layer.SeamRefuseReason;
import io.github.pkeppeler.deepcharter.layer.SeamSwapPayload;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.UnaryOperator;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
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
}
