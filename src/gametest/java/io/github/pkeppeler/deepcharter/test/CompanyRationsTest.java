package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.colony.ColonyRations;
import io.github.pkeppeler.deepcharter.colony.OreCarBlock;
import io.github.pkeppeler.deepcharter.colony.RationLedger;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.TestClocks;
import io.github.pkeppeler.deepcharter.test.support.UnreadableChecks;
import java.util.Map;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Company rations (#838): the starting tins, the ore cars' last-shift tins, the canteen dispenser's one ration a day, and the
 * stalk that cooks into a poor meal. Counts are literals on purpose: they are the design, not a copy of the tuning.
 */
public class CompanyRationsTest {
    private static final int STARTING_RATIONS = 3;
    private static final int CAR_RATIONS = 2;
    private static final long DAY_TICKS = 24_000;
    private static final BlockPos BLOCK = new BlockPos(1, 1, 0);

    private static Item item(String path) {
        return BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path));
    }

    private static Block block(String path) {
        return BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path));
    }

    private static int rations(ServerPlayer player) {
        Item ration = item("company_ration");
        int held = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(ration)) {
                held += stack.getCount();
            }
        }
        return held;
    }

    private static void clearRations(ServerPlayer player) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (player.getInventory().getItem(slot).is(item("company_ration"))) {
                player.getInventory().setItem(slot, ItemStack.EMPTY);
            }
        }
    }

    /** Uses the block at {@code pos} as {@code player} does, through the block as the game calls it. */
    private static void use(GameTestHelper helper, ServerPlayer player, BlockPos pos) {
        helper.getLevel()
                .getBlockState(pos)
                .useWithoutItem(
                        helper.getLevel(), player, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
    }

    private static boolean said(MockPlayer mock, String key) {
        return mock.actionBarMessages().stream().anyMatch(message -> isKey(message, key));
    }

    private static boolean isKey(Component message, String key) {
        return message.getContents() instanceof TranslatableContents contents
                && contents.getKey().equals(key);
    }

    private static BlockState freshCar() {
        return block("ore_car").defaultBlockState().setValue(OreCarBlock.LOOTED, false);
    }

    private static void awaitStarters(GameTestHelper helper, ServerPlayer player) {
        if (rations(player) != STARTING_RATIONS) {
            throw helper.assertionException("waiting for the starting rations, the player holds %s", rations(player));
        }
    }

    @GameTest(maxTicks = 100)
    public void aNewPlayerStartsWithRationsOnceAndNotOnEveryJoin(GameTestHelper helper) {
        ServerPlayer player = MockPlayers.join(helper, "Newhire").player();
        boolean[] rejoined = {false};
        Findings found = new Findings();
        helper.succeedWhen(() -> {
            if (!rejoined[0]) {
                awaitStarters(helper, player);
                clearRations(player);
                // The real join event again, as a second login fires it.
                ServerPlayerEvents.JOIN.invoker().onJoin(player);
                rejoined[0] = true;
            }
            found.expect(player, 0, "a player who was given the starting rations is given none on a later join");
            found.ensure(helper);
        });
    }

    @GameTest(maxTicks = 100)
    public void theRationIsEdibleFoodWithSensibleNutrition(GameTestHelper helper) {
        FoodProperties food = new ItemStack(item("company_ration")).get(DataComponents.FOOD);
        if (food == null) {
            throw helper.assertionException("the Company ration is not food");
        }
        if (food.nutrition() < 4 || food.nutrition() > 8) {
            throw helper.assertionException("a ration restores 4 to 8 hunger, it restores %s", food.nutrition());
        }
        FoodProperties meal = new ItemStack(item("stalk_meal")).get(DataComponents.FOOD);
        if (meal == null) {
            throw helper.assertionException("the stalk meal is not food");
        }
        if (meal.nutrition() >= food.nutrition() || meal.nutrition() < 2) {
            throw helper.assertionException(
                    "the poor meal restores 2 or more and less than a ration (%s), it restores %s",
                    food.nutrition(), meal.nutrition());
        }
        if (new ItemStack(item("company_ration")).get(DataComponents.CONSUMABLE) == null
                || new ItemStack(item("stalk_meal")).get(DataComponents.CONSUMABLE) == null) {
            throw helper.assertionException("food that cannot be consumed cannot be eaten");
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 100)
    public void anOreCarGivesItsTinsOnceThenTheEmptyLine(GameTestHelper helper) {
        MockPlayer mock = MockPlayers.join(helper, "Hand");
        ServerPlayer player = mock.player();
        BlockPos pos = helper.absolutePos(BLOCK);
        boolean[] used = {false};
        Findings found = new Findings();
        helper.succeedWhen(() -> {
            if (!used[0]) {
                awaitStarters(helper, player);
                used[0] = true;
                helper.getLevel().setBlock(pos, freshCar(), 3);
                use(helper, player, pos);
                found.expect(player, STARTING_RATIONS + CAR_RATIONS, "the first use of a car gives its tins");
                if (!helper.getLevel().getBlockState(pos).getValue(OreCarBlock.LOOTED)) {
                    found.note("a car that gave its tins is marked looted");
                }
                use(helper, player, pos);
                found.expect(player, STARTING_RATIONS + CAR_RATIONS, "a looted car gives nothing more");
            }
            found.ensure(helper);
            if (!said(mock, "colony.deepcharter.ore_car.found") || !said(mock, "colony.deepcharter.ore_car.empty")) {
                throw helper.assertionException(
                        "the car says what was found, then that it is empty; the player heard %s",
                        mock.actionBarMessages());
            }
        });
    }

    @GameTest(maxTicks = 100)
    public void aLootedCarStaysLootedForAnotherPlayer(GameTestHelper helper) {
        ServerPlayer first = MockPlayers.join(helper, "First").player();
        ServerPlayer second = MockPlayers.join(helper, "Second").player();
        BlockPos pos = helper.absolutePos(BLOCK);
        boolean[] used = {false};
        Findings found = new Findings();
        helper.succeedWhen(() -> {
            if (!used[0]) {
                awaitStarters(helper, first);
                awaitStarters(helper, second);
                used[0] = true;
                helper.getLevel().setBlock(pos, freshCar(), 3);
                use(helper, first, pos);
                use(helper, second, pos);
            }
            found.expect(first, STARTING_RATIONS + CAR_RATIONS, "the car is one find for the whole crew");
            found.expect(second, STARTING_RATIONS, "the second player finds a car already emptied");
            found.ensure(helper);
        });
    }

    @GameTest(maxTicks = 100)
    public void theCanteenGivesOneRationPerPlayerPerDayAndRefusesASecond(GameTestHelper helper) {
        MockPlayer mock = MockPlayers.join(helper, "Miner");
        ServerPlayer player = mock.player();
        ServerPlayer other = MockPlayers.join(helper, "Mate").player();
        MinecraftServer server = helper.getLevel().getServer();
        BlockPos pos = helper.absolutePos(BLOCK);
        boolean[] done = {false};
        Findings found = new Findings();
        helper.succeedWhen(() -> {
            if (!done[0]) {
                awaitStarters(helper, player);
                awaitStarters(helper, other);
                done[0] = true;
                helper.getLevel().setBlock(pos, block("canteen").defaultBlockState(), 3);
                TestClocks.paused(server, overworldClock(server), 5 * DAY_TICKS + 1000, () -> {
                    use(helper, player, pos);
                    use(helper, player, pos);
                    use(helper, other, pos);
                });
                found.expect(player, STARTING_RATIONS + 1, "one ration a day, a second use refused");
                found.expect(other, STARTING_RATIONS + 1, "each player has a ration of their own each day");
                TestClocks.paused(
                        server, overworldClock(server), 5 * DAY_TICKS + 20_000, () -> use(helper, player, pos));
                found.expect(player, STARTING_RATIONS + 1, "late the same day is the same day");
                TestClocks.paused(server, overworldClock(server), 6 * DAY_TICKS + 100, () -> use(helper, player, pos));
                found.expect(player, STARTING_RATIONS + 2, "the next day brings the next ration");
            }
            found.ensure(helper);
            if (!said(mock, "colony.deepcharter.canteen.dispensed")
                    || !said(mock, "colony.deepcharter.canteen.refused")) {
                throw helper.assertionException(
                        "the canteen says what it did, the player heard %s", mock.actionBarMessages());
            }
        });
    }

    private static Holder<WorldClock> overworldClock(MinecraftServer server) {
        return server.overworld().dimensionType().defaultClock().orElseThrow();
    }

    /**
     * Notes the first thing that is wrong. A check that runs once, inside {@code succeedWhen}, cannot throw: the poll catches it and
     * the next poll skips the check and passes. {@link #ensure} throws on every poll after, so the test fails at its limit.
     */
    private static final class Findings {
        private @Nullable String first;

        void note(String problem) {
            if (first == null) {
                first = problem;
            }
        }

        void expect(ServerPlayer player, int expected, String why) {
            if (rations(player) != expected) {
                note(why + ": expected " + expected + " rations, "
                        + player.getGameProfile().name() + " holds " + rations(player));
            }
        }

        void ensure(GameTestHelper helper) {
            if (first != null) {
                throw helper.assertionException("%s", first);
            }
        }
    }

    @GameTest(maxTicks = 100)
    public void theCanteenDayIsRememberedInTheSavedForm(GameTestHelper helper) {
        RationLedger ledger = new RationLedger(true, 42);
        Tag saved = RationLedger.CODEC
                .encodeStart(NbtOps.INSTANCE, Versioned.of(ledger))
                .getOrThrow();
        Versioned<RationLedger> value =
                RationLedger.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
        if (!value.equals(Versioned.of(ledger))) {
            throw helper.assertionException("the ledger %s came back as %s", ledger, value);
        }
        helper.succeed();
    }

    /**
     * The stalk is a log, so the furnace keeps turning it into charcoal (the campfire's fuel); the meal comes from the campfire and
     * the smoker, which take food only.
     */
    @GameTest(maxTicks = 100)
    public void aFungalStalkCooksIntoAPoorMealOnACampfireAndInASmokerAndStillSmeltsToCharcoal(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        SingleRecipeInput stalk = new SingleRecipeInput(new ItemStack(item("fungal_stalk")));
        var campfire = level.recipeAccess()
                .getRecipeFor(RecipeType.CAMPFIRE_COOKING, stalk, level)
                .orElseThrow(() -> helper.assertionException("no campfire recipe takes a fungal stalk"));
        expectMeal(helper, campfire.value().assemble(stalk), "the campfire");
        var smoker = level.recipeAccess()
                .getRecipeFor(RecipeType.SMOKING, stalk, level)
                .orElseThrow(() -> helper.assertionException("no smoker recipe takes a fungal stalk"));
        expectMeal(helper, smoker.value().assemble(stalk), "the smoker");
        var furnace = level.recipeAccess()
                .getRecipeFor(RecipeType.SMELTING, stalk, level)
                .orElseThrow(() -> helper.assertionException("no furnace recipe takes a fungal stalk"));
        ItemStack smelted = furnace.value().assemble(stalk);
        if (!smelted.is(Items.CHARCOAL)) {
            throw helper.assertionException("the furnace turns a stalk into %s, expected charcoal", smelted);
        }
        helper.succeed();
    }

    private static void expectMeal(GameTestHelper helper, ItemStack result, String where) {
        if (!result.is(item("stalk_meal")) || result.getCount() != 1) {
            throw helper.assertionException("%s cooks a stalk into %s, expected one stalk meal", where, result);
        }
    }

    /** A ledger this build cannot read: the join and the canteen never throw, give nothing, and leave the data as it was. */
    @GameTest(maxTicks = 100)
    public void anUnreadableLedgerGivesNothingAndNeverThrows(GameTestHelper helper) {
        ServerPlayer player = MockPlayers.join(helper, "Stranger").player();
        BlockPos pos = helper.absolutePos(BLOCK);
        boolean[] done = {false};
        Findings found = new Findings();
        helper.succeedWhen(() -> {
            if (!done[0]) {
                awaitStarters(helper, player);
                done[0] = true;
                clearRations(player);
                UnreadableChecks.makeUnreadable(player, ColonyRations.LEDGER);
                helper.getLevel().setBlock(pos, block("canteen").defaultBlockState(), 3);
                try {
                    UnreadableChecks.assertNoThrow(
                            helper,
                            "rations",
                            Map.of(
                                    "join",
                                            () -> ServerPlayerEvents.JOIN
                                                    .invoker()
                                                    .onJoin(player),
                                    "canteen", () -> use(helper, player, pos)));
                } catch (RuntimeException e) {
                    found.note(String.valueOf(e.getMessage()));
                }
            }
            found.expect(player, 0, "an unreadable ledger gives no starting ration and the canteen refuses");
            if (!(player.getAttached(ColonyRations.LEDGER) instanceof Versioned.Unreadable<?>)) {
                found.note("unreadable saved data must be left as it is");
            }
            found.ensure(helper);
        });
    }
}
