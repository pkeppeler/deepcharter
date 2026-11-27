package io.github.pkeppeler.deepcharter.client.layer;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.pod.CockpitHud;
import io.github.pkeppeler.deepcharter.client.theme.BreachLook;
import io.github.pkeppeler.deepcharter.client.theme.CockpitLook;
import io.github.pkeppeler.deepcharter.client.theme.Colors;
import io.github.pkeppeler.deepcharter.client.theme.HudLook;
import io.github.pkeppeler.deepcharter.client.ui.CrtDraw;
import io.github.pkeppeler.deepcharter.client.ui.HudKeepClear;
import io.github.pkeppeler.deepcharter.client.ui.PixelDigits;
import io.github.pkeppeler.deepcharter.layer.Altimeter;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The altimeter, top centre (the pod readout is top left), always on screen while in a world, in the cockpit's pixel digits (#1539, {@link CrtDraw#pixelDigits}). Then the
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
        // keep-clear-ok: the fade is the whole screen going black, there is nothing in it to keep a popup off
        ClientLifecycleEvents.CLIENT_STARTED.register(
                client -> HudElementRegistry.addLast(BREACH, BreachHud::extractBreach));
        HudKeepClear.register(BreachHud::keepClear);
    }

    /** The altimeter's reading as it is drawn, and the box it covers. */
    private record Reading(String text, HudKeepClear.Rect box) {}

    /**
     * The altimeter on a GUI {@code guiWidth} x {@code guiHeight} with {@code jitter} on it: top centre, or in the ribbon's recess in the
     * cockpit; empty when it is not drawn. The signal decay (#502) also fails the altimeter, which sits in the ribbon's recess: a dark
     * depth gauge must not leave the depth readable. What is drawn and what is kept clear are this one box.
     */
    private static Optional<Reading> reading(
            Minecraft client, int guiWidth, int guiHeight, BreachEffects.Offset jitter) {
        if (client.player == null || client.level == null) {
            return Optional.empty();
        }
        return CockpitHud.altimeter(
                        client, guiWidth, guiHeight, Altimeter.reading(client.level, client.player.getBlockY()))
                .map(Component::getString)
                .map(text -> {
                    CockpitLook.Digits digits = CockpitLook.current().digits();
                    int width = PixelDigits.width(text, digits);
                    int top = CockpitHud.seated(client, guiWidth, guiHeight)
                            .map(CockpitLook.Layout::altimeterY)
                            .orElse(HudLook.current().altimeterMargin());
                    return new Reading(
                            text,
                            new HudKeepClear.Rect(
                                    "altimeter",
                                    (guiWidth - width) / 2 + jitter.x(),
                                    top + jitter.y(),
                                    width,
                                    digits.height()));
                });
    }

    /** The altimeter, wherever the breach's shake can put it. */
    private static void keepClear(Minecraft client, int guiWidth, int guiHeight, List<HudKeepClear.Rect> into) {
        reading(client, guiWidth, guiHeight, new BreachEffects.Offset(0, 0))
                .ifPresent(reading ->
                        into.add(reading.box().grow(BreachLook.current().jitterPixels())));
    }

    public static void extractAltimeter(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft client = Minecraft.getInstance();
        reading(client, graphics.guiWidth(), graphics.guiHeight(), BreachEffects.jitter())
                .ifPresent(reading -> CrtDraw.pixelDigits(
                        graphics,
                        reading.text(),
                        reading.box().x(),
                        reading.box().y(),
                        HudLook.current().altimeterColor(),
                        CockpitLook.current().digits()));
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
