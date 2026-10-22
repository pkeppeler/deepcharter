package io.github.pkeppeler.deepcharter.test.support;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import java.lang.reflect.Field;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.entity.EntityTickList;

/**
 * A level's {@link EntityTickList} as the heap sees it (#723). Vanilla changes the list while it iterates by
 * copying {@code active} into {@code passive} and swapping them. {@code passive} is then the map that was iterated,
 * and nothing clears it until the next change during an iteration, so it holds every entity that was ticking at the swap, also
 * after the entity is removed. In a long server that is one snapshot a few ticks old; in a test run it was the last
 * mock players of the suite.
 */
public final class TickLists {
    private static final Field TICK_LIST = field(ServerLevel.class, "entityTickList");
    private static final Field ACTIVE = field(EntityTickList.class, "active");
    private static final Field PASSIVE = field(EntityTickList.class, "passive");
    private static final Field ITERATED = field(EntityTickList.class, "iterated");

    private TickLists() {}

    /** Whether {@code entity} is held by the level's tick list, in its live map or in the stale snapshot. */
    public static boolean retains(ServerLevel level, Entity entity) {
        EntityTickList list = tickList(level);
        return holds(read(ACTIVE, list), entity) || holds(read(PASSIVE, list), entity);
    }

    /** Make the level take the snapshot vanilla takes when an entity is added or removed while the list is iterated. */
    public static void snapshotAsIfChangedWhileTicking(ServerLevel level) {
        EntityTickList list = tickList(level);
        Entity never = TestEntities.create(EntityTypes.ARMOR_STAND, level);
        boolean[] changed = {false};
        list.forEach(entity -> {
            if (!changed[0]) {
                changed[0] = true;
                list.remove(never);
            }
        });
        if (!changed[0]) {
            throw new IllegalStateException(
                    "The tick list of " + level.dimension().identifier() + " was empty");
        }
    }

    /** Empty the stale snapshot of every level of the server. Call it outside a tick of the entities. */
    public static void dropStale(Iterable<ServerLevel> levels) {
        for (ServerLevel level : levels) {
            EntityTickList list = tickList(level);
            if (read(ITERATED, list) != null) {
                throw new IllegalStateException(
                        "The tick list of " + level.dimension().identifier() + " is being iterated");
            }
            Int2ObjectMap<?> passive = (Int2ObjectMap<?>) read(PASSIVE, list);
            passive.clear();
        }
    }

    private static boolean holds(Object map, Entity entity) {
        return ((Int2ObjectMap<?>) map).containsValue(entity);
    }

    private static EntityTickList tickList(ServerLevel level) {
        return (EntityTickList) read(TICK_LIST, level);
    }

    private static Object read(Field field, Object target) {
        try {
            return field.get(target);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot read " + field, e);
        }
    }

    private static Field field(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(
                    "Cannot read " + owner.getSimpleName() + "." + name + ", likely a mapping change in Minecraft", e);
        }
    }
}
