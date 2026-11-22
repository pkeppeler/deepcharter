package io.github.pkeppeler.deepcharter.client.handbook;

import io.github.pkeppeler.deepcharter.handbook.PencilPaths;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.Identifier;

/**
 * The pencil paths the server last said this player's charter has completed; on no charter, the ones the player completed alone. A
 * path is drawn when its Note is among {@link ClientNotes}. The handbook screen reads it. Read and written on the client thread.
 */
public final class ClientPencil {
    private static Set<Identifier> completed = Set.of();

    private ClientPencil() {}

    static void set(Collection<Identifier> newCompleted) {
        completed = Set.copyOf(newCompleted);
    }

    /** The paths the sheet draws for the Notes in {@link ClientNotes}, each marked done when the charter has reached its place. */
    public static List<HandbookPage.PencilEntry> entries() {
        List<Identifier> notes =
                ClientNotes.entries().stream().map(HandbookNote::id).toList();
        return PencilPaths.ALL.stream()
                .filter(path -> notes.contains(path.from()))
                .map(path -> new HandbookPage.PencilEntry(path, completed.contains(path.id())))
                .toList();
    }
}
