package io.github.pkeppeler.deepcharter.client.layer.mixin;

import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Reaches the map data a client level holds, which vanilla copies into the level a respawn builds (#1229, ADR 0089): a
 * swap copies it into the held level the same way, so a map in a hand or a frame still draws.
 */
@Mixin(ClientLevel.class)
public interface ClientLevelMapAccess {
    @Invoker("getAllMapData")
    Map<MapId, MapItemSavedData> deepcharter$allMapData();

    @Invoker("addMapData")
    void deepcharter$addMapData(Map<MapId, MapItemSavedData> data);
}
