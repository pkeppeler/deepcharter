package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.handbook.HandbookChapters;
import io.github.pkeppeler.deepcharter.handbook.HandbookProgressData;
import io.github.pkeppeler.deepcharter.handbook.KnownRecipes;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

/**
 * Server GameTest for #1602: the tow cable needs a lead, and the lead is listed as obtainable, so its recipe has to
 * load and reach back to items the world gives. Walks the loaded recipes, not the files, so a recipe the obtainable
 * filter (ADR 0093) leaves out does not count.
 */
public class TowCableCraftableTest {
    private static Item ours(String path) {
        return BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path));
    }

    @GameTest
    public void theLeadAndSoTheTowCableCraftFromSalvageClothAndIron(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        // What a crew finds or smelts before it needs a cable: cloth from lockers and crates, iron from ore.
        Set<Item> raw = Set.of(ours("salvage_cloth"), Items.IRON_INGOT);
        List<Recipe<?>> loaded = new ArrayList<>();
        server.reloadableRegistries()
                .lookup()
                .lookupOrThrow(Registries.RECIPE)
                .listElementIds()
                .forEach(key -> server.getRecipeManager().byKey(key).ifPresent(holder -> loaded.add(holder.value())));

        expect(
                helper,
                craftable(Items.LEAD, loaded, raw, Set.of()),
                "no loaded recipe chain makes a lead from salvage cloth, so the tow cable cannot be crafted");
        expect(
                helper,
                craftable(ours("tow_cable"), loaded, raw, Set.of()),
                "no loaded recipe chain makes a tow cable from salvage cloth and iron ingots");
        helper.succeed();
    }

    /**
     * The towing step asks for the tow cable, so its recipe is known and in the book of a player on that step. The
     * lead and string recipes follow the plain rule: string makes the lead known, cloth makes the string known.
     */
    @GameTest(maxTicks = 100)
    public void theTowingStepShowsTheCableAndHoldingStringAndClothShowsTheRest(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = MockPlayers.join(helper, "Cabler").player();
        Identifier towStep =
                Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "handbook/company_property/tow_prospector");
        expect(
                helper,
                Charters.found(
                                server,
                                player.getUUID(),
                                "Cable " + UUID.randomUUID().toString().substring(0, 8))
                        .isEmpty(),
                "founding a charter should succeed");
        CharterId charter = Charters.charterOfOrThrow(server, player.getUUID())
                .orElseThrow()
                .id();
        // The step is current once every step before it is done.
        for (Identifier directive : HandbookChapters.directivesOrEmpty(server)) {
            if (directive.equals(towStep)) {
                break;
            }
            HandbookProgressData.get(server).complete(charter, directive);
        }
        Identifier cable = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "tow_cable");
        Identifier lead = Identifier.withDefaultNamespace("lead");
        Identifier stringFromCloth = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "string_from_cloth");

        helper.startSequence()
                .thenWaitUntil(() -> {
                    expectShown(helper, server, player, cable, true);
                    expectShown(helper, server, player, lead, false);
                    expectShown(helper, server, player, stringFromCloth, false);
                })
                .thenExecute(() -> {
                    player.getInventory().add(new ItemStack(Items.STRING));
                    player.getInventory().add(new ItemStack(ours("salvage_cloth")));
                })
                .thenWaitUntil(() -> {
                    expectShown(helper, server, player, lead, true);
                    expectShown(helper, server, player, stringFromCloth, true);
                })
                .thenSucceed();
    }

    private static void expectShown(
            GameTestHelper helper, MinecraftServer server, ServerPlayer player, Identifier recipe, boolean expected) {
        expect(
                helper,
                KnownRecipes.knownFor(server, player.getUUID()).contains(recipe) == expected,
                "%s should %sbe known, knows %s",
                recipe,
                expected ? "" : "not ",
                KnownRecipes.knownFor(server, player.getUUID()));
        expect(
                helper,
                player.getRecipeBook().contains(ResourceKey.create(Registries.RECIPE, recipe)) == expected,
                "the recipe book should %shold %s",
                expected ? "" : "not ",
                recipe);
    }

    /**
     * Whether {@code item} is a raw item or some loaded recipe makes it from craftable ingredients; {@code path} stops
     * loops.
     */
    private static boolean craftable(Item item, List<Recipe<?>> loaded, Set<Item> raw, Set<Item> path) {
        if (raw.contains(item)) {
            return true;
        }
        if (path.contains(item)) {
            return false;
        }
        Set<Item> deeper = new HashSet<>(path);
        deeper.add(item);
        return loaded.stream()
                .filter(recipe -> makes(recipe, item))
                .anyMatch(recipe -> recipe.placementInfo().ingredients().stream()
                        .allMatch(ingredient -> ingredientCraftable(ingredient, loaded, raw, deeper)));
    }

    private static boolean ingredientCraftable(
            Ingredient ingredient, List<Recipe<?>> loaded, Set<Item> raw, Set<Item> path) {
        return BuiltInRegistries.ITEM
                .listElements()
                .filter(holder -> ingredient.acceptsItem(holder))
                .anyMatch(holder -> craftable(holder.value(), loaded, raw, path));
    }

    private static boolean makes(Recipe<?> recipe, Item item) {
        return recipe.display().stream()
                .flatMap(display -> display.result().resolveForStacks(ContextMap.EMPTY).stream())
                .map(ItemStack::getItem)
                .anyMatch(made -> made == item);
    }
}
