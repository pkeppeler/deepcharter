package io.github.pkeppeler.deepcharter.client.layer;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.pod.CockpitHud;
import io.github.pkeppeler.deepcharter.client.theme.BreachLook;
import io.github.pkeppeler.deepcharter.client.theme.CockpitLook;
import io.github.pkeppeler.deepcharter.client.theme.Colors;
import io.github.pkeppeler.deepcharter.client.theme.HudLook;
import java.util.Optional;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The altimeter, top centre (the pod readout is top left), always on screen while in a world. Then the
 * breach fade over the whole HUD. The transmission of a crossing is drawn on top of it by the transmission feature.
 *
 * <p>The fade is a full blackout, so it is registered when the client has started, after every mod's
 * initializer has registered its own elements. {@code addLast} puts it after all of them, so no other
 * HUD element draws over the black. An element registered later than that would draw over it.
 */
public final class BreachHud {
    private static final Identifier ALTIMETER = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "altimeter");
    private static final Identifier BREACH = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "breach_fade");

    private BreachHud() {}

    public static void init() {
        HudElementRegistry.addLast(ALTIMETER, BreachHud::extractAltimeter);
        ClientLifecycleEvents.CLIENT_STARTED.register(
                client -> HudElementRegistry.addLast(BREACH, BreachHud::extractBreach));
    }

    private static void extractAltimeter(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return;
        }
        // The depth instrument of the signal decay (#502) also fails the altimeter, which sits in the ribbon's recess:
        // a dark depth gauge must not leave the depth readable.
        Optional<Component> shown = CockpitHud.altimeter(client, Altimeter.reading(client));
        if (shown.isEmpty()) {
            return;
        }
        Component reading = shown.get();
        BreachEffects.Offset jitter = BreachEffects.jitter();
        int x = (graphics.guiWidth() - client.font.width(reading)) / 2 + jitter.x();
        HudLook look = HudLook.current();
        int top = CockpitHud.seated(client).map(CockpitLook.Layout::altimeterY).orElse(look.altimeterMargin());
        graphics.text(client.font, reading, x, top + jitter.y(), look.altimeterColor());
    }

    private static void extractBreach(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        float alpha = BreachEffects.fadeAlpha(deltaTracker.getGameTimeDeltaPartialTick(false));
        if (alpha > 0f) {
            graphics.fill(
                    0,
                    0,
                    graphics.guiWidth(),
                    graphics.guiHeight(),
                    Colors.withAlpha(BreachLook.current().fadeColor(), Math.round(alpha * 255)));
        }
    }
}
