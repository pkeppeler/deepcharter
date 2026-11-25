package io.github.pkeppeler.deepcharter.layer.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Reaches what a swap moves a vehicle between levels with (#1229, ADR 0089): the entity's callback into the level's
 * section manager, whose {@code onRemove} frees the UUID and ends tracking without destroying the entity; its protected
 * {@code setLevel}; and {@code unsetRemoved}. Each is matched by name and by the descriptor of the method here, so a
 * rename or a changed signature fails the mixin at apply time.
 */
@Mixin(Entity.class)
public interface EntitySeamAccess {
    @Accessor("levelCallback")
    EntityInLevelCallback deepcharter$levelCallback();

    @Invoker("setLevel")
    void deepcharter$setLevel(Level level);

    @Invoker("unsetRemoved")
    void deepcharter$unsetRemoved();
}
