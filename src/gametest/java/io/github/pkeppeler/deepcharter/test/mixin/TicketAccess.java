package io.github.pkeppeler.deepcharter.test.mixin;

import net.minecraft.server.level.Ticket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** For {@code SeamStreamingTest}: the ticks a ticket has left, to tell a ticket that runs down from one that is taken again. */
@Mixin(value = Ticket.class, remap = false)
public interface TicketAccess {
    @Accessor("ticksLeft")
    long deepcharter$ticksLeft();
}
