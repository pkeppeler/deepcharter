package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.client.ui.CrtButton;
import io.github.pkeppeler.deepcharter.client.upgrade.UpgradeScreen;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import java.lang.reflect.Field;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Which track the upgrade screen shows as chosen. The choice shows as the lit track button ({@code CrtButton.marked}), which the layout
 * that builds the chosen track's rows sets: the test reads the mark from the widget tree, by reflection, so production keeps no member for
 * it. Client thread.
 */
public final class UpgradeShown {
    private static final Field MARKED = markedField();

    private UpgradeShown() {}

    /** True when the lit track button is the one of {@code track}: the screen lists that track's tiers. */
    public static boolean isChosen(Screen screen, ComponentTrack track) {
        String label = Component.translatable("deepcharter.upgrade.track." + track.id())
                        .getString() + "  T";
        return ClientChecks.buttons(screen).stream()
                .anyMatch(button -> button instanceof CrtButton crt
                        && marked(crt)
                        && crt.getMessage().getString().startsWith(label));
    }

    /** Scrolls the upgrade screen's track list so that the button of {@code track} is shown (the list scrolls when the tracks do not fit). Client thread. */
    public static void reveal(UpgradeScreen screen, ComponentTrack track) {
        ScreenScroll.scrollTo(screen, track.ordinal());
    }

    private static boolean marked(CrtButton button) {
        try {
            return MARKED.getBoolean(button);
        } catch (IllegalAccessException e) {
            throw new LinkageError("cannot read CrtButton.marked", e);
        }
    }

    private static Field markedField() {
        try {
            Field field = CrtButton.class.getDeclaredField("marked");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) {
            throw new LinkageError("CrtButton has no field marked; the upgrade tests read the chosen track by name", e);
        }
    }
}
