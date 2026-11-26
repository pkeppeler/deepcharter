package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.CharterRefusal;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.handbook.HandbookChapters;
import io.github.pkeppeler.deepcharter.handbook.HandbookProgressData;
import io.github.pkeppeler.deepcharter.handbook.KnownRecipes;
import io.github.pkeppeler.deepcharter.handbook.KnownRecipesData;
import io.github.pkeppeler.deepcharter.handbook.RecipeIndex;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.UnreadableChecks;
import io.github.pkeppeler.deepcharter.test.support.WorldData;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * Server GameTests for #845: a charter knows the recipes its quest step asks for and the ones its members' items touch,
 * the server sends each player only their charter's, a player on no charter has their own, and they carry into a
 * charter and back out of it. Recipes used: the bucket and the chest, which share no item (the world has no diamond, so
 * the diamond pickaxe is not loaded, ADR 0093); the scanner unit recipe, which the scanner step of chapter 1 asks for
 * (it names the scanner unit, not its ingredients).
 */
public class KnownRecipesTest {
    private static final Identifier BUCKET = Identifier.withDefaultNamespace("bucket");
    private static final Identifier CHEST = Identifier.withDefaultNamespace("chest");
    private static final Identifier STONE_PICKAXE = Identifier.withDefaultNamespace("stone_pickaxe");
    private static final Identifier CRAFTING_TABLE = Identifier.withDefaultNamespace("crafting_table");
    private static final Identifier SCANNER_UNIT = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "scanner_unit");
    private static final Identifier SCANNER_STEP =
            Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "handbook/welcome/craft_scanner");

    private static ResourceKey<Recipe<?>> key(Identifier id) {
        return ResourceKey.create(Registries.RECIPE, id);
    }

    private static void expectDone(GameTestHelper helper, Optional<CharterRefusal> refusal, String what) {
        if (refusal.isPresent()) {
            throw helper.assertionException("%s should succeed, was refused: %s", what, refusal.get());
        }
    }

    private static CharterId found(GameTestHelper helper, MinecraftServer server, ServerPlayer director) {
        expectDone(
                helper,
                Charters.found(
                        server,
                        director.getUUID(),
                        "Recipes " + UUID.randomUUID().toString().substring(0, 8)),
                "founding");
        Charter charter = Charters.charterOfOrThrow(server, director.getUUID()).orElseThrow();
        return charter.id();
    }

    private static void join(
            GameTestHelper helper, MinecraftServer server, CharterId id, ServerPlayer director, ServerPlayer member) {
        expectDone(helper, Charters.apply(server, member.getUUID(), id), "applying");
        expectDone(helper, Charters.approve(server, director.getUUID(), member.getUUID()), "approving");
    }

    private static void expectKnows(
            GameTestHelper helper, MinecraftServer server, ServerPlayer player, Identifier recipe, boolean expected) {
        if (KnownRecipes.knownFor(server, player.getUUID()).contains(recipe) != expected) {
            throw helper.assertionException(
                    "%s should %sknow %s, knows %s",
                    player.getGameProfile().name(),
                    expected ? "" : "not ",
                    recipe,
                    KnownRecipes.knownFor(server, player.getUUID()));
        }
    }

    /** What the client was sent: the packets the mock applied. */
    private static void expectHeld(GameTestHelper helper, MockPlayer mock, Identifier recipe, boolean expected) {
        if (mock.recipesHeld().contains(recipe) != expected) {
            throw helper.assertionException(
                    "the client of %s should %shold %s, holds %s",
                    mock.player().getGameProfile().name(), expected ? "" : "not ", recipe, mock.recipesHeld());
        }
    }

    /** Never sent at all: a recipe sent and removed again was still seen by the client. */
    private static void expectNeverSent(GameTestHelper helper, MockPlayer mock, Identifier recipe) {
        if (mock.recipesEverSent().contains(recipe)) {
            throw helper.assertionException(
                    "the client of %s should never have been sent %s",
                    mock.player().getGameProfile().name(), recipe);
        }
    }

    private static void expectInBook(GameTestHelper helper, ServerPlayer player, Identifier recipe, boolean expected) {
        if (player.getRecipeBook().contains(key(recipe)) != expected) {
            throw helper.assertionException(
                    "the server's recipe book of %s should %scontain %s",
                    player.getGameProfile().name(), expected ? "" : "not ", recipe);
        }
    }

    @GameTest(maxTicks = 100)
    public void obtainingAnItemMakesItsRecipeKnown(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer director = MockPlayers.join(helper, "Director").player();
        ServerPlayer member = MockPlayers.join(helper, "Member").player();
        join(helper, server, found(helper, server, director), director, member);
        director.getInventory().add(new ItemStack(Items.BUCKET));

        // The recipe the item is the result of is known to the whole charter, and one the item has no part in is not.
        helper.succeedWhen(() -> {
            expectKnows(helper, server, director, BUCKET, true);
            expectKnows(helper, server, member, BUCKET, true);
            expectKnows(helper, server, member, CHEST, false);
        });
    }

    @GameTest(maxTicks = 100)
    public void anyoneInTheCharterObtainingAnIngredientMakesTheRecipeKnown(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer director = MockPlayers.join(helper, "Director").player();
        ServerPlayer member = MockPlayers.join(helper, "Member").player();
        join(helper, server, found(helper, server, director), director, member);
        member.getInventory().add(new ItemStack(Items.OAK_PLANKS));

        helper.succeedWhen(() -> {
            expectKnows(helper, server, director, CHEST, true);
            expectKnows(helper, server, director, BUCKET, false);
        });
    }

    @GameTest(maxTicks = 100)
    public void aStepThatAsksForAnItemMakesItsRecipeKnownWhenItBecomesCurrent(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer ahead = MockPlayers.join(helper, "Ahead").player();
        ServerPlayer behind = MockPlayers.join(helper, "Behind").player();
        CharterId aheadId = found(helper, server, ahead);
        found(helper, server, behind);
        // The scanner step asks for the scanner unit; it is current once every step before it is done.
        for (Identifier directive : HandbookChapters.directivesOrEmpty(server)) {
            if (directive.equals(SCANNER_STEP)) {
                break;
            }
            HandbookProgressData.get(server).complete(aheadId, directive);
        }

        helper.succeedWhen(() -> {
            expectKnows(helper, server, ahead, SCANNER_UNIT, true);
            expectKnows(helper, server, behind, SCANNER_UNIT, false);
        });
    }

    @GameTest
    public void unknownRecipesStillCraft(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = MockPlayers.join(helper, "Crafter").player();
        expectKnows(helper, server, player, Identifier.withDefaultNamespace("oak_planks"), false);
        InventoryMenu menu = player.inventoryMenu;

        // Control: with the vanilla rule that refuses a recipe the player has not unlocked, the same craft fails. So
        // the craft
        // below succeeds because nothing limits it, not because the test cannot fail.
        GameRules rules = server.getGameRules();
        rules.set(GameRules.LIMITED_CRAFTING, true, server);
        try {
            menu.getSlot(1).set(new ItemStack(Items.OAK_LOG));
            menu.clicked(0, 0, ContainerInput.PICKUP, player);
            if (!menu.getCarried().isEmpty()) {
                throw helper.assertionException(
                        "limited crafting should refuse an unknown recipe, took %s", menu.getCarried());
            }
        } finally {
            rules.set(GameRules.LIMITED_CRAFTING, false, server);
            menu.getSlot(1).set(ItemStack.EMPTY);
        }

        // One log in the 2x2 grid, then take the result: the real crafting path.
        menu.getSlot(1).set(new ItemStack(Items.OAK_LOG));
        menu.clicked(0, 0, ContainerInput.PICKUP, player);

        if (!menu.getCarried().is(Items.OAK_PLANKS)) {
            throw helper.assertionException(
                    "a player who knows no recipe still crafts planks, took %s", menu.getCarried());
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 100)
    public void holdingOnePlankTeachesARecipeThatTakesAnyPlank(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = MockPlayers.join(helper, "Planker").player();
        player.getInventory().add(new ItemStack(Items.OAK_PLANKS));

        // The crafting table takes the planks tag; oak planks are not the result of it.
        helper.succeedWhen(() -> expectKnows(helper, server, player, CRAFTING_TABLE, true));
    }

    @GameTest(maxTicks = 100)
    public void anItemOnTheCursorTeachesItsRecipe(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = MockPlayers.join(helper, "Carrier").player();
        player.containerMenu.setCarried(new ItemStack(Items.BUCKET));

        helper.succeedWhen(() -> expectKnows(helper, server, player, BUCKET, true));
    }

    @GameTest
    public void theRecipeIndexIsBuiltAgainWhenTheRecipeManagerIsReplaced(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        RecipeIndex current = RecipeIndex.of(server, server.getRecipeManager());
        RecipeManager replaced = new RecipeManager(server.reloadableRegistries().lookup());
        replaced.finalizeRecipeLoading(server.getWorldData().enabledFeatures());

        try {
            if (RecipeIndex.of(server, server.getRecipeManager()) != current) {
                throw helper.assertionException("the same recipe manager should keep its index");
            }
            // /reload replaces the manager; an index kept from the old one would hold the old recipes.
            if (RecipeIndex.of(server, replaced) == current) {
                throw helper.assertionException("a replaced recipe manager should get a new index");
            }
        } finally {
            // The cache is static: leave the server's own manager in it, not the replaced one.
            RecipeIndex.of(server, server.getRecipeManager());
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 100)
    public void aRecipeTakenOutOfTheBookIsPutBackBecauseTheCharterKnowsIt(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        MockPlayer mock = MockPlayers.join(helper, "Taken");
        ServerPlayer player = mock.player();
        RecipeHolder<?> bucket = server.getRecipeManager().byKey(key(BUCKET)).orElseThrow();
        KnownRecipesData.get(server).addToPlayer(player.getUUID(), Set.of(BUCKET));
        // What /recipe take calls.
        player.resetRecipes(List.of(bucket));

        helper.succeedWhen(() -> expectInBook(helper, player, BUCKET, true));
    }

    @GameTest(maxTicks = 100)
    public void theServerSendsEachPlayerOnlyTheirCharterRecipesAndSendsMoreAsTheyGrow(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        MockPlayer first = MockPlayers.join(helper, "First");
        MockPlayer second = MockPlayers.join(helper, "Second");
        MockPlayer loner = MockPlayers.join(helper, "Loner");
        found(helper, server, first.player());
        found(helper, server, second.player());
        first.player().getInventory().add(new ItemStack(Items.BUCKET));
        helper.runAfterDelay(40, () -> first.player().getInventory().add(new ItemStack(Items.OAK_PLANKS)));

        // The second batch arrives as an update to the book the client already holds.
        helper.succeedWhen(() -> {
            expectHeld(helper, first, BUCKET, true);
            expectHeld(helper, first, CHEST, true);
            expectInBook(helper, first.player(), CHEST, true);
            expectHeld(helper, second, BUCKET, false);
            expectHeld(helper, second, CHEST, false);
            expectHeld(helper, loner, BUCKET, false);
            if (first.recipeToasts() != 0) {
                throw helper.assertionException(
                        "learning a recipe makes no toast, the client was told to show %s", first.recipeToasts());
            }
        });
    }

    @GameTest(maxTicks = 100)
    public void aPlayerOnNoCharterDiscoversRecipesOfTheirOwn(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        MockPlayer loner = MockPlayers.join(helper, "Loner");
        ServerPlayer other = MockPlayers.join(helper, "Other").player();
        loner.player().getInventory().add(new ItemStack(Items.BUCKET));

        helper.succeedWhen(() -> {
            expectKnows(helper, server, loner.player(), BUCKET, true);
            expectKnows(helper, server, other, BUCKET, false);
            expectHeld(helper, loner, BUCKET, true);
        });
    }

    @GameTest(maxTicks = 100)
    public void foundingACharterCarriesTheFoundersDiscoveries(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        MockPlayer founder = MockPlayers.join(helper, "Founder");
        KnownRecipesData.get(server).addToPlayer(founder.player().getUUID(), Set.of(BUCKET));

        CharterId id = found(helper, server, founder.player());

        if (!KnownRecipesData.get(server).charter(id).contains(BUCKET)) {
            throw helper.assertionException("the charter should hold the founder's recipes");
        }
        helper.succeedWhen(() -> expectHeld(helper, founder, BUCKET, true));
    }

    @GameTest(maxTicks = 100)
    public void aJoinerAddsTheirDiscoveriesToTheCharterAndEveryMemberIsSentThem(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        MockPlayer director = MockPlayers.join(helper, "Director");
        MockPlayer joiner = MockPlayers.join(helper, "Joiner");
        CharterId id = found(helper, server, director.player());
        KnownRecipesData.get(server).addToCharter(id, Set.of(CHEST));
        KnownRecipesData.get(server).addToPlayer(joiner.player().getUUID(), Set.of(BUCKET));

        join(helper, server, id, director.player(), joiner.player());

        if (!KnownRecipesData.get(server).charter(id).equals(Set.of(CHEST, BUCKET))) {
            throw helper.assertionException("the charter should hold its own recipes and the joiner's");
        }
        helper.succeedWhen(() -> {
            expectHeld(helper, joiner, CHEST, true);
            expectHeld(helper, joiner, BUCKET, true);
            expectHeld(helper, director, BUCKET, true);
        });
    }

    @GameTest(maxTicks = 100)
    public void revivingADormantCharterAddsTheRevivingPlayersDiscoveries(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        MockPlayer director = MockPlayers.join(helper, "Director");
        CharterId id = found(helper, server, director.player());
        KnownRecipesData.get(server).addToCharter(id, Set.of(CHEST));
        expectDone(helper, Charters.leave(server, director.player().getUUID()), "the last person leaving");
        KnownRecipesData.get(server).addToPlayer(director.player().getUUID(), Set.of(BUCKET));

        expectDone(helper, Charters.revive(server, director.player().getUUID(), id), "reviving");

        if (!KnownRecipesData.get(server).charter(id).equals(Set.of(CHEST, BUCKET))) {
            throw helper.assertionException("the dormant charter keeps its recipes and takes the reviver's");
        }
        helper.succeedWhen(() -> {
            expectHeld(helper, director, CHEST, true);
            expectHeld(helper, director, BUCKET, true);
        });
    }

    @GameTest(maxTicks = 100)
    public void aLeaverHasTheirOwnRecipesAgainAndNotTheCharters(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        MockPlayer director = MockPlayers.join(helper, "Director");
        MockPlayer member = MockPlayers.join(helper, "Member");
        CharterId id = found(helper, server, director.player());
        KnownRecipesData.get(server).addToCharter(id, Set.of(CHEST));
        KnownRecipesData.get(server).addToPlayer(member.player().getUUID(), Set.of(BUCKET));
        join(helper, server, id, director.player(), member.player());

        expectDone(helper, Charters.leave(server, member.player().getUUID()), "leaving");

        expectKnows(helper, server, member.player(), BUCKET, true);
        expectKnows(helper, server, member.player(), CHEST, false);
        expectInBook(helper, member.player(), CHEST, false);
        helper.succeedWhen(() -> {
            expectHeld(helper, member, BUCKET, true);
            expectHeld(helper, member, CHEST, false);
            expectHeld(helper, director, CHEST, true);
        });
    }

    @GameTest(maxTicks = 100)
    public void aVanillaUnlockOfAnUnknownRecipeIsNeverSentToTheClient(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        MockPlayer mock = MockPlayers.join(helper, "Unsent");
        mock.player()
                .awardRecipes(List.of(
                        server.getRecipeManager().byKey(key(STONE_PICKAXE)).orElseThrow()));

        // The mock reads the channel a tick late, so look after the packet would have arrived.
        helper.runAfterDelay(5, () -> {
            expectNeverSent(helper, mock, STONE_PICKAXE);
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 100)
    public void aVanillaUnlockOfAnUnknownRecipeNeverReachesTheBook(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        MockPlayer mock = MockPlayers.join(helper, "Unlocked");
        ServerPlayer player = mock.player();
        RecipeHolder<?> stonePickaxe =
                server.getRecipeManager().byKey(key(STONE_PICKAXE)).orElseThrow();

        // What /recipe give and an advancement reward call.
        player.awardRecipes(List.of(stonePickaxe));

        expectInBook(helper, player, STONE_PICKAXE, false);
        // A recipe saved in the player's own data is not sent either: the book matches what is known when it is first
        // sent.
        player.getRecipeBook().add(key(STONE_PICKAXE));
        player.getRecipeBook().sendInitialRecipeBook(player);
        expectInBook(helper, player, STONE_PICKAXE, false);
        // A recipe the player knows is let through.
        KnownRecipesData.get(server).addToPlayer(player.getUUID(), Set.of(STONE_PICKAXE));
        player.awardRecipes(List.of(stonePickaxe));
        expectInBook(helper, player, STONE_PICKAXE, true);
        helper.succeedWhen(() -> expectHeld(helper, mock, STONE_PICKAXE, true));
    }

    @GameTest
    public void aRecipeSavedInThePlayersDataIsNotSentWhenTheBookIsFirstSent(GameTestHelper helper) {
        MockPlayer mock = MockPlayers.join(helper, "Saved");
        mock.player().getRecipeBook().add(key(STONE_PICKAXE));

        mock.player().getRecipeBook().sendInitialRecipeBook(mock.player());

        expectInBook(helper, mock.player(), STONE_PICKAXE, false);
        helper.runAfterDelay(5, () -> {
            expectNeverSent(helper, mock, STONE_PICKAXE);
            helper.succeed();
        });
    }

    @GameTest
    public void unreadableKnownRecipesNeverThrowAndLeaveNothingKnown(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        MockPlayer mock = MockPlayers.join(helper, "Reader");
        ServerPlayer player = mock.player();
        RecipeHolder<?> stonePickaxe =
                server.getRecipeManager().byKey(key(STONE_PICKAXE)).orElseThrow();
        Map<String, Runnable> paths = new LinkedHashMap<>();
        paths.put("read", () -> KnownRecipes.knownFor(server, player.getUUID()));
        paths.put("initial book", () -> player.getRecipeBook().sendInitialRecipeBook(player));
        paths.put("vanilla unlock", () -> player.awardRecipes(List.of(stonePickaxe)));
        // The carry runs on founding; the refusal on the second run (already on a charter) is not the point here.
        paths.put("founding", () -> Charters.found(server, player.getUUID(), "Unreadable " + UUID.randomUUID()));
        UnreadableChecks.assertSavedDataNoThrow(helper, "known recipes", server, KnownRecipesData.TYPE, paths);

        KnownRecipesData unreadable = KnownRecipesData.TYPE
                .codec()
                .parse(NbtOps.INSTANCE, UnreadableChecks.futureData())
                .getOrThrow();
        WorldData.with(server, KnownRecipesData.TYPE, unreadable, () -> {
            player.getRecipeBook().add(key(STONE_PICKAXE));
            player.getRecipeBook().sendInitialRecipeBook(player);
            expectInBook(helper, player, STONE_PICKAXE, false);
            expectKnows(helper, server, player, STONE_PICKAXE, false);
        });
        helper.succeed();
    }

    @GameTest
    public void knownRecipesAreSavedWithTheirVersion(GameTestHelper helper) {
        UUID player = UUID.randomUUID();
        CharterId charter = CharterId.random();
        KnownRecipesData data = new KnownRecipesData();
        data.addToPlayer(player, Set.of(BUCKET));
        data.addToCharter(charter, Set.of(CHEST, BUCKET));

        Tag saved = KnownRecipesData.CODEC.encodeStart(NbtOps.INSTANCE, data).getOrThrow();
        if (!(saved instanceof CompoundTag compound) || compound.getIntOr("version", -1) != KnownRecipesData.VERSION) {
            throw helper.assertionException(
                    "the saved form carries version %s, got %s", KnownRecipesData.VERSION, saved);
        }
        KnownRecipesData loaded =
                KnownRecipesData.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
        if (!loaded.player(player).equals(Set.of(BUCKET))
                || !loaded.charter(charter).equals(Set.of(CHEST, BUCKET))) {
            throw helper.assertionException("the saved recipes should load as they were saved");
        }
        helper.succeed();
    }
}
