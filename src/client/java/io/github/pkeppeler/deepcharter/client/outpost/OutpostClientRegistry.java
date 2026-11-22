package io.github.pkeppeler.deepcharter.client.outpost;

import io.github.pkeppeler.deepcharter.outpost.IncidentReportPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/** Shows an incident report over the screen that is open when it arrives: the terminal the player has just opened. */
public final class OutpostClientRegistry {
    private OutpostClientRegistry() {}

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(IncidentReportPayload.TYPE, (payload, context) -> {
            Minecraft client = Minecraft.getInstance();
            client.gui.setScreen(new IncidentReportScreen(payload, client.gui.screen()));
        });
    }
}
