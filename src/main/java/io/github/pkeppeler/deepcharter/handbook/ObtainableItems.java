package io.github.pkeppeler.deepcharter.handbook;

import io.github.pkeppeler.deepcharter.DeepCharter;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.display.RecipeDisplay;

/**
 * The items a player can obtain in the campaign world (ADR 0093). Every item outside the {@code minecraft} namespace is obtainable;
 * a vanilla item is obtainable only when the {@code deepcharter:obtainable} item tag lists it. A recipe that needs or makes an item
 * that is not obtainable is never loaded ({@code handbook/mixin/RecipeManagerMixin}), so it cannot be crafted, and the parts
 * catalogue and the creative tabs show only obtainable items. {@code ObtainableItemsTest} derives the list from the world and fails
 * when the tag drifts from it.
 */
public final class ObtainableItems {
    /** The vanilla items the campaign world yields. The tag is synced, so the client reads it too. */
    public static final TagKey<Item> LISTED =
            TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "obtainable"));

    private ObtainableItems() {}

    private static boolean isVanilla(Holder<Item> item) {
        return BuiltInRegistries.ITEM.getKey(item.value()).getNamespace().equals(Identifier.DEFAULT_NAMESPACE);
    }

    /** Whether the stack's item is obtainable, by the tags bound to it: on the client, the ones the server sent. */
    public static boolean isObtainable(ItemStack stack) {
        return !isVanilla(stack.typeHolder()) || stack.is(LISTED);
    }

    /**
     * {@code recipes} without the ones that need or make an item that is not obtainable. Read while the server loads its recipes,
     * before the reloaded tags are bound to the items, so the list is read from {@code registries}, which carries them, and not from
     * {@link #isObtainable}.
     */
    public static HolderLookup<Recipe<?>> loadable(HolderLookup<Recipe<?>> recipes, HolderLookup.Provider registries) {
        Set<Item> listed = registries
                .lookupOrThrow(Registries.ITEM)
                .get(LISTED)
                .stream()
                .flatMap(HolderSet::stream)
                .map(Holder::value)
                .collect(Collectors.toSet());
        Predicate<Holder<Item>> obtainable = item -> !isVanilla(item) || listed.contains(item.value());
        return new HolderLookup<>() {
            @Override
            public Stream<Holder.Reference<Recipe<?>>> listElements() {
                return recipes.listElements().filter(holder -> keeps(holder.value(), obtainable));
            }

            @Override
            public Stream<HolderSet.Named<Recipe<?>>> listTags() {
                return recipes.listTags();
            }

            @Override
            public Optional<Holder.Reference<Recipe<?>>> get(ResourceKey<Recipe<?>> key) {
                return recipes.get(key);
            }

            @Override
            public Optional<HolderSet.Named<Recipe<?>>> get(TagKey<Recipe<?>> tag) {
                return recipes.get(tag);
            }
        };
    }

    /**
     * Whether every ingredient of {@code recipe} has an obtainable item (a tag needs one member, not all) and every item it makes is
     * obtainable. A special recipe (map cloning, dyeing) names neither and is kept: what it takes is not obtainable either.
     */
    private static boolean keeps(Recipe<?> recipe, Predicate<Holder<Item>> obtainable) {
        for (Ingredient ingredient : recipe.placementInfo().ingredients()) {
            if (ingredient.items().noneMatch(obtainable)) {
                return false;
            }
        }
        for (RecipeDisplay display : recipe.display()) {
            // A result is an item, never a tag, so it resolves with no registries.
            for (ItemStack result : display.result().resolveForStacks(ContextMap.EMPTY)) {
                if (!result.isEmpty() && !obtainable.test(result.typeHolder())) {
                    return false;
                }
            }
        }
        return true;
    }
}
