package io.github.pkeppeler.deepcharter.client.handbook.catalogue;

import com.google.common.base.Splitter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/** Breaks a name into lines of a width, so no name is ever cut short. */
public final class TextWrap {
    private TextWrap() {}

    /**
     * {@code text} in lines no wider than {@code room} by {@code width}: broken at spaces, and a word wider than {@code room} broken
     * between its letters. Always at least one line.
     */
    public static List<String> lines(ToIntFunction<String> width, String text, int room) {
        List<String> lines = new ArrayList<>();
        String line = "";
        for (String word : Splitter.on(' ').omitEmptyStrings().split(text)) {
            String joined = line.isEmpty() ? word : line + " " + word;
            if (width.applyAsInt(joined) <= room) {
                line = joined;
                continue;
            }
            if (!line.isEmpty()) {
                lines.add(line);
            }
            line = word;
            while (width.applyAsInt(line) > room && line.length() > 1) {
                int cut = line.length() - 1;
                while (cut > 1 && width.applyAsInt(line.substring(0, cut)) > room) {
                    cut--;
                }
                lines.add(line.substring(0, cut));
                line = line.substring(cut);
            }
        }
        lines.add(line);
        return lines;
    }
}
