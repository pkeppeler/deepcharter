package io.github.pkeppeler.deepcharter.client.handbook;

import io.github.pkeppeler.deepcharter.handbook.HandbookChapter;
import io.github.pkeppeler.deepcharter.handbook.HandbookVisibility;
import io.github.pkeppeler.deepcharter.handbook.PencilPath;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.Identifier;

/** One page of the handbook, in the order it is bound. The Notes tab is not a page: the screen draws it apart. */
public sealed interface HandbookPage {
    /** The number of the chapter {@code page} belongs to, its text or its map, or 0 for any other page. */
    static int chapterNumber(HandbookPage page) {
        return switch (page) {
            case ChapterText text -> text.number();
            case Chapter chapter -> chapter.number();
            default -> 0;
        };
    }

    /** The cover. */
    record Cover() implements HandbookPage {}

    /** The issue slip: whom this copy was issued to. */
    record Slip() implements HandbookPage {}

    /** The Founder's letter. */
    record Letter() implements HandbookPage {}

    /**
     * One page of the table of contents. The contents flow over {@code parts} pages, so that every chapter has an entry on any size
     * of sheet. This page lists {@code count} chapters, from number {@code firstChapter} (counted from 1). {@code part} counts from 1.
     */
    record Contents(int part, int parts, int firstChapter, int count) implements HandbookPage {}

    /**
     * One page of the controls, after the contents: sheet {@code part} of {@code parts}, each counted from 1 ({@link
     * HandbookControls}).
     */
    record Controls(int part, int parts) implements HandbookPage {}

    /**
     * A chapter. {@code number} counts from 1 in handbook order. {@code visibility} says how much of it the player may read: a
     * {@link HandbookVisibility#CLASSIFIED} chapter still has its page, but the screen draws no title and no directive.
     * {@code completed} holds the directives of this chapter that the player's charter has completed.
     */
    record Chapter(
            int number,
            Identifier id,
            HandbookChapter chapter,
            HandbookVisibility visibility,
            Set<Identifier> completed)
            implements HandbookPage {
        public Chapter {
            completed = Set.copyOf(completed);
        }

        /** Whether every directive of the chapter is completed. */
        public boolean isComplete() {
            return chapter.directives().stream().allMatch(entry -> completed.contains(entry.id()));
        }
    }

    /**
     * One page of the text of a full chapter, before the chapter's own page of directives. {@code part} counts from 1 of
     * {@code parts}.
     */
    record ChapterText(int number, Identifier id, HandbookChapter chapter, int part, int parts)
            implements HandbookPage {}

    /** One pencil path on the pencil sheet: the path, and whether the charter has reached the place it points to. */
    record PencilEntry(PencilPath path, boolean done) {}

    /**
     * A pencil sheet, after the last chapter: earlier miners' paths from the Notes the charter has found, which are not Directives.
     * The paths flow over {@code parts} sheets, so that every one has room on any size of sheet. {@code part} counts from 1.
     */
    record Pencil(int part, int parts, List<PencilEntry> entries) implements HandbookPage {
        public Pencil {
            entries = List.copyOf(entries);
        }
    }

    /** The end page: the end of the official chapters. */
    record Appendix() implements HandbookPage {}

    /** One page of Appendix A, the employment contract, with its redactions. {@code part} counts from 1 of {@code parts}. */
    record Contract(int part, int parts) implements HandbookPage {}
}
