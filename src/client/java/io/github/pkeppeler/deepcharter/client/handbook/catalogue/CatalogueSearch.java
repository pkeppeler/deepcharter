package io.github.pkeppeler.deepcharter.client.handbook.catalogue;

import com.google.common.base.Splitter;
import java.util.Locale;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The catalogue's search. A query is words separated by spaces and an item must match every one: a word matches the item's name or the
 * path of its id, in any case, anywhere in it; a word that starts with {@code @} matches the start of the item's namespace instead
 * ({@code @deepcharter}). The catalogue holds a few dozen items, so this is a few lines and not EMI's search, which is built for
 * tens of thousands (ADR 0066 allowed copying it, and a copy would be the larger part of this feature).
 */
public final class CatalogueSearch {
    private static final Splitter WORDS = Splitter.onPattern("\\s+").omitEmptyStrings();

    private CatalogueSearch() {}

    public static boolean matches(String query, Item item) {
        String trimmed = query.strip();
        if (trimmed.isEmpty()) {
            return true;
        }
        Identifier id = BuiltInRegistries.ITEM.getKey(item);
        String name = new ItemStack(item).getHoverName().getString().toLowerCase(Locale.ROOT);
        for (String word : WORDS.split(trimmed.toLowerCase(Locale.ROOT))) {
            boolean found = word.startsWith("@")
                    ? id.getNamespace().startsWith(word.substring(1))
                    : name.contains(word) || id.getPath().contains(word);
            if (!found) {
                return false;
            }
        }
        return true;
    }
}
