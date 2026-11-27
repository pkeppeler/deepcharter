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
 * The navigator's keys: V pings the sonar, and G marks the echo they are looking toward ({@link SonarAim}), or takes its mark off. The server decides what
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
            InputConstants.KEY_G,
            HandbookKeys.OPEN.getCategory());

    private SonarKeys() {}

    public static void register() {
        KeyMappingHelper.registerKeyMapping(PING);
        KeyMappingHelper.registerKeyMapping(MARK);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (PING.consumeClick()) {
                press(client, () -> ClientPlayNetworking.send(new SonarPingPayload()));
            }
            while (MARK.consumeClick()) {
                press(client, () -> mark(client));
            }
        });
    }

    /**
     * Runs {@code navigatorAction} for the navigator. A pilot who presses a sonar key in the pod is told on the action bar that it is the
     * navigator's (the server says the same to anyone who gets a packet through); a player on foot or in a screen gets nothing.
     */
    private static void press(Minecraft client, Runnable navigatorAction) {
        LocalPlayer player = client.player;
        if (player == null || client.gui.screen() != null || !(player.getVehicle() instanceof PodEntity pod)) {
            return;
        }
        if (PodSeat.isNavigator(pod, player)) {
            navigatorAction.run();
        } else {
            player.sendOverlayMessage(Component.translatable("deepcharter.pod.sonar.not_navigator"));
        }
    }

    private static void mark(Minecraft client) {
        Optional<SonarEcho> aimed = SonarHud.frame(client).flatMap(SonarHud.Frame::aimed);
        if (aimed.isPresent()) {
            ClientPlayNetworking.send(new SonarMarkPayload(aimed.get().pos()));
        } else if (client.player != null) {
            client.player.sendOverlayMessage(Component.translatable("deepcharter.pod.sonar.no_aim"));
        }
    }
}
