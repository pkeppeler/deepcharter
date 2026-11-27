package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.colony.ColonyRations;
import io.github.pkeppeler.deepcharter.colony.OreCarBlock;
import io.github.pkeppeler.deepcharter.colony.RationLedger;
import io.github.pkeppeler.deepcharter.test.support.Findings;
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
import net.minecraft.nbt.CompoundTag;
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

/**
 * Company rations (#838): the starting tins, the ore cars' last-shift tins, the canteen dispenser's ration on every use (#1439),
 * and the stalk that cooks into a poor meal. Counts are literals on purpose: they are the design, not a copy of the tuning.
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
            throw failure(helper, "waiting for the starting rations, the player holds %s", rations(player));
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
            expectRations(
                    found, player, 0, "a player who was given the starting rations is given none on a later join");
            found.ensure(helper);
        });
    }

    @GameTest(maxTicks = 100)
    public void theRationIsEdibleFoodWithSensibleNutrition(GameTestHelper helper) {
        FoodProperties food = new ItemStack(item("company_ration")).get(DataComponents.FOOD);
        if (food == null) {
            throw failure(helper, "the Company ration is not food");
        }
        if (food.nutrition() < 4 || food.nutrition() > 8) {
            throw failure(helper, "a ration restores 4 to 8 hunger, it restores %s", food.nutrition());
        }
        FoodProperties meal = new ItemStack(item("stalk_meal")).get(DataComponents.FOOD);
        if (meal == null) {
            throw failure(helper, "the stalk meal is not food");
        }
        if (meal.nutrition() >= food.nutrition() || meal.nutrition() < 2) {
            throw failure(
                    helper,
                    "the poor meal restores 2 or more and less than a ration (%s), it restores %s",
                    food.nutrition(),
                    meal.nutrition());
        }
        if (new ItemStack(item("company_ration")).get(DataComponents.CONSUMABLE) == null
                || new ItemStack(item("stalk_meal")).get(DataComponents.CONSUMABLE) == null) {
            throw failure(helper, "food that cannot be consumed cannot be eaten");
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
                expectRations(found, player, STARTING_RATIONS + CAR_RATIONS, "the first use of a car gives its tins");
                if (!helper.getLevel().getBlockState(pos).getValue(OreCarBlock.LOOTED)) {
                    found.note("a car that gave its tins is marked looted");
                }
                use(helper, player, pos);
                expectRations(found, player, STARTING_RATIONS + CAR_RATIONS, "a looted car gives nothing more");
            }
            found.ensure(helper);
            if (!said(mock, "colony.deepcharter.ore_car.found") || !said(mock, "colony.deepcharter.ore_car.empty")) {
                throw failure(
                        helper,
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
            expectRations(found, first, STARTING_RATIONS + CAR_RATIONS, "the car is one find for the whole crew");
            expectRations(found, second, STARTING_RATIONS, "the second player finds a car already emptied");
            found.ensure(helper);
        });
    }

    @GameTest(maxTicks = 100)
    public void theCanteenGivesARationOnEveryUseWithNoDailyLimit(GameTestHelper helper) {
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
                // One paused moment of one day: the limit that went (#1439) was per in-game day.
                TestClocks.paused(server, overworldClock(server), 5 * DAY_TICKS + 1000, () -> {
                    use(helper, player, pos);
                    use(helper, player, pos);
                    use(helper, player, pos);
                    use(helper, other, pos);
                });
                expectRations(found, player, STARTING_RATIONS + 3, "each use the same day gives another ration");
                expectRations(found, other, STARTING_RATIONS + 1, "a crewmate's use gives the crewmate a ration");
            }
            found.ensure(helper);
            if (mock.actionBarMessages().stream()
                            .filter(message -> isKey(message, "colony.deepcharter.canteen.dispensed"))
                            .count()
                    != 3) {
                throw failure(
                        helper,
                        "the canteen says it dispensed on each of the three uses, the player heard %s",
                        mock.actionBarMessages());
            }
        });
    }

    private static Holder<WorldClock> overworldClock(MinecraftServer server) {
        return server.overworld().dimensionType().defaultClock().orElseThrow();
    }

    private static void expectRations(Findings found, ServerPlayer player, int expected, String why) {
        if (rations(player) != expected) {
            found.note(why + ": expected " + expected + " rations, "
                    + player.getGameProfile().name() + " holds " + rations(player));
        }
    }

    @GameTest(maxTicks = 100)
    public void theLedgerRoundTripsInTheSavedForm(GameTestHelper helper) {
        for (RationLedger ledger : new RationLedger[] {new RationLedger(true), new RationLedger(false)}) {
            Tag saved = RationLedger.CODEC
                    .encodeStart(NbtOps.INSTANCE, Versioned.of(ledger))
                    .getOrThrow();
            Versioned<RationLedger> value =
                    RationLedger.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
            if (!value.equals(Versioned.of(ledger))) {
                throw failure(helper, "the ledger %s came back as %s", ledger, value);
            }
        }
        helper.succeed();
    }

    /**
     * A ledger saved before #1439, when the canteen gave one a day: version 1, with the day of the last ration. It loads with its
     * starting-tins flag and is written back as version 2 without the day. Literal tags on purpose: they are the formats on disk.
     */
    @GameTest(maxTicks = 100)
    public void aVersionOneLedgerWithACanteenDayLoadsAndIsWrittenBackWithoutTheDay(GameTestHelper helper) {
        if (RationLedger.VERSION != 2) {
            throw failure(helper, "the ledger is version 2, it is %s", RationLedger.VERSION);
        }
        for (boolean starterGiven : new boolean[] {true, false}) {
            CompoundTag old = new CompoundTag();
            old.putInt("version", 1);
            old.putBoolean("starterGiven", starterGiven);
            old.putLong("canteenDay", 42);
            Versioned<RationLedger> loaded =
                    RationLedger.CODEC.parse(NbtOps.INSTANCE, old).getOrThrow();
            if (!loaded.equals(Versioned.of(new RationLedger(starterGiven)))) {
                throw failure(helper, "a version 1 ledger with starterGiven %s loaded as %s", starterGiven, loaded);
            }
            CompoundTag expected = new CompoundTag();
            expected.putBoolean("starterGiven", starterGiven);
            expected.putInt("version", 2);
            Tag written =
                    RationLedger.CODEC.encodeStart(NbtOps.INSTANCE, loaded).getOrThrow();
            if (!written.equals(expected)) {
                throw failure(
                        helper, "a loaded version 1 ledger is written back as %s, expected %s", written, expected);
            }
        }
        if (!(RationLedger.CODEC
                        .parse(NbtOps.INSTANCE, UnreadableChecks.futureData())
                        .getOrThrow()
                instanceof Versioned.Unreadable<RationLedger>)) {
            throw failure(helper, "a version of the future reads as unreadable, never as a failure");
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
                .orElseThrow(() -> failure(helper, "no campfire recipe takes a fungal stalk"));
        expectMeal(helper, campfire.value().assemble(stalk), "the campfire");
        var smoker = level.recipeAccess()
                .getRecipeFor(RecipeType.SMOKING, stalk, level)
                .orElseThrow(() -> failure(helper, "no smoker recipe takes a fungal stalk"));
        expectMeal(helper, smoker.value().assemble(stalk), "the smoker");
        var furnace = level.recipeAccess()
                .getRecipeFor(RecipeType.SMELTING, stalk, level)
                .orElseThrow(() -> failure(helper, "no furnace recipe takes a fungal stalk"));
        ItemStack smelted = furnace.value().assemble(stalk);
        if (!smelted.is(Items.CHARCOAL)) {
            throw failure(helper, "the furnace turns a stalk into %s, expected charcoal", smelted);
        }
        helper.succeed();
    }

    private static void expectMeal(GameTestHelper helper, ItemStack result, String where) {
        if (!result.is(item("stalk_meal")) || result.getCount() != 1) {
            throw failure(helper, "%s cooks a stalk into %s, expected one stalk meal", where, result);
        }
    }

    /**
     * A ledger this build cannot read: the join and the canteen never throw, the join gives no starting tins, and the data is left as
     * it was. The canteen reads no ledger, so it still gives its ration.
     */
    @GameTest(maxTicks = 100)
    public void anUnreadableLedgerGivesNoStartingTinsAndNeverThrows(GameTestHelper helper) {
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
                    // one-shot-ok: caught below and noted in found, which every poll ensures
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
            // assertNoThrow visits each path twice: two joins that give nothing, two canteen uses that give one each.
            expectRations(
                    found,
                    player,
                    2,
                    "an unreadable ledger gives no starting ration, and the canteen still gives one a use");
            if (!(player.getAttached(ColonyRations.LEDGER) instanceof Versioned.Unreadable<?>)) {
                found.note("unreadable saved data must be left as it is");
            }
            found.ensure(helper);
        });
    }
}
