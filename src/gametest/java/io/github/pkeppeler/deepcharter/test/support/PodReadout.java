package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.client.pod.PodStatusHud;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** The text of the pilot's readout, the lines the pod HUD and the cockpit plate both draw. */
public final class PodReadout {
    private PodReadout() {}

    public static List<Component> texts(Minecraft client, PodEntity pod) {
        return PodStatusHud.readout(ClientSession.player(client), pod).stream()
                .map(PodStatusHud.Line::text)
                .toList();
    }
}
