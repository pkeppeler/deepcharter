package io.github.pkeppeler.deepcharter.client.handbook;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the player left the handbook: the page, and the pan, zoom and picked step of each chapter's flowchart. The book opens there
 * next time. {@link #SESSION} is the one a player's own opening uses; it is forgotten on leaving a world. Client thread.
 */
public final class HandbookMemory {
    /** What {@link HandbookScreen#open} uses. */
    static final HandbookMemory SESSION = new HandbookMemory();

    /** The page left open, by what it is rather than its place in the book, which moves when a chapter unlocks its text pages. */
    private String page = "";

    private int chapter;
    private final Map<Integer, MapView> views = new HashMap<>();

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
            case HandbookPage.ChapterText text -> "text:" + text.number() + ":" + text.part();
            case HandbookPage.Chapter map -> "map:" + map.number();
            case HandbookPage.Appendix appendix -> "appendix";
            case HandbookPage.Contract contract -> "contract:" + contract.part();
        };
    }

    /** The flowchart state of chapter {@code number}, made on first use. */
    MapView view(int number) {
        return views.computeIfAbsent(number, key -> new MapView());
    }

    /** Forgets everything: the next opening is at the cover. */
    public static void forgetSession() {
        SESSION.page = "";
        SESSION.chapter = 0;
        SESSION.views.clear();
    }
}
