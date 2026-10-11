package io.github.pkeppeler.deepcharter.test.support;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.client.handbook.ClientHandbook;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookPage;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookPages;
import io.github.pkeppeler.deepcharter.handbook.HandbookChapter;
import io.github.pkeppeler.deepcharter.handbook.HandbookChapters;

/**
 * The pages {@code HandbookScreen.open} builds, without the test pack's sample chapter (it is never finished, which would classify every real
 * chapter after it): the shipped chapters and the progress the server synced. Client thread.
 */
public final class ShippedHandbook {
	private ShippedHandbook() {
	}

	public static List<HandbookPage> pages(Minecraft client) {
		Map<Identifier, HandbookChapter> shipped = new LinkedHashMap<>();
		HandbookChapters.all(client.getConnection().registryAccess()).stream()
				.filter(entry -> !entry.key().identifier().getPath().equals("sample"))
				.forEach(entry -> shipped.put(entry.key().identifier(), entry.value()));
		return HandbookPages.of(shipped, ClientHandbook.completed());
	}
}
