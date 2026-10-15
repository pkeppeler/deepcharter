package io.github.pkeppeler.deepcharter.test;

import com.google.gson.JsonObject;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.github.pkeppeler.deepcharter.upgrade.PartLabel;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeEvents;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeTuning;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;

/**
 * Server GameTests for #536 (ADR 0053): tier 1 of every track but the scanner (drill, hull, engine, fuel tank, radiator, cargo bay, lights, spoil
 * hopper, liner, sounder, brace, heat shield) is a crafted unit, made from the shipped recipe and fitted by using it on the pod of the player's own
 * charter, as the scanner's is
 * ({@link ScannerFittingTest}). A fit is stamped for the charter, moves the pod's stats like a bought part, and a refused use changes nothing.
 * The terminal's side (tier 1 is not sold, tier 2 is) is in {@link UpgradeTerminalTest}, and the cost of the recipes in {@link FirstTierPartsCostTest}.
 */
public class PartFittingTest {
    private static final AtomicInteger PLAYERS = new AtomicInteger();

    /** Every track but the scanner, whose unit is {@link ScannerFittingTest}'s. */
    private static final List<ComponentTrack> TRACKS = List.of(
            ComponentTrack.DRILL,
            ComponentTrack.HULL,
            ComponentTrack.ENGINE,
            ComponentTrack.FUEL_TANK,
            ComponentTrack.RADIATOR,
            ComponentTrack.CARGO_BAY,
            ComponentTrack.LIGHTS,
            ComponentTrack.SPOIL_HOPPER,
            ComponentTrack.LINER,
            ComponentTrack.SOUNDER,
            ComponentTrack.BRACE,
            ComponentTrack.HEAT_SHIELD,
            ComponentTrack.CONSOLE);

    /** What a fit moves, by track: the pod's number for the track, which a tier 1 part must improve on the stock part's. */
    private static final Map<ComponentTrack, Function<PodEntity, Float>> STATS = Map.of(
            ComponentTrack.DRILL, pod -> PodStats.of(pod).ticksPerHardness(),
            ComponentTrack.HULL, pod -> PodStats.of(pod).maxHull(),
            ComponentTrack.ENGINE, pod -> PodStats.of(pod).enginePower(),
            ComponentTrack.FUEL_TANK, pod -> PodStats.of(pod).tankLitres(),
            ComponentTrack.RADIATOR, PodComponents::radiatorRatio,
            ComponentTrack.CARGO_BAY, pod -> (float) PodStats.of(pod).cargoSlots());

    /** The pod's number a fit moves, or 0 for a hazard answer, which moves no stat of PodStats. */
    private static float stat(ComponentTrack track, PodEntity pod) {
        return STATS.containsKey(track) ? STATS.get(track).apply(pod) : 0f;
    }

    /** The pods a fit fired the event for, and the track and tier it named. The listener acts only on pods it was told about, as a Fabric event cannot be unregistered. */
    private static final Map<UUID, List<String>> FITTED = new HashMap<>();

    static {
        UpgradeEvents.FITTED.register((server, player, pod, track, tier) -> {
            List<String> log = FITTED.get(pod.getUUID());
            if (log != null) {
                log.add(track.id() + ":" + tier);
            }
        });
    }

    /** A player who may be on a charter, with the pods they stand by. */
    private record Scene(MockPlayer mock, ServerPlayer player, List<PodEntity> pods) {
        void clean() {
            pods.forEach(pod -> {
                FITTED.remove(pod.getUUID());
                pod.discard();
            });
            mock.leave();
        }
    }

    private static Scene scene(GameTestHelper helper, boolean onCharter) {
        MockPlayer mock = MockPlayers.join(helper, "Unit" + PLAYERS.incrementAndGet());
        ServerPlayer player = mock.player();
        player.setGameMode(GameType.SURVIVAL);
        if (onCharter
                && Charters.found(
                                helper.getLevel().getServer(),
                                player.getUUID(),
                                "Unit " + UUID.randomUUID().toString().substring(0, 8))
                        .isPresent()) {
            throw helper.assertionException("founding a charter should succeed");
        }
        return new Scene(mock, player, new ArrayList<>());
    }

