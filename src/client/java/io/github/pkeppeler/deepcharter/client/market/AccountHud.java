package io.github.pkeppeler.deepcharter.client.market;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.charter.ClientCharter;
import io.github.pkeppeler.deepcharter.client.pod.CockpitHud;
import io.github.pkeppeler.deepcharter.client.theme.HudLook;
import io.github.pkeppeler.deepcharter.client.ui.HudKeepClear;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The charter's name and balance, at the left edge halfway down so it clears the pod readout and the altimeter. Shown while on a charter, except in the pilot's
 * seat under a cockpit style, where it is a line of the cockpit's readout plate.
 */
public final class AccountHud {
    private static final Identifier ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "account");

    private AccountHud() {}

    public static void init() {
        HudElementRegistry.addLast(ID, AccountHud::extract);
        HudKeepClear.register(AccountHud::keepClear);
    }

    /** The line, left edge halfway down, while it is drawn. */
    private static void keepClear(Minecraft client, int guiWidth, int guiHeight, List<HudKeepClear.Rect> into) {
        Optional<Component> text = text();
        if (text.isEmpty() || CockpitHud.showsReadout(client, guiWidth, guiHeight)) {
            return;
        }
        HudLook look = HudLook.current();
        into.add(new HudKeepClear.Rect(
                "account line",
                look.accountMargin(),
                (int) (guiHeight * look.accountY()),
                client.font.width(text.get()),
                client.font.lineHeight));
    }

    /** The text shown, or empty when the player is on no charter. Public so tests can check it without reading pixels. */
    public static Optional<Component> text() {
        return ClientCharter.view()
                .map(charter -> Component.translatable("hud.deepcharter.account", charter.name(), charter.balance()));
    }

    private static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Optional<Component> text = text();
        if (text.isEmpty() || CockpitHud.showsReadout(Minecraft.getInstance())) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        HudLook look = HudLook.current();
        graphics.text(
                font,
                text.get(),
                look.accountMargin(),
                (int) (graphics.guiHeight() * look.accountY()),
                look.accountColor());
    }
}
