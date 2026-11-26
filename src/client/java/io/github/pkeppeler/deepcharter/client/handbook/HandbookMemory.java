package io.github.pkeppeler.deepcharter.client.handbook;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Where the player left the handbook: the page, and the picked step of each chapter's flowchart. The book opens there
 * next time. {@link #SESSION} is the one a player's own opening uses; it is forgotten on leaving a world. Client thread.
 */
public final class HandbookMemory {
    /** What {@link HandbookScreen#open} uses. */
    static final HandbookMemory SESSION = new HandbookMemory();

    /** The page left open, by what it is rather than its place in the book, which moves when a chapter unlocks its text pages. */
    private String page = "";

    private int chapter;
    /** The step picked on each chapter's index card, by chapter number; a chapter with none shows its current step. */
    private final Map<Integer, Identifier> picks = new HashMap<>();

    /** Remembers {@code page} as the one the book was left on. */
    void remember(HandbookPage page) {
        this.page = key(page);
        this.chapter = HandbookPage.chapterNumber(page);
    }

    /**
     * The index in {@code pages} of the page left open: the same page, else the map of the same chapter if its page is gone, else the
     * cover.
     */
    int pageIn(List<HandbookPage> pages) {
        for (int index = 0; index < pages.size(); index++) {
            if (key(pages.get(index)).equals(page)) {
                return index;
            }
        }
        for (int index = 0; index < pages.size(); index++) {
            if (chapter > 0 && pages.get(index) instanceof HandbookPage.Chapter map && map.number() == chapter) {
                return index;
            }
        }
        return 0;
    }

    private static String key(HandbookPage page) {
        return switch (page) {
            case HandbookPage.Cover cover -> "cover";
            case HandbookPage.Slip slip -> "slip";
            case HandbookPage.Letter letter -> "letter";
            case HandbookPage.Contents contents -> "contents:" + contents.part();
            case HandbookPage.Controls controls -> "controls:" + controls.part();
            case HandbookPage.ChapterText text -> "text:" + text.number() + ":" + text.part();
            case HandbookPage.ChapterControls controls -> "controls-of:" + controls.number();
            case HandbookPage.Chapter map -> "map:" + map.number();
            case HandbookPage.Pencil pencil -> "pencil:" + pencil.part();
            case HandbookPage.Appendix appendix -> "appendix";
            case HandbookPage.Contract contract -> "contract:" + contract.part();
        };
    }

    /** The step picked on chapter {@code number}'s card, or null when the player has picked none. */
    @Nullable
    Identifier picked(int number) {
        return picks.get(number);
    }

    /** Remembers {@code step} as the one picked on chapter {@code number}'s card. */
    void pick(int number, Identifier step) {
        picks.put(number, step);
    }

    /** Forgets everything: the next opening is at the cover. */
    public static void forgetSession() {
        SESSION.page = "";
        SESSION.chapter = 0;
        SESSION.picks.clear();
    }
}
