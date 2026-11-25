package io.github.pkeppeler.deepcharter.layer;

import io.github.pkeppeler.deepcharter.layer.mixin.EntitySeamAccess;
import io.github.pkeppeler.deepcharter.layer.mixin.ServerPlayerSeamAccess;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.game.ClientboundChangeDifficultyPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;

/**
 * The server half of the swap (#1229, ADR 0089): moves a vehicle and all its riders into another level with no respawn,
 * so the vehicle keeps its object, entity id and UUID, and each rider's client keeps its player. Vanilla moves a {@code
 * ServerPlayer} between levels like this already; this does the same for a vehicle, which vanilla recreates.
 *
 * <p>The order is the one vanilla's {@code ServerPlayer.teleport} has, with the respawn left out: the client is told
 * first ({@code seam_swap}), so every packet about the new level reaches it after it has switched. A non-player is
 * detached through its level callback and never {@code setRemoved}, which would run {@code stopRiding} on its
 * passengers and tell the clients they dismounted. A player is removed by vanilla's {@code removePlayerImmediately},
 * because a callback-only detach would let the tracker send forgets for every chunk of the held level.
 */
public final class SeamSwap {
    /**
     * Whether the {@link BreachEvents#CROSSED} being fired is for a swap, so its payload rumbles and does not fade.
     * Server thread only.
     */
    private static boolean announcing;

    /**
     * Riders swapped by {@link #move} or respawned by {@link #recover}, with the tick their chunk tracking is re-read
     * until. Server thread only.
     */
    private static final Map<UUID, Long> PAIRING = new HashMap<>();

    private SeamSwap() {}

    /**
     * Re-reads the chunk tracking of each rider just swapped or recovered, as a move of the player does: the vehicle it
     * rides is paired with it once the chunk of the vehicle is sent (a chunk that changed since it was sent is sent
     * again), and vanilla looks again only when someone changes section, which a vehicle at rest never does.
     */
    static void tick(MinecraftServer server) {
        if (PAIRING.isEmpty()) {
            return;
        }
        long now = server.getTickCount();
        PAIRING.entrySet().removeIf(entry -> {
            ServerPlayer rider = server.getPlayerList().getPlayer(entry.getKey());
            if (rider == null || entry.getValue() <= now) {
                return true;
            }
            rider.level().getChunkSource().chunkMap.move(rider);
            return false;
        });
    }

    /** Whether the crossing event being fired now is for a swap: {@link BreachPayload} reads it. */
    public static boolean announcing() {
        return announcing;
    }

    /** Runs {@code fire}, the crossing events of a crossing that was {@code swapped} or not. */
    public static void announce(boolean swapped, Runnable fire) {
        announcing = swapped;
        try {
            fire.run();
        } finally {
            announcing = false;
        }
    }

