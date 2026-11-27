package io.github.pkeppeler.deepcharter.layer;

import io.github.pkeppeler.deepcharter.colony.ColonyBlocks;
import io.github.pkeppeler.deepcharter.layer.LayerChain.Band;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Breach crossing: a player who drops below their layer's floor appears at the same X/Z under the
 * ceiling of the next layer, and one who rises above a layer's top appears above the crust of the
 * layer above. A vehicle crosses as one with its passengers. The overworld is layer 0: its floor
 * leads into layer 1, and the top of layer 1 leads back up to it.
 *
 * <p>Only players, and the vehicles that carry them, cross. The arrival point is
 * {@link LayerTuning#pocketHeight()} blocks inside the destination, so an entity has to move that
 * far before it could cross back: that distance is the hysteresis. The last layer's floor has no
 * crossing, and neither has the top of the overworld.
 *
 * <p>In the campaign world the layers are bands of one level, so nobody moves: {@link BandCrossing} fires the same event in place.
 *
 * <p>{@link SeamStreaming} loads the arrival area of the level below ahead of a descent (#1159, ADR 0089), so the crossing finds the chunks
 * it carves in loaded; one that does not logs how many were missing. {@link SeamSender} has sent the riders that area ahead (#1185), and the
 * teleport runs as its crossing scope: what a rider holds of it is not sent again.
 *
 * <p>A crossing whose riders all hold the arrival area and accepted it is a swap (#1229): {@link SeamSwap} moves the
 * vehicle with no respawn, carving the pocket first only if the arrival is obstructed. Otherwise it is the teleport
 * above, with its pocket and fade.
 *
 * <p>The breach and grained crusts are pod-only ({@link HandBreak}): drills break them through {@link #breakCrust},
 * creative players break them as any block, and a hand tool brute-forces them only at the cost ADR 0069 sets.
 */
public final class BreachService {
    /** How far a crossing looks for a pocket column free of protected blocks. */
    private static final int SEARCH_RADIUS = 16;

    private BreachService() {}

    public static void init() {
        BandCrossing.init();
        ServerTickEvents.END_LEVEL_TICK.register(BreachService::crossEntities);
    }

    /**
     * The drill's way through the crust. A hand tool brute-forces it only through {@link HandBreak};
     * this removes the block directly, so it is not subject to that rule. Returns false when
     * {@code pos} holds no crust.
     */
    public static boolean breakCrust(ServerLevel level, BlockPos pos) {
        if (!level.getBlockState(pos).is(LayerBlocks.BREACH_CRUST)) {
            return false;
        }
        return level.destroyBlock(pos, false);
    }

    private static void crossEntities(ServerLevel level) {
        if (LayerChain.isCampaign(level)) {
            BandCrossing.tick(level);
            return;
        }
        Optional<Band> band = LayerChain.bandAt(level, level.getMinY());
        if (band.isEmpty()) {
            return;
        }
        int layer = band.get().layer();
        int layers = LayerChain.count(level.registryAccess());
        // Anything else that falls out is the void's. Collect first: a crossing changes the player list.
        Set<Entity> descending = new LinkedHashSet<>();
        Set<Entity> ascending = new LinkedHashSet<>();
        for (ServerPlayer player : level.players()) {
            Entity root = player.getRootVehicle();
            if (root.isRemoved()) {
                continue;
            }
            if (root.getY() < level.getMinY() && layer < layers) {
                descending.add(root);
            } else if (root.getY() > level.getMaxY() && layer > LayerChain.SURFACE) {
                ascending.add(root);
            }
        }
        for (Entity entity : descending) {
            cross(entity, level, layer, layer + 1);
        }
        for (Entity entity : ascending) {
            cross(entity, level, layer, layer - 1);
        }
    }

    private static void cross(Entity entity, ServerLevel from, int fromLayer, int toLayer) {
        ServerLevel to = LayerChain.levelOf(from.getServer(), toLayer);
        // An entity that cannot change dimension stays put, and nothing is carved for it.
        if (!entity.canUsePortal(true) || !entity.canTeleport(from, to)) {
            return;
        }
        LayerTuning tuning = LayerTuning.DEFAULT;
        boolean descending = toLayer > fromLayer;
        int arrivalY = descending ? to.getMaxY() - tuning.pocketHeight() : to.getMinY() + tuning.crustThickness();
        BlockPos requested = BlockPos.containing(entity.getX(), arrivalY, entity.getZ());
        ChunkPos requestedChunk = ChunkPos.containing(requested);
        SeamStreaming.reportCrossing(
                entity, to, requestedChunk, descending ? SeamStreaming.Direction.DOWN : SeamStreaming.Direction.UP);
        List<ServerPlayer> riders = entity.getSelfAndPassengers()
                .filter(ServerPlayer.class::isInstance)
                .map(ServerPlayer.class::cast)
                .toList();
        // The swap is all or nothing for a vehicle: one rider that cannot take it sends everyone through the teleport.
        boolean swap = !riders.isEmpty()
                && riders.stream().allMatch(rider -> SeamSender.readyToSwap(rider, to, requestedChunk));
        Vec3 requestedPosition = new Vec3(entity.getX(), arrivalY, entity.getZ());
        boolean free = swap && arrivalIsFree(entity, to, requestedPosition);
        BlockPos pocket = free ? requested : preparePocket(to, requested, tuning);
        Vec3 position = pocket.equals(requested)
                ? requestedPosition
                : new Vec3(pocket.getX() + 0.5, arrivalY, pocket.getZ() + 0.5);
        if (swap) {
            // A carved pocket is sent ahead of the swap, so the held level has it; one that cannot be sent is the
            // fallback's.
            swap = free
                    || (SeamSender.resendCarved(riders, to, ChunkPos.containing(pocket))
                            && riders.stream()
                                    .allMatch(rider -> SeamSender.readyToSwap(rider, to, ChunkPos.containing(pocket))));
        }
        boolean swapped = swap;
        Entity arrived = SeamSender.crossing(
                to,
                entity,
                () -> swapped
                        ? SeamSwap.move(entity, from, to, position)
                        : entity.teleport(new TeleportTransition(
                                to,
                                position,
                                Vec3.ZERO,
                                entity.getYRot(),
                                entity.getXRot(),
                                TeleportTransition.DO_NOTHING)));
        if (arrived == null) {
            throw new IllegalStateException(
                    "Vanilla refused to teleport " + entity + " from layer " + fromLayer + " to layer " + toLayer);
        }
        SeamSwap.announce(
                swapped,
                () -> arrived.getPassengersAndSelf().forEach(crossed -> {
                    crossed.resetFallDistance();
                    BreachEvents.CROSSED.invoker().onCrossed(crossed, from, to, fromLayer, toLayer);
                }));
    }

    /**
     * Whether {@code vehicle} and everything it carries would stand clear of blocks, liquid and entities in {@code to}
     * with its root at {@code at}, over a floor under the root: otherwise the arrival is carved, as {@link #carve}
     * lays its floor over air and liquid, so the vehicle is not dropped into lava or a cavern.
     */
    private static boolean arrivalIsFree(Entity vehicle, ServerLevel to, Vec3 at) {
        Vec3 shift = at.subtract(vehicle.position());
        boolean clear = vehicle.getSelfAndPassengers().allMatch(e -> {
            AABB box = e.getBoundingBox().move(shift);
            return to.noCollision(e, box) && !to.containsAnyLiquid(box);
        });
        return clear && hasFloor(to, vehicle.getBoundingBox().move(shift));
    }

    /** Whether every block directly under {@code box} is one to stand on: it has a collision shape and holds no fluid. */
    private static boolean hasFloor(ServerLevel level, AABB box) {
        int y = Mth.floor(box.minY) - 1;
        for (int x = Mth.floor(box.minX); x <= Mth.floor(box.maxX); x++) {
            for (int z = Mth.floor(box.minZ); z <= Mth.floor(box.maxZ); z++) {
                BlockPos pos = new BlockPos(x, y, z);
                BlockState state = level.getBlockState(pos);
                if (state.getCollisionShape(level, pos).isEmpty()
                        || !state.getFluidState().isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Makes room for an arrival: air for {@code pocketHeight} blocks up from {@code bottom},
     * {@code pocketRadius} blocks out each way, over a solid floor. A protected block, which is a block entity or the
     * Conduit's casing, is never deleted, so a column whose pocket holds one is skipped for the nearest clear one.
     * Returns the column used.
     */
    private static BlockPos preparePocket(ServerLevel level, BlockPos bottom, LayerTuning tuning) {
        for (int ring = 0; ring <= SEARCH_RADIUS; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    BlockPos column = bottom.offset(dx, 0, dz);
                    if (!holdsProtectedBlock(level, column, tuning)) {
                        carve(level, column, tuning);
                        return column;
                    }
                }
            }
        }
        throw new IllegalStateException(
                "No pocket free of protected blocks within " + SEARCH_RADIUS + " blocks of " + bottom);
    }

    private static boolean holdsProtectedBlock(ServerLevel level, BlockPos bottom, LayerTuning tuning) {
        int radius = tuning.pocketRadius();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -1; dy < tuning.pocketHeight(); dy++) {
                    BlockPos pos = bottom.offset(dx, dy, dz);
                    if (level.getBlockEntity(pos) != null
                            || level.getBlockState(pos).is(ColonyBlocks.CONDUIT)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static void carve(ServerLevel level, BlockPos bottom, LayerTuning tuning) {
        int radius = tuning.pocketRadius();
        BlockState ground = LayerBlocks.groundOf(level, bottom.getY()).defaultBlockState();
        // The shell first: the cut below tells its neighbours, and lava among them would flow in.
        RoomSeal.seal(
                level, bottom.offset(-radius, 0, -radius), bottom.offset(radius, tuning.pocketHeight() - 1, radius));
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos floor = bottom.offset(dx, -1, dz);
                BlockState below = level.getBlockState(floor);
                if (below.isAir() || !below.getFluidState().isEmpty()) {
                    level.setBlock(floor, ground, 3);
                }
                for (int dy = 0; dy < tuning.pocketHeight(); dy++) {
                    BlockPos pos = bottom.offset(dx, dy, dz);
                    if (!level.getBlockState(pos).isAir()) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
        }
        // The arriving entity is sent these chunks in this tick: hold them until the light of the carve (a layer's lava
        // gone) is computed.
        LightSync.holdSendUntilLit(
                level, bottom.offset(-radius, -1, -radius), bottom.offset(radius, tuning.pocketHeight() - 1, radius));
    }
}
