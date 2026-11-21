package io.github.pkeppeler.deepcharter.test.support;

import java.util.BitSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

/**
 * The block light sections {@code LightSync} sent a watched player, per chunk (fed by {@code LightSyncSendMixin}). Inert until a test
 * {@link #watch}es a player, and it forgets the player on {@link #unwatch}.
 */
public final class LightSyncSends {
    private static final Map<UUID, Map<Long, BitSet>> SENT = new ConcurrentHashMap<>();

    private LightSyncSends() {}

    public static void watch(UUID player) {
        SENT.put(player, new ConcurrentHashMap<>());
    }

    public static void unwatch(UUID player) {
        SENT.remove(player);
    }

    /** The light sections, counted from the lowest, that a packet to the player for {@code chunk} carried, empty or not. */
    public static BitSet sectionsCarried(UUID player, ChunkPos chunk) {
        Map<Long, BitSet> chunks = SENT.get(player);
        if (chunks == null) {
            throw new IllegalStateException("player " + player + " is not watched");
        }
        BitSet sections = chunks.get(chunk.pack());
        if (sections == null) {
            return new BitSet();
        }
        synchronized (sections) {
            return (BitSet) sections.clone();
        }
    }

    /** Called by the mixin: {@code LightSync} sends {@code light} to {@code player}. */
    public static void record(ServerPlayer player, ClientboundLightUpdatePacket light) {
        Map<Long, BitSet> chunks = SENT.get(player.getUUID());
        if (chunks == null) {
            return;
        }
        ClientboundLightUpdatePacketData data = light.lightData();
        BitSet sections = chunks.computeIfAbsent(new ChunkPos(light.x(), light.z()).pack(), key -> new BitSet());
        synchronized (sections) {
            sections.or(data.blockYMask());
            sections.or(data.emptyBlockYMask());
        }
    }
}
