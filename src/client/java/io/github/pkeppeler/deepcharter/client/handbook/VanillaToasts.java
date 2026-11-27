package io.github.pkeppeler.deepcharter.client.handbook;

import io.github.pkeppeler.deepcharter.client.handbook.mixin.ToastInstanceAccess;
import io.github.pkeppeler.deepcharter.client.handbook.mixin.ToastManagerAccess;
import io.github.pkeppeler.deepcharter.client.ui.HudKeepClear;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.Toast;

/**
 * The toasts that are not step popups (recipe, system and the like) take the right column's top slots, where a step popup that found
 * no room on the left would go. They are not a HUD part that draws itself, so this lists their slots for {@link HudKeepClear} (#1075).
 */
final class VanillaToasts {
    private VanillaToasts() {}

    static void init() {
        HudKeepClear.register(VanillaToasts::keepClear);
    }

    private static void keepClear(Minecraft client, int guiWidth, int guiHeight, List<HudKeepClear.Rect> into) {
        for (Object instance : ((ToastManagerAccess) client.gui.toastManager()).deepcharter$visibleToasts()) {
            ToastInstanceAccess access = (ToastInstanceAccess) instance;
            Toast toast = access.deepcharter$toast();
            if (!(toast instanceof StepToast)) {
                into.add(new HudKeepClear.Rect(
                        "vanilla toast",
                        Math.round(toast.xPos(guiWidth, 1f)),
                        Math.round(toast.yPos(access.deepcharter$firstSlotIndex())),
                        toast.width(),
                        toast.height()));
            }
        }
    }
}
