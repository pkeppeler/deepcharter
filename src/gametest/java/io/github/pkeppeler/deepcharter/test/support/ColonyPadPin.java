package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.colony.ColonyEvents;
import net.fabricmc.api.ModInitializer;

/**
 * Keeps the colony pad's chunks loaded and ticking in every GameTest world. The Host's displays are entities, found only in
 * chunks that tick, and the pods and towers that tests put on the pad must stay in the world. This is an entrypoint, not a static
 * block of one test class: a server shard that holds no {@code ColonyTest} must pin the pad as well (#1586).
 */
public final class ColonyPadPin implements ModInitializer {
    @Override
    public void onInitialize() {
        ColonyEvents.BUILT.register((server, colony) -> ColonyChunks.force(server.overworld(), colony));
    }
}
