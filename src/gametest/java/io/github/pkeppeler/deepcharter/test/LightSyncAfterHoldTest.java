package io.github.pkeppeler.deepcharter.test;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import io.github.pkeppeler.deepcharter.layer.LightSync;
import io.github.pkeppeler.deepcharter.test.support.BlockLightOracle;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.LightSyncSends;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import java.util.BitSet;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;

/**
 * A light change that settles after a hold has ended, in a chunk the player was sent that does not tick yet (#863). Vanilla drops the
 * change of a chunk that does not tick, and the hold of {@link LightSync} was over, so the player kept the old light. The case:
 *
 * <ol>
 *   <li>a player who tracks two chunks beyond the simulation distance is sent both, so neither ticks;
 *   <li>a lamp is placed beside the border and held as a draw is, and the hold ends;
 *   <li>the lamp is removed with no hold: the light of the chunk that does not hold the lamp changes, and vanilla drops the change;
 *   <li>the simulation distance rises, the chunk ticks, and {@link LightSync} must send the player the sections that changed. The
 *       test reads what {@code LightSync} sends through {@link LightSyncSends}, so a vanilla update cannot stand in for it.
 * </ol>
 *
 * The server's stored light is the light of the blocks, which {@link BlockLightOracle} works out without the light engine, before and
 * after the change.
 */
public class LightSyncAfterHoldTest {
    private static final String SAMPLE = "deepcharter:campaign_sample";
    /** Far from the cells other tests use; the chunk column of the player. */
    private static final int PLAYER_X = -40_000;

    private static final int PLAYER_Z = 40_000;
    private static final int SIMULATION_DISTANCE = 2;
    /** Chunks east of the player's chunk: a chunk that ticks at the simulation distance of the case is at most 1 chunk out. */
    private static final int FAR_CHUNK_OFFSET = 2;

    private static final int VIEW_DISTANCE = FAR_CHUNK_OFFSET + 1;
    /** Rows round the lamp that the oracle compares; light fades to nothing within 15 blocks. */
    private static final int ROWS = 8;

    private static final int SETTLE_TICKS = 20;
    private static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + 1_200;

