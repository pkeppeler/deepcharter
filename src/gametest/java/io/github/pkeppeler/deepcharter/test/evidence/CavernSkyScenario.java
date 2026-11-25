package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.test.support.CavernSky;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ParticleStatus;

/**
 * Evidence for #1161: the hall of {@code CavernSkyClientTest} in layer 1, at each of its views (dusk, moonrise and night, down into the
 * pit at dusk and night, and straight down from under the water of the flooded pit) with {@code BreachVoidCover} off, so the sky
 * fix shows alone, then the surface sky over the colony at dusk (the
 * sun in the west) and at night (the moon high in the east), which must not change.
 */
public class CavernSkyScenario extends EvidenceScenario {
    /** How far above the colony's ground the surface stills stand: clear of its roofs. */
    private static final int ABOVE_THE_COLONY = 60;

    private static final List<CavernSky.View> SURFACE = List.of(
            new CavernSky.View("surface-dusk-west", 0, 90, -10),
            new CavernSky.View("surface-night-east", 144_000, -90, -50));

    @Override
    protected String name() {
        return "cavern-sky";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            awaitWorld(context);
            TestServerContext server = singleplayer.getServer();
            server.runCommand("gamerule advance_time false");
            server.runCommand("weather clear");
            CavernSky.tour(context, server, (view, underwater) -> still(context, "cavern-" + view.name()));
            BlockPos above = server.computeOnServer(s -> {
                var colony = Colony.placed(s).orElseThrow();
                return colony.center().atY(colony.groundY() + ABOVE_THE_COLONY);
            });
            for (CavernSky.View view : SURFACE) {
                CavernSky.stand(context, server, MinecraftServer::overworld, above, view);
                still(context, view.name());
            }
        }
    }

    /** A still and a frame of the view, with the HUD hidden and no particles, once the sky clock has reached the client. */
    private void still(ClientGameTestContext context, String still) {
        ClientChecks.withHudHidden(context, ParticleStatus.MINIMAL, () -> {
            // tick-wait: the sky clock and the camera's attribute probe ease over a few ticks, and nothing
            // reports when they have
            context.waitTicks(40);
            screenshot(context, still);
            frame(context);
        });
    }
}
