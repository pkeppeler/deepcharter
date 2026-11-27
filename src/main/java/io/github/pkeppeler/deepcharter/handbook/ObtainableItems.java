package io.github.pkeppeler.deepcharter.handbook;

import io.github.pkeppeler.deepcharter.DeepCharter;
import java.util.List;
import java.util.Optional;
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
 * The items a player can obtain in the campaign world (ADR 0093). Every item outside the {@code minecraft} namespace is
 * obtainable; a vanilla item is obtainable only when the {@code deepcharter:obtainable} item tag lists it. A recipe
 * that needs or makes an item that is not obtainable is left out of the server's recipes
 * ({@code handbook/mixin/RecipeManagerMixin}), so it cannot be crafted, and the parts catalogue and the creative tabs
 * show only obtainable items. {@code ObtainableItemsTest} derives the list from the world and fails when the tag drifts
 * from it.
 */
public final class ObtainableItems {
    /** The vanilla items the campaign world yields. The tag is synced, so the client reads it too. */
    public static final TagKey<Item> LISTED =
            TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "obtainable"));

    private ObtainableItems() {}

    private static boolean isObtainable(Holder<Item> item) {
        return !BuiltInRegistries.ITEM.getKey(item.value()).getNamespace().equals(Identifier.DEFAULT_NAMESPACE)
                || item.is(LISTED);
    }

    /** Whether the stack's item is obtainable, by the tags bound to it: on the client, the ones the server sent. */
    public static boolean isObtainable(ItemStack stack) {
        return isObtainable(stack.typeHolder());
    }

    /**
     * {@code recipes} without the ones that need or make an item that is not obtainable. Call once the reloaded tags
     * are bound to the items, which is after the recipes load.
     */
    public static HolderLookup<Recipe<?>> loadable(HolderLookup<Recipe<?>> recipes) {
        List<Holder.Reference<Item>> obtainable = BuiltInRegistries.ITEM
                .listElements()
                .filter(ObtainableItems::isObtainable)
                .toList();
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
     * Whether every ingredient of {@code recipe} takes an obtainable item (a tag needs one member, not all) and every
     * item it makes is obtainable. A special recipe (map cloning, dyeing) names neither and is kept: what it takes is
     * not obtainable either. The station is not checked: a kept recipe whose station the world lacks crafts nothing,
     * and the gate's derivation, which does need the station, lists no item that only such a recipe makes.
     */
    private static boolean keeps(Recipe<?> recipe, List<Holder.Reference<Item>> obtainable) {
        for (Ingredient ingredient : recipe.placementInfo().ingredients()) {
            if (obtainable.stream().noneMatch(ingredient::acceptsItem)) {
                return false;
            }
        }
        for (RecipeDisplay display : recipe.display()) {
            // A result is an item, never a tag, so it resolves with no registries.
            for (ItemStack result : display.result().resolveForStacks(ContextMap.EMPTY)) {
                if (!result.isEmpty() && !isObtainable(result)) {
                    return false;
                }
            }
        }
        return true;
    }
}
