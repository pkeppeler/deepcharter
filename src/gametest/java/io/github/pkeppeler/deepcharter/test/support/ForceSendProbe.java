package io.github.pkeppeler.deepcharter.test.support;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * What the server saw when vanilla force-sent the blocks under a player (#1317): the move packets that led to it, and
 * the blocks under and inside the player's box where the packet puts it. Inert until {@link #watch}; {@code
 * ForceSendProbeMixin} feeds it.
 */
public final class ForceSendProbe {
    private static final int MOVES_KEPT = 6;

    /**
     * One move packet as the server took it, before moving the player.
     *
     * @param tick the server tick it was handled in
     * @param fromY the server's Y of the player before it
     * @param toY the packet's Y
     * @param onGround the packet's on-ground flag
     * @param collidedBelow the server's {@code verticalCollisionBelow} before it, which vanilla's check reads
     * @param serverVy the server's simulated vertical speed of the player before it
     */
    public record Move(int tick, double fromY, double toY, boolean onGround, boolean collidedBelow, double serverVy) {
        @Override
        public String toString() {
            return "t" + tick + " y " + fromY + " -> " + toY + (onGround ? " ground" : " air")
                    + (collidedBelow ? " below" : " -") + " vy " + serverVy;
        }
    }

    /** A force-send: the last moves, the box the packet puts the player in, and each block there on the server. */
    public record Shot(int tick, List<Move> moves, AABB box, List<BlockPos> positions, List<String> serverBlocks) {}

    private static final Map<UUID, Deque<Move>> MOVES = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<Shot> SHOTS = new ConcurrentLinkedQueue<>();
    private static volatile boolean watching;

    private ForceSendProbe() {}

    /** Turns recording on or off; off clears what was recorded. */
    public static void watch(boolean on) {
        watching = on;
        if (!on) {
            MOVES.clear();
            SHOTS.clear();
        }
    }

    /** The next force-send recorded, or null when there is none. */
    public static @Nullable Shot poll() {
        return SHOTS.poll();
    }

    public static void move(ServerPlayer player, double toY, boolean onGround) {
        if (!watching) {
            return;
        }
        Move move = new Move(
                player.level().getServer().getTickCount(),
                player.getY(),
                toY,
                onGround,
                player.verticalCollisionBelow,
                player.getDeltaMovement().y);
        Deque<Move> moves = MOVES.computeIfAbsent(player.getUUID(), id -> new ArrayDeque<>());
        synchronized (moves) {
            moves.addLast(move);
            if (moves.size() > MOVES_KEPT) {
                moves.removeFirst();
            }
        }
    }

    /** Called before vanilla's move of {@code player}, so its box is still where the last packet put it. */
    public static void forceSend(ServerPlayer player) {
        if (!watching) {
            return;
        }
        Deque<Move> moves = MOVES.getOrDefault(player.getUUID(), new ArrayDeque<>());
        List<Move> kept;
        synchronized (moves) {
            kept = List.copyOf(moves);
        }
        AABB box = player.getBoundingBox();
        if (!kept.isEmpty()) {
            Move last = kept.getLast();
            box = box.move(0, last.toY() - last.fromY(), 0);
        }
        List<BlockPos> positions = positions(box);
        List<String> blocks = new ArrayList<>();
        for (BlockPos pos : positions) {
            blocks.add(describe(player.level(), pos));
        }
        SHOTS.add(new Shot(player.level().getServer().getTickCount(), kept, box, positions, blocks));
    }

    /** The blocks of the row under the box and of its lowest row. */
    public static List<BlockPos> positions(AABB box) {
        List<BlockPos> positions = new ArrayList<>();
        int below = (int) Math.floor(box.minY - 1.0E-5);
        for (int y = below; y <= below + 1; y++) {
            for (int x = (int) Math.floor(box.minX); x <= (int) Math.floor(box.maxX); x++) {
                for (int z = (int) Math.floor(box.minZ); z <= (int) Math.floor(box.maxZ); z++) {
                    positions.add(new BlockPos(x, y, z));
                }
            }
        }
        return positions;
    }

    /** A block's state and the bounds of its collision shape. */
    public static String describe(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        VoxelShape shape = state.getCollisionShape(level, pos);
        return pos.toShortString() + " " + state + " " + (shape.isEmpty() ? "no collision" : shape.bounds());
    }
}
