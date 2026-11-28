package io.github.pkeppeler.deepcharter.client.pod;

import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.pod.LooseOreTuning;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * Names the cost of ore in the pack on the ore's own tooltip while the viewer sits in a pod (ADR 0110): "Loose
 * aboard: x2". Only for the stack in the viewer's own pack, never for ore racked in the hold. A hook of its own, so the
 * ore item's tooltip code stays as it is.
 */
public final class LooseOreTooltip {
    private LooseOreTooltip() {}

    public static void init() {
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null
                    && player.getVehicle() instanceof PodEntity
                    && OreRegistry.typeOf(stack).isPresent()
                    && inPack(player, stack)) {
                lines.add(Component.translatable(
                        "tooltip.deepcharter.ore.loose_aboard", Math.round(LooseOreTuning.DEFAULT.weightFactor())));
            }
        });
    }

    /**
     * Whether {@code stack} is the very stack in one of the player's pack slots: the screen's slots over the pack hand
     * out those stacks, and the hold's slots hand out their own.
     */
    private static boolean inPack(LocalPlayer player, ItemStack stack) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (player.getInventory().getItem(slot) == stack) {
                return true;
            }
        }
        return false;
    }
}
