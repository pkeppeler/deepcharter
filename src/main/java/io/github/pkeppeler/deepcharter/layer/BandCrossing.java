package io.github.pkeppeler.deepcharter.layer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * Breach crossing inside the campaign world (ADR 0061): the layers are Y bands of one level, so a crossing moves nobody. It is
 * noticed when a player's root vehicle passes a band boundary, and {@link BreachEvents#CROSSED} fires with the same level as
 * {@code from} and {@code to}.
 *
 * <p>Each player has the layer last seen. A descent is the root vehicle dropping below the floor of that layer, and an ascent is it
 * rising {@link LayerTuning#crustThickness()} rows above the floor of the layer above, so the rows of crust, or the same rows over the
 * surface's open floor, are the hysteresis: a pod that bobs inside them raises nothing. At most one layer is crossed a tick. The
 * last layer's floor has no crossing, and neither has the top of the surface.
 *
 * <p>The layer last seen is kept in memory only. A player seen for the first time, after a login or from another level, is put in the
 * layer their Y is in (a Y in the crust counts as the layer above it) and raises nothing. So is a player who left the level and came
 * back, and one who respawned.
 */
final class BandCrossing {
    /** The layer a player was last in, and the level it was seen in. */
    private record Seen(ResourceKey<Level> level, int layer) {}

    private static final Map<UUID, Seen> SEEN = new HashMap<>();

    private BandCrossing() {}

    static void init() {
        ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, server) -> SEEN.remove(handler.getPlayer().getUUID()));
        // A respawn in the same level lands at the surface: it is a new sighting, not an ascent from where they died.
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> SEEN.remove(newPlayer.getUUID()));
    }

    /** The layer after one tick of the root vehicle being at {@code y}, for a player last seen in {@code layer}. */
    static int next(int layer, double y) {
        int crust = CampaignBands.crustRows();
        if (layer < CampaignBands.lastLayer()
                && y < CampaignBands.ofLayer(layer).minY()) {
            return layer + 1;
        }
        if (layer > LayerChain.SURFACE && y >= CampaignBands.ofLayer(layer - 1).minY() + crust) {
            return layer - 1;
        }
        return layer;
    }

    /** The layer a player first seen at {@code y} is in. */
    private static int settle(double y) {
        int layer = LayerChain.SURFACE;
        while (layer < CampaignBands.lastLayer()
                && y < CampaignBands.ofLayer(layer).minY()) {
            layer++;
        }
        return layer;
    }

    static void tick(ServerLevel level) {
        // Collect first: a listener may move a player out of the level.
        Map<Entity, int[]> crossed = new HashMap<>();
        // A player who left this level without disconnecting (a portal, a command, a mock removed with its test) is
        // forgotten, so coming back at another Y is a first sighting.
        Set<UUID> here = new HashSet<>();
        level.players().forEach(player -> here.add(player.getUUID()));
        SEEN.entrySet()
                .removeIf(
                        entry -> entry.getValue().level().equals(level.dimension()) && !here.contains(entry.getKey()));
        for (ServerPlayer player : level.players()) {
            Entity root = player.getRootVehicle();
            if (root.isRemoved()) {
                continue;
            }
            Seen seen = SEEN.get(player.getUUID());
            if (seen == null || !seen.level().equals(level.dimension())) {
                SEEN.put(player.getUUID(), new Seen(level.dimension(), settle(root.getY())));
                continue;
            }
            int layer = next(seen.layer(), root.getY());
            if (layer != seen.layer()) {
                SEEN.put(player.getUUID(), new Seen(level.dimension(), layer));
                // Players of one vehicle raise one crossing of that vehicle; a player riding alone in the world crosses
                // alone.
                crossed.putIfAbsent(root, new int[] {seen.layer(), layer});
            }
        }
        crossed.forEach((root, layers) -> root.getPassengersAndSelf()
                .forEach(entity ->
                        BreachEvents.CROSSED.invoker().onCrossed(entity, level, level, layers[0], layers[1])));
    }
}