    private static CharterId charterOf(GameTestHelper helper, ServerPlayer player) {
        return Charters.charterOfOrThrow(helper.getLevel().getServer(), player.getUUID())
                .orElseThrow()
                .id();
    }

    private static PodEntity pod(GameTestHelper helper, Scene scene, Optional<CharterId> owner) {
        PodEntity pod = helper.spawn(PodRegistry.POD, 2, 1, 2);
        scene.pods().add(pod);
        FITTED.put(pod.getUUID(), new ArrayList<>());
        owner.ifPresent(charter -> PodComponents.register(pod, charter));
        return pod;
    }

    private static CharterId otherCharter(GameTestHelper helper) {
        UUID founder = UUID.randomUUID();
        if (Charters.found(
                        helper.getLevel().getServer(),
                        founder,
                        "Other " + UUID.randomUUID().toString().substring(0, 8))
                .isPresent()) {
            throw helper.assertionException("founding the other charter should succeed");
        }
        return Charters.charterOfOrThrow(helper.getLevel().getServer(), founder)
                .orElseThrow()
                .id();
    }

    /** The unit of a track, found by the id that packs and chests name. */
    private static Item unit(ComponentTrack track) {
        return BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("deepcharter", track.id() + "_unit"));
    }

    /** Holds one unit in the main hand and uses it on the pod through the game's own entity-use event, as a click does. */
    private static InteractionResult useUnit(Scene scene, PodEntity pod, ComponentTrack track) {
        scene.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(unit(track)));
        return UseEntityCallback.EVENT
                .invoker()
                .interact(scene.player(), pod.level(), InteractionHand.MAIN_HAND, pod, null);
    }

    private static RuntimeException failure(GameTestHelper helper, String format, Object... args) {
        return helper.assertionException(format, args);
    }

    /** A world's inventories, chests and recipes name these ids (ADR 0053), so a rename is a decision: each is pinned by its literal. */
    @GameTest
    public void theUnitIdsThatPacksAndChestsNameArePinned(GameTestHelper helper) {
        List<String> expected = List.of(
                "deepcharter:drill_unit",
                "deepcharter:hull_unit",
                "deepcharter:engine_unit",
                "deepcharter:fuel_tank_unit",
                "deepcharter:radiator_unit",
                "deepcharter:cargo_bay_unit",
                "deepcharter:lights_unit",
                "deepcharter:spoil_hopper_unit",
                "deepcharter:liner_unit",
                "deepcharter:sounder_unit",
                "deepcharter:brace_unit",
                "deepcharter:heat_shield_unit",
                "deepcharter:console_unit");
        List<String> actual = TRACKS.stream()
                .map(track -> BuiltInRegistries.ITEM.getKey(unit(track)).toString())
                .toList();
        if (!actual.equals(expected) || TRACKS.stream().anyMatch(track -> unit(track) == Items.AIR)) {
            throw failure(helper, "the units' ids are permanent, got %s", actual);
        }
        helper.succeed();
    }

    /** The recipe is the real one, in the real recipe manager: its shipped grid makes one unit, and a grid with an ingredient missing makes nothing. */
    @GameTest
    public void everyUnitIsCraftedFromItsShippedGrid(GameTestHelper helper) {
        for (ComponentTrack track : TRACKS) {
            JsonObject recipe = BootstrapCost.recipe(track.id() + "_unit");
            List<ItemStack> grid = new ArrayList<>();
            for (String cell : BootstrapCost.cells(recipe)) {
                grid.add(
                        cell.isEmpty()
                                ? ItemStack.EMPTY
                                : new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(cell))));
            }
            int width = recipe.getAsJsonArray("pattern").get(0).getAsString().length();
            int height = recipe.getAsJsonArray("pattern").size();
            CraftingInput input = CraftingInput.of(width, height, grid);
            ItemStack made = helper.getLevel()
                    .recipeAccess()
                    .getRecipeFor(RecipeType.CRAFTING, input, helper.getLevel())
                    .map(holder -> holder.value().assemble(input))
                    .orElseThrow(() -> failure(helper, "no crafting recipe takes the %s unit's grid", track));
            if (made.getItem() != unit(track) || made.getCount() != 1) {
                throw failure(helper, "the %s grid should make one unit, made %s", track, made);
            }
            List<ItemStack> lacking = new ArrayList<>(grid);
            lacking.set(
                    grid.indexOf(grid.stream()
                            .filter(stack -> !stack.isEmpty())
                            .findFirst()
                            .orElseThrow()),
                    ItemStack.EMPTY);
            CraftingInput short1 = CraftingInput.of(width, height, lacking);
            if (helper.getLevel()
                    .recipeAccess()
                    .getRecipeFor(RecipeType.CRAFTING, short1, helper.getLevel())
                    .isPresent()) {
                throw failure(helper, "the %s grid with an ingredient missing should make nothing", track);
            }
        }
        helper.succeed();
    }

    @GameTest
    public void usingAUnitOnYourOwnPodFitsATierOneStampedForTheCharterAndMovesTheStats(GameTestHelper helper) {
        Scene scene = scene(helper, true);
        try {
            CharterId charter = charterOf(helper, scene.player());
            for (ComponentTrack track : TRACKS) {
                PodEntity pod = pod(helper, scene, Optional.of(charter));
                float before = stat(track, pod);
                InteractionResult result = useUnit(scene, pod, track);
                if (!result.consumesAction()) {
                    throw failure(helper, "using the %s unit on the pod should be consumed, got %s", track, result);
                }
                PartLabel part = PodComponents.partOf(pod, track)
                        .orElseThrow(() -> failure(helper, "the pod should hold a %s part now", track));
                if (part.tier() != 1
                        || !part.charter().equals(charter)
                        || !part.serial().startsWith(track.id().toUpperCase(Locale.ROOT))) {
                    throw failure(
                            helper,
                            "the %s part should be tier 1 of the charter with its own serial, got %s",
                            track,
                            part);
                }
                float after = stat(track, pod);
                boolean better = !STATS.containsKey(track)
                        || ((track == ComponentTrack.DRILL || track == ComponentTrack.RADIATOR)
                                ? after < before
                                : after > before);
                if (!better) {
                    throw failure(
                            helper,
                            "a tier 1 %s should improve on the stock part: %s before, %s after",
                            track,
                            before,
                            after);
                }
                if (PodComponents.effectiveTier(pod, track) != 1) {
                    throw failure(helper, "the fitted %s should work as tier 1", track);
                }
                if (!scene.player().getMainHandItem().isEmpty()) {
                    throw failure(
                            helper,
                            "the %s unit should be used up, the hand holds %s",
                            track,
                            scene.player().getMainHandItem());
                }
                if (!FITTED.get(pod.getUUID()).equals(List.of(track.id() + ":1"))) {
                    throw failure(
                            helper,
                            "the fit should fire FITTED once for %s tier 1, got %s",
                            track,
                            FITTED.get(pod.getUUID()));
                }
            }
            helper.succeed();
        } finally {
            scene.clean();
        }
    }

    /** The units go on one pod in any order: each takes its own track and leaves the others. */
    @GameTest
    public void allTheUnitsFitOneMoleAndEachLeavesTheOthersAlone(GameTestHelper helper) {
        Scene scene = scene(helper, true);
        try {
            PodEntity pod = pod(helper, scene, Optional.of(charterOf(helper, scene.player())));
            List<ComponentTrack> order = new ArrayList<>(TRACKS);
            Collections.reverse(order);
            for (int i = 0; i < order.size(); i++) {
                useUnit(scene, pod, order.get(i));
                for (ComponentTrack track : ComponentTrack.values()) {
                    boolean fitted = order.subList(0, i + 1).contains(track);
                    if (PodComponents.partOf(pod, track).isPresent() != fitted) {
                        throw failure(
                                helper,
                                "after fitting %s the pod's %s part should be %s",
                                order.subList(0, i + 1),
                                track,
                                fitted ? "present" : "absent");
                    }
                }
            }
            helper.succeed();
        } finally {
            scene.clean();
        }
    }

    @GameTest
    public void aHullUnitRefillsTheHullAndATankUnitTheFuelAsAPurchaseDoes(GameTestHelper helper) {
        Scene scene = scene(helper, true);
        try {
            CharterId charter = charterOf(helper, scene.player());
            PodEntity hull = pod(helper, scene, Optional.of(charter));
            hull.setHull(40f);
            hull.setFuel(30f);
            useUnit(scene, hull, ComponentTrack.HULL);
            if (hull.hull() != PodStats.of(hull).maxHull() || hull.fuel() != 30f) {
                throw failure(
                        helper,
                        "a hull unit should refill the hull (%s of %s) and not the tank (%s)",
                        hull.hull(),
                        PodStats.of(hull).maxHull(),
                        hull.fuel());
            }
            PodEntity wreck = pod(helper, scene, Optional.of(charter));
            wreck.setHull(0f);
            useUnit(scene, wreck, ComponentTrack.HULL);
            if (wreck.hull() != 0f) {
                throw failure(helper, "a hull unit must not repair a wreck, hull is %s", wreck.hull());
            }
            PodEntity tank = pod(helper, scene, Optional.of(charter));
            tank.setHull(50f);
            tank.setFuel(30f);
            useUnit(scene, tank, ComponentTrack.FUEL_TANK);
            if (tank.fuel() <= 30f || tank.hull() != 50f) {
                throw failure(
                        helper,
                        "a tank unit should refill the tank (%s) and not the hull (%s)",
                        tank.fuel(),
                        tank.hull());
            }
            helper.succeed();
        } finally {
            scene.clean();
        }
    }

    @GameTest
    public void aCreativePlayerKeepsTheUnit(GameTestHelper helper) {
        Scene scene = scene(helper, true);
        try {
            PodEntity pod = pod(helper, scene, Optional.of(charterOf(helper, scene.player())));
            scene.player().setGameMode(GameType.CREATIVE);
            useUnit(scene, pod, ComponentTrack.DRILL);
            if (PodComponents.effectiveTier(pod, ComponentTrack.DRILL) != 1
                    || !scene.player().getMainHandItem().is(unit(ComponentTrack.DRILL))) {
                throw failure(
                        helper,
                        "a creative player fits the drill and keeps the unit, hand holds %s",
                        scene.player().getMainHandItem());
            }
            helper.succeed();
        } finally {
            scene.clean();
        }
    }

    /** Each refusal leaves the pod without a new part and the unit in the hand. */
    @GameTest
    public void aRefusedUseChangesNothingAndKeepsTheUnit(GameTestHelper helper) {
        Scene scene = scene(helper, true);
        Scene loner = scene(helper, false);
        try {
            CharterId charter = charterOf(helper, scene.player());
            for (ComponentTrack track : TRACKS) {
                expectKept(helper, scene, pod(helper, scene, Optional.empty()), track, "a pod nobody owns");
                expectKept(
                        helper,
                        scene,
                        pod(helper, scene, Optional.of(otherCharter(helper))),
                        track,
                        "a pod of another charter");
                expectKept(helper, loner, pod(helper, loner, Optional.of(charter)), track, "a player on no charter");
            }
            helper.succeed();
        } finally {
            scene.clean();
            loner.clean();
        }
    }

    /**
     * An old world: a pod already holds a tier 1 part that was bought (or a better one). It keeps the part, and a unit is refused and kept, so
     * nothing the pod holds is replaced, downgraded or lost. A bought part is the same item and label as a fitted one.
     */
    @GameTest
    public void aPodThatBoughtItsTierOnePartKeepsItAndRefusesTheUnit(GameTestHelper helper) {
        Scene scene = scene(helper, true);
        try {
            CharterId charter = charterOf(helper, scene.player());
            for (ComponentTrack track : TRACKS) {
                for (int tier = 1; tier <= Math.min(2, track.maxTier()); tier++) {
                    PodEntity pod = pod(helper, scene, Optional.of(charter));
                    PodComponents.install(
                            pod, ComponentItems.mint(helper.getLevel().getServer(), track, tier, charter));
                    PartLabel bought = PodComponents.partOf(pod, track).orElseThrow();
                    float stat = stat(track, pod);
                    expectKept(helper, scene, pod, track, "a pod with its bought tier " + tier + " " + track);
                    if (!PodComponents.partOf(pod, track).equals(Optional.of(bought)) || stat(track, pod) != stat) {
                        throw failure(helper, "the bought tier %d %s must stay as it was", tier, track);
                    }
                }
            }
            helper.succeed();
        } finally {
            scene.clean();
        }
    }

    private static void expectKept(
            GameTestHelper helper, Scene scene, PodEntity pod, ComponentTrack track, String what) {
        Optional<PartLabel> before = PodComponents.partOf(pod, track);
        InteractionResult result = useUnit(scene, pod, track);
        if (result != InteractionResult.FAIL) {
            throw failure(helper, "%s should refuse the %s unit, got %s", what, track, result);
        }
        if (!scene.player().getMainHandItem().is(unit(track))) {
            throw failure(helper, "%s: the refused %s unit should stay in the hand", what, track);
        }
        if (!PodComponents.partOf(pod, track).equals(before)) {
            throw failure(helper, "%s: the pod's %s changed to %s", what, track, PodComponents.partOf(pod, track));
        }
        if (!FITTED.get(pod.getUUID()).isEmpty()) {
            throw failure(helper, "%s: a refused fit fired FITTED, %s", what, FITTED.get(pod.getUUID()));
        }
    }

    /** Anything else held on a pod is left to the other features (fuel, towing); only a unit fits a part, and a unit for one track fits no other. */
    @GameTest
    public void aUnitFitsOnlyItsOwnTrackAndOnlyAUnitFits(GameTestHelper helper) {
        Scene scene = scene(helper, true);
        try {
            PodEntity pod = pod(helper, scene, Optional.of(charterOf(helper, scene.player())));
            scene.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_INGOT));
            UseEntityCallback.EVENT
                    .invoker()
                    .interact(scene.player(), pod.level(), InteractionHand.MAIN_HAND, pod, null);
            if (!scene.player().getMainHandItem().is(Items.IRON_INGOT)) {
                throw failure(
                        helper,
                        "an iron ingot fits nothing, hand holds %s",
                        scene.player().getMainHandItem());
            }
            useUnit(scene, pod, ComponentTrack.CARGO_BAY);
            for (ComponentTrack track : ComponentTrack.values()) {
                if (track != ComponentTrack.CARGO_BAY
                        && PodComponents.partOf(pod, track).isPresent()) {
                    throw failure(helper, "the cargo bay unit fitted a %s part too", track);
                }
            }
            helper.succeed();
        } finally {
            scene.clean();
        }
    }

    /** The tier 1 price is gone from the table of every track, and tier 2 keeps its price: the standard ladder's, or the one the track had. */
    @GameTest
    public void theTierTwoPricesAreTheOnesTheTracksHad(GameTestHelper helper) {
        Map<ComponentTrack, Long> tierTwo = Map.ofEntries(
                Map.entry(ComponentTrack.DRILL, 500L),
                Map.entry(ComponentTrack.HULL, 500L),
                Map.entry(ComponentTrack.ENGINE, 500L),
                Map.entry(ComponentTrack.FUEL_TANK, 500L),
                Map.entry(ComponentTrack.RADIATOR, 1250L),
                Map.entry(ComponentTrack.CARGO_BAY, 500L),
                Map.entry(ComponentTrack.LIGHTS, 375L),
                Map.entry(ComponentTrack.LINER, 1000L),
                Map.entry(ComponentTrack.SOUNDER, 1000L),
                Map.entry(ComponentTrack.SCANNER, 500L),
                Map.entry(ComponentTrack.CONSOLE, 500L));
        for (ComponentTrack track : ComponentTrack.values()) {
            if (UpgradeTuning.DEFAULT.price(track, 1) != 0) {
                throw failure(
                        helper,
                        "%s: tier 1 is crafted (price 0), got $%d",
                        track,
                        UpgradeTuning.DEFAULT.price(track, 1));
            }
            if (track.maxTier() > 1 && UpgradeTuning.DEFAULT.price(track, 2) != tierTwo.get(track)) {
                throw failure(
                        helper,
                        "%s: tier 2 costs $%d, got $%d",
                        track,
                        tierTwo.get(track),
                        UpgradeTuning.DEFAULT.price(track, 2));
            }
        }
        helper.succeed();
    }
}
