package io.github.pkeppeler.deepcharter.test.support;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;

/** Entities a test builds or looks up by hand, failing loud when the game has none. */
public final class TestEntities {
    private TestEntities() {}

    /** A new {@code type} in {@code level}, made as the {@code /summon} command makes it, not yet added to the level. */
    public static <T extends Entity> T create(EntityType<T> type, Level level) {
        return create(type, level, EntitySpawnReason.COMMAND);
    }

    /** A new {@code type} in {@code level} for {@code reason}, not yet added to the level. */
    public static <T extends Entity> T create(EntityType<T> type, Level level, EntitySpawnReason reason) {
        return Objects.requireNonNull(type.create(level, reason), () -> "no " + type + " was created");
    }

    /** As {@link #create}, for a mob that {@code Mob.checkDespawn} never removes however long a test idles (#1234). */
    public static <T extends Mob> T mob(EntityType<T> type, Level level) {
        T mob = create(type, level);
        mob.setPersistenceRequired();
        return mob;
    }

    /** The entity with this network id, which must be in {@code level}. */
    public static Entity require(Level level, int id) {
        return Objects.requireNonNull(level.getEntity(id), () -> "no entity " + id + " in " + level.dimension());
    }

    /** The entity with this network id, which must be in {@code level} and a {@code type}. */
    public static <T extends Entity> T require(Level level, int id, Class<T> type) {
        return type.cast(require(level, id));
    }

    /** The entity with this id, which must be in {@code level}. */
    public static Entity require(ServerLevel level, UUID id) {
        return Objects.requireNonNull(level.getEntity(id), () -> "no entity " + id + " in " + level.dimension());
    }

    /** The entity with this id, which must be in {@code level} and a {@code type}. */
    public static <T extends Entity> T require(ServerLevel level, UUID id, Class<T> type) {
        return type.cast(require(level, id));
    }

    /** The vehicle {@code rider} is riding, which must be a {@code type}. */
    public static <T extends Entity> T vehicle(Entity rider, Class<T> type) {
        return type.cast(Objects.requireNonNull(rider.getVehicle(), () -> rider + " is not riding anything"));
    }

    /** The vehicle {@code rider} is riding. */
    public static Entity vehicle(Entity rider) {
        return Objects.requireNonNull(rider.getVehicle(), () -> rider + " is not riding anything");
    }
}
