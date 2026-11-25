package io.github.pkeppeler.deepcharter.client.handbook.catalogue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * What one craft of a known recipe takes in total, down through the sub-recipes the charter knows (#1378, EMI's recipe tree). An
 * ingredient with no known recipe is a base material: the tree follows only the recipes it is given, so it reveals nothing the recipe
 * book does not (SPEC §5). No drawing here.
 *
 * <p>The rules, in the order the tree is walked (depth first, ingredients in the order {@link KnownRecipe#needs} lists them):
 * <ul>
 * <li>A need is met first from what an earlier craft of the tree made too many of, then from the player's stock, and only the rest is
 * made: an intermediate the player holds is not broken down. The shown recipe's own result is never taken from stock.
 * <li>An intermediate is made by its first known recipe that neither undoes another known recipe (ingots from an iron block, when the
 * block is made from ingots) nor takes an item already being made further up this branch, so a recipe loop ends; with no such recipe
 * it is a base material.
 * <li>It is made as many times as the need takes, rounding up; what that makes beyond the need feeds later needs.
 * <li>A cell that takes several items (a tag) takes the one the player has most of, or with none held, the first that a known recipe
 * can make, or else the first.
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
     */
    public record Node(
            Item item,
            int needed,
            int fromStock,
            int fromSpare,
            @Nullable KnownRecipe recipe,
            int crafts,
            List<Node> children) {
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
     */
    public record Material(Item item, int need, int have) {
        /** How many more the player must find. */
        public int shortBy() {
            return Math.max(0, need - have);
        }
    }

    /** Whether the player holds everything the craft takes. */
    public boolean allHeld() {
        return materials.stream().allMatch(material -> material.shortBy() == 0);
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
        tally(root, needs);
        List<Material> materials = new ArrayList<>();
        needs.forEach((item, need) -> materials.add(new Material(item, need, stock.getOrDefault(item, 0))));
        // What is short first; the sort is stable, so each group keeps the order the tree met them in.
        materials.sort(Comparator.comparing((Material material) -> material.shortBy() == 0));
        return new MaterialsTree(root, List.copyOf(materials));
    }

    /** Adds what {@code node} and the nodes under it take from the stock or leave missing to {@code needs}. */
    private static void tally(Node node, Map<Item, Integer> needs) {
        int taken = node.fromStock() + node.missing();
        if (taken > 0) {
            needs.merge(node.item(), taken, Integer::sum);
        }
        for (Node child : node.children()) {
            tally(child, needs);
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

    /** One walk of the tree: the stock and the leftovers it uses up, and the items being made on the branch walked. */
    private static final class Walk {
        private final Function<Item, List<KnownRecipe>> known;
        private final Map<Item, Integer> stock;
        private final Map<Item, Integer> spare = new HashMap<>();
        private final Set<Item> branch = new HashSet<>();

        Walk(Function<Item, List<KnownRecipe>> known, Map<Item, Integer> stock) {
            this.known = known;
            this.stock = new HashMap<>(stock);
        }

        /** {@code needed} of {@code item}: from the leftovers, the stock, then its recipe. */
        Node node(Item item, int needed) {
            int fromSpare = take(spare, item, needed);
            int fromStock = take(stock, item, needed - fromSpare);
            int rest = needed - fromSpare - fromStock;
            KnownRecipe recipe = rest == 0 ? null : recipeFor(item);
            if (recipe == null) {
                return new Node(item, needed, fromStock, fromSpare, null, 0, List.of());
            }
            return made(item, needed, fromStock, fromSpare, recipe, rest);
        }

        /** {@code item} made by {@code recipe}, enough times for {@code rest}. */
        Node made(Item item, int needed, int fromStock, int fromSpare, KnownRecipe recipe, int rest) {
            int makes = makes(recipe, item);
            int crafts = Math.ceilDiv(rest, makes);
            branch.add(item);
            List<Node> children = new ArrayList<>();
            for (KnownRecipe.Need need : recipe.needs()) {
                Item choice = choose(need);
                int count = need.count() * crafts;
                children.add(
                        branch.contains(choice)
                                ? new Node(choice, count, 0, 0, null, 0, List.of())
                                : node(choice, count));
            }
            branch.remove(item);
            spare.merge(item, crafts * makes - rest, Integer::sum);
            return new Node(item, needed, fromStock, fromSpare, recipe, crafts, List.copyOf(children));
        }

        /**
         * The first known recipe of {@code item} that does not undo another and whose every cell can take something not being made on
         * this branch, or null.
         */
        private @Nullable KnownRecipe recipeFor(Item item) {
            for (KnownRecipe recipe : known.apply(item)) {
                if (!undoes(recipe, item)
                        && recipe.needs().stream()
                                .allMatch(need -> need.choices().stream()
                                        .anyMatch(choice -> !choice.is(item) && !branch.contains(choice.getItem())))) {
                    return recipe;
                }
            }
            return null;
        }

        /**
         * Whether {@code recipe}, which makes {@code item}, undoes a known recipe: some cell of it takes only items a known recipe makes
         * from {@code item} (ingots from an iron block, or from nuggets, when the block and the nuggets are made from ingots).
         */
        private boolean undoes(KnownRecipe recipe, Item item) {
            return recipe.needs().stream()
                    .anyMatch(need -> need.choices().stream()
                            .allMatch(choice -> known.apply(choice.getItem()).stream()
                                    .anyMatch(back -> back.ingredientItems().contains(item))));
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
            int taken = Math.min(held, Math.max(0, wanted));
            if (taken > 0) {
                from.put(item, held - taken);
            }
            return taken;
        }
    }
}
