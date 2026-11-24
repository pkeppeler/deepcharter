package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.UnderwaterFog;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.level.ParticleStatus;

/**
 * Evidence for #1187: the view of {@code UnderwaterFogClientTest} in each layer, from under the water of a flooded hall in its middle
 * zone, east past the render distance, once the eyes have adjusted.
 */
public class UnderwaterFogScenario extends EvidenceScenario {
    @Override
    protected String name() {
        return "underwater-fog";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            awaitWorld(context);
            TestServerContext server = singleplayer.getServer();
            server.runCommand("gamerule advance_time false");
            UnderwaterFog.tour(
                    context,
                    server,
                    layer -> ClientChecks.withHudHidden(context, ParticleStatus.MINIMAL, () -> {
                        // tick-wait: the camera's attribute probe eases over a few ticks, and nothing reports when it has
                        context.waitTicks(40);
                        screenshot(context, "underwater-layer-" + layer);
                        frame(context);
                    }));
        }
    }
}
