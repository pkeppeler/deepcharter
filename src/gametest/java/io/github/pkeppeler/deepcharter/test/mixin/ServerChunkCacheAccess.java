package io.github.pkeppeler.deepcharter.test.mixin;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.TicketStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** For {@code SeamStreamingTest}: the tickets vanilla holds for a level, so a test reads them and not the mod's own record of them. */
@Mixin(value = ServerChunkCache.class, remap = false)
public interface ServerChunkCacheAccess {
    @Accessor("ticketStorage")
    TicketStorage deepcharter$ticketStorage();
}
