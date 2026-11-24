package io.github.pkeppeler.deepcharter.test.mixin;

import io.github.pkeppeler.deepcharter.test.support.EvidencePins;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Leaves out of the HUD the two things in it that fade or ease on client ticks: the screen-edge vignette ({@link
 * EvidencePins#vignetteHidden}) and the name of the held item that shows for two seconds after a slot changes ({@link
 * EvidencePins#itemNameHidden}). While {@link EvidencePins#skyHidden} is on it writes {@link
 * EvidencePins#SKY_HIDDEN_CAPTION} in the bottom-left corner, hidden HUD or not, in the look of a text display's label:
 * white text on its quarter-black backing. Test code only.
 */
@Mixin(value = Hud.class, remap = false)
public abstract class HudMixin {
    /** A text display's default backing: black at a quarter opacity. */
    private static final int CAPTION_BACKING = 0x40000000;

    private static final int CAPTION_TEXT = 0xFFFFFFFF;
    /** The caption's distance from the frame's edges, and the backing's margin round the text, in GUI pixels. */
    private static final int CAPTION_INSET = 4;

    private static final int CAPTION_PAD = 1;

    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void deepcharter$skyCaption(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo ci) {
        if (!EvidencePins.skyHidden) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        String caption = EvidencePins.SKY_HIDDEN_CAPTION;
        int x = CAPTION_INSET;
        int y = graphics.guiHeight() - CAPTION_INSET - font.lineHeight;
        graphics.fill(
                x - CAPTION_PAD,
                y - CAPTION_PAD,
                x + font.width(caption) + CAPTION_PAD,
                y + font.lineHeight,
                CAPTION_BACKING);
        graphics.text(font, caption, x, y, CAPTION_TEXT, false);
    }

    @Inject(method = "extractVignette", at = @At("HEAD"), cancellable = true)
    private void deepcharter$noVignette(CallbackInfo ci) {
        if (EvidencePins.vignetteHidden) {
            ci.cancel();
        }
    }

    @Inject(method = "extractSelectedItemName", at = @At("HEAD"), cancellable = true)
    private void deepcharter$noItemName(CallbackInfo ci) {
        if (EvidencePins.itemNameHidden) {
            ci.cancel();
        }
    }
}
