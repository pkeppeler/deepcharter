package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * Server GameTests for #835: vanilla's advancements are not loaded, so nothing grants one or unlocks a recipe from one, while the
 * mod's directive advancements still complete, and the locator bar is off in a fresh world (ADR 0067).
 */
public class NoVanillaAdvancementsTest {
    private static final Identifier GATHER_WOOD =
            Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "handbook/welcome/gather_wood");

    @GameTest
    public void noMinecraftAdvancementIsLoadedAndTheDirectivesAre(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        List<Identifier> vanilla = server.getAdvancements().getAllAdvancements().stream()
                .map(AdvancementHolder::id)
                .filter(id -> id.getNamespace().equals(Identifier.DEFAULT_NAMESPACE))
                .toList();
        if (!vanilla.isEmpty()) {
            throw helper.assertionException("vanilla advancements are loaded: %s", vanilla);
        }
        if (server.getAdvancements().get(GATHER_WOOD) == null) {
            throw helper.assertionException("the directive advancement %s is not loaded", GATHER_WOOD);
        }
        if (server.getAdvancements().tree().get(GATHER_WOOD) == null) {
            throw helper.assertionException("the directive advancement %s is not in the tree", GATHER_WOOD);
        }
        helper.succeed();
    }

    @GameTest
    public void gettingALogUnlocksNoVanillaRecipeButCompletesTheDirectiveAdvancement(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        MockPlayer mock = MockPlayers.join(helper, "Logger");
        ServerPlayer player = mock.player();
        player.setGameMode(GameType.SURVIVAL);
        HandbookChaptersOneToFiveTest.give(player, Items.OAK_LOG);
        // Vanilla's "has oak logs" advancement is what unlocks the oak planks recipe and shows its toast.
        ResourceKey<Recipe<?>> oakPlanks =
                ResourceKey.create(Registries.RECIPE, Identifier.withDefaultNamespace("oak_planks"));
        if (player.getRecipeBook().contains(oakPlanks)) {
            throw helper.assertionException("getting an oak log unlocked the vanilla oak planks recipe");
        }
        AdvancementHolder directive = server.getAdvancements().get(GATHER_WOOD);
        if (directive == null) {
            throw helper.assertionException("the directive advancement %s is not loaded", GATHER_WOOD);
        }
        if (!player.getAdvancements().getOrStartProgress(directive).isDone()) {
            throw helper.assertionException("getting an oak log did not complete %s", GATHER_WOOD);
        }
        helper.succeed();
    }

    @GameTest
    public void theLocatorBarIsOffInEveryLevelOfAFreshWorld(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        if (server.getGameRules().get(GameRules.LOCATOR_BAR)) {
            throw helper.assertionException("the locator bar is on in a fresh world");
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getGameRules().get(GameRules.LOCATOR_BAR)) {
                throw helper.assertionException(
                        "the locator bar is on in %s", level.dimension().identifier());
            }
        }
        helper.succeed();
    }
}
