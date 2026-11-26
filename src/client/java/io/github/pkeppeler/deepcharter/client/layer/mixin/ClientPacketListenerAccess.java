package io.github.pkeppeler.deepcharter.client.layer.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Reads and sets what {@code SeamClient} needs of the client's packet listener, which keeps it private (#1185, ADR 0089): the level it
 * applies packets to, the server's radius and simulation distance, and the two steps of a chunk's light that vanilla runs from its own
 * chunk handler.
 */
@Mixin(ClientPacketListener.class)
public interface ClientPacketListenerAccess {
    @Accessor("level")
    ClientLevel deepcharter$level();

    @Accessor("level")
    void deepcharter$setLevel(ClientLevel level);

    @Accessor("levelData")
    void deepcharter$setLevelData(ClientLevel.ClientLevelData levelData);

    @Accessor("serverChunkRadius")
    int deepcharter$serverChunkRadius();

    @Accessor("serverSimulationDistance")
    int deepcharter$serverSimulationDistance();

    @Invoker("applyLightData")
    void deepcharter$applyLightData(int x, int z, ClientboundLightUpdatePacketData lightData, boolean scheduleRebuild);

    @Invoker("enableChunkLight")
    void deepcharter$enableChunkLight(LevelChunk chunk, int x, int z);
}
