package io.github.pkeppeler.deepcharter.test.evidence;

import static io.github.pkeppeler.deepcharter.test.support.ContainerScreenFields.intField;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CataloguePanel;
import io.github.pkeppeler.deepcharter.client.theme.CatalogueLook;
import io.github.pkeppeler.deepcharter.test.support.CatalogueScene;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Evidence scenario "salvage-cloth" for #1262: what a salvage crate drops (cloth, scrap iron, scrap copper, planks,
 * sticks and redstone) in the hotbar of the inventory, with the parts catalogue beside it turned to the page that lists
 * the cloth by the scrap and no cell hovered (still {@code inventory}), and nearest-neighbour zooms of the hotbar
 * ({@code inventory-zoom}) and of the catalogue's grid ({@code catalogue-zoom}), so the cloth's pixels can be judged
 * next to its neighbours.
 */
public class SalvageClothScenario extends EvidenceScenario {
    /** The first hotbar slot in the inventory menu. */
    private static final int HOTBAR_START = 36;

    private static final int HOTBAR_SLOTS = 9;
    /** A slot's pitch in the inventory image, and its frame round the 16-pixel item. */
    private static final int SLOT_PITCH = 18;

    private static final int SLOT_FRAME = 1;
    /** The advancement toasts of the first items held, which sit over the top of the catalogue, fade by now. */
    private static final int TOAST_TICKS = 60;

    private static final int HOLD_FRAMES = 4;

    @Override
    protected String name() {
        return "salvage-cloth";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            awaitWorld(context);
            Item cloth = item("salvage_cloth");
            List<ItemStack> drop = List.of(
                    new ItemStack(cloth),
                    new ItemStack(item("scrap_iron"), 2),
                    new ItemStack(item("scrap_copper"), 2),
                    new ItemStack(Items.OAK_PLANKS, 3),
                    new ItemStack(Items.STICK, 2),
                    new ItemStack(Items.REDSTONE));
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.getInventory().clearContent();
                for (int slot = 0; slot < drop.size(); slot++) {
                    player.getInventory().setItem(slot, drop.get(slot).copy());
                }
            });
            CatalogueScene.openInventory(context);
            CataloguePanel panel = CatalogueScene.panel(context);
            ClientWait.until(
                    context,
                    "the cloth in the catalogue, taught by what the player holds",
                    client -> panel.shownItems().contains(cloth));
            context.waitTicks(TOAST_TICKS); // tick-wait: the toasts of the first items held fade
            ClientChecks.withHudHidden(context, () -> recordStills(context, panel, cloth));
            context.setScreen(() -> null);
        }
    }

    /** The inventory beside the catalogue's page of the cloth, and the two zooms. */
    private void recordStills(ClientGameTestContext context, CataloguePanel panel, Item cloth) {
        CatalogueScene.clearSearch(context, panel);
        context.waitTicks(2); // tick-wait: the catalogue lists what it now knows before the cell is looked up
        CatalogueLayout layout = CatalogueScene.layout(context, panel);
        CatalogueLayout.Shelf shelf = layout.shelf();
        int index = CatalogueScene.indexOf(context, panel, cloth);
        CatalogueScene.showPage(context, panel, shelf.perPage(), index / shelf.perPage());
        CatalogueScene.parkCursor(context, layout);
        hold(context);

        screenshot(context, "inventory");
        int[] hotbar = hotbarTopLeft(context);
        zoomStill(context, "inventory", "inventory-zoom", hotbar[0], hotbar[1], HOTBAR_SLOTS * SLOT_PITCH, SLOT_PITCH);
        int cell = CatalogueLook.current().gridCell();
        zoomStill(
                context,
                "inventory",
                "catalogue-zoom",
                shelf.left(),
                shelf.gridY(),
                shelf.columns() * cell,
                shelf.rows() * cell);
    }

    /** The top left of the hotbar's first slot frame, in GUI pixels. */
    private static int[] hotbarTopLeft(ClientGameTestContext context) {
        return context.computeOnClient(client -> {
            AbstractContainerScreen<?> screen = ClientSession.screen(client, AbstractContainerScreen.class);
            Slot slot = screen.getMenu().getSlot(HOTBAR_START);
            return new int[] {
                intField(screen, "leftPos") + slot.x - SLOT_FRAME, intField(screen, "topPos") + slot.y - SLOT_FRAME
            };
        });
    }

    private void hold(ClientGameTestContext context) {
        context.runOnClient(client -> client.gui.toastManager().clear());
        for (int frame = 0; frame < HOLD_FRAMES; frame++) {
            frame(context);
        }
    }

    /** The mod's item {@code path}; an unknown id fails here, not as a wait that times out later. */
    private static Item item(String path) {
        Identifier id = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path);
        return BuiltInRegistries.ITEM.getOptional(id).orElseThrow(() -> new AssertionError("no item " + id));
    }
}
