package io.github.pkeppeler.deepcharter.client.pod;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookKeys;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodSeat;
import io.github.pkeppeler.deepcharter.pod.SonarEcho;
import io.github.pkeppeler.deepcharter.pod.SonarMarkPayload;
import io.github.pkeppeler.deepcharter.pod.SonarPingPayload;
import java.util.Optional;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/**
 * The navigator's keys: V pings the sonar, and B marks the echo they are looking toward ({@link SonarAim}), or takes its mark off. The server decides what
 * each does, and tells the player when it refuses.
 */
public final class SonarKeys {
    public static final KeyMapping PING = new KeyMapping(
            "key.deepcharter.sonar_ping",
            InputConstants.Type.KEYBOARD,
            InputConstants.KEY_V,
            HandbookKeys.OPEN.getCategory());

    public static final KeyMapping MARK = new KeyMapping(
            "key.deepcharter.sonar_mark",
            InputConstants.Type.KEYBOARD,
            InputConstants.KEY_B,
            HandbookKeys.OPEN.getCategory());

    private SonarKeys() {}

    public static void register() {
        KeyMappingHelper.registerKeyMapping(PING);
        KeyMappingHelper.registerKeyMapping(MARK);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (PING.consumeClick()) {
                if (client.player != null
                        && client.gui.screen() == null
                        && client.player.getVehicle() instanceof PodEntity pod
                        && PodSeat.find(pod, client.player)
                                .filter(seat -> seat == PodSeat.NAVIGATOR)
                                .isPresent()) {
                    ClientPlayNetworking.send(new SonarPingPayload());
                }
            }
            while (MARK.consumeClick()) {
                if (client.player != null && client.gui.screen() == null) {
                    mark(client, client.player);
                }
            }
        });
    }

    private static void mark(Minecraft client, LocalPlayer player) {
        Optional<SonarEcho> aimed = SonarHud.aimedEcho(client);
        if (aimed.isPresent()) {
            ClientPlayNetworking.send(new SonarMarkPayload(aimed.get().pos()));
        } else if (SonarHud.navigatedPod(client).isPresent()) {
            player.sendOverlayMessage(Component.translatable("deepcharter.pod.sonar.no_aim"));
        }
    }
}
