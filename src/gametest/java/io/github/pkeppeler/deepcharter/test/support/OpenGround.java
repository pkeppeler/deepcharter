package io.github.pkeppeler.deepcharter.test.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * A flat pad of generated ground in the overworld, far from the colony, for a drive that must meet nothing a world's spawn happens to
 * build. The pad is long along +z (south), the way a pilot who has not turned drives.
 */
public final class OpenGround {
    /** The pad's corner: far enough from the world spawn that no colony or other structure of it reaches. */
    public static final int PAD_X = 6000;

    public static final int PAD_Z = 0;
    /** The pad's margin behind and beside the riders, and ahead of them along the drive, in blocks. */
    private static final int PAD_BEHIND = 4;

    private static final int PAD_SIDE = 16;
    private static final int PAD_AHEAD = 70;
    private static final int PAD_HEADROOM = 10;

    private OpenGround() {}

    /**
     * Lays the pad, moves the first player of {@code server} that {@code traveller} accepts onto it, and waits for the pad's chunks to
     * tick on the server and for the client to hold the pad. {@code floor} gives the block at the pad's column {@code x}, {@code z}
     * (world coordinates) of the floor, one block under the ground the pods stand on. Returns the floor block the traveller stands on.
     */
    public static BlockPos prepare(
            ClientGameTestContext context,
            TestServerContext server,
            Predicate<ServerPlayer> traveller,
            BiFunction<Integer, Integer, Block> floor) {
        List<ChunkPos> chunks = new ArrayList<>();
        for (int chunkX = (PAD_X - PAD_BEHIND) >> 4; chunkX <= (PAD_X + PAD_SIDE) >> 4; chunkX++) {
            for (int chunkZ = (PAD_Z - PAD_BEHIND) >> 4; chunkZ <= (PAD_Z + PAD_AHEAD) >> 4; chunkZ++) {
                chunks.add(new ChunkPos(chunkX, chunkZ));
            }
        }
        // force-ok: a client test has no GameTestHelper to release through; the world is discarded when the test ends
        server.runOnServer(minecraftServer ->
                chunks.forEach(chunk -> minecraftServer.overworld().setChunkForced(chunk.x(), chunk.z(), true)));
        ClientWait.until(
                context,
                "the pad's chunks entity-ticking far from the colony",
                () -> server.computeOnServer(minecraftServer -> chunks.stream()
                        .allMatch(chunk ->
                                minecraftServer.overworld().isPositionEntityTicking(chunk.getMiddleBlockPosition(64)))),
                () -> "chunks " + chunks);
        BlockPos floorPos = server.computeOnServer(minecraftServer -> {
            ServerLevel level = minecraftServer.overworld();
            int groundY = level.getHeightmapPos(
                            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(PAD_X, 64, PAD_Z))
                    .getY();
            BlockState air = Blocks.AIR.defaultBlockState();
            for (int x = PAD_X - PAD_BEHIND; x <= PAD_X + PAD_SIDE; x++) {
                for (int z = PAD_Z - PAD_BEHIND; z <= PAD_Z + PAD_AHEAD; z++) {
                    level.setBlock(
                            new BlockPos(x, groundY - 1, z),
                            floor.apply(x, z).defaultBlockState(),
                            Block.UPDATE_CLIENTS);
                    for (int y = groundY; y < groundY + PAD_HEADROOM; y++) {
                        level.setBlock(new BlockPos(x, y, z), air, Block.UPDATE_CLIENTS);
                    }
                }
            }
            ServerPlayer player = minecraftServer.getPlayerList().getPlayers().stream()
                    .filter(traveller)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no player on the server to move onto the pad"));
            player.teleportTo(level, PAD_X + 0.5, groundY, PAD_Z + 0.5, Set.of(), 0f, 0f, true);
            return new BlockPos(PAD_X, groundY - 1, PAD_Z);
        });
        ClientWait.until(
                context,
                "the client holding the flat pad",
                client -> ClientLight.clientLevel(client)
                                .getBlockState(floorPos)
                                .is(floor.apply(floorPos.getX(), floorPos.getZ()))
                        && ClientLight.clientLevel(client)
                                .getBlockState(floorPos.above())
                                .isAir()
                        && ClientSession.player(client)
                                        .distanceToSqr(
                                                floorPos.getX() + 0.5, floorPos.getY() + 1, floorPos.getZ() + 0.5)
                                < 4);
        return floorPos;
    }
}
