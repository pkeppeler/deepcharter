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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Whether the server's own blocks hold up the player where a force-send's packet lands them (#1317). Vanilla's check
 * reads the collision of the server's previous move of the player, which can be stale: a supported landing is that
 * race, an unsupported one is a block the client holds and the server does not. Inert until {@link #watch}; {@code
 * ForceSendProbeMixin} feeds it.
 */
public final class ForceSendProbe {
    private static final int MOVES_KEPT = 6;
    /** How far under the box a block counts as holding it up: the depth vanilla's own force-send reads. */
    private static final double SUPPORT_DEPTH = 1.0E-5;

    /**
     * One on-foot move packet as the server took it, before moving the player.
     *
     * @param tick the server tick it was handled in
     * @param fromY the server's Y of the player before it
     * @param to the packet's position
     * @param onGround the packet's on-ground flag
     * @param collidedBelow the server's {@code verticalCollisionBelow} before it, which vanilla's check reads
     * @param serverVy the server's simulated vertical speed of the player before it
     */
    public record Move(int tick, double fromY, Vec3 to, boolean onGround, boolean collidedBelow, double serverVy) {
        @Override
        public String toString() {
            return "t" + tick + " y " + fromY + " -> " + to + (onGround ? " ground" : " air")
                    + (collidedBelow ? " below" : " -") + " vy " + serverVy;
        }
    }

    /**
     * A force-send that passed vanilla's cooldown.
     *
     * @param player who it was for
     * @param box the player's box where the packet lands them
     * @param serverSupported whether the server's blocks hold up {@code box}: see {@link #supports}
     * @param serverBlocks each block of {@code positions} on the server, for the failure message
     */
    public record Shot(
            UUID player,
            int tick,
            List<Move> moves,
            AABB box,
            boolean serverSupported,
            List<BlockPos> positions,
            List<String> serverBlocks) {}

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

    public static void move(ServerPlayer player, Vec3 to, boolean onGround) {
        if (!watching) {
            return;
        }
        Move move = new Move(
                player.level().getServer().getTickCount(),
                player.getY(),
                to,
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

    /** Called from vanilla's force-send, before it moves {@code player} to the packet that made it. */
    public static void forceSend(ServerPlayer player) {
        if (!watching) {
            return;
        }
        List<Move> kept = List.of();
        Deque<Move> moves = MOVES.get(player.getUUID());
        if (moves != null) {
            synchronized (moves) {
                kept = List.copyOf(moves);
            }
        }
        AABB box = kept.isEmpty()
                ? player.getBoundingBox()
                : player.getDimensions(player.getPose())
                        .makeBoundingBox(kept.getLast().to());
        List<BlockPos> positions = positions(box);
        List<String> blocks = new ArrayList<>();
        for (BlockPos pos : positions) {
            blocks.add(describe(player.level(), pos));
        }
        SHOTS.add(new Shot(
                player.getUUID(),
                player.level().getServer().getTickCount(),
                kept,
                box,
                supports(player.level(), player, box),
                positions,
                blocks));
    }

    /** Whether a block of {@code level} collides with {@code entity} just under {@code box}: a floor under its feet. */
    private static boolean supports(CollisionGetter level, Entity entity, AABB box) {
        return !level.noBlockCollision(entity, box.expandTowards(0, -SUPPORT_DEPTH, 0));
    }

    /** The blocks of the row under the box and of its lowest row. */
    private static List<BlockPos> positions(AABB box) {
        List<BlockPos> positions = new ArrayList<>();
        int below = (int) Math.floor(box.minY - SUPPORT_DEPTH);
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
