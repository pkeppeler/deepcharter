package io.github.pkeppeler.deepcharter.test.evidence;

import static io.github.pkeppeler.deepcharter.test.support.ContainerScreenFields.intField;

import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout;
import io.github.pkeppeler.deepcharter.client.theme.CatalogueLook;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.test.support.CatalogueScene;
import io.github.pkeppeler.deepcharter.test.support.ChatLines;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import java.util.Objects;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;

/**
 * Evidence scenario "overburdened" for #853 and #1290: a player picks up their first ore. The HUD shows the
 * Overburdened icon and the one-time line in the chat; the inventory lists the status with its level, and the ore's
 * tooltip says it is heavy and where to haul it. The inventory is shown with the parts catalogue open ({@code
 * overburdened-inventory}) and put away ({@code overburdened-inventory-catalogue-hidden}), at GUI scale 2 (427 wide)
 * and at scale 1 ({@code ...-wide}, 854 wide), beside a second effect, and in a window too short for tiles ({@code
 * overburdened-inventory-icons}, a row of bare icons).
 */
public class OverburdenedScenario extends EvidenceScenario {
    /** The first hotbar slot in the inventory menu. */
    private static final int HOTBAR_START = 36;

    private static final int HALF_SLOT = 8;
    /** The window's default GUI scale (427 by 240), and the scale that doubles its width. */
    private static final int DEFAULT_SCALE = 2;
    /** The GUI width at the default scale. */
    private static final int DEFAULT_WIDTH = 427;

    private static final int WIDE_SCALE = 1;

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
            singleplayer.getServer().runCommand("effect give @p minecraft:speed infinite 0");
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
            inventoryStills(context, "overburdened-inventory");
            context.runOnClient(client -> client.options.guiScale().set(WIDE_SCALE));
            context.waitTicks(2); // tick-wait: the screen lays itself out again at the new GUI size
            inventoryStills(context, "overburdened-inventory-wide");
            context.runOnClient(client -> client.options.guiScale().set(DEFAULT_SCALE));
            context.waitTicks(2); // tick-wait: the screen lays itself out again at the default GUI size
            // A GUI between the shortest for bare icons and the shortest for tiles, laid out in the window's top.
            context.runOnClient(client -> Objects.requireNonNull(client.gui.screen(), "the inventory is open")
                    .resize(
                            DEFAULT_WIDTH,
                            CatalogueLayout.shortest(CatalogueLook.current(), CatalogueLayout.EffectRow.TILES) - 6));
            context.waitTicks(2); // tick-wait: the panels and the row are laid out at the new size
            hover(context);
            screenshot(context, "overburdened-inventory-icons");
            frame(context);
        }
    }

    /** The inventory with the catalogue open and the cursor on the first hotbar slot, then with the catalogue put away. */
    private void inventoryStills(ClientGameTestContext context, String still) {
        context.runOnClient(client -> client.gui.toastManager().clear());
        hover(context);
        screenshot(context, still);
        frame(context);
        context.runOnClient(client -> CatalogueScene.panelNow(client).state().toggleHidden());
        context.waitTicks(2); // tick-wait: the panels are gone and vanilla's list is the one drawn
        screenshot(context, still + "-catalogue-hidden");
        frame(context);
        context.runOnClient(client -> CatalogueScene.panelNow(client).state().toggleHidden());
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
