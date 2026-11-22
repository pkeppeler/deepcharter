package io.github.pkeppeler.deepcharter.test.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

/**
 * The item drops a test makes, counted where they spawn and not where they come to rest: a drop can be pushed out of the cell it
 * spawned in and slide far, so no box around the test holds every resting drop (#967). A recording ends with {@link #stop} or, on any
 * exit of the test, with {@link #release} from {@code GameTestInfoMixin}'s {@code finish}.
 *
 * <p>A Fabric event cannot be unregistered, so the one listener stays and reads {@link #RECORDINGS}; a recording holds plain values
 * only. Everything runs on the server thread.
 */
public final class ItemSpawns {
    private static final List<ItemSpawns> RECORDINGS = new ArrayList<>();

    static {
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof ItemEntity item) {
                for (ItemSpawns recording : RECORDINGS) {
                    recording.offer(item);
                }
            }
        });
    }

    private final AABB testBounds;
    private final AABB region;
    private final Map<String, Integer> counts = new TreeMap<>();
    private final List<String> spawns = new ArrayList<>();

    private ItemSpawns(AABB testBounds, AABB region) {
        this.testBounds = testBounds;
        this.region = region;
    }

    /** Starts recording the item spawns inside {@code region} (absolute) for the test of {@code helper}. */
    public static ItemSpawns start(GameTestHelper helper, AABB region) {
        ItemSpawns recording = new ItemSpawns(helper.getBounds(), region);
        RECORDINGS.add(recording);
        return recording;
    }

    /** Ends every recording of the test with these bounds. */
    public static void release(AABB testBounds) {
        RECORDINGS.removeIf(recording -> recording.testBounds.equals(testBounds));
    }

    private void offer(ItemEntity item) {
        if (!region.contains(item.position())) {
            return;
        }
        String id = BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).toString();
        counts.merge(id, item.getItem().getCount(), Integer::sum);
        spawns.add(String.format(
                "%s x%s at %s",
                id, item.getItem().getCount(), item.blockPosition().toShortString()));
    }

    /** Ends the recording and returns the spawns by item id. */
    public Map<String, Integer> stop() {
        RECORDINGS.remove(this);
        return new TreeMap<>(counts);
    }

    public List<String> spawns() {
        return List.copyOf(spawns);
    }
}
