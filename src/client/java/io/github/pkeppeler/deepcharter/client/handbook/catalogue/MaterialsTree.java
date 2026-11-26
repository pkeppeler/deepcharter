package io.github.pkeppeler.deepcharter.client.handbook.catalogue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import org.jspecify.annotations.Nullable;

/**
 * What one craft of a known recipe takes in total, down through the sub-recipes the charter knows (#1378, EMI's recipe tree). An
 * ingredient with no known recipe is a base material: the tree follows only the recipes it is given, so it reveals nothing the recipe
 * book does not (SPEC §5). No drawing here.
 *
 * <p>The rules, in the order the tree is walked (depth first, ingredients in the order {@link KnownRecipe#needs} lists them):
 * <ul>
 * <li>A need is met first from what an earlier craft of the tree made too many of, then from the player's stock, and only the rest is
 * made: an intermediate the player holds is not broken down. The shown recipe's own result is never taken from stock in place of
 * the craft, but a cell of it that takes that result (a smithing template copying itself) is met from stock and never crafted.
 * <li>An intermediate is made by its first known recipe that neither undoes another nor takes an item already being made further up
 * this branch, so a recipe loop ends; with no such recipe it is a base material. A recipe for X undoes another when some cell takes
 * only items made solely from X. Y is made solely from X when it has a known recipe and every one has a cell taking only X or items
 * themselves made solely from X, at any depth; an item with no known recipe is not. So ingots are not unpacked from an iron block or
 * nuggets made only from ingots, but an iron block is packed from ingots, since ingots also come from smelting. The limit: when X
 * and Y are each made only from the other, each recipe undoes the other and neither is followed, so both stay base materials.
 * <li>Salvage is never followed below the shown recipe, nor counted as a way to make anything: a furnace recipe that takes worn gear
 * (a damageable item), like iron nuggets smelted from iron tools and armour. Otherwise nuggets would not count as made solely from
 * ingots, and an anvil would come to hundreds of pickaxes.
 * <li>It is made as many times as the need takes, rounding up; what that makes beyond the need feeds later needs.
 * <li>A cell that takes several items (a tag) takes the one the player has most of, or with none held, the first that a known recipe
 * can make, or else the first.
 * <li>Once the tree has {@link #NODE_BUDGET} nodes, nothing more is broken down: an item a known recipe would make still takes from
 * the leftovers and the stock, and the rest is cut, listed as it is and marked. A dense tangle of recipes would otherwise unfold into
 * a node for every path down, exponential in the items, and the tree is built on the render thread. The budget is far above the
 * largest tree of the shipped recipes. Sharing repeated subtrees would not keep the totals: each need takes the leftovers and the
 * stock as the walk reaches it, and which recipe and tag member it takes depends on what is being made above it.
 * <li>A cut tree's totals are partial: the materials under a cut item are not counted, so a row may be short even when the player
 * holds its count, and no row is shown as held ({@link #held}).
 * </ul>
 *
 * @param root the shown recipe, made once
 * @param materials what the craft takes from the player or must still be found: each item once, what is short first
 */
public record MaterialsTree(Node root, List<Material> materials) {
    /**
     * One item of the tree and how its need is met.
     *
     * @param item the item
     * @param needed how many the parent's crafts take (for the root, how many one craft makes)
     * @param fromStock how many come from the player's stock
     * @param fromSpare how many come from what an earlier craft made too many of
     * @param recipe the known recipe it is made by, or null when none is made
     * @param crafts how many times that recipe is made
     * @param children what those crafts take
     * @param cut whether a known recipe makes it but the tree stopped short of it, its node budget spent
     */
    public record Node(
            Item item,
            int needed,
            int fromStock,
            int fromSpare,
            @Nullable KnownRecipe recipe,
            int crafts,
            List<Node> children,
            boolean cut) {
        /** How many must still be found: what neither the stock, the leftovers nor a known recipe covers. */
        public int missing() {
            return recipe == null ? needed - fromStock - fromSpare : 0;
        }
    }

