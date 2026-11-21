package io.github.pkeppeler.deepcharter.test.mixin;

import it.unimi.dsi.fastutil.objects.ObjectList;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Lets {@code ClientLight.serverLightIdle} see the light tasks a block change queues: {@code LevelLightEngine.hasLightWork} counts
 * only the work inside the two light engines, not the {@code checkBlock} tasks still waiting in this list, nor a batch of them that
 * is running now ({@code scheduled}).
 */
@Mixin(value = ThreadedLevelLightEngine.class, remap = false)
public interface ThreadedLightEngineAccess {
    @Accessor("lightTasks")
    ObjectList<?> deepcharter$lightTasks();

    @Accessor("scheduled")
    AtomicBoolean deepcharter$scheduled();
}
