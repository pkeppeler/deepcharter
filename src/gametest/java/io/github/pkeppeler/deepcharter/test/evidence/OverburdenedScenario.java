package io.github.pkeppeler.deepcharter.test.evidence;

import static io.github.pkeppeler.deepcharter.test.support.ContainerScreenFields.intField;

import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.test.support.ChatLines;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;

/**
 * Evidence scenario "overburdened" for #853: a player picks up their first ore. The HUD shows the Overburdened icon and the one-time line
 * in the chat; the inventory lists the status with its level, and the ore's tooltip says it is heavy and where to haul it.
 */
public class OverburdenedScenario extends EvidenceScenario {
    /** The first hotbar slot in the inventory menu. */
    private static final int HOTBAR_START = 36;

    private static final int HALF_SLOT = 8;

    @Override
    protected String name() {
        return "overburdened";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            awaitWorld(context);
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.getInventory().clearContent();
                player.getInventory().setItem(0, OreRegistry.stack(OreType.GOLDIUM));
            });
            ClientWait.until(
                    context,
                    "the status and the first-ore line on the client",
                    client -> ClientSession.player(client).hasEffect(OreRegistry.OVERBURDENED)
                            && ChatLines.received(client).stream().anyMatch(line -> line.contains("Ore is heavy")));
            context.waitTicks(10); // tick-wait: the status icon and the chat line fade in over a few frames
            screenshot(context, "overburdened-hud");
            frame(context);
            context.setScreen(() -> new InventoryScreen(ClientSession.player(Minecraft.getInstance())));
            ClientWait.screen(context, InventoryScreen.class);
            hover(context);
            screenshot(context, "overburdened-inventory");
            frame(context);
        }
    }

    private static void hover(ClientGameTestContext context) {
        double[] cursor = context.computeOnClient(client -> {
            AbstractContainerScreen<?> screen = ClientSession.screen(client, AbstractContainerScreen.class);
            Slot slot = screen.getMenu().getSlot(HOTBAR_START);
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