    /**
     * An item the craft takes from the player or must be found.
     *
     * @param item the item
     * @param need how many the craft takes of it in all
     * @param have how many the player holds
     * @param cut whether the tree stopped short of breaking some of it down: it is made from materials not listed
     */
    public record Material(Item item, int need, int have, boolean cut) {
        /** How many more the player must find. */
        public int shortBy() {
            return Math.max(0, need - have);
        }

        /** Its row's have/need, {@code 3/5}, after {@link #MARK} when it is cut, so a narrow row trimmed from the end keeps it. */
        public String figures() {
            return (cut ? MARK : "") + have + "/" + need;
        }
    }

    /** What marks a material the tree stopped short of breaking down; the cut heading explains it. */
    public static final String MARK = "*";

    /** How many nodes the tree breaks down to at most, before the crafts under way finish their cells. */
    private static final int NODE_BUDGET = 500;

    /** Whether the tree stopped short of breaking some material down ({@link #NODE_BUDGET}). */
    public boolean cut() {
        return materials.stream().anyMatch(Material::cut);
    }

    /** Whether the player holds everything the craft takes. */
    public boolean allHeld() {
        return materials.stream().allMatch(material -> material.shortBy() == 0);
    }

    /** Whether {@code material}'s row may show it as held: the player holds its count and the tree was not cut short. */
    public boolean held(Material material) {
        return material.shortBy() == 0 && !cut();
    }

    /** The lang key of the heading over the rows: that the player holds it all, that the totals are partial, or plain. */
    public String headingKey() {
        if (allHeld()) {
            return "deepcharter.catalogue.materials.all";
        }
        return cut() ? "deepcharter.catalogue.materials.cut" : "deepcharter.catalogue.materials";
    }

    /**
     * The tree of one craft of {@code recipe}, following the recipes {@code known} lists for an item (the catalogue's {@code
     * recipesFor}), with {@code stock} the player's items and their counts.
     */
    public static MaterialsTree of(
            KnownRecipe recipe, Function<Item, List<KnownRecipe>> known, Map<Item, Integer> stock) {
        Walk walk = new Walk(known, stock);
        Item made = recipe.results().getFirst().getItem();
        int makes = makes(recipe, made);
        Node root = walk.made(made, makes, 0, 0, recipe, makes);
        Map<Item, Integer> needs = new LinkedHashMap<>();
        Set<Item> cut = new HashSet<>();
        tally(root, needs, cut);
        List<Material> materials = new ArrayList<>();
        needs.forEach((item, need) ->
                materials.add(new Material(item, need, stock.getOrDefault(item, 0), cut.contains(item))));
        // What is short first; the sort is stable, so each group keeps the order the tree met them in.
        materials.sort(Comparator.comparing((Material material) -> material.shortBy() == 0));
        return new MaterialsTree(root, List.copyOf(materials));
    }

    /**
     * Adds what {@code node} and the nodes under it take from the stock or leave missing to {@code needs}, and the items of the cut
     * nodes to {@code cut}.
     */
    private static void tally(Node node, Map<Item, Integer> needs, Set<Item> cut) {
        int taken = node.fromStock() + node.missing();
        if (taken > 0) {
            needs.merge(node.item(), taken, Integer::sum);
        }
        if (node.cut()) {
            cut.add(node.item());
        }
        for (Node child : node.children()) {
            tally(child, needs, cut);
        }
    }

    /** How many of {@code item} one craft of {@code recipe} makes; at least one. */
    private static int makes(KnownRecipe recipe, Item item) {
        for (ItemStack stack : recipe.results()) {
            if (stack.is(item)) {
                return Math.max(1, stack.getCount());
            }
        }
        return 1;
    }

