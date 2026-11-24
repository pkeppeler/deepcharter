package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ShaftViews;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.GameType;

/**
 * Evidence for #1188: straight down each deep shaft of {@code BreachVoidClientTest} ({@link ShaftViews}), to the floor of layer 1, to
 * the floor of layer 2 and through the bottom of the world, with the render distance cut so each shaft reaches past the drawn sections;
 * then the same three with night vision.
 */
public class NadirCoverScenario extends EvidenceScenario {
    /** Where the shafts start: far from the colony and from the other scenarios' rooms. */
    private static final int SHAFTS_X = 6_000;

    private static final int SHAFTS_Z = 6_000;

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
            ClientChecks.withHudHidden(
                    context,
                    ParticleStatus.MINIMAL,
                    () -> ShaftViews.withShortRenderDistance(context, () -> {
                        for (boolean nightVision : new boolean[] {false, true}) {
                            server.runOnServer(s -> {
                                ServerPlayer player =
                                        s.getPlayerList().getPlayers().getFirst();
                                player.removeAllEffects();
                                if (nightVision) {
                                    player.addEffect(new MobEffectInstance(
                                            MobEffects.NIGHT_VISION,
                                            MobEffectInstance.INFINITE_DURATION,
                                            0,
                                            false,
                                            false));
                                }
                            });
                            for (ShaftViews.Shaft shaft : shafts) {
                                String still = "nadir-" + shaft.name() + (nightVision ? "-night-vision" : "");
                                ShaftViews.stand(context, server, shaft.eye(), shaft.below(), shaft.held(), still);
                                // tick-wait: the fog colour is computed per frame, and a few frames must pass after
                                // the last chunk section is built
                                context.waitTicks(10);
                                screenshot(context, still);
                                frame(context);
                            }
                        }
                    }));
        }
    }
}
