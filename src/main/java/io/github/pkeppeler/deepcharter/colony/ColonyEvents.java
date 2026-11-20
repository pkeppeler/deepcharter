package io.github.pkeppeler.deepcharter.colony;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.server.MinecraftServer;

/** Server-side events of the colony feature. */
public final class ColonyEvents {
    /**
     * The colony was built, recorded in {@link ColonySite} and the world spawn set to the Continuity Office. Fires once per
     * world, at server start, and never for a world whose colony was built before. A listener that throws stops the server start.
     */
    public static final Event<Built> BUILT =
            EventFactory.createArrayBacked(Built.class, listeners -> (server, colony) -> {
                for (Built listener : listeners) {
                    listener.onBuilt(server, colony);
                }
            });

    /**
     * The colony's pad is chosen and recorded in {@link ColonySite} (its anchors are known), before the first block of the build. Fires once
     * per world, from the server's first start with the mod, and never for a world whose build began before; a build that stopped half way
     * and starts again does not fire it again. The pad's chunks may have loaded already, as the build reads the ground. A listener that
     * throws stops the server start.
     */
    public static final Event<Begun> BEGUN =
            EventFactory.createArrayBacked(Begun.class, listeners -> (server, colony) -> {
                for (Begun listener : listeners) {
                    listener.onBegun(server, colony);
                }
            });

    private ColonyEvents() {}

    @FunctionalInterface
    public interface Begun {
        void onBegun(MinecraftServer server, ColonySite.Placed colony);
    }

    @FunctionalInterface
    public interface Built {
        void onBuilt(MinecraftServer server, ColonySite.Placed colony);
    }
}