    /** Whether {@code recipe} smelts worn gear down (salvage): a furnace recipe with a damageable item in a cell. */
    private static boolean salvage(KnownRecipe recipe) {
        return recipe.entry().display() instanceof FurnaceRecipeDisplay
                && recipe.cells().stream().flatMap(List::stream).anyMatch(ItemStack::isDamageableItem);
    }

    /** One walk of the tree: the stock and the leftovers it uses up, the items being made on the branch walked, and its size. */
    private static final class Walk {
        private final Function<Item, List<KnownRecipe>> known;
        private final Map<Item, Integer> stock;
        private final Map<Item, Integer> spare = new HashMap<>();
        private final Set<Item> branch = new HashSet<>();
        private final Map<Item, List<KnownRecipe>> followed = new HashMap<>();
        /** For each X asked about, whether an item is made solely from X. */
        private final Map<Item, Map<Item, Boolean>> solelyFrom = new HashMap<>();
        /** The nodes made so far. */
        private int size;

        Walk(Function<Item, List<KnownRecipe>> known, Map<Item, Integer> stock) {
            this.known = known;
            this.stock = new HashMap<>(stock);
        }

        /**
         * {@code needed} of {@code item}: from the leftovers, the stock, then, when {@code craft}, its recipe, unless the tree has
         * reached its {@link #NODE_BUDGET}.
         */
        private Node node(Item item, int needed, boolean craft) {
            int fromSpare = take(spare, item, needed);
            int fromStock = take(stock, item, needed - fromSpare);
            int rest = needed - fromSpare - fromStock;
            KnownRecipe recipe = rest == 0 || !craft ? null : recipeFor(item);
            if (recipe == null || size >= NODE_BUDGET) {
                size++;
                return new Node(item, needed, fromStock, fromSpare, null, 0, List.of(), recipe != null);
            }
            return made(item, needed, fromStock, fromSpare, recipe, rest);
        }

        /** {@code item} made by {@code recipe}, enough times for {@code rest}. */
        Node made(Item item, int needed, int fromStock, int fromSpare, KnownRecipe recipe, int rest) {
            int makes = makes(recipe, item);
            int crafts = Math.ceilDiv(rest, makes);
            size++;
            branch.add(item);
            List<Node> children = new ArrayList<>();
            for (KnownRecipe.Need need : recipe.needs()) {
                Item choice = choose(need);
                int count = need.count() * crafts;
                // A cell taking only an item being made (the shown recipe copying itself) is not crafted: the loop
                // ends there.
                children.add(node(choice, count, !branch.contains(choice)));
            }
            branch.remove(item);
            spare.merge(item, crafts * makes - rest, Integer::sum);
            return new Node(item, needed, fromStock, fromSpare, recipe, crafts, List.copyOf(children), false);
        }

        /**
         * The first known recipe of {@code item} that does not undo another and whose every cell can take something not being made on
         * this branch, or null.
         */
        private @Nullable KnownRecipe recipeFor(Item item) {
            for (KnownRecipe recipe : recipes(item)) {
                if (!undoes(recipe, item)
                        && recipe.needs().stream()
                                .allMatch(need -> need.choices().stream()
                                        .anyMatch(choice -> !choice.is(item) && !branch.contains(choice.getItem())))) {
                    return recipe;
                }
            }
            return null;
        }

        /** The known recipes of {@code item} the tree may follow: salvage left out. */
        private List<KnownRecipe> recipes(Item item) {
            return followed.computeIfAbsent(
                    item,
                    key -> known.apply(key).stream()
                            .filter(recipe -> !salvage(recipe))
                            .toList());
        }

        /**
         * Whether {@code recipe}, which makes {@code item}, undoes a known recipe: some cell of it takes only items made solely from
         * {@code item} (ingots from an iron block, or from nuggets, when the block and the nuggets are made only from ingots). Each
         * answer is worked out once per {@code item} by {@link #solely}.
         */
        private boolean undoes(KnownRecipe recipe, Item item) {
            Map<Item, Boolean> settled = solelyFrom.computeIfAbsent(item, key -> new HashMap<>());
            return recipe.needs().stream()
                    .anyMatch(need ->
                            need.choices().stream().allMatch(choice -> solely(choice.getItem(), item, settled)));
        }

