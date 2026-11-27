package io.github.pkeppeler.deepcharter.client.outpost;

import io.github.pkeppeler.deepcharter.outpost.IncidentReportPayload;
import io.github.pkeppeler.deepcharter.outpost.SpecSheet;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.BlockItem;

/**
 * Registers the ratings on a block item's tooltip ({@link SpecSheet}), and shows an incident report over the screen
 * that is open when it arrives: the terminal the player has just opened.
 */
public final class OutpostClientRegistry {
    /** The tooltip lines come after the item's name. */
    private static final int AFTER_NAME = 1;

    private OutpostClientRegistry() {}

    public static void register() {
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            if (stack.getItem() instanceof BlockItem item) {
                lines.addAll(AFTER_NAME, SpecSheet.lines(item.getBlock().defaultBlockState()));
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(IncidentReportPayload.TYPE, (payload, context) -> {
            Minecraft client = Minecraft.getInstance();
            client.gui.setScreen(new IncidentReportScreen(payload, client.gui.screen()));
        });
    }
}