    /**
     * Moves {@code root} and everything it carries from {@code from} into {@code to}, the root at {@code arrival}.
     * Every player in the tree must be ready ({@link SeamSender#readyToSwap}). Returns the root, which is the same
     * object.
     */
    public static Entity move(Entity root, ServerLevel from, ServerLevel to, Vec3 arrival) {
        List<Entity> tree = root.getSelfAndPassengers().toList();
        List<ServerPlayer> riders = tree.stream()
                .filter(ServerPlayer.class::isInstance)
                .map(ServerPlayer.class::cast)
                .toList();
        List<Entity> others =
                tree.stream().filter(e -> !(e instanceof ServerPlayer)).toList();
        List<Integer> carried = tree.stream().map(Entity::getId).toList();
        PlayerList playerList = from.getServer().getPlayerList();

        // The velocity ends here, as the fallback's teleport ends it: a vehicle whose arrival is a floor away must not
        // land at terminal speed.
        for (Entity entity : tree) {
            entity.setDeltaMovement(Vec3.ZERO);
        }
        // 1. The client first: everything about the new level follows this packet.
        for (ServerPlayer rider : riders) {
            ServerPlayNetworking.send(
                    rider,
                    new SeamSwapPayload(
                            to.dimension(),
                            SeamSender.generationOf(rider),
                            root.getId(),
                            root.getUUID(),
                            arrival,
                            carried));
            SeamSender.noteSwap(rider, to);
        }
        // 2. The non-players leave the old level, tracked or not, without being removed.
        for (Entity entity : others) {
            ((EntitySeamAccess) entity).deepcharter$levelCallback().onRemove(RemovalReason.CHANGED_DIMENSION);
        }
        // 3. The players leave it the vanilla way.
        LevelData levelData = to.getLevelData();
        for (ServerPlayer rider : riders) {
            ((ServerPlayerSeamAccess) rider).deepcharter$setChangingDimension(true);
            rider.connection.send(
                    new ClientboundChangeDifficultyPacket(levelData.getDifficulty(), levelData.isDifficultyLocked()));
            playerList.sendPlayerPermissionLevel(rider);
            from.removePlayerImmediately(rider, RemovalReason.CHANGED_DIMENSION);
            ((EntitySeamAccess) rider).deepcharter$unsetRemoved();
        }
        // 4. Everything is in the new level, at the arrival.
        for (Entity entity : others) {
            ((EntitySeamAccess) entity).deepcharter$setLevel(to);
        }
        for (ServerPlayer rider : riders) {
            rider.setServerLevel(to);
        }
        root.setPos(arrival);
        for (Entity entity : tree) {
            entity.getPassengers().forEach(entity::positionRider);
        }
        // 5. The vehicle joins the new level before its riders, so each rider pairs with it at once.
        for (Entity entity : others) {
            to.addDuringTeleport(entity);
        }
        // 6. Each rider is told where it is, which changes nothing it sees, and joins.
        for (ServerPlayer rider : riders) {
            rider.connection.teleport(
                    new PositionMoveRotation(rider.position(), Vec3.ZERO, 0, 0),
                    Relative.union(Relative.ROTATION, Relative.DELTA));
            rider.connection.resetPosition();
            to.addDuringTeleport(rider);
        }
        // 7. What vanilla sends after a respawn that the client has not kept, and the level-change events, which
        // vanilla's own moves fire from paths this one does not take.
        for (ServerPlayer rider : riders) {
            playerList.sendLevelInfo(rider, to);
            playerList.sendAllPlayerInfo(rider);
            rider.sendPostEffects();
        }
        to.resetEmptyTime();
        for (ServerPlayer rider : riders) {
            PAIRING.put(rider.getUUID(), SeamSender.missedWindowEnd(to));
        }
        // Fabric fires the entity event around Entity.teleportCrossDimension, which ServerPlayer's own teleport does
        // not go through, and fires the player event from there: a player gets only the second.
        for (Entity entity : others) {
            ServerEntityLevelChangeEvents.AFTER_ENTITY_CHANGE_LEVEL
                    .invoker()
                    .afterChangeLevel(entity, entity, from, to);
        }
        for (ServerPlayer rider : riders) {
            ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.invoker().afterChangeLevel(rider, from, to);
        }
        return root;
    }

    /**
     * Respawns {@code rider} in the level it is in, for a client that could not take the swap that put it there
     * (#1229): vanilla's respawn packet and the sends that follow it, then the rider leaves and joins its own level,
     * which pairs it again with the vehicle it still rides and sends it the area from nothing. The vehicle is not
     * touched.
     */
    static void recover(ServerPlayer rider) {
        ServerLevel level = rider.level();
        PlayerList playerList = level.getServer().getPlayerList();
        LevelData levelData = level.getLevelData();
        ((ServerPlayerSeamAccess) rider).deepcharter$setChangingDimension(true);
        rider.connection.send(new ClientboundRespawnPacket(rider.createCommonSpawnInfo(level), (byte) 3));
        rider.connection.send(
                new ClientboundChangeDifficultyPacket(levelData.getDifficulty(), levelData.isDifficultyLocked()));
        playerList.sendPlayerPermissionLevel(rider);
        level.removePlayerImmediately(rider, RemovalReason.CHANGED_DIMENSION);
        ((EntitySeamAccess) rider).deepcharter$unsetRemoved();
        rider.connection.teleport(
                new PositionMoveRotation(rider.position(), Vec3.ZERO, rider.getYRot(), rider.getXRot()), Set.of());
        rider.connection.resetPosition();
        level.addDuringTeleport(rider);
        rider.connection.send(new ClientboundPlayerAbilitiesPacket(rider.getAbilities()));
        playerList.sendLevelInfo(rider, level);
        playerList.sendAllPlayerInfo(rider);
        playerList.sendActivePlayerEffects(rider);
        rider.sendPostEffects();
        PAIRING.put(rider.getUUID(), SeamSender.missedWindowEnd(level));
    }
}