        /**
         * Whether {@code item} is {@code from}, or has a known recipe and every one has a cell taking only {@code from} or items made
         * solely from it, without going round a loop: the item has a derivation from {@code from} that never needs itself.
         *
         * <p>A least fixpoint over the items {@code item} reaches through known recipes, stopping at {@code from} and at items already
         * in {@code settled}: start from {@code from} and the settled items made solely from it, add every reached item whose every
         * recipe has a cell taking only items already added, and repeat until none is added. Every reached item's answer goes into
         * {@code settled}: added is made solely from {@code from}, the rest is not. An item on a loop that never reaches {@code from}
         * is never added, so the loop leaves it out without any order of visits mattering. Each pass is linear in the choices of the
         * reached items' recipes and at most one pass more than the reached items is needed, so the cost is polynomial in the items,
         * paid once per item for each {@code from}.
         */
        private boolean solely(Item item, Item from, Map<Item, Boolean> settled) {
            if (item == from) {
                return true;
            }
            Boolean answer = settled.get(item);
            if (answer != null) {
                return answer;
            }
            Set<Item> reached = new LinkedHashSet<>();
            List<Item> toVisit = new ArrayList<>(List.of(item));
            while (!toVisit.isEmpty()) {
                Item next = toVisit.removeLast();
                if (next == from || settled.containsKey(next) || !reached.add(next)) {
                    continue;
                }
                for (KnownRecipe recipe : recipes(next)) {
                    for (KnownRecipe.Need need : recipe.needs()) {
                        for (ItemStack choice : need.choices()) {
                            toVisit.add(choice.getItem());
                        }
                    }
                }
            }
            Set<Item> made = new HashSet<>();
            Predicate<Item> madeSolely =
                    choice -> choice == from || made.contains(choice) || settled.getOrDefault(choice, false);
            boolean grew = true;
            while (grew) {
                grew = false;
                for (Item next : reached) {
                    List<KnownRecipe> recipes = recipes(next);
                    if (!made.contains(next)
                            && !recipes.isEmpty()
                            && recipes.stream()
                                    .allMatch(recipe -> recipe.needs().stream()
                                            .anyMatch(need -> need.choices().stream()
                                                    .allMatch(choice -> madeSolely.test(choice.getItem()))))) {
                        made.add(next);
                        grew = true;
                    }
                }
            }
            for (Item next : reached) {
                settled.put(next, made.contains(next));
            }
            return made.contains(item);
        }

        /**
         * The item a cell of {@code need} takes: of those not being made on this branch, the one most of is at hand, or with none at hand
         * the first that a known recipe can make, or the first. A cell that takes only items being made gets its first: the loop ends there.
         */
        private Item choose(KnownRecipe.Need need) {
            List<Item> open = new ArrayList<>();
            for (ItemStack choice : need.choices()) {
                if (!branch.contains(choice.getItem()) && !open.contains(choice.getItem())) {
                    open.add(choice.getItem());
                }
            }
            if (open.isEmpty()) {
                return need.choices().getFirst().getItem();
            }
            Item most = open.getFirst();
            for (Item item : open) {
                if (atHand(item) > atHand(most)) {
                    most = item;
                }
            }
            if (atHand(most) > 0) {
                return most;
            }
            return open.stream()
                    .filter(item -> recipeFor(item) != null)
                    .findFirst()
                    .orElse(open.getFirst());
        }

        private int atHand(Item item) {
            return spare.getOrDefault(item, 0) + stock.getOrDefault(item, 0);
        }

        private static int take(Map<Item, Integer> from, Item item, int wanted) {
            int held = from.getOrDefault(item, 0);
            int taken = Math.min(held, wanted);
            if (taken > 0) {
                from.put(item, held - taken);
            }
            return taken;
        }
    }
}
