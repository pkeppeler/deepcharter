package io.github.pkeppeler.deepcharter.client.pod;

import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

public final class PodClientRegistry {
    private PodClientRegistry() {}

    public static void register() {
        // Every chassis draws with GeckoLib from its look file, so a new chassis is files only. The renderer is built
        // again on every resource reload.
        for (Chassis chassis : Chassis.all()) {
            EntityRendererRegistry.register(
                    PodRegistry.typeOf(chassis), context -> new PodGeoRenderer(context, chassis));
        }
        TowChainRenderer.init();
    }
}
