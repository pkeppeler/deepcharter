package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.test.support.CavernSky;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

/**
 * Evidence for #1161: the hall of {@code CavernSkyClientTest} in layer 1, at each of its views (dusk, moonrise, night), then the
 * surface sky over the colony at dusk (the sun in the west) and at night (the moon high in the east), which must not change.
 */
public class CavernSkyScenario extends EvidenceScenario {
    /** How far above the colony's ground the surface stills stand: clear of its roofs. */
    private static final int ABOVE_THE_COLONY = 60;

    private record SurfaceView(String name, long skyTicks, float yaw, float pitch) {}

    private static final List<SurfaceView> SURFACE = List.of(
            new SurfaceView("surface-dusk-west", 0, 90, -10), new SurfaceView("surface-night-east", 144_000, -90, -50));

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
            BlockPos floor = CavernSky.carve(server);
            CavernSky.withShortRenderDistance(context, () -> {
                for (CavernSky.View view : CavernSky.VIEWS) {
                    CavernSky.stand(context, server, floor, view);
                    still(context, "cavern-" + view.name());
                }
            });
            BlockPos above = server.computeOnServer(s -> {
                var colony = Colony.placed(s).orElseThrow();
                return colony.center().atY(colony.groundY() + ABOVE_THE_COLONY);
            });
            for (SurfaceView view : SURFACE) {
                server.runCommand("time of deepcharter:sky set " + view.skyTicks());
                server.runOnServer(s -> {
                    ServerPlayer player = s.getPlayerList().getPlayers().getFirst();
                    player.setGameMode(GameType.SPECTATOR);
                    player.teleportTo(
                            s.overworld(),
                            above.getX() + 0.5,
                            above.getY(),
                            above.getZ() + 0.5,
                            Set.of(),
                            view.yaw(),
                            view.pitch(),
                            true);
                });
                ClientWait.until(
                        context,
                        "the client over the colony with its chunks drawn",
                        client -> client.player != null
                                && client.player.blockPosition().equals(above)
                                && client.levelRenderer.hasRenderedAllSections());
                still(context, view.name());
            }
        }
    }

    /** A still and a frame of the view, with the HUD hidden and no particles, once the sky clock has reached the client. */
    private void still(ClientGameTestContext context, String still) {
        ClientChecks.withHudHidden(context, ParticleStatus.MINIMAL, () -> {
            // tick-wait: the sky clock and the camera's attribute probe ease over a few ticks, and nothing reports when
            // they have
            context.waitTicks(40);
            screenshot(context, still);
            frame(context);
        });
    }
}
