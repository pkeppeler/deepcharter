package io.github.pkeppeler.deepcharter.client.market;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.charter.ClientCharter;
import io.github.pkeppeler.deepcharter.client.pod.CockpitHud;
import io.github.pkeppeler.deepcharter.client.theme.HudLook;
import io.github.pkeppeler.deepcharter.client.ui.HudKeepClear;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The charter's name and balance at the left edge, {@code accountY} down: below the pod readout and the cockpit's
 * gauge titles, between vanilla's subtitle and its action bar. Shown while on a charter, in the same spot on foot and
 * in the pilot's seat (#1642), so the money never moves.
 */
public final class AccountHud {
    private static final Identifier ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "account");
    /** The account line's lang key. */
    public static final String KEY = "hud.deepcharter.account";

    /** The balance alone, which the line falls back to where the whole of it would run into the cockpit. */
    public static final String MONEY_KEY = "hud.deepcharter.account_money";

    /**
     * Vanilla's armour and air row, the top of its status rows over the hotbar, is this far above the bottom of the GUI
     * (26.3's {@code Hud}). On a short GUI the line rises to stay above it.
     */
    private static final int VANILLA_STATUS_TOP = 49;

    /** Vanilla's crosshair sprite, this many GUI pixels square in the middle of the GUI. */
    private static final int CROSSHAIR = 15;

    private AccountHud() {}

    public static void init() {
        HudElementRegistry.addLast(ID, AccountHud::extract);
        HudKeepClear.register(AccountHud::keepClear);
    }

    /**
     * Where {@code text} is drawn on a GUI {@code guiHeight} tall: the left edge, {@code accountY} down, one line, and
     * no lower than a pixel above vanilla's status rows, which a short GUI (213 x 120) brings up to it. What is drawn
     * and what is kept clear are this one box.
     */
    public static HudKeepClear.Rect box(Font font, Component text, int guiHeight) {
        HudLook look = HudLook.current();
        int top = Math.min((int) (guiHeight * look.accountY()), guiHeight - VANILLA_STATUS_TOP - font.lineHeight - 1);
        return new HudKeepClear.Rect("account line", look.accountMargin(), top, font.width(text), font.lineHeight);
    }

    /**
     * The line as it is drawn: {@code full}, the charter's name and balance, or {@code money}, the balance alone, where
     * the whole line would run into one of {@code covered} (the cockpit's plate or bezel on a narrow GUI). The line
     * keeps its spot either way, so the money never moves (#1642).
     */
    public static Component fitted(
            Font font, Component full, Component money, int guiHeight, List<HudKeepClear.Rect> covered) {
        HudKeepClear.Rect whole = box(font, full, guiHeight);
        return covered.stream().anyMatch(whole::overlaps) ? money : full;
    }

    /** The line drawn now, or empty when the player is on no charter. */
    private static Optional<Component> shown(Minecraft client, int guiWidth, int guiHeight) {
        return text().flatMap(full -> ClientCharter.view()
                .map(charter -> fitted(
                        client.font, full, money(charter.balance()), guiHeight, covered(client, guiWidth, guiHeight))));
    }

    /**
     * What the whole line must not run into, or it gives way to the balance: the cockpit, and the crosshair, which a
     * short GUI (213 x 120) brings down to the line.
     */
    public static List<HudKeepClear.Rect> covered(Minecraft client, int guiWidth, int guiHeight) {
        List<HudKeepClear.Rect> covered = new ArrayList<>(CockpitHud.covers(client, guiWidth, guiHeight));
        covered.add(crosshair(guiWidth, guiHeight));
        return covered;
    }

    /** Vanilla's crosshair on a GUI {@code guiWidth} x {@code guiHeight}. */
    public static HudKeepClear.Rect crosshair(int guiWidth, int guiHeight) {
        return new HudKeepClear.Rect(
                "crosshair", (guiWidth - CROSSHAIR) / 2, (guiHeight - CROSSHAIR) / 2, CROSSHAIR, CROSSHAIR);
    }

    private static void keepClear(Minecraft client, int guiWidth, int guiHeight, List<HudKeepClear.Rect> into) {
        shown(client, guiWidth, guiHeight).ifPresent(text -> into.add(box(client.font, text, guiHeight)));
    }

    /**
     * The whole line, the charter's name and balance, or empty when the player is on no charter. Public so tests can
     * check it without reading pixels.
     */
    public static Optional<Component> text() {
        return ClientCharter.view().map(charter -> whole(charter.name(), charter.balance()));
    }

    /** The whole line for a charter named {@code name} with {@code balance} dollars: "Name  $12,345". */
    public static Component whole(String name, long balance) {
        return Component.translatable(KEY, name, grouped(balance));
    }

    /** The balance alone, "$12,345", grouped as in the whole line so the money reads the same in both. */
    public static Component money(long balance) {
        return Component.translatable(MONEY_KEY, grouped(balance));
    }

    private static String grouped(long balance) {
        // Locale.US so the thousands separator is the same on every machine.
        return String.format(Locale.US, "%,d", balance);
    }

    public static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft client = Minecraft.getInstance();
        shown(client, graphics.guiWidth(), graphics.guiHeight()).ifPresent(text -> {
            HudKeepClear.Rect box = box(client.font, text, graphics.guiHeight());
            graphics.text(client.font, text, box.x(), box.y(), HudLook.current().accountColor());
        });
    }
}
