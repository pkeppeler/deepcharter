package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.UnbuiltDepthViews;
import java.util.EnumSet;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

/**
 * Evidence for #1192: the breach of {@code UnbuiltDepthClientTest} at dusk, seen from the last layer (layer 3) down through the broken
 * crust, then from the unbuilt depth under it straight down the shaft and level along the hall, with and without night vision, and
 * straight down from under the water of the flooded shaft. The recording is a fall from the last layer through the breach and down the shaft.
 */
public class UnbuiltDepthScenario extends EvidenceScenario {
    /** The fall looks nearly straight down, facing east. */
    private static final float FALL_PITCH = 80f;

    private static final float FALL_YAW = -90f;

    /** The recording holds on the view down through the breach for this many ticks before the fall. */
    private static final int HOLD_TICKS = 15;

    /** The fall is recorded for this many ticks: past the breach, down the shaft and onto its floor. */
    private static final int FALL_TICKS = 60;

    @Override
    protected String name() {
        return "unbuilt-depth";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            awaitWorld(context);
            TestServerContext server = singleplayer.getServer();
            server.runCommand("gamerule advance_time false");
            server.runCommand("weather clear");
            UnbuiltDepthViews.tour(
                    context,
                    server,
                    EnumSet.allOf(UnbuiltDepthViews.Place.class),
                    look -> still(context, look.name()),
                    () -> fall(context, server));
        }
    }

    /** A still of the view, with the HUD hidden and no particles, once the sky clock and the camera's probe have settled. */
    private void still(ClientGameTestContext context, String still) {
        ClientChecks.withHudHidden(context, ParticleStatus.MINIMAL, () -> {
            // tick-wait: the sky clock and the camera's attribute probe ease over a few ticks, and nothing
            // reports when they have
            context.waitTicks(40);
            screenshot(context, still);
        });
    }

    /** Drops the player, in survival and unhurt, from the room in the last layer through the breach, a frame a tick. */
    private void fall(ClientGameTestContext context, TestServerContext server) {
        BlockPos start = server.computeOnServer(s -> {
            ServerLevel level = UnbuiltDepthViews.level(s);
            BlockPos feet = UnbuiltDepthViews.feet(level, UnbuiltDepthViews.Place.BREACH);
            // Still a spectator from the views, so the player hangs there until the fall starts.
            s.getPlayerList()
                    .getPlayers()
                    .getFirst()
                    .teleportTo(
                            level,
                            feet.getX() + 0.5,
                            feet.getY(),
                            feet.getZ() + 0.5,
                            Set.of(),
                            FALL_YAW,
                            FALL_PITCH,
                            true);
            return feet;
        });
        ClientWait.until(
                context,
                "the player at the top of the breach",
                client -> client.player != null && client.player.blockPosition().equals(start));
        ClientChecks.withHudHidden(context, ParticleStatus.MINIMAL, () -> {
            // tick-wait: the camera's attribute probe eases over a few ticks after the teleport, and nothing reports
            // when it has
            context.waitTicks(40);
            for (int tick = 0; tick < HOLD_TICKS; tick++) {
                frame(context);
                context.waitTick();
            }
            server.runOnServer(s -> {
                ServerPlayer player = s.getPlayerList().getPlayers().getFirst();
                player.setPermanentlyInvulnerable(true);
                player.setGameMode(GameType.SURVIVAL);
            });
            for (int tick = 0; tick < FALL_TICKS; tick++) {
                frame(context);
                context.waitTick();
            }
        });
    }
}
