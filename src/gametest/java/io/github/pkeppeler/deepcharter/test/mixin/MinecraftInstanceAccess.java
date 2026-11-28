package io.github.pkeppeler.deepcharter.test.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The client, read from the gametest thread, where {@code Minecraft.getInstance()} refuses. {@code WorldBeforeItems}
 * notes the level a class starts with. Test code only.
 */
@Mixin(value = Minecraft.class, remap = false)
public interface MinecraftInstanceAccess {
    @SuppressWarnings("DoNotCallSuggester") // the body is replaced by Mixin
    @Accessor("instance")
    static Minecraft deepcharter$instance() {
        throw new AssertionError();
    }
}
