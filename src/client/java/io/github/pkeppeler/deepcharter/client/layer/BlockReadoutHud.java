package io.github.pkeppeler.deepcharter.client.layer;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.theme.Colors;
import io.github.pkeppeler.deepcharter.client.theme.ReadoutLook;
import io.github.pkeppeler.deepcharter.client.ui.HudKeepClear;
import io.github.pkeppeler.deepcharter.layer.BlockReadouts;
import io.github.pkeppeler.deepcharter.layer.BlockReadouts.Readout;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The block readout under the crosshair (#859): the name of the block the client's own crosshair hit and, for some blocks, a short hint on how to
 * deal with them. What it says is {@link BlockReadouts}'; when it shows and fades is {@link ReadoutFade}'s; how it looks is {@code theme/readout.json}.
 * Nothing shows while the HUD is hidden (F1) or a screen is open, and the lines sit at a fixed place under the crosshair, so a sweep of the view
 * moves nothing.
 */
public final class BlockReadoutHud {
    private static final Identifier ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "block_readout");
    private static final ReadoutFade<Readout> FADE = new ReadoutFade<>();
    /** Vanilla text with an alpha of 3 or less is drawn opaque, so a fade ends one step above it. */
    private static final int MIN_ALPHA = 4;

    private BlockReadoutHud() {}

    public static void init() {
        HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR, ID, BlockReadoutHud::extract);
        ClientTickEvents.END_CLIENT_TICK.register(BlockReadoutHud::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> FADE.reset());
        HudKeepClear.register(BlockReadoutHud::keepClear);
    }

    /**
     * The box of the lines under the crosshair on a GUI {@code guiWidth} x {@code guiHeight}, as the scale puts them: as wide as the wider
     * line, the name and the hint under it. What is drawn and what is kept clear are this one box.
     */
    private static HudKeepClear.Rect box(Font font, Readout readout, int guiWidth, int guiHeight) {
        ReadoutLook look = ReadoutLook.current();
        int widest = widest(font, readout);
        int height = font.lineHeight;
        if (readout.hint().isPresent()) {
            height += font.lineHeight + look.lineGap();
        }
        int width = (int) Math.ceil(widest * look.scale());
        return new HudKeepClear.Rect(
                "block readout", (guiWidth - width) / 2, guiHeight / 2 + look.offsetY(), width, (int)
                        Math.ceil(height * look.scale()));
    }

    private static int widest(Font font, Readout readout) {
        int widest = font.width(readout.name());
        return readout.hint().map(hint -> Math.max(widest, font.width(hint))).orElse(widest);
    }

    private static void keepClear(Minecraft client, int guiWidth, int guiHeight, List<HudKeepClear.Rect> into) {
        if (visible(client)) {
            shown().ifPresent(readout -> into.add(box(client.font, readout, guiWidth, guiHeight)));
        }
    }

    /** What the crosshair rests on, as the readout says it: empty with no world, in a screen, or with the HUD hidden. */
    public static Optional<Readout> looked(Minecraft client) {
        if (!visible(client) || client.player == null || client.level == null || client.hitResult == null) {
            return Optional.empty();
        }
        return BlockReadouts.SHARED.resolve(client.level, client.hitResult, client.player);
    }

    /** The readout on screen, whatever its opacity: what a client test reads instead of pixels. */
    public static Optional<Readout> shown() {
        return FADE.shown();
    }

    private static boolean visible(Minecraft client) {
        return client.player != null && client.gui.screen() == null && !client.gui.hud.isHidden();
    }

    /** Before a world there is nothing to look at, and the theme may not be loaded yet (the title screen ticks before the first resource reload). */
    private static void tick(Minecraft client) {
        if (client.player == null || client.level == null) {
            FADE.reset();
            return;
        }
        FADE.tick(looked(client), ReadoutLook.current());
    }

    public static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft client = Minecraft.getInstance();
        Optional<Readout> shown = shown();
        if (shown.isEmpty() || !visible(client)) {
            return;
        }
        int alpha = Math.round(FADE.alpha(deltaTracker.getGameTimeDeltaPartialTick(false)) * 255);
        if (alpha < MIN_ALPHA) {
            return;
        }
        ReadoutLook look = ReadoutLook.current();
        Font font = client.font;
        graphics.pose().pushMatrix();
        HudKeepClear.Rect box = box(font, shown.get(), graphics.guiWidth(), graphics.guiHeight());
        graphics.pose().translate(box.x(), box.y());
        graphics.pose().scale((float) look.scale(), (float) look.scale());
        int widest = widest(font, shown.get());
        centred(graphics, font, widest, shown.get().name(), 0, Colors.withAlpha(look.nameColor(), alpha));
        shown.get()
                .hint()
                .ifPresent(hint -> centred(
                        graphics,
                        font,
                        widest,
                        hint,
                        font.lineHeight + look.lineGap(),
                        Colors.withAlpha(look.hintColor(), alpha)));
        graphics.pose().popMatrix();
    }

    /** Whole-pixel centring from the box's integer origin, so the text stays inside the box kept clear. */
    private static void centred(
            GuiGraphicsExtractor graphics, Font font, int widest, Component text, int y, int color) {
        graphics.text(font, text, (widest - font.width(text)) / 2, y, color, true);
    }
}
