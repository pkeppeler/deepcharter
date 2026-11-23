package io.github.pkeppeler.deepcharter.client.layer;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.pod.CockpitHud;
import io.github.pkeppeler.deepcharter.client.theme.BreachLook;
import io.github.pkeppeler.deepcharter.client.theme.CockpitLook;
import io.github.pkeppeler.deepcharter.client.theme.HudLook;
import io.github.pkeppeler.deepcharter.client.ui.HudKeepClear;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** The altimeter, top centre (the pod readout is top left), always on screen while in a world. It shakes for a moment after a crossing. */
public final class BreachHud {
    private static final Identifier ALTIMETER = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "altimeter");

    private BreachHud() {}

    public static void init() {
        HudElementRegistry.addLast(ALTIMETER, BreachHud::extractAltimeter);
        HudKeepClear.register(BreachHud::keepClear);
    }

    /** The altimeter's reading as it is drawn, and the box it covers. */
    private record Reading(Component text, HudKeepClear.Rect box) {}

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
        return CockpitHud.altimeter(client, guiWidth, guiHeight, Altimeter.reading(client.player.getBlockY()))
                .map(text -> {
                    int width = client.font.width(text);
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
                                    client.font.lineHeight));
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
                .ifPresent(reading -> graphics.text(
                        client.font,
                        reading.text(),
                        reading.box().x(),
                        reading.box().y(),
                        HudLook.current().altimeterColor()));
    }
}
