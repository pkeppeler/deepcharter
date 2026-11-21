package io.github.pkeppeler.deepcharter.test;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import io.github.pkeppeler.deepcharter.layer.LightSync;
import io.github.pkeppeler.deepcharter.test.support.BlockLightOracle;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.LightBroadcastSuppression;
import io.github.pkeppeler.deepcharter.test.support.LightSyncSends;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import java.util.BitSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;

/**
 * The safety net of {@link LightSync} (#863, ADR 0068). A player who tracks two chunks is sent both, a lamp beside their border is
 * placed and held as a draw is, and the hold ends. Vanilla's light broadcast is switched off for the player's chunk
 * ({@link LightBroadcastSuppression}), so a light change after the hold reaches the player by no other way. Then:
 *
 * <ul>
 *   <li>the lamp is removed: the light the server stores in the chunk is the light of the blocks, which {@link BlockLightOracle} works
 *       out without the light engine, and the net sends the player the sections that changed ({@link LightSyncSends} records what
 *       {@code LightSync} sends, so a vanilla update cannot stand in);
 *   <li>nothing is changed: the net sends nothing.
 * </ul>
 */
public class LightSyncSafetyNetTest {
    private static final String SAMPLE = "deepcharter:campaign_sample";
    /** Far from the cells other tests use. */
    private static final int PLAYER_X = -40_000;

    private static final int PLAYER_Z = 40_000;
    /** The chunks held loaded east and west of the player's chunk, and north and south of it. */
    private static final int REACH_X = 3;

    private static final int REACH_Z = 2;
    /** Rows round the lamp that the oracle compares; light fades to nothing within 15 blocks. */
    private static final int ROWS = 8;

    private static final int SETTLE_TICKS = 20;
    /** Ticks the net compares for after a hold ended, with a margin: restates its window. */
    private static final int NET_WAIT_TICKS = 100 + 40;

    private static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + 1_200;

