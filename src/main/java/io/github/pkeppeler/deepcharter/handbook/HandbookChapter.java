package io.github.pkeppeler.deepcharter.handbook;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;

/**
 * One chapter of the Employee Handbook, loaded from {@code data/<namespace>/deepcharter/handbook_chapter/<name>.json}. Chapters
 * show in {@code order}, ties broken by id. Each directive is completed by the advancement with the same id: see
 * {@link Directives}.
 *
 * @param order      where the chapter stands in the handbook, lowest first
 * @param title      the chapter title
 * @param directives what the chapter asks of the crew, in the order shown; never empty, ids unique
 */
public record HandbookChapter(int order, Component title, List<Entry> directives) {
	public static final Codec<HandbookChapter> CODEC = RecordCodecBuilder.<HandbookChapter>create(instance -> instance.group(
			Codec.INT.fieldOf("order").forGetter(HandbookChapter::order),
			ComponentSerialization.CODEC.fieldOf("title").forGetter(HandbookChapter::title),
			Entry.CODEC.listOf().fieldOf("directives").forGetter(HandbookChapter::directives)).apply(instance, HandbookChapter::new))
			.validate(HandbookChapter::validate);

	public HandbookChapter {
		directives = List.copyOf(directives);
	}

	/** One directive: its id, which is also the id of its advancement, and the line the handbook shows. */
	public record Entry(Identifier id, Component text) {
		public static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Identifier.CODEC.fieldOf("id").forGetter(Entry::id),
				ComponentSerialization.CODEC.fieldOf("text").forGetter(Entry::text)).apply(instance, Entry::new));
	}

	private static DataResult<HandbookChapter> validate(HandbookChapter chapter) {
		if (chapter.directives.isEmpty()) {
			return DataResult.error(() -> "a handbook chapter needs at least one directive");
		}
		Set<Identifier> seen = new HashSet<>();
		for (Entry entry : chapter.directives) {
			if (!seen.add(entry.id())) {
				return DataResult.error(() -> "directive " + entry.id() + " appears twice in one chapter");
			}
		}
		return DataResult.success(chapter);
	}
}
