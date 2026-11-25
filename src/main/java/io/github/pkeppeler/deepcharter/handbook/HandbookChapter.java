package io.github.pkeppeler.deepcharter.handbook;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    public static final Codec<HandbookChapter> CODEC = RecordCodecBuilder.<HandbookChapter>create(
                    instance -> instance.group(
                                    Codec.INT.fieldOf("order").forGetter(HandbookChapter::order),
                                    ComponentSerialization.CODEC
                                            .fieldOf("title")
                                            .forGetter(HandbookChapter::title),
                                    Entry.CODEC.listOf().fieldOf("directives").forGetter(HandbookChapter::directives))
                            .apply(instance, HandbookChapter::new))
            .validate(HandbookChapter::validate);

    public HandbookChapter {
        directives = List.copyOf(directives);
    }

    /**
     * One directive: its id, which is also the id of its advancement, the line the handbook shows, and the items the
     * step asks the player to obtain or craft, each with a count (never the ingredients of one). {@code targets} is for
     * the quest map's index card only, where a click opens the item's recipe: nothing here takes the items.
     */
    public record Entry(Identifier id, Component text, List<Target> targets) {
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        Identifier.CODEC.fieldOf("id").forGetter(Entry::id),
                        ComponentSerialization.CODEC.fieldOf("text").forGetter(Entry::text),
                        Target.CODEC
                                .listOf()
                                .optionalFieldOf("targets", List.of())
                                .forGetter(Entry::targets))
                .apply(instance, Entry::new));

        public Entry {
            targets = List.copyOf(targets);
        }
    }

    /** {@code count} of {@code item}, which the player is asked to obtain or craft, for the step that lists it. */
    public record Target(Identifier item, int count) {
        public static final Codec<Target> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        Identifier.CODEC.fieldOf("item").forGetter(Target::item),
                        Codec.intRange(1, Integer.MAX_VALUE).fieldOf("count").forGetter(Target::count))
                .apply(instance, Target::new));
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
