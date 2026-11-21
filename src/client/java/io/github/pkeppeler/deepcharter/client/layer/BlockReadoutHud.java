package io.github.pkeppeler.deepcharter.client.layer;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.theme.Colors;
import io.github.pkeppeler.deepcharter.client.theme.ReadoutLook;
import io.github.pkeppeler.deepcharter.layer.BlockReadouts;
import io.github.pkeppeler.deepcharter.layer.BlockReadouts.Readout;
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
    private static final ReadoutFade FADE = new ReadoutFade();
    /** Vanilla text with an alpha of 3 or less is drawn opaque, so a fade ends one step above it. */
    private static final int MIN_ALPHA = 4;

    private BlockReadoutHud() {}

    public static void init() {
        HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR, ID, BlockReadoutHud::extract);
        ClientTickEvents.END_CLIENT_TICK.register(BlockReadoutHud::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> FADE.reset());
    }

    /** What the crosshair rests on, as the readout says it: empty with no world, in a screen, or with the HUD hidden. */
    public static Optional<Readout> looked(Minecraft client) {
        if (!visible(client) || client.level == null || client.hitResult == null) {
            return Optional.empty();
        }
        return BlockReadouts.SHARED.resolve(client.level, client.hitResult);
    }

    /** The readout on screen, whatever its opacity: what a client test reads instead of pixels. */
    public static Optional<Readout> shown() {
        return FADE.shown();
    }

    private static boolean visible(Minecraft client) {
        return client.player != null && client.gui.screen() == null && !client.gui.hud.isHidden();
    }

    private static void tick(Minecraft client) {
        FADE.tick(looked(client), ReadoutLook.current());
    }

    private static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
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
        graphics.pose().translate(graphics.guiWidth() / 2f, graphics.guiHeight() / 2f + look.offsetY());
        graphics.pose().scale((float) look.scale(), (float) look.scale());
        centred(graphics, font, shown.get().name(), 0, Colors.withAlpha(look.nameColor(), alpha));
        shown.get()
                .hint()
                .ifPresent(hint -> centred(
                        graphics,
                        font,
                        hint,
                        font.lineHeight + look.lineGap(),
                        Colors.withAlpha(look.hintColor(), alpha)));
        graphics.pose().popMatrix();
    }

    private static void centred(GuiGraphicsExtractor graphics, Font font, Component text, int y, int color) {
        graphics.text(font, text, -font.width(text) / 2, y, color);
    }
}
