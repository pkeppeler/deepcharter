package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

/**
 * Evidence scenario "m3-campaign-cutaway" (#742, #803): a cut through one column of the real overworld, which is the campaign world, in a
 * default-preset world, seen from inside the cut. Four stills, each a 44-block window of the face: the surface's ground, the surface's floor above layer 1,
 * layer 1's floor with the crust and the head of layer 2 under it, and layer 2's floor with the crust and the unbuilt rock under it. The
 * player is a spectator with night vision, looking north at the face.
 */
public class CampaignCutawayScenario extends EvidenceScenario {
    private static final int X = 2000;
    private static final int Z = 2000;
    /** The cut is this many blocks to each side of the column, and this deep (south of the face), so that a 44-block window fits the view. */
    private static final int HALF_WIDTH = 40;

    private static final int DEPTH = 30;
    private static final int HALF_WINDOW = 22;
    private static final int CAMERA_BACK = 2;
    private static final float NORTH = 180f;
    /** A still: its name and the Y at the middle of its window. */
    private record Window(String name, int centreY) {}

    @Override
    protected String name() {
        return "m3-campaign-cutaway";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        List<Window> windows = List.of(
                new Window("surface-ground", LayerTuning.DEFAULT.seaLevel()),
                new Window("surface-floor", CampaignBands.SURFACE.minY()),
                new Window("layer-1-floor", CampaignBands.LAYER_1.minY() + CampaignBands.crustRows()),
                new Window("layer-2-floor", CampaignBands.LAYER_2.minY() + CampaignBands.crustRows()));
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            awaitWorld(context);
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SPECTATOR);
                player.addEffect(new MobEffectInstance(
                        MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION, 0, false, false));
                ServerLevel level = TestLayers.level(server, TestLayers.SURFACE);
                for (Window window : windows) {
                    RoomCarver.carve(
                            level,
                            new BlockPos(X - HALF_WIDTH, window.centreY() - HALF_WINDOW, Z),
                            new BlockPos(X + HALF_WIDTH, window.centreY() + HALF_WINDOW, Z + DEPTH),
                            Blocks.AIR.defaultBlockState());
                }
            });
            ClientChecks.withHudHidden(context, () -> {
                for (Window window : windows) {
                    look(context, singleplayer, window);
                    screenshot(context, window.name());
                    frame(context);
                }
            });
        }
    }

    private static void look(ClientGameTestContext context, TestSingleplayerContext singleplayer, Window window) {
        singleplayer.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            player.teleportTo(
                    TestLayers.level(server, TestLayers.SURFACE),
                    X + 0.5,
                    window.centreY(),
                    Z + DEPTH - CAMERA_BACK + 0.5,
                    Set.of(),
                    NORTH,
                    0f,
                    true);
        });
        // The face is in view when the client holds its chunk: some block of the face's column is solid in the window.
        ClientWait.until(context, "the cut face of " + window.name(), client -> {
            Level level = ClientLight.clientLevel(client);
            if (!LayerChain.isCampaign(level)) {
                return false;
            }
            for (int y = window.centreY() - HALF_WINDOW; y <= window.centreY() + HALF_WINDOW; y++) {
                if (!level.getBlockState(new BlockPos(X, y, Z - 1)).isAir()) {
                    return true;
                }
            }
            return false;
        });
        context.waitTicks(60);
    }
}
