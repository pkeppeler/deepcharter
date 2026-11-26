package io.github.pkeppeler.deepcharter.terminal;

import io.github.pkeppeler.deepcharter.handbook.HandbookItems;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Terminals from the seat (#1447). An empty-hand use of the pod a player sits in asks the server
 * ({@link SeatedTerminalPayload}) for the terminal in reach that the player is looking toward, and the
 * server opens it as a click on it would: reach is {@link Terminals#inReach}, which measures from the pod
 * for anyone seated in one.
 */
public final class SeatedTerminals {
    private SeatedTerminals() {}

    /** The pod {@code player} is seated in, in any seat, or empty on foot. Safe on both sides. */
    public static Optional<PodEntity> seat(Player player) {
        return player.getRootVehicle() instanceof PodEntity pod ? Optional.of(pod) : Optional.empty();
    }

    /**
     * Whether {@code player}'s use of {@code used} with {@code hand} asks for a terminal: a main-hand use, not
     * crouched, of the pod they sit in, with the off hand empty and the main hand empty or holding the
     * handbook, which has no use on a pod. Any other use keeps its meaning (a held fuel, tether or part, in
     * either hand, acts on the pod; a crouched use opens its cargo hold). Safe on both sides.
     */
    public static boolean wantsTerminal(Player player, InteractionHand hand, Entity used) {
        ItemStack held = player.getMainHandItem();
        return hand == InteractionHand.MAIN_HAND
                && seat(player).filter(pod -> pod == used).isPresent()
                && (held.isEmpty() || HandbookItems.isHandbook(held))
                && player.getOffhandItem().isEmpty()
                && !player.isSecondaryUseActive();
    }

    /**
     * Opens, for {@code player} seated in a pod, the terminal in reach and in sight nearest the direction they
     * look, the nearer one on a tie, or tells them none is in reach. It is forgiving: a terminal behind the
     * pilot opens when it is the only one. A request from anyone not seated in a pod is ignored: only a
     * forged packet sends one.
     */
    static void open(ServerPlayer player) {
        Optional<PodEntity> seat = seat(player);
        if (seat.isEmpty()) {
            return;
        }
        Optional<BlockPos> faced = faced(player, seat.get());
        if (faced.isEmpty()) {
            Terminals.refuse(player, TerminalRefusal.NONE_IN_REACH);
            return;
        }
        Terminals.open(player, faced.get());
    }

    private static Optional<BlockPos> faced(ServerPlayer player, PodEntity pod) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        AABB box = pod.getBoundingBox();
        return inReach(player, box.inflate(TerminalTuning.DEFAULT.maxDistance())).stream()
                .filter(pos -> inSight(player, eye, pos))
                .min(Comparator.<BlockPos>comparingDouble(pos ->
                                -look.dot(Vec3.atCenterOf(pos).subtract(eye).normalize()))
                        .thenComparingDouble(pos -> box.distanceToSqr(Vec3.atCenterOf(pos))));
    }

    /**
     * Whether nothing solid stands between the eyes and the middle of the terminal at {@code pos}: the ray
     * hits nothing, or that terminal's own blocks. Only the seated pick asks, so a pod under the colony cannot
     * pick a terminal through the rock; reach itself ({@link Terminals#inReach}) stays a distance, as on foot.
     * Entities do not block, so the pod round the eyes does not.
     */
    private static boolean inSight(ServerPlayer player, Vec3 eye, BlockPos pos) {
        BlockHitResult hit = player.level()
                .clip(new ClipContext(
                        eye, Vec3.atCenterOf(pos), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.MISS
                || TerminalBlock.lowerHalf(player.level(), hit.getBlockPos()).equals(pos);
    }

    /**
     * The terminals in reach of {@code player} among the block entities of the loaded chunks under {@code around}. A
     * terminal's block entity sits in its lower half, so each terminal is found once.
     */
    private static List<BlockPos> inReach(ServerPlayer player, AABB around) {
        ServerLevel level = player.level();
        List<BlockPos> found = new ArrayList<>();
        for (int chunkX = SectionPos.posToSectionCoord(around.minX);
                chunkX <= SectionPos.posToSectionCoord(around.maxX);
                chunkX++) {
            for (int chunkZ = SectionPos.posToSectionCoord(around.minZ);
                    chunkZ <= SectionPos.posToSectionCoord(around.maxZ);
                    chunkZ++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) {
                    continue;
                }
                for (BlockEntity entity : chunk.getBlockEntities().values()) {
                    if (entity instanceof TerminalBlockEntity && Terminals.inReach(player, entity.getBlockPos())) {
                        found.add(entity.getBlockPos());
                    }
                }
            }
        }
        return found;
    }
}
