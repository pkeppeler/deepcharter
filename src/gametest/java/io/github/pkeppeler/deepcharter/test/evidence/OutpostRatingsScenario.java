package io.github.pkeppeler.deepcharter.test.evidence;

import static io.github.pkeppeler.deepcharter.test.support.ContainerScreenFields.intField;

import io.github.pkeppeler.deepcharter.client.outpost.PlacementReadoutHud;
import io.github.pkeppeler.deepcharter.colony.ColonyKit;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.ore.SlagBrick;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.PlacementStage;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.HitResult;

/**
 * Evidence scenario "outpost-ratings" for #989: what a player can see of the ratings today. Layers 1 and 2 ask nothing, so a plank in hand aimed at a
 * block in either layer shows no readout; and the spec sheet, a block's tooltip, states the ratings of a rated block (slag brick, layer 1's rock, window
 * glass, which holds some axes and not others) and says nothing for a block rated nothing (stone).
 */
public class OutpostRatingsScenario extends EvidenceScenario {
    private static final int HOLD_FRAMES = 6;
    private static final int TICKS_PER_FRAME = 3;
    /** The first hotbar slot in the inventory menu. */
    private static final int HOTBAR_START = 36;

    private static final int HALF_SLOT = 8;

    /** The hotbar slot of each block whose tooltip is shown, with its still's name. */
    private record Tooltip(Item item, String still) {}

    private static final List<Tooltip> TOOLTIPS = List.of(
            new Tooltip(SlagBrick.item(), "tooltip-slag-brick"),
            new Tooltip(LayerBlocks.PACKED_SHALE.asItem(), "tooltip-layer-1-rock"),
            new Tooltip(ColonyKit.WINDOW_GLASS.asItem(), "tooltip-window-glass"),
            new Tooltip(Items.STONE, "tooltip-stone"));

    @Override
    protected String name() {
        return "outpost-ratings";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            awaitWorld(context);
            for (int layer : new int[] {1, 2}) {
                singleplayer.getServer().runOnServer(server -> PlacementStage.build(server, layer));
                ClientWait.until(
                        context, "the player in layer " + layer, client -> TestLayers.clientIsIn(client, layer));
                singleplayer
                        .getServer()
                        .runOnServer(server -> PlacementStage.hold(server, new ItemStack(Items.OAK_PLANKS)));
                ClientWait.until(
                        context,
                        "a plank in hand with the crosshair on the aimed block in layer " + layer,
                        client -> client.player != null
                                && client.player.getMainHandItem().is(Items.OAK_PLANKS)
                                && client.hitResult != null
                                && client.hitResult.getType() == HitResult.Type.BLOCK
                                && PlacementReadoutHud.looked(client).isEmpty());
                hold(context);
                screenshot(context, "layer-" + layer + "-plank-no-readout");
            }

            singleplayer.getServer().runOnServer(server -> {
                PlacementStage.hold(server, ItemStack.EMPTY);
                var inventory = server.getPlayerList().getPlayers().getFirst().getInventory();
                for (int slot = 0; slot < TOOLTIPS.size(); slot++) {
                    inventory.setItem(slot, new ItemStack(TOOLTIPS.get(slot).item()));
                }
            });
            ClientWait.until(
                    context,
                    "the blocks in the client's hotbar",
                    client -> ClientSession.player(client)
                            .getInventory()
                            .getItem(TOOLTIPS.size() - 1)
                            .is(TOOLTIPS.getLast().item()));
            context.setScreen(() -> new InventoryScreen(ClientSession.player(Minecraft.getInstance())));
            ClientWait.screen(context, InventoryScreen.class);
            for (int slot = 0; slot < TOOLTIPS.size(); slot++) {
                hover(context, slot);
                screenshot(context, TOOLTIPS.get(slot).still());
                frame(context);
            }
        }
    }

    private void hold(ClientGameTestContext context) {
        for (int i = 0; i < HOLD_FRAMES; i++) {
            context.waitTicks(TICKS_PER_FRAME);
            frame(context);
        }
    }

    private static void hover(ClientGameTestContext context, int hotbar) {
        double[] cursor = context.computeOnClient(client -> {
            AbstractContainerScreen<?> screen = ClientSession.screen(client, AbstractContainerScreen.class);
            Slot slot = screen.getMenu().getSlot(HOTBAR_START + hotbar);
            double scale = client.getWindow().getGuiScale();
            return new double[] {
                (intField(screen, "leftPos") + slot.x + HALF_SLOT) * scale,
                (intField(screen, "topPos") + slot.y + HALF_SLOT) * scale
            };
        });
        context.getInput().setCursorPos(cursor[0], cursor[1]);
        context.waitTicks(4); // tick-wait: the hovered slot and its tooltip show on the next frames
    }
}
