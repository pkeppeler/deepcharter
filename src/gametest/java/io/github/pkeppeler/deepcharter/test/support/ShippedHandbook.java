package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.client.handbook.ClientHandbook;
import io.github.pkeppeler.deepcharter.client.handbook.ClientPencil;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookPage;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookPages;
import io.github.pkeppeler.deepcharter.handbook.HandbookChapter;
import io.github.pkeppeler.deepcharter.handbook.HandbookChapters;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * The pages {@code HandbookScreen.open} builds, without the test pack's sample chapter (it is never finished, which would classify every real
 * chapter after it): the shipped chapters, the progress the server synced and the pencil paths of the Notes found. Client thread.
 */
public final class ShippedHandbook {
    private ShippedHandbook() {}

    public static List<HandbookPage> pages(Minecraft client) {
        return HandbookPages.withPencil(
                HandbookPages.of(chapters(client), ClientHandbook.completed()), ClientPencil.entries());
    }

    /** The pages with every shipped directive done, so that every chapter is full and binds all of its text pages. */
    public static List<HandbookPage> everyChapterFull(Minecraft client) {
        Map<Identifier, HandbookChapter> chapters = chapters(client);
        Set<Identifier> done = chapters.values().stream()
                .flatMap(chapter -> chapter.directives().stream())
                .map(HandbookChapter.Entry::id)
                .collect(Collectors.toSet());
        return HandbookPages.of(chapters, done);
    }

    private static Map<Identifier, HandbookChapter> chapters(Minecraft client) {
        Map<Identifier, HandbookChapter> shipped = new LinkedHashMap<>();
        HandbookChapters.all(Objects.requireNonNull(client.getConnection(), "the client is not connected")
                        .registryAccess())
                .stream()
                .filter(entry -> !entry.key().identifier().getPath().equals("sample"))
                .forEach(entry -> shipped.put(entry.key().identifier(), entry.value()));
        return shipped;
    }
}
