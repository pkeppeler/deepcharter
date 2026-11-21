package io.github.pkeppeler.deepcharter.client.handbook;

import java.util.HashMap;
import java.util.Map;

/**
 * Where the player left the handbook: the page, and the pan, zoom and picked step of each chapter's flowchart. The book opens there
 * next time. {@link #SESSION} is the one a player's own opening uses; it is forgotten on leaving a world. Client thread.
 */
public final class HandbookMemory {
    /** What {@link HandbookScreen#open} uses. */
    static final HandbookMemory SESSION = new HandbookMemory();

    private int page;
    private final Map<Integer, MapView> views = new HashMap<>();

    int page() {
        return page;
    }

    void setPage(int page) {
        this.page = page;
    }

    /** The flowchart state of chapter {@code number}, made on first use. */
    MapView view(int number) {
        return views.computeIfAbsent(number, key -> new MapView());
    }

    /** Forgets everything: the next opening is at the cover. */
    public static void forgetSession() {
        SESSION.page = 0;
        SESSION.views.clear();
    }
}
