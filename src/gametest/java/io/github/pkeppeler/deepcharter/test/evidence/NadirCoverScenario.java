package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ShaftViews;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Evidence for #1188: straight down each deep shaft of {@code BreachVoidClientTest} ({@link ShaftViews}), to the floor of layer 1, to
 * the floor of layer 2 and through the bottom of the world, with the render distance cut so each shaft reaches past the drawn sections;
 * then the same three with night vision. Then the surface, where the cover now lies under the drawn sections too: straight down, and out
 * towards the horizon, from high over open ground at an ordinary render distance.
 */
public class NadirCoverScenario extends EvidenceScenario {
    /** Where the shafts start: far from the colony and from the other scenarios' rooms. */
    private static final int SHAFTS_X = 6_000;

    private static final int SHAFTS_Z = 6_000;
    /** The surface views are this far north of the shafts, out of their drawn chunks. */
    private static final int SURFACE_NORTH = 1_200;
    /** An ordinary render distance, in chunks, for the views over open ground; the tall world's chunks round them generate in time. */
    private static final int SURFACE_RENDER_DISTANCE = 6;
    /** The views over open ground stand this far over the ground. */
    private static final int OVER_GROUND = 100;
    /**
     * The view out to the horizon looks this far north for each {@link #HORIZON_DOWN} down, about 35 degrees under the horizon: the sky
     * at the top of the picture, the drawn ground at the bottom, and between them what lies past the drawn sections.
     */
    private static final int HORIZON_OUT = 100;

    private static final int HORIZON_DOWN = 70;
    /** A block of the ground this far north of the camera is in the view out to the horizon. */
    private static final int AHEAD = 60;

    @Override
    protected String name() {
        return "nadir-cover";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            awaitWorld(context);
            TestServerContext server = singleplayer.getServer();
            server.runCommand("gamerule advance_time false");
            server.runCommand("weather clear");
            server.runOnServer(s -> s.getPlayerList().getPlayers().getFirst().setGameMode(GameType.SPECTATOR));
            List<ShaftViews.Shaft> shafts =
                    server.computeOnServer(s -> ShaftViews.open(TestLayers.level(s, 1), SHAFTS_X, SHAFTS_Z));
            ResourceKey<Level> surface = server.computeOnServer(
                    s -> TestLayers.level(s, LayerChain.SURFACE).dimension());
            int groundX = SHAFTS_X;
            int groundZ = SHAFTS_Z - SURFACE_NORTH;
            BlockPos under = server.computeOnServer(
                    s -> ShaftViews.ground(TestLayers.level(s, LayerChain.SURFACE), groundX, groundZ));
            BlockPos ahead = server.computeOnServer(
                    s -> ShaftViews.ground(TestLayers.level(s, LayerChain.SURFACE), groundX, groundZ - AHEAD));
            ClientChecks.withHudHidden(context, ParticleStatus.MINIMAL, () -> {
                ClientChecks.withRenderDistance(context, ShaftViews.RENDER_DISTANCE, () -> {
                    for (boolean nightVision : new boolean[] {false, true}) {
                        ShaftViews.nightVision(server, nightVision);
                        for (ShaftViews.Shaft shaft : shafts) {
                            view(
                                    context,
                                    server,
                                    shaft.level(),
                                    shaft.eye(),
                                    shaft.below(),
                                    shaft.held(),
                                    "nadir-" + shaft.name() + (nightVision ? "-night-vision" : ""));
                        }
                    }
                });
                ClientChecks.withRenderDistance(context, SURFACE_RENDER_DISTANCE, () -> {
                    Vec3 high = new Vec3(groundX + 0.5, under.getY() + OVER_GROUND + 0.5, groundZ + 0.5);
                    // Each view waits for the section of a ground block in it, besides every section the client draws.
                    view(
                            context,
                            server,
                            surface,
                            high,
                            high.subtract(0, OVER_GROUND, 0),
                            ClientLight.Box.around(under, 0, 0, 0),
                            "nadir-surface-down");
                    view(
                            context,
                            server,
                            surface,
                            high,
                            high.add(0, -HORIZON_DOWN, -HORIZON_OUT),
                            ClientLight.Box.around(ahead, 0, 0, 0),
                            "nadir-surface-horizon");
                });
            });
        }
    }

    private void view(
            ClientGameTestContext context,
            TestServerContext server,
            ResourceKey<Level> level,
            Vec3 eye,
            Vec3 target,
            ClientLight.Box held,
            String still) {
        ShaftViews.stand(context, server, level, eye, target, held, still);
        // tick-wait: the fog colour is computed per frame, and a few frames must pass after the last chunk section is
        // built
        context.waitTicks(10);
        screenshot(context, still);
        frame(context);
    }
}
