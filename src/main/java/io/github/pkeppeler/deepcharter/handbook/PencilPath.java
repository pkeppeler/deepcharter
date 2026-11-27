package io.github.pkeppeler.deepcharter.handbook;

import io.github.pkeppeler.deepcharter.DeepCharter;
import net.minecraft.resources.Identifier;

/**
 * One pencil path (ADR 0065): drawn from the Note numbered {@code fromNote} once a charter has found it, and complete when the
 * charter reaches {@code place}. It is not a Directive and gives nothing but the place. Its id is
 * {@code deepcharter:pencil/<name>}; what the sheet says about it is the language keys {@link #hintKey} and {@link #placeKey}.
 *
 * @param name     the path's name, lower case with underscores
 * @param fromNote the number of the Note that draws it (N07 is 7)
 * @param place    where it ends
 */
public record PencilPath(String name, int fromNote, PencilPlace place) {
    public PencilPath {
        if (!name.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException(
                    "a pencil path's name is lower case letters, digits and underscores: " + name);
        }
    }

    public Identifier id() {
        return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "pencil/" + name);
    }

    /** The Note that draws the path. */
    public Identifier from() {
        return Notes.id(fromNote);
    }

    /** What the earlier miner pencilled before the place is reached. */
    public String hintKey() {
        return "deepcharter.handbook.pencil." + name + ".hint";
    }

    /** What the sheet calls the place once it is reached. */
    public String placeKey() {
        return "deepcharter.handbook.pencil." + name + ".place";
    }
}
