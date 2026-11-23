package io.github.pkeppeler.deepcharter.layer;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Breach crossing: the layers are Y bands of the campaign world, so nobody moves and {@link BandCrossing} fires
 * {@link BreachEvents#CROSSED} in place when the root vehicle of a player passes a crust band (ADR 0061).
 *
 * <p>Survival and adventure players cannot break {@code breach_crust} by hand; creative players can.
 * Drills do it through {@link #breakCrust}.
 */
public final class BreachService {
    private BreachService() {}

    public static void init() {
        BandCrossing.init();
        ServerTickEvents.END_LEVEL_TICK.register(BreachService::crossEntities);
        PlayerBlockBreakEvents.BEFORE.register(
                (level, player, pos, state, blockEntity) -> player.isCreative() || !state.is(LayerBlocks.BREACH_CRUST));
    }

    /**
     * The drill's way through the crust. Hand-breaking is refused by the player block-break event;
     * this removes the block directly, so it is not subject to that guard. Returns false when
     * {@code pos} holds no crust.
     */
    public static boolean breakCrust(ServerLevel level, BlockPos pos) {
        if (!level.getBlockState(pos).is(LayerBlocks.BREACH_CRUST)) {
            return false;
        }
        return level.destroyBlock(pos, false);
    }

    private static void crossEntities(ServerLevel level) {
        if (LayerChain.isCampaign(level)) {
            BandCrossing.tick(level);
        }
    }
}
