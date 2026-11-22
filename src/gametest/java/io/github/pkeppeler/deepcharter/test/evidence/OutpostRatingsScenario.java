package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.client.outpost.PlacementReadoutHud;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.ore.SlagBrick;
import io.github.pkeppeler.deepcharter.outpost.Placements.Readout;
import io.github.pkeppeler.deepcharter.outpost.Placements.Verdict;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.PlacementStage;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Evidence scenario "outpost-ratings" for #989: the placement readout under the crosshair for the block in hand, in the lit pocket of a layer's rock. In
 * layer 1 an oak plank WILL NOT HOLD UNATTENDED and layer 1's own rock HOLDS HERE; in layer 2 that same rock is below the rating, and slag brick, fused
 * from spoil, HOLDS HERE.
 */
public class OutpostRatingsScenario extends EvidenceScenario {
    private static final int HOLD_FRAMES = 6;
    private static final int TICKS_PER_FRAME = 3;

    /** A layer, the block in hand, the verdict the readout must show, and the still's name. */
    private record Still(int layer, Item item, Verdict verdict, String name) {}

    @Override
    protected String name() {
        return "outpost-ratings";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            awaitWorld(context);
            int staged = 0;
            for (Still still : new Still[] {
                new Still(1, Items.OAK_PLANKS, Verdict.WILL_NOT_HOLD, "layer-1-plank-will-not-hold"),
                new Still(1, LayerBlocks.PACKED_SHALE.asItem(), Verdict.HOLDS_HERE, "layer-1-shale-holds"),
                new Still(2, LayerBlocks.PACKED_SHALE.asItem(), Verdict.WILL_NOT_HOLD, "layer-2-shale-will-not-hold"),
                new Still(2, SlagBrick.item(), Verdict.HOLDS_HERE, "layer-2-slag-holds"),
            }) {
                if (staged != still.layer()) {
                    singleplayer.getServer().runOnServer(server -> PlacementStage.build(server, still.layer()));
                    ClientWait.until(
                            context,
                            "the player in layer " + still.layer(),
                            client -> TestLayers.clientIsIn(client, still.layer()));
                    staged = still.layer();
                }
                singleplayer
                        .getServer()
                        .runOnServer(server -> PlacementStage.hold(server, new ItemStack(still.item())));
                ClientWait.until(
                        context,
                        "the readout " + still.verdict() + " for " + still.item(),
                        client -> client.player != null
                                && client.player.getMainHandItem().is(still.item())
                                && PlacementReadoutHud.looked(client)
                                        .map(Readout::verdict)
                                        .filter(shown -> shown == still.verdict())
                                        .isPresent());
                hold(context);
                screenshot(context, still.name());
            }
        }
    }

    private void hold(ClientGameTestContext context) {
        for (int i = 0; i < HOLD_FRAMES; i++) {
            context.waitTicks(TICKS_PER_FRAME);
            frame(context);
        }
    }
}