    @GameTest(dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void aLightChangeAfterAHoldNoBroadcastReachesAPlayerIsSent(GameTestHelper helper) {
        run(helper, true);
    }

    @GameTest(dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void noLightPacketIsSentWhenTheLightDidNotChangeAfterAHold(GameTestHelper helper) {
        run(helper, false);
    }

    private static void run(GameTestHelper helper, boolean change) {
        ServerLevel level = helper.getLevel();
        MockPlayer mock = MockPlayers.join(helper, change ? "net-change" : "net-same");
        UUID id = mock.player().getUUID();
        mock.player().setGameMode(GameType.SPECTATOR);
        int offsetX = change ? 0 : 20;
        ChunkPos home = new ChunkPos((PLAYER_X >> 4) + offsetX, PLAYER_Z >> 4);
        ChunkPos lampChunk = new ChunkPos(home.x() + 1, home.z());
        List<ChunkPos> pair = List.of(home, lampChunk);
        LightSyncSends.watch(id);
        FarChunks.Deadline deadline = FarChunks.deadline(120);
        int[] phase = {0};
        int[] waited = {0};
        BlockPos[] lamp = {null};
        int[][] before = {null};
        boolean[] ended = {false};
        Runnable finish = () -> {
            ended[0] = true;
            LightBroadcastSuppression.restore(home);
            LightBroadcastSuppression.restore(lampChunk);
            LightSyncSends.unwatch(id);
            mock.leave();
        };
        TestTicks.each(helper, () -> {
            if (ended[0]) {
                return;
            }
            try {
                switch (phase[0]) {
                    case 0 -> {
                        // Every chunk of the case is loaded and forced, so it ticks as far as its neighbours allow.
                        forEach(home, chunk -> {
                            FarChunks.force(helper, level, chunk.x(), chunk.z());
                            level.getChunk(chunk.x(), chunk.z(), ChunkStatus.FULL);
                        });
                        phase[0] = 1;
                    }
                    case 1 -> {
                        boolean ticking = level.isPositionEntityTicking(home.getWorldPosition());
                        deadline.await(helper, level, ticking, () -> "the chunk " + home + " did not tick");
                        if (!ticking) {
                            return;
                        }
                        mock.teleportTo(
                                level,
                                TestLayers.vec(
                                        TestLayers.SURFACE, home.getMiddleBlockX() + 0.5, 100, home.getMiddleBlockZ()),
                                0f,
                                0f);
                        phase[0] = 2;
                    }
                    case 2 -> {
                        boolean sent = mock.chunksSent().containsAll(pair);
                        deadline.await(helper, level, sent, () -> "the player was not sent " + pair);
                        if (!sent) {
                            return;
                        }
                        // Vanilla sends the player no light of these chunks from here on.
                        LightBroadcastSuppression.suppress(home);
                        LightBroadcastSuppression.suppress(lampChunk);
                        // A lamp beside the border with the player's chunk, placed as a draw places blocks.
                        int x = lampChunk.getMinBlockX() + 1;
                        int z = lampChunk.getMiddleBlockZ();
                        lamp[0] = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 2, z);
                        level.setBlock(lamp[0], Blocks.SEA_LANTERN.defaultBlockState(), 3);
                        LightSync.holdSendUntilLit(level, lamp[0], lamp[0]);
                        phase[0] = 3;
                    }
                    case 3 -> {
                        boolean over =
                                holdsEnded(pair) && !level.getLightEngine().hasLightWork();
                        deadline.await(helper, level, over, () -> "the hold of " + pair + " did not end");
                        if (!over || waited[0]++ < SETTLE_TICKS) {
                            return;
                        }
                        checkStoredLight(helper, level, home, lampChunk, lamp[0], "after the hold");
                        before[0] = storedLight(level, home, lamp[0]);
                        // The hold is over. The sends so far are not the ones the case is about.
                        LightSyncSends.unwatch(id);
                        LightSyncSends.watch(id);
                        if (change) {
                            level.setBlock(lamp[0], Blocks.AIR.defaultBlockState(), 3);
                        }
                        waited[0] = 0;
                        phase[0] = 4;
                    }
                    default -> {
                        if (waited[0]++ < NET_WAIT_TICKS) {
                            return;
                        }
                        if (change) {
                            checkSentAfterChange(helper, level, home, lampChunk, lamp[0], before[0], id);
                        } else {
                            BitSet sent = LightSyncSends.sectionsCarried(id, home);
                            sent.or(LightSyncSends.sectionsCarried(id, lampChunk));
                            if (!sent.isEmpty()) {
                                throw failure(
                                        helper,
                                        "the light did not change, and the net sent the player the sections %s",
                                        sent);
                            }
                        }
                        finish.run();
                        helper.succeed();
                    }
                }
            } catch (RuntimeException e) {
                finish.run();
                throw e;
            }
        });
    }

    private static void forEach(ChunkPos home, Consumer<ChunkPos> body) {
        for (int x = home.x() - REACH_X; x <= home.x() + REACH_X; x++) {
            for (int z = home.z() - REACH_Z; z <= home.z() + REACH_Z; z++) {
                body.accept(new ChunkPos(x, z));
            }
        }
    }

    private static boolean holdsEnded(List<ChunkPos> chunks) {
        return LightSync.heldChunks().stream()
                .noneMatch(line -> chunks.stream().anyMatch(chunk -> line.contains(chunk.toString())));
    }

    /** The rows the case compares, round the lamp. */
    private static BoundingBox box(ChunkPos home, ChunkPos lampChunk, BlockPos lamp) {
        return new BoundingBox(
                home.getMinBlockX(),
                lamp.getY() - ROWS,
                home.getMinBlockZ(),
                lampChunk.getMaxBlockX(),
                lamp.getY() + ROWS,
                home.getMaxBlockZ());
    }

    /** The server stores the light of the blocks in both chunks of the pair. */
    private static void checkStoredLight(
            GameTestHelper helper, ServerLevel level, ChunkPos home, ChunkPos lampChunk, BlockPos lamp, String when) {
        BlockLightOracle.Field expected = BlockLightOracle.expected(level, box(home, lampChunk, lamp));
        List<BlockLightOracle.Mismatch> stored = BlockLightOracle.mismatches(level, expected);
        if (!stored.isEmpty()) {
            throw failure(
                    helper,
                    "%s: the server stores block light the blocks do not give in %d cells; the first: %s",
                    when,
                    stored.size(),
                    stored.subList(0, Math.min(6, stored.size())));
        }
    }

    /** The block light the engine stores for every cell of the chunk, in the rows of the case. */
    private static int[] storedLight(ServerLevel level, ChunkPos chunk, BlockPos lamp) {
        int[] light = new int[16 * 16 * (2 * ROWS + 1)];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int at = 0;
        for (int x = chunk.getMinBlockX(); x <= chunk.getMaxBlockX(); x++) {
            for (int z = chunk.getMinBlockZ(); z <= chunk.getMaxBlockZ(); z++) {
                for (int y = lamp.getY() - ROWS; y <= lamp.getY() + ROWS; y++) {
                    light[at++] = level.getBrightness(LightLayer.BLOCK, pos.set(x, y, z));
                }
            }
        }
        return light;
    }

    /** The net sent the player every section of the player's chunk whose stored light changed, and the stored light is the oracle's. */
    private static void checkSentAfterChange(
            GameTestHelper helper,
            ServerLevel level,
            ChunkPos home,
            ChunkPos lampChunk,
            BlockPos lamp,
            int[] before,
            UUID player) {
        checkStoredLight(helper, level, home, lampChunk, lamp, "after the change");
        int[] after = storedLight(level, home, lamp);
        int minLightSection = level.getLightEngine().getMinLightSection();
        BitSet changed = new BitSet();
        for (int i = 0; i < after.length; i++) {
            if (after[i] != before[i]) {
                changed.set(((lamp.getY() - ROWS + i % (2 * ROWS + 1)) >> 4) - minLightSection);
            }
        }
        if (changed.isEmpty()) {
            throw failure(helper, "removing the lamp changed no light in %s, so the case proves nothing", home);
        }
        BitSet unsent = (BitSet) changed.clone();
        unsent.andNot(LightSyncSends.sectionsCarried(player, home));
        if (!unsent.isEmpty()) {
            throw failure(
                    helper,
                    "the light of %s changed in the sections %s (counted from the lowest light section) after its hold ended, no broadcast reached the player, and the net did not send them, so the client keeps the old light",
                    home,
                    unsent);
        }
    }

    @FormatMethod
    private static RuntimeException failure(
            GameTestHelper helper, @FormatString String format, @Nullable Object... args) {
        return helper.assertionException(Component.literal(String.format(format, args)));
    }
}
