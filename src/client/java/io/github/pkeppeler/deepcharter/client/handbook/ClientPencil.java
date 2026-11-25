package io.github.pkeppeler.deepcharter.client.handbook;

import io.github.pkeppeler.deepcharter.handbook.PencilPath;
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

    /** Takes the set the server sent. A pencil sheet that is open inks in a path it completes at its next draw. */
    public static void set(Collection<Identifier> newCompleted) {
        completed = Set.copyOf(newCompleted);
    }

    /** Whether the charter has reached the place of {@code path}, as the server last said. */
    public static boolean isDone(PencilPath path) {
        return completed.contains(path.id());
    }

    /** The paths the sheet draws for the Notes in {@link ClientNotes}, each marked done when the charter has reached its place. */
    public static List<HandbookPage.PencilEntry> entries() {
        return PencilPaths.drawnFrom(
                        ClientNotes.entries().stream().map(HandbookNote::id).toList())
                .stream()
                .map(path -> new HandbookPage.PencilEntry(path, isDone(path)))
                .toList();
    }
}
