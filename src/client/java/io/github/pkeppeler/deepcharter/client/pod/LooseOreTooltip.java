package io.github.pkeppeler.deepcharter.client.pod;

import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.pod.LooseOreTuning;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Names the cost of ore in the pack on the ore's own tooltip while the viewer sits in a pod (ADR 0110): "Loose
 * aboard: x2". A hook of its own, so the ore item's tooltip code stays as it is.
 */
public final class LooseOreTooltip {
    private LooseOreTooltip() {}

    public static void init() {
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            Minecraft client = Minecraft.getInstance();
            if (client.player != null
                    && client.player.getVehicle() instanceof PodEntity
                    && OreRegistry.typeOf(stack).isPresent()) {
                lines.add(Component.translatable(
                        "tooltip.deepcharter.ore.loose_aboard", Math.round(LooseOreTuning.DEFAULT.weightFactor())));
            }
        });
    }
}
