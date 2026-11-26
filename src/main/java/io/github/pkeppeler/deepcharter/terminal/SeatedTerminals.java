package io.github.pkeppeler.deepcharter.terminal;

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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Terminals from the seat (#1447). An empty-hand use of the pod a player sits in asks the server
 * ({@link SeatedTerminalPayload}) for the terminal in reach that the player faces, and the server opens it
 * as a click on it would: reach is {@link Terminals#inReach}, which measures from the pod's box for anyone
 * seated in one.
 */
public final class SeatedTerminals {
    private SeatedTerminals() {}

    /**
     * Whether {@code player}'s use of {@code used} with {@code hand} asks for a terminal: a main-hand use,
     * with nothing in it and not crouched, of the pod they sit in. Any other use keeps its meaning (a held
     * fuel, tether or part acts on the pod; a crouched use opens its cargo hold). Safe on both sides.
     */
    public static boolean wantsTerminal(Player player, InteractionHand hand, Entity used) {
        return hand == InteractionHand.MAIN_HAND
                && used instanceof PodEntity
                && player.getVehicle() == used
                && player.getMainHandItem().isEmpty()
                && !player.isSecondaryUseActive();
    }

    /**
     * Opens, for {@code player} seated in a pod, the terminal in reach nearest the direction they look, the
     * nearer one on a tie, or tells them none is in reach. A request from anyone not seated in a pod is
     * ignored: only a forged packet sends one.
     */
    static void open(ServerPlayer player) {
        if (!(player.getRootVehicle() instanceof PodEntity pod)) {
            return;
        }
        Optional<BlockPos> faced = faced(player, pod);
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
                .min(Comparator.<BlockPos>comparingDouble(pos ->
                                -look.dot(Vec3.atCenterOf(pos).subtract(eye).normalize()))
                        .thenComparingDouble(pos -> box.distanceToSqr(Vec3.atCenterOf(pos))));
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
