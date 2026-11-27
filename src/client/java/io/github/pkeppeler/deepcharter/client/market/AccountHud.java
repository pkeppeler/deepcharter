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
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The charter's name and balance, at the left edge halfway down so it clears the pod readout and the altimeter. Shown while on a charter, except in the pilot's
 * seat under a cockpit style, where it is a line of the cockpit's readout plate.
 */
public final class AccountHud {
    private static final Identifier ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "account");
    /** The account line's lang key: the cockpit's plate shows the line as a note ({@link io.github.pkeppeler.deepcharter.client.pod.PodStatusHud.Line#kind}). */
    public static final String KEY = "hud.deepcharter.account";

    private AccountHud() {}

    public static void init() {
        HudElementRegistry.addLast(ID, AccountHud::extract);
        HudKeepClear.register(AccountHud::keepClear);
    }

    /** The line and the box it covers, left edge halfway down; empty when it is not drawn. What is drawn and what is kept clear are this one box. */
    private static Optional<Line> line(Minecraft client, int guiWidth, int guiHeight) {
        Optional<Component> text = text();
        if (text.isEmpty() || CockpitHud.showsReadout(client, guiWidth, guiHeight)) {
            return Optional.empty();
        }
        HudLook look = HudLook.current();
        return Optional.of(new Line(
                text.get(),
                new HudKeepClear.Rect(
                        "account line",
                        look.accountMargin(),
                        (int) (guiHeight * look.accountY()),
                        client.font.width(text.get()),
                        client.font.lineHeight)));
    }

    private record Line(Component text, HudKeepClear.Rect box) {}

    private static void keepClear(Minecraft client, int guiWidth, int guiHeight, List<HudKeepClear.Rect> into) {
        line(client, guiWidth, guiHeight).ifPresent(line -> into.add(line.box()));
    }

    /** The text shown, or empty when the player is on no charter. Public so tests can check it without reading pixels. */
    public static Optional<Component> text() {
        return ClientCharter.view().map(charter -> Component.translatable(KEY, charter.name(), charter.balance()));
    }

    public static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft client = Minecraft.getInstance();
        line(client, graphics.guiWidth(), graphics.guiHeight())
                .ifPresent(line -> graphics.text(
                        client.font,
                        line.text(),
                        line.box().x(),
                        line.box().y(),
                        HudLook.current().accountColor()));
    }
}
