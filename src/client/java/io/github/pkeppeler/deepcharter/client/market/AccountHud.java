package io.github.pkeppeler.deepcharter.client.market;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.charter.ClientCharter;
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
 * The charter's name and balance, at the left edge halfway down so it clears the pod readout and the altimeter. Shown
 * while on a charter, in the same spot on foot and in the pilot's seat (#1642), so the money never moves.
 */
public final class AccountHud {
    private static final Identifier ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "account");
    /** The account line's lang key. */
    public static final String KEY = "hud.deepcharter.account";

    private AccountHud() {}

    public static void init() {
        HudElementRegistry.addLast(ID, AccountHud::extract);
        HudKeepClear.register(AccountHud::keepClear);
    }

    /**
     * Where {@code text} is drawn on a GUI {@code guiHeight} tall: the left edge, {@code accountY} down, one line. What
     * is drawn and what is kept clear are this one box.
     */
    public static HudKeepClear.Rect box(Font font, Component text, int guiHeight) {
        HudLook look = HudLook.current();
        return new HudKeepClear.Rect(
                "account line",
                look.accountMargin(),
                (int) (guiHeight * look.accountY()),
                font.width(text),
                font.lineHeight);
    }

    private static void keepClear(Minecraft client, int guiWidth, int guiHeight, List<HudKeepClear.Rect> into) {
        text().ifPresent(text -> into.add(box(client.font, text, guiHeight)));
    }

    /** The text shown, or empty when the player is on no charter. Public so tests can check it without reading pixels. */
    public static Optional<Component> text() {
        return ClientCharter.view().map(charter -> Component.translatable(KEY, charter.name(), charter.balance()));
    }

    public static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft client = Minecraft.getInstance();
        text().ifPresent(text -> {
            HudKeepClear.Rect box = box(client.font, text, graphics.guiHeight());
            graphics.text(client.font, text, box.x(), box.y(), HudLook.current().accountColor());
        });
    }
}
