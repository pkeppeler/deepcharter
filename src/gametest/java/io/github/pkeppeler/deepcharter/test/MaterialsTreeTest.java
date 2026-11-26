package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.client.handbook.catalogue.KnownRecipe;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.MaterialsTree;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.MaterialsTree.Material;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.MaterialsTree.Node;
import io.github.pkeppeler.deepcharter.handbook.ObtainableItems;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeBookCategories;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import org.jspecify.annotations.Nullable;

/**
 * Server GameTests for #1378: the total-materials breakdown of a known recipe. The recipes are built by hand as the client's recipe book
 * would hold them and resolved through {@link KnownRecipe#of}, so a tag cell holds exactly the members the world yields. The tree follows
 * only the recipes it is given (the charter's known ones), crafts an intermediate as often as the need takes, rounding up, feeds a later
 * need from what an earlier craft made too many of, does not follow a recipe that undoes another (ingots from an iron block, or round a longer loop) or salvages worn gear,
 * stops at an item already being made further up, takes the tag member the player holds most of, and takes what the player
 * holds before it breaks anything down. A dense tangle of recipes is cut short at the tree's node budget, and the rows left unbroken are
 * marked (#1419).
 */
public class MaterialsTreeTest {
    private static final SlotDisplay PLANKS = item(Items.OAK_PLANKS);
    private static final SlotDisplay STICK = item(Items.STICK);
    private static final SlotDisplay LOG = item(Items.OAK_LOG);
    // Items.COPPER_BLOCK is the weathering family, not one item.
    private static final Item COPPER_BLOCK =
            BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace("copper_block"));

    private static SlotDisplay item(Item item) {
        return new SlotDisplay.ItemSlotDisplay(item);
    }

    private static SlotDisplay[] times(SlotDisplay slot, int count) {
        SlotDisplay[] slots = new SlotDisplay[count];
        java.util.Arrays.fill(slots, slot);
        return slots;
    }

    /** A shapeless recipe that makes {@code count} of {@code made} from one of each of {@code ingredients}. */
    private static KnownRecipe recipe(GameTestHelper helper, Item made, int count, SlotDisplay... ingredients) {
        RecipeDisplayEntry entry = new RecipeDisplayEntry(
                new RecipeDisplayId(0),
                new ShapelessCraftingRecipeDisplay(
                        List.of(ingredients),
                        new SlotDisplay.ItemStackSlotDisplay(new ItemStackTemplate(made, count)),
                        SlotDisplay.Empty.INSTANCE),
                OptionalInt.empty(),
                RecipeBookCategories.CRAFTING_MISC,
                Optional.empty());
        return KnownRecipe.of(entry, SlotDisplayContext.fromLevel(helper.getLevel()));
    }

    /** The shipped recipe {@code id} (vanilla or ours), as the client's recipe book would hold it. */
    private static KnownRecipe shipped(GameTestHelper helper, String id) {
        RecipeDisplayEntry entry = new RecipeDisplayEntry(
                new RecipeDisplayId(0),
                helper.getLevel()
                        .getServer()
                        .getRecipeManager()
                        .byKey(ResourceKey.create(Registries.RECIPE, Identifier.parse(id)))
                        .orElseThrow(() -> failure(helper, "no recipe %s", id))
                        .value()
                        .display()
                        .getFirst(),
                OptionalInt.empty(),
                RecipeBookCategories.CRAFTING_MISC,
                Optional.empty());
        return KnownRecipe.of(entry, SlotDisplayContext.fromLevel(helper.getLevel()));
    }

    /** The known recipes, by what they make, in the order given: what the catalogue's {@code recipesFor} answers. */
    private static Function<Item, List<KnownRecipe>> known(KnownRecipe... recipes) {
        Map<Item, List<KnownRecipe>> making = new LinkedHashMap<>();
        for (KnownRecipe recipe : recipes) {
            making.computeIfAbsent(recipe.results().getFirst().getItem(), ignored -> new ArrayList<>())
                    .add(recipe);
        }
        return item -> making.getOrDefault(item, List.of());
    }

    private static Map<Item, Integer> stock(Object... itemsAndCounts) {
        Map<Item, Integer> stock = new LinkedHashMap<>();
        for (int i = 0; i < itemsAndCounts.length; i += 2) {
            stock.put((Item) itemsAndCounts[i], (Integer) itemsAndCounts[i + 1]);
        }
        return stock;
    }

    private static String describe(List<Material> materials) {
        List<String> rows = new ArrayList<>();
        for (Material material : materials) {
            rows.add(BuiltInRegistries.ITEM.getKey(material.item()).getPath() + " " + material.have() + "/"
                    + material.need() + " short " + material.shortBy());
        }
        return rows.toString();
    }

    /** That the rows are exactly {@code expected}: item, need, have, in order. */
    private static void expectMaterials(GameTestHelper helper, MaterialsTree tree, String what, Object... expected) {
        List<Material> materials = tree.materials();
        boolean same = materials.size() * 3 == expected.length;
        for (int i = 0; same && i < materials.size(); i++) {
            Material material = materials.get(i);
            same = material.item() == expected[3 * i]
                    && material.need() == (Integer) expected[3 * i + 1]
                    && material.have() == (Integer) expected[3 * i + 2]
                    && material.shortBy() == Math.max(0, material.need() - material.have());
        }
        expect(helper, same, "%s: got %s", what, describe(materials));
    }

    /** The first node making or taking {@code item}, depth first, or null. */
    private static @Nullable Node find(Node node, Item item) {
        if (node.item() == item) {
            return node;
        }
        for (Node child : node.children()) {
            Node found = find(child, item);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static Node node(GameTestHelper helper, MaterialsTree tree, Item item) {
        Node found = find(tree.root(), item);
        if (found == null) {
            throw failure(helper, "the tree has no node for %s: %s", item, tree.root());
        }
        return found;
    }

    @GameTest
    public void aRecipeOfBaseMaterialsNeedsWhatItTakes(GameTestHelper helper) {
        KnownRecipe chest = recipe(helper, Items.CHEST, 1, times(PLANKS, 8));
        MaterialsTree tree = MaterialsTree.of(chest, known(chest), stock(Items.OAK_PLANKS, 3));
        expectMaterials(helper, tree, "a chest from eight planks, three held", Items.OAK_PLANKS, 8, 3);
        expect(
                helper,
                tree.root().crafts() == 1,
                "the shown recipe is made once, got %s",
                tree.root().crafts());
        expect(helper, !tree.allHeld(), "five planks short is not everything held");
        helper.succeed();
    }

    @GameTest
    public void anIngredientWithNoKnownRecipeIsABaseMaterial(GameTestHelper helper) {
        KnownRecipe chest = recipe(helper, Items.CHEST, 1, times(PLANKS, 8));
        // The world has a way to make planks, but the charter does not know it: the tree must not follow it.
        KnownRecipe unknownPlanks = recipe(helper, Items.OAK_PLANKS, 4, LOG);
        MaterialsTree tree = MaterialsTree.of(chest, known(chest), stock());
        expect(helper, unknownPlanks.resultItems().contains(Items.OAK_PLANKS), "the unknown recipe makes planks");
        expectMaterials(helper, tree, "the planks are a base material", Items.OAK_PLANKS, 8, 0);
        expect(helper, node(helper, tree, Items.OAK_PLANKS).recipe() == null, "the planks are not broken down");

        MaterialsTree knowing = MaterialsTree.of(chest, known(chest, unknownPlanks), stock());
        expectMaterials(helper, knowing, "once known, the planks come from logs", Items.OAK_LOG, 2, 0);
        helper.succeed();
    }

    @GameTest
    public void anIntermediateIsCraftedOftenEnoughRoundingUp(GameTestHelper helper) {
        // Six sticks at four a craft is two crafts: four planks, which is one craft of planks from one log.
        KnownRecipe ladder = recipe(helper, Items.LADDER, 3, times(STICK, 6));
        KnownRecipe sticks = recipe(helper, Items.STICK, 4, PLANKS, PLANKS);
        KnownRecipe planks = recipe(helper, Items.OAK_PLANKS, 4, LOG);
        MaterialsTree tree = MaterialsTree.of(ladder, known(ladder, sticks, planks), stock());
        Node stick = node(helper, tree, Items.STICK);
        expect(helper, stick.needed() == 6 && stick.crafts() == 2, "six sticks are two crafts, got %s", stick);
        Node plank = node(helper, tree, Items.OAK_PLANKS);
        expect(helper, plank.needed() == 4 && plank.crafts() == 1, "four planks are one craft, got %s", plank);
        expectMaterials(helper, tree, "a ladder down to logs", Items.OAK_LOG, 1, 0);
        helper.succeed();
    }

    @GameTest
    public void whatACraftMakesTooManyOfFeedsALaterNeed(GameTestHelper helper) {
        // The plank cell takes one of the four planks a log makes; the stick craft takes two of the three left over.
        KnownRecipe torch = recipe(helper, Items.TORCH, 1, PLANKS, STICK);
        KnownRecipe sticks = recipe(helper, Items.STICK, 4, PLANKS, PLANKS);
        KnownRecipe planks = recipe(helper, Items.OAK_PLANKS, 4, LOG);
        MaterialsTree tree = MaterialsTree.of(torch, known(torch, sticks, planks), stock());
        expectMaterials(helper, tree, "one log covers both needs of planks", Items.OAK_LOG, 1, 0);
        Node stickNode = node(helper, tree, Items.STICK);
        Node fromSpare = stickNode.children().getFirst();
        expect(
                helper,
                fromSpare.item() == Items.OAK_PLANKS && fromSpare.fromSpare() == 2 && fromSpare.crafts() == 0,
                "the stick's planks are the leftovers, got %s",
                fromSpare);
        helper.succeed();
    }

    @GameTest
    public void aRecipeThatUndoesAnotherIsNotFollowed(GameTestHelper helper) {
        // Iron block from ingots, nuggets from an ingot; ingots from a block (listed first), from nuggets, or smelted
        // from raw iron.
        KnownRecipe block = recipe(helper, Items.IRON_BLOCK, 1, times(item(Items.IRON_INGOT), 9));
        KnownRecipe nuggets = recipe(helper, Items.IRON_NUGGET, 9, item(Items.IRON_INGOT));
        KnownRecipe ingotFromBlock = recipe(helper, Items.IRON_INGOT, 9, item(Items.IRON_BLOCK));
        KnownRecipe ingotFromNuggets = recipe(helper, Items.IRON_INGOT, 1, times(item(Items.IRON_NUGGET), 9));
        KnownRecipe smelted = recipe(helper, Items.IRON_INGOT, 1, item(Items.RAW_IRON));

        MaterialsTree ofBlock =
                MaterialsTree.of(block, known(block, nuggets, ingotFromBlock, ingotFromNuggets, smelted), stock());
        expectMaterials(helper, ofBlock, "the block's ingots are smelted", Items.RAW_IRON, 9, 0);
        expect(
                helper,
                node(helper, ofBlock, Items.IRON_INGOT).recipe() == smelted,
                "the ingots are smelted, not unpacked from a block or packed from nuggets");

        MaterialsTree unsmelted =
                MaterialsTree.of(block, known(block, nuggets, ingotFromBlock, ingotFromNuggets), stock());
        expectMaterials(
                helper, unsmelted, "with no smelting known, the ingots are a base material", Items.IRON_INGOT, 9, 0);

        MaterialsTree ofIngots = MaterialsTree.of(
                ingotFromBlock, known(block, nuggets, ingotFromBlock, ingotFromNuggets, smelted), stock());
        expectMaterials(helper, ofIngots, "the shown recipe is made even when it unpacks", Items.IRON_BLOCK, 1, 0);
        helper.succeed();
    }

    @GameTest
    public void aRecipeLoopIsNotFollowed(GameTestHelper helper) {
        // A torch from coal, coal from a stick, a stick from a torch: no pair undoes the other, but the stick comes
        // only from a torch, which comes only from coal, so coal from a stick undoes the torch.
        KnownRecipe torch = recipe(helper, Items.TORCH, 1, item(Items.COAL));
        KnownRecipe coal = recipe(helper, Items.COAL, 1, STICK);
        KnownRecipe stickFromTorch = recipe(helper, Items.STICK, 1, item(Items.TORCH));
        MaterialsTree loop = MaterialsTree.of(torch, known(torch, coal, stickFromTorch), stock());
        expectMaterials(helper, loop, "the coal would go round the loop, so it is a base material", Items.COAL, 1, 0);
        expect(helper, node(helper, loop, Items.COAL).recipe() == null, "the coal is not made from the stick");

        // With sticks from planks too, the stick is not made only from coal: the coal is made from a stick, and the
        // stick from planks, since a stick from the torch being made would go round.
        KnownRecipe stickFromPlanks = recipe(helper, Items.STICK, 1, PLANKS);
        MaterialsTree out = MaterialsTree.of(torch, known(torch, coal, stickFromTorch, stickFromPlanks), stock());
        expectMaterials(helper, out, "the loop is left through the planks", Items.OAK_PLANKS, 1, 0);
        expect(
                helper,
                node(helper, out, Items.STICK).recipe() == stickFromPlanks,
                "the stick is made from planks, not from the torch being made");
        helper.succeed();
    }

    @GameTest
    public void theTreeDoesNotDependOnTheOrderRecipesAreListed(GameTestHelper helper) {
        // Coal from a torch or a stick, a torch from a stick and coal, a stick from a torch. The torch has a cell of
        // only coal, so it is made solely from coal, and so is the stick, made only from the torch: both coal
        // recipes undo another, whichever is listed first.
        KnownRecipe campfire = recipe(helper, Items.CAMPFIRE, 1, item(Items.COAL));
        KnownRecipe coalFromTorch = recipe(helper, Items.COAL, 1, item(Items.TORCH));
        KnownRecipe coalFromStick = recipe(helper, Items.COAL, 1, STICK);
        KnownRecipe torch = recipe(helper, Items.TORCH, 1, STICK, item(Items.COAL));
        KnownRecipe stickFromTorch = recipe(helper, Items.STICK, 1, item(Items.TORCH));
        MaterialsTree torchFirst = MaterialsTree.of(
                campfire, known(campfire, coalFromTorch, coalFromStick, torch, stickFromTorch), stock());
        MaterialsTree stickFirst = MaterialsTree.of(
                campfire, known(campfire, coalFromStick, coalFromTorch, torch, stickFromTorch), stock());
        expect(
                helper,
                torchFirst.root().equals(stickFirst.root()),
                "the same recipes in two orders give one tree, got %s and %s",
                describe(torchFirst.materials()),
                describe(stickFirst.materials()));
        expectMaterials(helper, torchFirst, "the coal is a base material", Items.COAL, 1, 0);
        helper.succeed();
    }

    @GameTest
    public void anIntermediateIsMadeByItsFirstKnownRecipe(GameTestHelper helper) {
        KnownRecipe ladder = recipe(helper, Items.LADDER, 3, times(STICK, 6));
        KnownRecipe oakSticks = recipe(helper, Items.STICK, 4, PLANKS, PLANKS);
        KnownRecipe spruceSticks = recipe(helper, Items.STICK, 4, item(Items.SPRUCE_PLANKS), item(Items.SPRUCE_PLANKS));
        expectMaterials(
                helper,
                MaterialsTree.of(ladder, known(ladder, oakSticks, spruceSticks), stock()),
                "oak listed first",
                Items.OAK_PLANKS,
                4,
                0);
        expectMaterials(
                helper,
                MaterialsTree.of(ladder, known(ladder, spruceSticks, oakSticks), stock()),
                "spruce listed first",
                Items.SPRUCE_PLANKS,
                4,
                0);
        helper.succeed();
    }

    @GameTest
    public void aTagTakesTheMemberHeldMost(GameTestHelper helper) {
        SlotDisplay anyPlanks = new SlotDisplay.TagSlotDisplay(BuiltInRegistries.ITEM.getOrThrow(ItemTags.PLANKS));
        KnownRecipe chest = recipe(helper, Items.CHEST, 1, times(anyPlanks, 8));
        expect(
                helper,
                chest.cells().getFirst().size() > 1
                        && chest.cells().getFirst().getFirst().is(Items.OAK_PLANKS),
                "the tag cell offers oak first and more besides, got %s",
                chest.cells().getFirst());

        expectMaterials(
                helper,
                MaterialsTree.of(chest, known(chest), stock(Items.OAK_PLANKS, 2, Items.SPRUCE_PLANKS, 5)),
                "spruce, held most",
                Items.SPRUCE_PLANKS,
                8,
                5);
        expectMaterials(
                helper, MaterialsTree.of(chest, known(chest), stock()), "none held: the first", Items.OAK_PLANKS, 8, 0);
        // None held, and only spruce has a known recipe: spruce, which can be broken down.
        KnownRecipe sprucePlanks = recipe(helper, Items.SPRUCE_PLANKS, 4, LOG);
        expectMaterials(
                helper,
                MaterialsTree.of(chest, known(chest, sprucePlanks), stock()),
                "none held: the first that has a known recipe",
                Items.OAK_LOG,
                2,
                0);
        helper.succeed();
    }

    @GameTest
    public void aHeldIntermediateIsTakenBeforeItIsBrokenDown(GameTestHelper helper) {
        KnownRecipe ladder = recipe(helper, Items.LADDER, 3, times(STICK, 6));
        KnownRecipe sticks = recipe(helper, Items.STICK, 4, PLANKS, PLANKS);
        Function<Item, List<KnownRecipe>> known = known(ladder, sticks);

        MaterialsTree all = MaterialsTree.of(ladder, known, stock(Items.STICK, 10));
        expectMaterials(helper, all, "six of ten sticks held, nothing below them", Items.STICK, 6, 10);
        expect(helper, node(helper, all, Items.STICK).crafts() == 0, "held sticks are not crafted");
        expect(helper, all.allHeld(), "everything is held");

        MaterialsTree some = MaterialsTree.of(ladder, known, stock(Items.STICK, 2, Items.OAK_PLANKS, 1));
        expectMaterials(
                helper,
                some,
                "two sticks held, four crafted from planks; what is short comes first",
                Items.OAK_PLANKS,
                2,
                1,
                Items.STICK,
                2,
                2);
        Node stick = node(helper, some, Items.STICK);
        expect(helper, stick.fromStock() == 2 && stick.crafts() == 1, "two held, one craft of four, got %s", stick);
        expect(helper, !some.allHeld(), "a plank short is not everything held");
        helper.succeed();
    }

    @GameTest
    public void theShownRecipesOwnResultHeldIsNotTaken(GameTestHelper helper) {
        KnownRecipe ladder = recipe(helper, Items.LADDER, 3, times(STICK, 6));
        MaterialsTree tree = MaterialsTree.of(ladder, known(ladder), stock(Items.LADDER, 9));
        expectMaterials(helper, tree, "ladders held do not make another", Items.STICK, 6, 0);
        helper.succeed();
    }

    @GameTest
    public void aStorageBlockAsAnIngredientIsUnpacked(GameTestHelper helper) {
        // The anvil takes iron blocks. Ingots come from nuggets too, not only from the block, so packing ingots into a
        // block is followed; only unpacking a block or nuggets into ingots is the undoing.
        KnownRecipe anvil = shipped(helper, "minecraft:anvil");
        KnownRecipe block = shipped(helper, "minecraft:iron_block");
        KnownRecipe nuggets = shipped(helper, "minecraft:iron_nugget");
        KnownRecipe ingotFromBlock = shipped(helper, "minecraft:iron_ingot_from_iron_block");
        KnownRecipe ingotFromNuggets = shipped(helper, "minecraft:iron_ingot_from_nuggets");
        KnownRecipe smelted = shipped(helper, "minecraft:iron_ingot_from_smelting_raw_iron");

        MaterialsTree unsmelted =
                MaterialsTree.of(anvil, known(anvil, block, nuggets, ingotFromBlock, ingotFromNuggets), stock());
        expectMaterials(helper, unsmelted, "three blocks of nine and four ingots", Items.IRON_INGOT, 31, 0);
        expect(
                helper,
                node(helper, unsmelted, Items.IRON_BLOCK).recipe() == block,
                "the blocks are packed from ingots");

        MaterialsTree smelting = MaterialsTree.of(
                anvil, known(anvil, block, nuggets, ingotFromBlock, ingotFromNuggets, smelted), stock());
        expectMaterials(helper, smelting, "with smelting known, down to raw iron", Items.RAW_IRON, 31, 0);
        helper.succeed();
    }

    @GameTest
    public void nuggetsAsAnIngredientAreCutFromAnIngot(GameTestHelper helper) {
        KnownRecipe lantern = shipped(helper, "minecraft:lantern");
        KnownRecipe block = shipped(helper, "minecraft:iron_block");
        KnownRecipe nuggets = shipped(helper, "minecraft:iron_nugget");
        KnownRecipe ingotFromBlock = shipped(helper, "minecraft:iron_ingot_from_iron_block");
        KnownRecipe ingotFromNuggets = shipped(helper, "minecraft:iron_ingot_from_nuggets");
        MaterialsTree tree =
                MaterialsTree.of(lantern, known(lantern, block, nuggets, ingotFromBlock, ingotFromNuggets), stock());
        expectMaterials(helper, tree, "eight nuggets are one ingot cut", Items.IRON_INGOT, 1, 0, Items.TORCH, 1, 0);
        helper.succeed();
    }

    /** That no material is worn gear (a tool or armour piece), which only salvage smelting would break down to. */
    private static void expectNoGear(GameTestHelper helper, MaterialsTree tree, String what) {
        expect(
                helper,
                tree.materials().stream()
                        .noneMatch(
                                material -> material.item().getDefaultInstance().isDamageableItem()),
                "%s: no tool or armour, got %s",
                what,
                describe(tree.materials()));
    }

    @GameTest
    public void ironGearSmeltedToNuggetsIsNotFollowed(GameTestHelper helper) {
        // Ingots from nuggets, nuggets smelted from iron tools and armour, a pickaxe from ingots: a loop of three
        // steps. Smelting gear down is salvage, so the nuggets come only from ingots and unpacking them undoes that.
        KnownRecipe anvil = shipped(helper, "minecraft:anvil");
        KnownRecipe block = shipped(helper, "minecraft:iron_block");
        KnownRecipe nuggets = shipped(helper, "minecraft:iron_nugget");
        KnownRecipe ingotFromBlock = shipped(helper, "minecraft:iron_ingot_from_iron_block");
        KnownRecipe ingotFromNuggets = shipped(helper, "minecraft:iron_ingot_from_nuggets");
        KnownRecipe salvage = shipped(helper, "minecraft:iron_nugget_from_smelting");
        KnownRecipe pickaxe = shipped(helper, "minecraft:iron_pickaxe");
        KnownRecipe smelted = shipped(helper, "minecraft:iron_ingot_from_smelting_raw_iron");

        MaterialsTree gearUnknown = MaterialsTree.of(
                anvil, known(anvil, block, nuggets, ingotFromBlock, ingotFromNuggets, salvage), stock());
        expectMaterials(helper, gearUnknown, "no pickaxe recipe known: ingots", Items.IRON_INGOT, 31, 0);
        expectNoGear(helper, gearUnknown, "no pickaxe recipe known");

        MaterialsTree gearKnown = MaterialsTree.of(
                anvil, known(anvil, block, nuggets, ingotFromBlock, ingotFromNuggets, salvage, pickaxe), stock());
        expectMaterials(helper, gearKnown, "the pickaxe recipe known: ingots", Items.IRON_INGOT, 31, 0);
        expectNoGear(helper, gearKnown, "the pickaxe recipe known");

        MaterialsTree smelting = MaterialsTree.of(
                anvil,
                known(anvil, block, nuggets, ingotFromBlock, ingotFromNuggets, salvage, pickaxe, smelted),
                stock());
        expectMaterials(helper, smelting, "with smelting known, raw iron", Items.RAW_IRON, 31, 0);
        expectNoGear(helper, smelting, "with smelting known");
        helper.succeed();
    }

    @GameTest
    public void copperGearSmeltedToNuggetsIsNotFollowed(GameTestHelper helper) {
        KnownRecipe radiator = shipped(helper, "deepcharter:radiator_unit");
        KnownRecipe block = shipped(helper, "minecraft:copper_block");
        KnownRecipe nuggets = shipped(helper, "minecraft:copper_nugget");
        KnownRecipe ingotFromBlock = shipped(helper, "minecraft:copper_ingot");
        KnownRecipe ingotFromNuggets = shipped(helper, "minecraft:copper_ingot_from_nuggets");
        KnownRecipe salvage = shipped(helper, "minecraft:copper_nugget_from_smelting");
        KnownRecipe pickaxe = shipped(helper, "minecraft:copper_pickaxe");
        MaterialsTree tree = MaterialsTree.of(
                radiator, known(radiator, block, nuggets, ingotFromBlock, ingotFromNuggets, salvage, pickaxe), stock());
        expectMaterials(
                helper, tree, "a block of nine and seven ingots", Items.COPPER_INGOT, 16, 0, Items.REDSTONE, 1, 0);
        expectNoGear(helper, tree, "the radiator unit");
        helper.succeed();
    }

    @GameTest
    public void theRadiatorUnitsCopperBlockCountsHeldIngots(GameTestHelper helper) {
        // The radiator unit takes a copper block and seven ingots. Holding copper teaches the block, the nuggets and
        // both ways back to ingots.
        KnownRecipe radiator = shipped(helper, "deepcharter:radiator_unit");
        KnownRecipe block = shipped(helper, "minecraft:copper_block");
        KnownRecipe nuggets = shipped(helper, "minecraft:copper_nugget");
        KnownRecipe ingotFromBlock = shipped(helper, "minecraft:copper_ingot");
        KnownRecipe ingotFromNuggets = shipped(helper, "minecraft:copper_ingot_from_nuggets");
        Function<Item, List<KnownRecipe>> known = known(radiator, block, nuggets, ingotFromBlock, ingotFromNuggets);

        MaterialsTree held = MaterialsTree.of(radiator, known, stock(Items.COPPER_INGOT, 16, Items.REDSTONE, 1));
        Node copperBlock = node(helper, held, COPPER_BLOCK);
        expect(
                helper,
                copperBlock.recipe() == block && copperBlock.crafts() == 1,
                "the copper block is packed from nine ingots, got %s",
                copperBlock);
        expectMaterials(
                helper,
                held,
                "sixteen ingots held cover the block and the seven",
                Items.COPPER_INGOT,
                16,
                16,
                Items.REDSTONE,
                1,
                1);
        expect(helper, held.allHeld(), "sixteen ingots and a redstone make a radiator unit");
        helper.succeed();
    }

    @GameTest
    public void aPairThatOnlyMakeEachOtherStopsBothWays(GameTestHelper helper) {
        // The known limit of the undo rule: when the block and the ingot are each made only from the other, each recipe
        // undoes the other, so neither is followed and the block stays a base material.
        KnownRecipe radiator = shipped(helper, "deepcharter:radiator_unit");
        KnownRecipe block = shipped(helper, "minecraft:copper_block");
        KnownRecipe ingotFromBlock = shipped(helper, "minecraft:copper_ingot");
        MaterialsTree tree =
                MaterialsTree.of(radiator, known(radiator, block, ingotFromBlock), stock(Items.COPPER_INGOT, 16));
        expect(helper, node(helper, tree, COPPER_BLOCK).recipe() == null, "the block is not packed");
        expect(
                helper,
                tree.materials().stream()
                        .anyMatch(material -> material.item() == COPPER_BLOCK && material.shortBy() == 1),
                "the block is short by one, got %s",
                describe(tree.materials()));
        helper.succeed();
    }

    /** Items in the long loop: the tree is rebuilt on the render thread, so its cost must stay polynomial in the items. */
    private static final int LOOP_ITEMS = 32;

    /**
     * The fastest of a few builds, after as many to warm the JIT, must stay under this. One build takes well under 5 ms; the bound
     * leaves room for a shared CI runner, and a walk exponential in the loop takes seconds here. The walk has no step count to read
     * without a test-only member, so the test times it.
     */
    private static final long LOOP_BUDGET_NANOS = 50_000_000L;

    private static final int LOOP_BUILDS = 5;

    @GameTest
    public void aLongRecipeLoopBuildsQuickly(GameTestHelper helper) {
        // The shown recipe takes a head item, made from the first item of a loop; loop item i is made only from items
        // i+1 and i+2, wrapping round. Whether the head's recipe undoes another asks whether the first loop item is
        // made solely from the head, and no path round the loop reaches it: the walk must not try every such path.
        List<Item> items = BuiltInRegistries.ITEM.stream()
                .filter(item -> ObtainableItems.isObtainable(item.getDefaultInstance()))
                .limit(LOOP_ITEMS + 2)
                .toList();
        Item shown = items.get(0);
        Item head = items.get(1);
        List<Item> loop = items.subList(2, items.size());
        List<KnownRecipe> recipes = new ArrayList<>();
        recipes.add(recipe(helper, shown, 1, item(head)));
        recipes.add(recipe(helper, head, 1, item(loop.getFirst())));
        for (int i = 0; i < LOOP_ITEMS; i++) {
            recipes.add(recipe(
                    helper,
                    loop.get(i),
                    1,
                    item(loop.get((i + 1) % LOOP_ITEMS)),
                    item(loop.get((i + 2) % LOOP_ITEMS))));
        }
        Function<Item, List<KnownRecipe>> known = known(recipes.toArray(KnownRecipe[]::new));
        MaterialsTree tree = MaterialsTree.of(recipes.getFirst(), known, stock());
        for (int warm = 1; warm < LOOP_BUILDS; warm++) {
            MaterialsTree.of(recipes.getFirst(), known, stock());
        }
        long fastest = Long.MAX_VALUE;
        for (int build = 0; build < LOOP_BUILDS; build++) {
            long start = System.nanoTime();
            MaterialsTree.of(recipes.getFirst(), known, stock());
            fastest = Math.min(fastest, System.nanoTime() - start);
        }
        expect(
                helper,
                fastest < LOOP_BUDGET_NANOS,
                "one tree over a loop of %s items builds in %s ms",
                LOOP_ITEMS,
                fastest / 1e6);
        // The head is made from the first loop item, whose recipe undoes the last item's, so it is a base material.
        expectMaterials(helper, tree, "the head is made from the first loop item", loop.getFirst(), 1, 0);
        expect(helper, node(helper, tree, head).recipe() == recipes.get(1), "the head is made by its recipe");
        helper.succeed();
    }

    /** The tree's nodes, the root's included. */
    private static int size(Node node) {
        int size = 1;
        for (Node child : node.children()) {
            size += size(child);
        }
        return size;
    }

    /**
     * A dense tangle of {@code items}, each made by its first recipe from the two before it: the first three are base materials,
     * and each other item has two recipes of two cells, each cell taking either of two of the three items before it. Unfolded, the
     * tree of item i has a node for every path down, 2 * Fibonacci(i) - 1 of them.
     */
    private static Function<Item, List<KnownRecipe>> tangle(GameTestHelper helper, List<Item> items) {
        List<KnownRecipe> recipes = new ArrayList<>();
        for (int i = 3; i < items.size(); i++) {
            SlotDisplay near = either(items.get(i - 1), items.get(i - 2));
            SlotDisplay far = either(items.get(i - 2), items.get(i - 3));
            SlotDisplay skip = either(items.get(i - 1), items.get(i - 3));
            recipes.add(recipe(helper, items.get(i), 1, near, far));
            recipes.add(recipe(helper, items.get(i), 1, skip, far));
        }
        return known(recipes.toArray(KnownRecipe[]::new));
    }

    private static SlotDisplay either(Item first, Item second) {
        return new SlotDisplay.Composite(List.of(item(first), item(second)));
    }

    /** Items in the dense tangle: unfolded, the tree of its last item has 8,361 nodes. */
    private static final int TANGLE_ITEMS = 20;

    /** Well above the tree's node budget, which lets the crafts under way finish their cells; far below the unfolded tree. */
    private static final int TANGLE_MAX_NODES = 1_000;

    @GameTest
    public void aDenseRecipeTangleKeepsTheTreeSmall(GameTestHelper helper) {
        List<Item> items = BuiltInRegistries.ITEM.stream()
                .filter(item -> ObtainableItems.isObtainable(item.getDefaultInstance()))
                .limit(TANGLE_ITEMS)
                .toList();
        Function<Item, List<KnownRecipe>> known = tangle(helper, items);
        MaterialsTree tree = MaterialsTree.of(known.apply(items.getLast()).getFirst(), known, stock());
        expect(
                helper,
                size(tree.root()) <= TANGLE_MAX_NODES,
                "a tangle of %s items builds a tree of %s nodes",
                TANGLE_ITEMS,
                size(tree.root()));
        expect(helper, tree.cut(), "the tree says it was cut short");
        expect(
                helper,
                tree.headingKey().equals("deepcharter.catalogue.materials.cut"),
                "the heading says the totals are partial, got %s",
                tree.headingKey());
        // With nothing held, a material a known recipe makes is listed only because the tree stopped short of it.
        expect(
                helper,
                tree.materials().stream()
                        .allMatch(material ->
                                material.cut() == !known.apply(material.item()).isEmpty()),
                "exactly the rows not broken down are marked, got %s",
                tree.materials());
        expect(
                helper,
                tree.materials().stream()
                        .allMatch(material -> material.figures()
                                .equals((material.cut() ? MaterialsTree.MARK : "") + material.have() + "/"
                                        + material.need())),
                "a marked row leads with the mark, got %s",
                tree.materials().stream().map(Material::figures).toList());

        // A small tangle breaks down in full, to items 2 and 1 the Fibonacci numbers 8 and 5 of times.
        List<Item> few = items.subList(0, 8);
        Function<Item, List<KnownRecipe>> knownFew = tangle(helper, few);
        MaterialsTree whole = MaterialsTree.of(knownFew.apply(few.getLast()).getFirst(), knownFew, stock());
        expect(helper, !whole.cut(), "a small tangle is not cut short, got %s", whole.materials());
        expect(
                helper,
                whole.headingKey().equals("deepcharter.catalogue.materials"),
                "a whole tree has the plain heading, got %s",
                whole.headingKey());
        expect(
                helper,
                whole.materials().stream()
                        .noneMatch(material -> material.figures().startsWith(MaterialsTree.MARK)),
                "a whole tree marks no row, got %s",
                whole.materials().stream().map(Material::figures).toList());
        expectMaterials(helper, whole, "a small tangle down to its base materials", few.get(2), 8, 0, few.get(1), 5, 0);
        MaterialsTree held = MaterialsTree.of(
                knownFew.apply(few.getLast()).getFirst(),
                knownFew,
                stock(few.get(0), 64, few.get(1), 64, few.get(2), 64));
        expect(
                helper,
                held.headingKey().equals("deepcharter.catalogue.materials.all")
                        && held.materials().stream().allMatch(held::held),
                "a whole tree held in full says so and shows every row held, got %s: %s",
                held.headingKey(),
                held.materials());
        helper.succeed();
    }

    @GameTest
    public void aCutTreeTakesFromStockAndShowsNoRowHeld(GameTestHelper helper) {
        List<Item> obtainable = BuiltInRegistries.ITEM.stream()
                .filter(item -> ObtainableItems.isObtainable(item.getDefaultInstance()))
                .limit(TANGLE_ITEMS + 3)
                .toList();
        List<Item> items = obtainable.subList(0, TANGLE_ITEMS);
        Item cutItem = obtainable.get(TANGLE_ITEMS);
        Item base = obtainable.get(TANGLE_ITEMS + 1);
        Item shown = obtainable.get(TANGLE_ITEMS + 2);
        // Three of the tangle's last item come first (the most needed), so the budget is spent before the two of an
        // item a known
        // recipe makes, of which one is held, and the one of a base material, held.
        KnownRecipe top = recipe(
                helper,
                shown,
                1,
                item(items.getLast()),
                item(items.getLast()),
                item(items.getLast()),
                item(cutItem),
                item(cutItem),
                item(base));
        KnownRecipe makeCut = recipe(helper, cutItem, 1, item(items.getFirst()));
        Function<Item, List<KnownRecipe>> tangled = tangle(helper, items);
        Function<Item, List<KnownRecipe>> known =
                item -> item == shown ? List.of(top) : item == cutItem ? List.of(makeCut) : tangled.apply(item);
        MaterialsTree tree = MaterialsTree.of(top, known, stock(cutItem, 1, base, 1));
        expect(helper, tree.cut(), "the tree says it was cut short");
        Node cutNode = node(helper, tree, cutItem);
        expect(
                helper,
                cutNode.cut() && cutNode.fromStock() == 1 && cutNode.missing() == 1,
                "a cut node still takes the one held, got %s",
                cutNode);
        Material cutRow = material(helper, tree, cutItem);
        expect(
                helper,
                cutRow.cut() && cutRow.have() == 1 && cutRow.need() == 2 && cutRow.shortBy() == 1,
                "the cut row counts the held one in its need, got %s",
                cutRow);
        Material baseRow = material(helper, tree, base);
        expect(
                helper,
                baseRow.shortBy() == 0 && !tree.held(baseRow),
                "a row the player holds is not shown held in a cut tree, got %s",
                baseRow);
        helper.succeed();
    }

    private static Material material(GameTestHelper helper, MaterialsTree tree, Item item) {
        return tree.materials().stream()
                .filter(material -> material.item() == item)
                .findFirst()
                .orElseThrow(() -> failure(helper, "no row for %s: %s", item, tree.materials()));
    }

    @GameTest
    public void theShownRecipeTakesItselfFromStock(GameTestHelper helper) {
        // A recipe that copies its own result, the way a smithing template does (no template is obtainable here):
        // a stick and a plank make two sticks.
        KnownRecipe copy = recipe(helper, Items.STICK, 2, STICK, PLANKS);
        MaterialsTree tree = MaterialsTree.of(copy, known(copy), stock(Items.STICK, 1, Items.OAK_PLANKS, 1));
        Node cell = tree.root().children().getFirst();
        expect(
                helper,
                cell.item() == Items.STICK && cell.fromStock() == 1 && cell.missing() == 0,
                "the stick it takes is the one held, got %s",
                cell);
        helper.succeed();
    }
}
