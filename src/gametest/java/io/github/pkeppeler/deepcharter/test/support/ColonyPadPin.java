package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.colony.ColonyEvents;
import net.fabricmc.api.ModInitializer;

/** Pins the colony pad from an entrypoint, because a shard that holds no {@code ColonyTest} never ran its static block (#1586). */
public final class ColonyPadPin implements ModInitializer {
    @Override
    public void onInitialize() {
        ColonyEvents.BUILT.register((server, colony) -> ColonyChunks.force(server.overworld(), colony));
    }
}