    @GameTest(dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void aLightChangeAfterAHoldInAChunkThatDoesNotTickIsSentWhenItTicks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        int simulationBefore = server.getPlayerList().getSimulationDistance();
        int viewBefore = server.getPlayerList().getViewDistance();
        server.getPlayerList().setSimulationDistance(SIMULATION_DISTANCE);
        server.getPlayerList().setViewDistance(VIEW_DISTANCE);
        MockPlayer mock = MockPlayers.join(helper, "after-hold");
        UUID id = mock.player().getUUID();
        ClientInformation info = mock.player().clientInformation();
        mock.player()
                .updateOptions(new ClientInformation(
                        info.language(),
                        VIEW_DISTANCE,
                        info.chatVisibility(),
                        info.chatColors(),
                        info.modelCustomisation(),
                        info.mainHand(),
                        info.textFilteringEnabled(),
                        info.allowsListing(),
                        info.particleStatus()));
        mock.player().setGameMode(GameType.SPECTATOR);
        ChunkPos home = new ChunkPos(PLAYER_X >> 4, PLAYER_Z >> 4);
        ChunkPos far = new ChunkPos(home.x() + FAR_CHUNK_OFFSET, home.z());
        ChunkPos lampChunk = new ChunkPos(far.x() - 1, far.z());
        List<ChunkPos> pair = List.of(far, lampChunk);
        LightSyncSends.watch(id);
        FarChunks.Deadline deadline = FarChunks.deadline(120);
        int[] phase = {0};
        int[] waited = {0};
        BlockPos[] lamp = {null};
        int[][] before = {null};
        boolean[] ended = {false};
        Runnable finish = () -> {
            ended[0] = true;
            server.getPlayerList().setSimulationDistance(simulationBefore);
            server.getPlayerList().setViewDistance(viewBefore);
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
                        mock.teleportTo(
                                level, TestLayers.vec(TestLayers.SURFACE, PLAYER_X + 0.5, 100, PLAYER_Z + 0.5), 0f, 0f);
                        phase[0] = 1;
                    }
                    case 1 -> {
                        boolean sent = mock.chunksSent().containsAll(pair) && ringLoaded(level, far, lampChunk);
                        deadline.await(
                                helper,
                                level,
                                sent,
                                () -> "the player was not sent " + pair + ": server view distance "
                                        + server.getPlayerList().getViewDistance() + ", the player's tracking view "
                                        + mock.player().getChunkTrackingView() + ", sent "
                                        + mock.chunksSent().size() + " chunks, ring loaded "
                                        + ringLoaded(level, far, lampChunk));
                        if (!sent) {
                            return;
                        }
                        if (level.isPositionEntityTicking(far.getWorldPosition())) {
                            throw failure(helper, "the chunk %s ticks, so the case proves nothing", far);
                        }
                        // A lamp on the chunk border, in the air above the ground, placed as a draw places blocks.
                        int x = lampChunk.getMaxBlockX() - 1;
                        int z = lampChunk.getMiddleBlockZ();
                        lamp[0] = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 2, z);
                        level.setBlock(lamp[0], Blocks.SEA_LANTERN.defaultBlockState(), 3);
                        LightSync.holdSendUntilLit(level, lamp[0], lamp[0]);
                        phase[0] = 2;
                    }
                    case 2 -> {
                        boolean over =
                                holdsEnded(pair) && !level.getLightEngine().hasLightWork();
                        deadline.await(helper, level, over, () -> "the hold of " + pair + " did not end");
                        if (!over || waited[0]++ < SETTLE_TICKS) {
                            return;
                        }
                        checkStoredLight(helper, level, far, lampChunk, lamp[0], "after the hold");
                        before[0] = storedLight(level, far, lamp[0]);
                        // The hold is over. The sends so far are not the ones the case is about.
                        LightSyncSends.unwatch(id);
                        LightSyncSends.watch(id);
                        level.setBlock(lamp[0], Blocks.AIR.defaultBlockState(), 3);
                        waited[0] = 0;
                        phase[0] = 3;
                    }
                    case 3 -> {
                        boolean changed =
                                changedSections(level, far, lamp[0], before[0]).cardinality() > 0;
                        deadline.await(helper, level, changed, () -> "the light of " + far + " did not change");
                        if (!changed || waited[0]++ < SETTLE_TICKS) {
                            return;
                        }
                        checkStoredLight(helper, level, far, lampChunk, lamp[0], "after the change");
                        if (level.isPositionEntityTicking(far.getWorldPosition())) {
                            throw failure(helper, "the chunk %s ticked before the case let it", far);
                        }
                        server.getPlayerList().setSimulationDistance(VIEW_DISTANCE + 4);
                        waited[0] = 0;
                        phase[0] = 4;
                    }
                    default -> {
                        boolean ticking = level.isPositionEntityTicking(far.getWorldPosition());
                        deadline.await(helper, level, ticking, () -> "the chunk " + far + " did not tick");
                        if (!ticking || waited[0]++ < SETTLE_TICKS) {
                            return;
                        }
                        BitSet changed = changedSections(level, far, lamp[0], before[0]);
                        BitSet unsent = (BitSet) changed.clone();
                        unsent.andNot(LightSyncSends.sectionsCarried(id, far));
                        if (!unsent.isEmpty()) {
                            throw failure(
                                    helper,
                                    "the light of %s changed in the sections %s (counted from the lowest light section) after its hold ended, and the player who had the chunk was not sent them once it ticked, so the client keeps the old light",
                                    far,
                                    unsent);
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

    /** Every chunk within light's reach of the pair is loaded, as the oracle needs. */
    private static boolean ringLoaded(ServerLevel level, ChunkPos far, ChunkPos lampChunk) {
        for (int x = lampChunk.x() - 1; x <= far.x() + 1; x++) {
            for (int z = far.z() - 1; z <= far.z() + 1; z++) {
                if (level.getChunkSource().getChunkNow(x, z) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean holdsEnded(List<ChunkPos> chunks) {
        return LightSync.heldChunks().stream()
                .noneMatch(line -> chunks.stream().anyMatch(chunk -> line.contains(chunk.toString())));
    }

    /** The rows the case compares, round the lamp. */
    private static BoundingBox box(ChunkPos far, ChunkPos lampChunk, BlockPos lamp) {
        return new BoundingBox(
                lampChunk.getMinBlockX(),
                lamp.getY() - ROWS,
                far.getMinBlockZ(),
                far.getMaxBlockX(),
                lamp.getY() + ROWS,
                far.getMaxBlockZ());
    }

    /** The server stores the light of the blocks in both chunks of the pair. */
    private static void checkStoredLight(
            GameTestHelper helper, ServerLevel level, ChunkPos far, ChunkPos lampChunk, BlockPos lamp, String when) {
        BlockLightOracle.Field expected = BlockLightOracle.expected(level, box(far, lampChunk, lamp));
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

    /** The block light the engine stores for every cell of the chunk beside the lamp's, in the rows of the case. */
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

    /** The light sections, counted from the lowest, in which the stored light of the chunk differs from {@code before}. */
    private static BitSet changedSections(ServerLevel level, ChunkPos chunk, BlockPos lamp, int[] before) {
        int[] after = storedLight(level, chunk, lamp);
        int minLightSection = level.getLightEngine().getMinLightSection();
        BitSet sections = new BitSet();
        for (int i = 0; i < after.length; i++) {
            if (after[i] != before[i]) {
                sections.set(((lamp.getY() - ROWS + i % (2 * ROWS + 1)) >> 4) - minLightSection);
            }
        }
        return sections;
    }

    @FormatMethod
    private static RuntimeException failure(
            GameTestHelper helper, @FormatString String format, @Nullable Object... args) {
        return helper.assertionException(Component.literal(String.format(format, args)));
    }
}
