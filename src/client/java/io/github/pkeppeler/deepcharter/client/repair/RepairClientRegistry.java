package io.github.pkeppeler.deepcharter.client.repair;

import io.github.pkeppeler.deepcharter.client.terminal.TerminalScreens;
import io.github.pkeppeler.deepcharter.repair.RepairRegistry;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;

/** Registers the repair station's screen and draws the thrown kit in flight as the item it is. */
public final class RepairClientRegistry {
    private RepairClientRegistry() {}

    // Fabric deprecated EntityRendererRegistry; vanilla EntityRenderers.register is private
    @SuppressWarnings("deprecation")
    public static void register() {
        TerminalScreens.register(TerminalTypes.REPAIR_STATION, RepairStationScreen::new);
        EntityRendererRegistry.register(RepairRegistry.THROWN_KIT, ThrownItemRenderer::new);
    }
}
