package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.SeamSite.HOLD_HEIGHT;
import static io.github.pkeppeler.deepcharter.test.support.SeamSite.MAX_TICKS;
import static io.github.pkeppeler.deepcharter.test.support.SeamSite.hold;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.SeamSite.Run;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import java.lang.reflect.Field;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * A pod stays on its pilot's client while a send of its chunk is pending (#1302, ADR 0092): {@code LightSync} re-sends
 * the chunks round a carve to a player who holds them, vanilla's {@code isChunkTracked} is false while one is queued,
 * and a pod that changes section then was dropped from its pilot's client, unmounting them. A real client's next move
 * packet after the send tracks it again; the mock sends none, so the test asserts on the remove-entity itself, which
 * is the visible drop. The redirect only keeps a pod a rider tracks already ({@code TrackedEntityMixin}); a first
 * tracking is vanilla's.
 */
public class RiddenPodTrackingTest {
    /** Past the sites of {@code SeamSwapTest}, which starts at 40. */
    private static final int SITE_INDEX = 70;
    /** Blocks the pod rises to change section: a section is 16 tall. */
    private static final int SECTION = 16;
    /** Ticks the sender is held with the pod's chunk queued, over the move to the next section. */
    private static final int HELD_TICKS = 10;
    /** A count of unacknowledged batches no send gets under: what a client that is slow to acknowledge looks like. */
    private static final int NEVER_ACKNOWLEDGED = 1 << 20;

    @GameTest(maxTicks = MAX_TICKS)
    public void aPodChangingSectionStaysWithItsPilotWhileItsChunkIsQueued(GameTestHelper helper) {
        Run run = Run.of(helper, SITE_INDEX);
        MockPlayer mock = MockPlayers.join(helper, "ridden-pod-tracking");
        PodEntity[] pods = new PodEntity[1];
        int[] markedAt = {-1};
        int[] markedTick = {0};
        run.stage(
                () -> {
                    run.site().carveShaft(run.site().x(), HOLD_HEIGHT + 2 * SECTION);
                    PodEntity pod =
                            TestEntities.create(PodRegistry.POD, run.site().one());
                    hold(run.site(), pod, HOLD_HEIGHT);
                    run.site().one().addFreshEntity(pod);
                    pods[0] = pod;
                    mock.teleportTo(run.site().one(), run.site().at(HOLD_HEIGHT), 0, 0);
                    expect(helper, mock.player().startRiding(pod, true, false), "the mock could not board the pod");
                },
                ticks -> {
                    PodEntity pod = pods[0];
                    PlayerChunkSender sender = mock.player().connection.chunkSender;
                    if (markedAt[0] < 0) {
                        hold(run.site(), pod, HOLD_HEIGHT);
                        if (firstAdd(mock, pod.getId()) < 0
                                || sender.isPending(pod.chunkPosition().pack())) {
                            return;
                        }
                        // The pilot has the pod. Block the sender, and queue the pod's chunk as LightSync does.
                        setUnacknowledged(sender, NEVER_ACKNOWLEDGED);
                        LevelChunk chunk = run.site().one().getChunkAt(pod.blockPosition());
                        sender.markChunkPendingToSend(chunk);
                        markedAt[0] = mock.sequence().size();
                        markedTick[0] = ticks;
                        return;
                    }
                    hold(run.site(), pod, HOLD_HEIGHT + SECTION);
                    expect(
                            helper,
                            removedSince(mock, markedAt[0], pod.getId()) < 0,
                            "the pilot's client was told to drop the pod while its chunk was queued");
                    if (ticks - markedTick[0] >= HELD_TICKS) {
                        setUnacknowledged(sender, 0);
                        helper.succeed();
                    }
                });
    }

    private static int firstAdd(MockPlayer mock, int id) {
        List<Object> sequence = mock.sequence();
        for (int i = 0; i < sequence.size(); i++) {
            if (sequence.get(i) instanceof ClientboundAddEntityPacket add && add.getId() == id) {
                return i;
            }
        }
        return -1;
    }

    private static int removedSince(MockPlayer mock, int from, int id) {
        List<Object> sequence = mock.sequence();
        for (int i = from; i < sequence.size(); i++) {
            if (sequence.get(i) instanceof ClientboundRemoveEntitiesPacket remove
                    && remove.entityIds().contains(id)) {
                return i;
            }
        }
        return -1;
    }

    // test-seam: vanilla gives no way to make a client slow to acknowledge a chunk batch, which keeps a queued chunk
    // queued
    private static void setUnacknowledged(PlayerChunkSender sender, int batches) {
        try {
            Field field = PlayerChunkSender.class.getDeclaredField("unacknowledgedBatches");
            field.setAccessible(true);
            field.setInt(sender, batches);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("PlayerChunkSender has no unacknowledgedBatches", failure);
        }
    }
}
