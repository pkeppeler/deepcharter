package io.github.pkeppeler.deepcharter.handbook;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

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
     * One directive: its id, which is also the id of its advancement, the line the handbook shows, and what the step
     * asks the player to obtain or craft, one slot each (never the ingredients of one). {@code targets} is for the
     * quest map's index card only, where a click opens the shown item's recipe: nothing here takes the items.
     */
    public record Entry(Identifier id, Component text, List<TargetSlot> targets) {
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        Identifier.CODEC.fieldOf("id").forGetter(Entry::id),
                        ComponentSerialization.CODEC.fieldOf("text").forGetter(Entry::text),
                        TargetSlot.CODEC
                                .listOf()
                                .optionalFieldOf("targets", List.of())
                                .forGetter(Entry::targets))
                .apply(instance, Entry::new));

        public Entry {
            targets = List.copyOf(targets);
        }
    }

    /**
     * What one slot of an index card asks for: {@code count} of one item ({@link Target}), or {@code count} of any one
     * of several ({@link AnyOf}), like a BetterQuesting task.
     */
    public sealed interface TargetSlot permits Target, AnyOf {
        Codec<TargetSlot> CODEC = RecordCodecBuilder.<RawSlot>create(instance -> instance.group(
                                Identifier.CODEC.optionalFieldOf("item").forGetter(RawSlot::item),
                                Choice.CODEC.listOf().optionalFieldOf("any_of").forGetter(RawSlot::anyOf),
                                Codec.intRange(1, Integer.MAX_VALUE)
                                        .fieldOf("count")
                                        .forGetter(RawSlot::count))
                        .apply(instance, RawSlot::new))
                .flatXmap(RawSlot::toSlot, slot -> DataResult.success(RawSlot.of(slot)));

        int count();

        /** The items the slot accepts, in the order the card cycles through them; empty when none is registered. */
        List<Item> items();
    }

    /** {@code count} of {@code item}, which the player is asked to obtain or craft, for the step that lists it. */
    public record Target(Identifier item, int count) implements TargetSlot {
        @Override
        public List<Item> items() {
            return new Choice(item, false).items();
        }
    }

    /** {@code count} held in all of any of {@code choices}: the step is done by whichever the player gets first. */
    public record AnyOf(List<Choice> choices, int count) implements TargetSlot {
        public AnyOf {
            choices = List.copyOf(choices);
        }

        @Override
        public List<Item> items() {
            Set<Item> items = new LinkedHashSet<>();
            choices.forEach(choice -> items.addAll(choice.items()));
            return List.copyOf(items);
        }
    }

    /** An item id, or an item tag when {@code tag}: written {@code "minecraft:torch"} or {@code "#minecraft:logs"}. */
    public record Choice(Identifier id, boolean tag) {
        public static final Codec<Choice> CODEC = Codec.STRING.comapFlatMap(
                text -> text.startsWith("#")
                        ? Identifier.read(text.substring(1)).map(id -> new Choice(id, true))
                        : Identifier.read(text).map(id -> new Choice(id, false)),
                choice -> (choice.tag ? "#" : "") + choice.id);

        /** The items this choice names: the item, or each member of the tag. */
        public List<Item> items() {
            if (!tag) {
                return BuiltInRegistries.ITEM.getOptional(id).map(List::of).orElse(List.of());
            }
            return BuiltInRegistries.ITEM
                    .get(TagKey.create(Registries.ITEM, id))
                    .map(members -> members.stream().map(Holder::value).toList())
                    .orElse(List.of());
        }
    }

    /** A slot as written in a chapter: {@code item} or {@code any_of}, one of them, and a {@code count}. */
    private record RawSlot(Optional<Identifier> item, Optional<List<Choice>> anyOf, int count) {
        DataResult<TargetSlot> toSlot() {
            if (item.isPresent() == anyOf.isPresent()) {
                return DataResult.error(() -> "a target needs exactly one of item and any_of");
            }
            if (anyOf.isPresent()) {
                return anyOf.get().isEmpty()
                        ? DataResult.error(() -> "any_of needs at least one choice")
                        : DataResult.success(new AnyOf(anyOf.get(), count));
            }
            return DataResult.success(new Target(item.get(), count));
        }

        static RawSlot of(TargetSlot slot) {
            return switch (slot) {
                case Target target -> new RawSlot(Optional.of(target.item()), Optional.empty(), target.count());
                case AnyOf any -> new RawSlot(Optional.empty(), Optional.of(any.choices()), any.count());
            };
        }
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
