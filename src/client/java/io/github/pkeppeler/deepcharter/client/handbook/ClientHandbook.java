package io.github.pkeppeler.deepcharter.client.handbook;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.Identifier;

/**
 * The directives the server last said this player's charter has completed; on no charter, the ones the player completed
 * alone. The handbook screen (#66) reads it. Read and written on the client thread.
 */
public final class ClientHandbook {
    /** More steps than this finishing in one sync is a charter change handing over its progress, not a deed: no popups. */
    public static final int MOST_TOASTED_AT_ONCE = 2;

    private static Set<Identifier> completed = Set.of();
    /** Whether a sync has arrived in this world: the first one is everything done so far, not news. */
    private static boolean synced;

    private ClientHandbook() {}

    public static Set<Identifier> completed() {
        return completed;
    }

    /** Takes the set the server sent, and returns the steps it newly completes that deserve a popup, in id order. */
    public static List<Identifier> set(Collection<Identifier> newCompleted) {
        Set<Identifier> next = Set.copyOf(newCompleted);
        List<Identifier> fresh = !synced
                ? List.of()
                : next.stream().filter(id -> !completed.contains(id)).sorted().toList();
        completed = next;
        synced = true;
        return fresh.size() > MOST_TOASTED_AT_ONCE ? List.of() : fresh;
    }

    /** Forgets the progress and that a sync ever arrived: the client has left its world. */
    public static void forget() {
        completed = Set.of();
        synced = false;
    }
}
