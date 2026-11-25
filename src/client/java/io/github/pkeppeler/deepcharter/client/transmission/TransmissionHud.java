package io.github.pkeppeler.deepcharter.client.transmission;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.theme.TransmissionLook;
import io.github.pkeppeler.deepcharter.client.ui.CrtDraw;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;
import io.github.pkeppeler.deepcharter.client.ui.HudKeepClear;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

/**
 * Draws {@link TransmissionOverlay} as a CRT panel over the whole HUD: a dark window with a border, the header in its framing's colour,
 * the text typed below it, and scanlines over the panel. It is registered once the client has started, with {@code addLast}, after the
 * breach fade of the layer feature, so the text is drawn over the black and not under it.
 */
public final class TransmissionHud {
    private static final Identifier ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "transmission");

    private TransmissionHud() {}

    static void init() {
        ClientLifecycleEvents.CLIENT_STARTED.register(
                client -> HudElementRegistry.addLast(ID, TransmissionHud::extract));
        HudKeepClear.register(TransmissionHud::keepClear);
    }

    /** The panel while a transmission is on screen. */
    private static void keepClear(Minecraft client, int guiWidth, int guiHeight, List<HudKeepClear.Rect> into) {
        if (!TransmissionOverlay.active()) {
            return;
        }
        Panel panel = panel(client.font, guiWidth, guiHeight);
        into.add(new HudKeepClear.Rect("transmission", panel.left(), panel.top(), panel.width(), panel.height()));
    }

    /** Where the panel is and the lines of the whole text in it, which is sized for all of it so the letters appear in a panel that does not grow. */
    private record Panel(List<String> lines, int left, int top, int width, int height) {}

    private static Panel panel(Font font, int guiWidth, int guiHeight) {
        CrtTuning tuning = CrtTuning.current();
        TransmissionLook look = TransmissionLook.current();
        int padding = tuning.padding();
        int width = Math.min(look.maxWidth(), guiWidth - 2 * look.screenMargin());
        int step = font.lineHeight + tuning.lineSpacing();
        List<String> lines = wrap(font, TransmissionOverlay.bodyFull(), width - 2 * padding);
        int height = 2 * padding + step + look.headerGap() + lines.size() * step;
        int top = Math.max(look.screenMargin(), Math.round(guiHeight * (float) look.centerY() - height / 2f));
        return new Panel(lines, (guiWidth - width) / 2, top, width, height);
    }

    public static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        if (!TransmissionOverlay.active()) {
            return;
        }
        CrtTuning tuning = CrtTuning.current();
        TransmissionLook look = TransmissionLook.current();
        Font font = Minecraft.getInstance().font;
        int padding = tuning.padding();
        int step = font.lineHeight + tuning.lineSpacing();

        Panel panel = panel(font, graphics.guiWidth(), graphics.guiHeight());
        List<String> lines = panel.lines();
        int left = panel.left();
        int top = panel.top();
        int right = left + panel.width();
        int bottom = top + panel.height();

        graphics.fill(left, top, right, bottom, look.panelFillColor());
        CrtDraw.border(graphics, left, top, right, bottom, tuning.dimColor());

        int x = left + padding;
        int y = top + padding;
        int headerColor = TransmissionOverlay.headerColor(
                TransmissionOverlay.transmission().orElseThrow().framing());
        CrtDraw.glowText(graphics, font, TransmissionOverlay.headerShown(), x, y, headerColor);
        y += step + look.headerGap();

        // The panel is sized for the whole text, and the letters appear in it, so the lines never re-wrap as they type.
        // Each line is found in the whole text to know how much of it is typed: the wrap drops the line breaks and the
        // spaces it breaks at.
        String full = TransmissionOverlay.bodyFull();
        int typed = TransmissionOverlay.bodyShown().length();
        int from = 0;
        int lastX = x;
        int lastY = y;
        for (String line : lines) {
            int start = full.indexOf(line, from);
            if (start < 0 || start >= typed) {
                break;
            }
            from = start + line.length();
            String shown = line.substring(0, Math.min(line.length(), typed - start));
            CrtDraw.glowText(graphics, font, shown, x, y, look.textColor());
            lastX = x + font.width(shown);
            lastY = y;
            y += step;
        }
        if (!TransmissionOverlay.typed()) {
            graphics.fill(lastX + 1, lastY, lastX + font.width("W"), lastY + font.lineHeight - 1, look.textColor());
        }

        graphics.enableScissor(left, top, right, bottom);
        CrtDraw.scanlines(graphics, graphics.guiWidth(), graphics.guiHeight());
        graphics.disableScissor();
    }

    private static List<String> wrap(Font font, String text, int width) {
        return font.getSplitter().splitLines(FormattedText.of(text), width, Style.EMPTY).stream()
                .map(FormattedText::getString)
                .toList();
    }
}
