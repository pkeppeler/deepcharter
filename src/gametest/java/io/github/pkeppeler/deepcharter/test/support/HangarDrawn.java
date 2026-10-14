package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.test.support.DrawnText.Drawn;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The hangar terminal's price and advance lines as the screen draws them ({@link DrawnText}). A block opens with the text of its lang
 * key before the first argument and closes with the line that ends in the last word of the key's text after the last argument. The
 * screen must be laid out. Client thread.
 */
public final class HangarDrawn {
    private static final String ARGUMENT = "\u0000";
    private static final String PRICES_KEY = "screen.deepcharter.hangar.restore_prices";
    private static final int PRICES_ARGUMENTS = 5;
    private static final String ADVANCE_KEY = "screen.deepcharter.hangar.advance";
    private static final int ADVANCE_ARGUMENTS = 2;

    private HangarDrawn() {}

    /** The wrapped lines of the restore prices, top to bottom; empty when the screen draws none. */
    public static List<Drawn> priceLines(Screen screen) {
        return block(DrawnText.of(screen), PRICES_KEY, PRICES_ARGUMENTS);
    }

    /** The wrapped lines of the Company's advance, top to bottom; empty when the screen draws none. */
    public static List<Drawn> advanceLines(Screen screen) {
        return block(DrawnText.of(screen), ADVANCE_KEY, ADVANCE_ARGUMENTS);
    }

    private static List<Drawn> block(List<Drawn> drawn, String key, int arguments) {
        Object[] marks = new Object[arguments];
        Arrays.fill(marks, ARGUMENT);
        String pattern = Component.translatable(key, marks).getString();
        String start = pattern.substring(0, pattern.indexOf(ARGUMENT));
        String lastWord = lastWord(pattern.substring(pattern.lastIndexOf(ARGUMENT) + 1));
        int first = -1;
        for (int index = 0; index < drawn.size(); index++) {
            if (drawn.get(index).text().startsWith(start)) {
                first = index;
                break;
            }
        }
        if (first < 0) {
            return List.of();
        }
        for (int index = first; index < drawn.size(); index++) {
            if (drawn.get(index).text().endsWith(lastWord)) {
                return drawn.subList(first, index + 1);
            }
        }
        throw new AssertionError("the block '" + start + "...' opens but no drawn line ends in '" + lastWord + "': "
                + drawn.subList(first, drawn.size()));
    }

    private static String lastWord(String tail) {
        String trimmed = tail.trim();
        return trimmed.substring(trimmed.lastIndexOf(' ') + 1);
    }
}
