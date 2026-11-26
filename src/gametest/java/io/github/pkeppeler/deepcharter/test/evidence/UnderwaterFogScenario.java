package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.UnderwaterFog;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.level.ParticleStatus;

/**
 * Evidence for #1187: the view of {@code UnderwaterFogClientTest} in each layer ({@code UnderwaterFog.tour}).
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
                        // tick-wait: particles spawned before the switch to minimal live on until they expire, and
                        // nothing reports when they have; ClientChecks.skyShot waits as long
                        context.waitTicks(40);
                        screenshot(context, "underwater-layer-" + layer);
                        frame(context);
                    }));
        }
    }
}
