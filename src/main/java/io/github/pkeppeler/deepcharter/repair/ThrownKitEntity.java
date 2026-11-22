package io.github.pkeppeler.deepcharter.repair;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/** Stub: filled below. */
public final class ThrownKitEntity extends ThrowableItemProjectile {
    public ThrownKitEntity(EntityType<? extends ThrownKitEntity> type, Level level) {
        super(type, level);
    }

    @Override
    protected Item getDefaultItem() {
        return RepairRegistry.item(Consumable.THROWN_FLARE);
    }

    public boolean landed() {
        return false;
    }
}
