package io.github.pkeppeler.deepcharter.test.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.List;
import net.minecraft.server.level.Ticket;
import net.minecraft.world.level.TicketStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** For {@code ServerTickets}: every active ticket of a level, so a test counts them by type. */
@Mixin(value = TicketStorage.class, remap = false)
public interface TicketStorageAccess {
    @Accessor("tickets")
    Long2ObjectOpenHashMap<List<Ticket>> deepcharter$tickets();
}
