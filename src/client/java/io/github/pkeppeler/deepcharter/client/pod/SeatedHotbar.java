package io.github.pkeppeler.deepcharter.client.pod;

import io.github.pkeppeler.deepcharter.client.ui.HudKeepClear;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;

/**
 * Vanilla's hotbar under the cockpit (#1457): a seated pilot keeps it, and the held item's name over it, so it can pick dynamite and the other consumables from the
 * seat. Vanilla draws both; this only says where, from the 26.3 {@code Hud}'s own figures, so a popup keeps clear of them ({@link HudKeepClear}) and the cockpit's plate
 * stops above the bar ({@link CockpitHud#plateRows}). On foot nothing is listed: {@link HudKeepClear} lists the mod's own parts, and the vanilla HUD on foot is not one.
 */
public final class SeatedHotbar {
    /** Half the hotbar sprite's width. */
    private static final int HALF = 91;
    /** The selection frame, 24 x 23, stands one pixel out of the 182 x 22 bar on the left, the right and the top. */
    private static final int FRAME = 1;

    private static final int BAR_HEIGHT = 22;
    /** The off-hand slot beside the bar, 29 x 24 from one pixel over the bar's top. */
    private static final int OFFHAND_WIDTH = 29;

    private static final int OFFHAND_HEIGHT = 24;
    /** The held item's name: its text this far over the bottom of the GUI, 14 lower for a player who cannot be hurt, with a backdrop of 2 round it. */
    private static final int NAME_ABOVE = 59;

    private static final int NAME_DROP_UNHURT = 14;
    private static final int BACKDROP = 2;

    private SeatedHotbar() {}

    static void init() {
        HudKeepClear.register(SeatedHotbar::keepClear);
    }

    /** The bar with its selection frame on a GUI {@code guiWidth} x {@code guiHeight}: centred on the bottom edge. */
    public static HudKeepClear.Rect bar(int guiWidth, int guiHeight) {
        int top = guiHeight - BAR_HEIGHT - FRAME;
        return new HudKeepClear.Rect(
                "hotbar", guiWidth / 2 - HALF - FRAME, top, 2 * (HALF + FRAME), BAR_HEIGHT + FRAME);
    }

    /**
     * While the cockpit is up: the bar, the off-hand slot when the off hand holds something, and the held item's name whenever a slot holds an item. The name fades a
     * moment after a pick, but it is listed for as long as the item is held, so a popup does not jump up and down as it comes and goes.
     */
    private static void keepClear(Minecraft client, int guiWidth, int guiHeight, List<HudKeepClear.Rect> into) {
        LocalPlayer player = client.player;
        if (player == null || CockpitHud.seated(client, guiWidth, guiHeight).isEmpty()) {
            return;
        }
        into.add(bar(guiWidth, guiHeight));
        int centre = guiWidth / 2;
        if (!player.getOffhandItem().isEmpty()) {
            boolean left = player.getMainArm().getOpposite() == HumanoidArm.LEFT;
            into.add(new HudKeepClear.Rect(
                    "off-hand slot",
                    left ? centre - HALF - OFFHAND_WIDTH : centre + HALF,
                    guiHeight - BAR_HEIGHT - FRAME,
                    OFFHAND_WIDTH,
                    OFFHAND_HEIGHT));
        }
        ItemStack held = player.getInventory().getSelectedItem();
        if (!held.isEmpty()) {
            int width = client.font.width(name(held));
            boolean unhurt = client.gameMode != null && !client.gameMode.canHurtPlayer();
            int top = guiHeight - NAME_ABOVE + (unhurt ? NAME_DROP_UNHURT : 0);
            into.add(new HudKeepClear.Rect(
                    "held item name",
                    (guiWidth - width) / 2 - BACKDROP,
                    top - BACKDROP,
                    width + 2 * BACKDROP,
                    client.font.lineHeight + 2 * BACKDROP));
        }
    }

    /** The held item's name as vanilla writes it: in its rarity's colour, italic when it was renamed. */
    private static Component name(ItemStack held) {
        MutableComponent name = Component.empty()
                .append(held.getHoverName())
                .withStyle(held.getRarity().color());
        return held.has(DataComponents.CUSTOM_NAME) ? name.withStyle(ChatFormatting.ITALIC) : name;
    }
}
