package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.github.pkeppeler.deepcharter.upgrade.PartLabel;
import java.lang.reflect.Field;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Evidence scenario "part-tooltip" for #573: the inventory with a labelled hull part in the first hotbar slot and a hull
 * part without a label in the second, each hovered in turn so its tooltip shows.
 */
public class PartTooltipScenario extends EvidenceScenario {
    private static final int LABELLED_HOTBAR = 0;
    private static final int UNLABELLED_HOTBAR = 1;
    /** The first hotbar slot in the inventory menu. */
    private static final int HOTBAR_START = 36;

    private static final int HALF_SLOT = 8;

    @Override
    protected String name() {
        return "part-tooltip";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            awaitWorld(context);
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.getInventory().clearContent();
                player.getInventory()
                        .setItem(
                                LABELLED_HOTBAR,
                                ComponentItems.stackOf(
                                        ComponentTrack.HULL, new PartLabel(2, CharterId.random(), "HULL-0007")));
                player.getInventory()
                        .setItem(UNLABELLED_HOTBAR, new ItemStack(ComponentItems.item(ComponentTrack.HULL)));
            });
            ClientWait.until(
                    context,
                    "both parts in the client's hotbar",
                    client -> !hotbarStack(client, LABELLED_HOTBAR).isEmpty()
                            && !hotbarStack(client, UNLABELLED_HOTBAR).isEmpty());
            context.setScreen(() -> new InventoryScreen(ClientSession.player(Minecraft.getInstance())));
            ClientWait.screen(context, InventoryScreen.class);
            hover(context, LABELLED_HOTBAR);
            screenshot(context, "part-tooltip-labelled");
            frame(context);
            hover(context, UNLABELLED_HOTBAR);
            screenshot(context, "part-tooltip-unlabelled");
            frame(context);
        }
    }

    private static ItemStack hotbarStack(Minecraft client, int hotbar) {
        return ClientSession.player(client).getInventory().getItem(hotbar);
    }

    private static void hover(ClientGameTestContext context, int hotbar) {
        double[] cursor = context.computeOnClient(client -> {
            AbstractContainerScreen<?> screen = ClientSession.screen(client, AbstractContainerScreen.class);
            Slot slot = screen.getMenu().getSlot(HOTBAR_START + hotbar);
            double scale = client.getWindow().getGuiScale();
            return new double[] {
                (field(screen, "leftPos") + slot.x + HALF_SLOT) * scale,
                (field(screen, "topPos") + slot.y + HALF_SLOT) * scale
            };
        });
        context.getInput().setCursorPos(cursor[0], cursor[1]);
        context.waitTicks(4); // tick-wait: the hovered slot and its tooltip show on the next frames
    }

    private static int field(AbstractContainerScreen<?> screen, String name) {
        try {
            Field field = AbstractContainerScreen.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.getInt(screen);
        } catch (ReflectiveOperationException e) {
            throw new LinkageError("a container screen has no field " + name + "; the scenario reads it by name", e);
        }
    }
}
