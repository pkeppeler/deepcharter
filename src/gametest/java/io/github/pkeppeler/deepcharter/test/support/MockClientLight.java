package io.github.pkeppeler.deepcharter.test.support;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.jspecify.annotations.Nullable;

/**
 * The light a client would hold, built from the chunk and light packets a {@link MockPlayer} was sent: a section in a packet's mask
 * replaces the client's data, a section in its empty mask clears it, and a section in neither keeps what the client had. Server thread
 * only. A section the client holds no data for reads as dark here, as the server's missing section does, so {@link #staleSections}
 * finds a section whose light the client keeps from an older packet.
 *
 * <p>Blind spot: a real client reads full sky in a section it holds no data for when no section above has any, and this model reads
 * dark, so a server that left a section out of both masks, or a light the client has queued and not applied, is not caught here.
 */
public final class MockClientLight {
    private final Map<ChunkPos, Map<Integer, byte[]>> held = new HashMap<>();
    private final List<String> events = new ArrayList<>();

    /** Applies the light of a chunk packet or a light packet. */
    void apply(long tick, String kind, ChunkPos chunk, ClientboundLightUpdatePacketData data) {
        Map<Integer, byte[]> sections = held.computeIfAbsent(chunk, key -> new HashMap<>());
        events.add("tick " + tick + " " + kind + " " + chunk + " sky=" + describe(data.skyYMask(), data.emptySkyYMask())
                + " block=" + describe(data.blockYMask(), data.emptyBlockYMask()));
        store(sections, LightLayer.SKY, data.skyYMask(), data.emptySkyYMask(), data.skyUpdates());
        store(sections, LightLayer.BLOCK, data.blockYMask(), data.emptyBlockYMask(), data.blockUpdates());
    }

    private static String describe(BitSet lit, BitSet empty) {
        return "lit" + lit + "/empty" + empty;
    }

    private static void store(
            Map<Integer, byte[]> sections, LightLayer layer, BitSet mask, BitSet emptyMask, List<byte[]> updates) {
        int next = 0;
        for (int i = mask.nextSetBit(0); i >= 0; i = mask.nextSetBit(i + 1)) {
            sections.put(key(layer, i), updates.get(next++));
        }
        for (int i = emptyMask.nextSetBit(0); i >= 0; i = emptyMask.nextSetBit(i + 1)) {
            sections.remove(key(layer, i));
        }
    }

    private static int key(LightLayer layer, int index) {
        return layer.ordinal() << 16 | index;
    }

    /** Every packet applied so far, oldest first, with the server tick the mock read it on. */
    public List<String> events() {
        return List.copyOf(events);
    }

    /**
     * The light sections, counted from the lowest, of {@code layer} in which the client's data differs from the server's published
     * data for the chunk; a section without data counts as zeros on both sides.
     */
    public BitSet staleSections(LevelLightEngine engine, LightLayer layer, ChunkPos chunk) {
        Map<Integer, byte[]> sections = held.getOrDefault(chunk, Map.of());
        BitSet stale = new BitSet();
        for (int i = 0; i < engine.getLightSectionCount(); i++) {
            DataLayer data = engine.getLayerListener(layer)
                    .getDataLayerData(SectionPos.of(chunk, engine.getMinLightSection() + i));
            byte[] server = data == null || data.isEmpty() ? null : data.getData();
            byte @Nullable [] client = sections.get(key(layer, i));
            if (!Arrays.equals(server, client)) {
                stale.set(i);
            }
        }
        return stale;
    }
}
