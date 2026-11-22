package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import com.mojang.serialization.Codec;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.handbook.HandbookRegistry;
import io.github.pkeppeler.deepcharter.handbook.ReadMarks;
import io.github.pkeppeler.deepcharter.handbook.SpecSheet;
import io.github.pkeppeler.deepcharter.handbook.SpecSheetPayload;
import io.github.pkeppeler.deepcharter.handbook.SpecSheetPopups;
import io.github.pkeppeler.deepcharter.handbook.SpecSheets;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.repair.RepairRegistry;
import io.github.pkeppeler.deepcharter.repair.RepairStation;
import io.github.pkeppeler.deepcharter.repair.RepairTuning;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.Terminals;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.UnreadableChecks;
import io.github.pkeppeler.deepcharter.test.support.WorldData;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeEvents;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeTuning;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * Server GameTests for #848, the spec sheet: what each sheet is built from, and when a player is shown one. A craft goes through the
 * real inventory menu and its result slot, a buy through the repair station's own terminal action, so the events and the tick that
 * read them are the real ones.
 */
public class SpecSheetsTest {
    /** Ticks to let the tick that reads the crafted count, and the mock that records what the server sent, run. */
    private static final int SETTLE = 4;

    /** Read when a test runs, never in a static field: the class can load before the mod has registered its items. */
    private static Item item(String path) {
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path));
        if (item == Items.AIR) {
            throw new AssertionError("the mod registers no item " + path);
        }
        return item;
    }

    private static MockPlayer join(GameTestHelper helper, String name) {
        MockPlayer mock = MockPlayers.join(helper, name);
        mock.announceChannel(SpecSheetPayload.TYPE);
        return mock;
    }

    /** Crafts stalk planks in the player's 2 x 2 grid and takes the result: the real crafting path. */
    private static void craftPlanks(GameTestHelper helper, ServerPlayer player) {
        InventoryMenu menu = player.inventoryMenu;
        menu.getSlot(1).set(new ItemStack(item("fungal_stalk")));
        menu.clicked(0, 0, ContainerInput.PICKUP, player);
        expect(
                helper,
                menu.getCarried().is(item("stalk_planks")),
                "the grid's one stalk should craft planks, took %s",
                menu.getCarried());
        menu.setCarried(ItemStack.EMPTY);
    }

    private static List<Item> shown(MockPlayer mock) {
        return mock.payloadsOf(SpecSheetPayload.class).stream()
                .map(payload -> BuiltInRegistries.ITEM.getValue(payload.item()))
                .toList();
    }

    private static void expectShown(GameTestHelper helper, MockPlayer mock, List<Item> expected, String when) {
        expect(
                helper,
                shown(mock).equals(expected),
                "%s: sheets shown should be %s, got %s",
                when,
                expected,
                shown(mock));
    }

    // ---------------------------------------------------------------- when a sheet pops up

    @GameTest(maxTicks = 200)
    public void theFirstCraftOfACompanyItemShowsItsSheetAndNoCraftShowsItAgain(GameTestHelper helper) {
        MockPlayer crafter = join(helper, "SpecCrafter");
        ServerPlayer player = crafter.player();
        helper.startSequence()
                .thenIdle(SETTLE)
                .thenExecute(() -> expectShown(helper, crafter, List.of(), "before any craft"))
                .thenExecute(() -> craftPlanks(helper, player))
                .thenIdle(SETTLE)
                .thenExecute(() -> {
                    expectShown(helper, crafter, List.of(item("stalk_planks")), "after the first craft");
                    expect(
                            helper,
                            ReadMarks.isRead(player, SpecSheetPopups.markOf(item("stalk_planks"))),
                            "the first craft should be kept as a read mark on the player");
                    craftPlanks(helper, player);
                })
                .thenIdle(SETTLE)
                .thenExecute(() -> {
                    expectShown(helper, crafter, List.of(item("stalk_planks")), "after a second craft");
                    crafter.leave();
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 200)
    public void aSheetIsShownToEachPlayerOnTheirOwnFirstCraft(GameTestHelper helper) {
        MockPlayer first = join(helper, "SpecFirst");
        MockPlayer second = join(helper, "SpecSecond");
        helper.startSequence()
                .thenIdle(SETTLE)
                .thenExecute(() -> craftPlanks(helper, first.player()))
                .thenIdle(SETTLE)
                .thenExecute(() -> {
                    expectShown(helper, first, List.of(item("stalk_planks")), "the one who crafted");
                    expectShown(helper, second, List.of(), "their crewmate, who did not");
                    craftPlanks(helper, second.player());
                })
                .thenIdle(SETTLE)
                .thenExecute(() -> {
                    expectShown(helper, first, List.of(item("stalk_planks")), "the first, not shown it twice");
                    expectShown(helper, second, List.of(item("stalk_planks")), "the second, on their own first craft");
                    first.leave();
                    second.leave();
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 200)
    public void whatAPlayerCraftedBeforeTheyJoinedShowsNothing(GameTestHelper helper) {
        MockPlayer veteran = MockPlayers.join(helper, "SpecVeteran");
        ServerPlayer player = veteran.player();
        // The crafted count is already above zero when the server first looks at the player: that is history, not a
        // craft.
        player.getStats().setValue(player, net.minecraft.stats.Stats.ITEM_CRAFTED.get(item("stalk_planks")), 5);
        veteran.announceChannel(SpecSheetPayload.TYPE);
        helper.startSequence()
                .thenIdle(SETTLE)
                .thenExecute(() -> {
                    expectShown(helper, veteran, List.of(), "with five planks crafted before");
                    veteran.leave();
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 200)
    public void aStockItemAndAClientWithoutTheChannelShowNoSheet(GameTestHelper helper) {
        MockPlayer deaf = MockPlayers.join(helper, "SpecDeaf");
        MockPlayer hearing = join(helper, "SpecHearing");
        helper.startSequence()
                .thenIdle(SETTLE)
                .thenExecute(() -> {
                    expect(
                            helper,
                            !SpecSheetPopups.offer(hearing.player(), Items.OAK_PLANKS),
                            "a stock item has no pop-up");
                    expect(
                            helper,
                            !SpecSheetPopups.offer(deaf.player(), item("stalk_planks")),
                            "a client that cannot receive the payload is not sent one");
                    expect(
                            helper,
                            !ReadMarks.isRead(deaf.player(), SpecSheetPopups.markOf(item("stalk_planks"))),
                            "and the sheet is not marked as seen, so it shows when it can");
                    expect(
                            helper,
                            SpecSheetPopups.offer(hearing.player(), item("stalk_planks")),
                            "a Company item is offered to a client that can receive it");
                    expect(
                            helper,
                            !SpecSheetPopups.offer(hearing.player(), item("stalk_planks")),
                            "and only the first time");
                    deaf.leave();
                    hearing.leave();
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 200)
    public void theFirstBuyAtTheRepairStationShowsTheSheetOnce(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Item bought = RepairRegistry.item(Consumable.BLASTING_CHARGE);
        MockPlayer[] buyer = new MockPlayer[1];
        RepairState fresh = new RepairState();
        WorldData.with(server, RepairState.TYPE, fresh, () -> {
            for (TerminalType type : List.of(
                    TerminalTypes.FUEL_PUMP,
                    TerminalTypes.ORE_PROCESSOR,
                    TerminalTypes.UPGRADE_TERMINAL,
                    TerminalTypes.REPAIR_STATION)) {
                for (var part : type.parts()) {
                    fresh.insert(type, part).ifPresent(refusal -> {
                        throw failure(helper, "repairing %s: %s", type.id(), refusal);
                    });
                }
            }
            BlockPos relative = new BlockPos(0, 1, 0);
            helper.setBlock(relative, TerminalTypes.REPAIR_STATION.block().defaultBlockState());
            BlockPos pos = helper.absolutePos(relative);
            buyer[0] = join(helper, "SpecBuyer");
            ServerPlayer player = buyer[0].player();
            player.setGameMode(GameType.SURVIVAL);
            expect(
                    helper,
                    Charters.found(server, player.getUUID(), "Spec Buyers").isEmpty(),
                    "founding the charter should succeed");
            CharterId charter = Charters.charterOfOrThrow(server, player.getUUID())
                    .orElseThrow()
                    .id();
            expect(helper, Charters.deposit(server, charter, 10_000).isEmpty(), "funding the account should succeed");
            buyer[0].teleportTo(helper.getLevel(), Vec3.atCenterOf(pos).add(2, -player.getEyeHeight(), 0), 0, 0);
            CompoundTag args = new CompoundTag();
            args.putString(
                    RepairStation.ITEM_KEY, Consumable.BLASTING_CHARGE.itemId().toString());
            expect(helper, Terminals.act(player, pos, RepairStation.BUY, args).isEmpty(), "the first buy is done");
            expect(
                    helper,
                    ReadMarks.isRead(player, SpecSheetPopups.markOf(bought)),
                    "the first buy marks the sheet shown, in the same action");
            expect(helper, Terminals.act(player, pos, RepairStation.BUY, args).isEmpty(), "the second buy is done");
        });
        // The mock records what the server sent on its own tick, so what it was sent is read after the swap has ended.
        helper.startSequence()
                .thenIdle(SETTLE)
                .thenExecute(() -> {
                    expectShown(helper, buyer[0], List.of(bought), "after two buys");
                    buyer[0].leave();
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 200)
    public void aPartBoughtAtTheUpgradeTerminalShowsThePartsSheetOnce(GameTestHelper helper) {
        MockPlayer buyer = join(helper, "SpecPartBuyer");
        PodEntity pod = helper.spawn(PodRegistry.POD, new Vec3(0.5, 1, 0.5));
        MinecraftServer server = helper.getLevel().getServer();
        Item part = ComponentItems.item(ComponentTrack.HULL);
        helper.startSequence()
                .thenIdle(SETTLE)
                .thenExecute(() -> {
                    // The event the upgrade terminal fires when it installs what it sold.
                    UpgradeEvents.BOUGHT.invoker().onBought(server, buyer.player(), pod, ComponentTrack.HULL, 2);
                    UpgradeEvents.BOUGHT.invoker().onBought(server, buyer.player(), pod, ComponentTrack.HULL, 3);
                })
                .thenIdle(SETTLE)
                .thenExecute(() -> {
                    expectShown(helper, buyer, List.of(part), "after buying a hull at tier 2 and then tier 3");
                    pod.discard();
                    buyer.leave();
                })
                .thenSucceed();
    }

    // ---------------------------------------------------------------- saved state

    @GameTest
    public void theSheetMarkIsPinnedAndSurvivesASaveAndALoad(GameTestHelper helper) {
        expect(
                helper,
                SpecSheetPopups.markOf(item("stalk_planks")).toString().equals("deepcharter:spec_sheet/stalk_planks"),
                "a mark's id is saved in the player's data, so it never changes: %s",
                SpecSheetPopups.markOf(item("stalk_planks")));
        Identifier other = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "hint/first_ore");
        ReadMarks marks = new ReadMarks(Set.of(SpecSheetPopups.markOf(item("stalk_planks")), other));
        Codec<Versioned<ReadMarks>> codec = ReadMarks.CODEC;
        Tag saved = codec.encodeStart(NbtOps.INSTANCE, Versioned.of(marks)).getOrThrow();
        Versioned<ReadMarks> loaded = codec.parse(NbtOps.INSTANCE, saved).getOrThrow();
        if (!(loaded instanceof Versioned.Readable<ReadMarks> readable)) {
            throw failure(helper, "marks saved by this build should load readable, got %s", loaded);
        }
        expect(
                helper,
                readable.value().equals(marks),
                "a loaded player keeps the sheet mark and the other one-time lines, got %s",
                readable.value());
        helper.succeed();
    }

    @GameTest(maxTicks = 200)
    public void unreadableMarksShowNoSheetAndNeverThrow(GameTestHelper helper) {
        MockPlayer reader = join(helper, "SpecUnreadable");
        ServerPlayer player = reader.player();
        UnreadableChecks.makeUnreadable(player, HandbookRegistry.READ_MARKS);
        UnreadableChecks.assertNoThrow(
                helper, "spec sheets", Map.of("offer", () -> SpecSheetPopups.offer(player, item("stalk_planks"))));
        helper.startSequence()
                .thenIdle(SETTLE)
                .thenExecute(() -> craftPlanks(helper, player))
                .thenIdle(SETTLE)
                .thenExecute(() -> {
                    expectShown(helper, reader, List.of(), "with marks this build cannot read");
                    expect(
                            helper,
                            player.getAttached(HandbookRegistry.READ_MARKS) instanceof Versioned.Unreadable<?>,
                            "the unreadable marks are left as they were");
                    reader.leave();
                })
                .thenSucceed();
    }

    // ---------------------------------------------------------------- what a sheet says

    /** The arguments of a translatable component, as the strings they print as. */
    private static List<String> argsOf(Component component) {
        if (!(component.getContents() instanceof TranslatableContents translatable)) {
            throw new AssertionError("not a lang key: " + component);
        }
        return java.util.Arrays.stream(translatable.getArgs())
                .map(arg -> arg instanceof Component inner ? inner.getString() : String.valueOf(arg))
                .toList();
    }

    private static String keyOf(Component component) {
        if (!(component.getContents() instanceof TranslatableContents translatable)) {
            throw new AssertionError("not a lang key: " + component);
        }
        return translatable.getKey();
    }

    private static SpecSheet.Rating row(GameTestHelper helper, SpecSheet sheet, String label) {
        return sheet.ratings().stream()
                .filter(rating -> keyOf(rating.label()).equals("deepcharter.spec." + label))
                .findFirst()
                .orElseThrow(() -> failure(helper, "the sheet of %s has no %s row: %s", sheet.item(), label, sheet));
    }

    @GameTest
    public void aPartsSheetReadsItsTiersFromTheUpgradeTuning(GameTestHelper helper) {
        SpecSheet drill = SpecSheets.of(ComponentItems.item(ComponentTrack.DRILL));
        // Tier 0, the stock part, then one row for each of the six tiers a drill comes in.
        expect(helper, drill.ratings().size() >= 7, "a drill part lists every tier, got %s", drill.ratings());
        UpgradeTuning tuning = UpgradeTuning.DEFAULT;
        List<SpecSheet.Rating> tiers = drill.ratings().subList(0, ComponentTrack.DRILL.maxTier() + 1);
        for (int tier = 0; tier <= ComponentTrack.DRILL.maxTier(); tier++) {
            SpecSheet.Rating row = tiers.get(tier);
            expect(
                    helper,
                    keyOf(row.value()).equals("deepcharter.spec.value.drill")
                            && argsOf(row.value()).equals(List.of(figure(tuning.value(ComponentTrack.DRILL, tier)))),
                    "tier %s of the drill should read the tuning's %s, got %s %s",
                    tier,
                    tuning.value(ComponentTrack.DRILL, tier),
                    keyOf(row.value()),
                    argsOf(row.value()));
        }
        // The figures themselves, written out, so a tuning that moves must move this too.
        expect(helper, argsOf(tiers.get(1).value()).equals(List.of("2.8")), "tier 1 drills at 2.8 (stock 2)");
        expect(
                helper,
                argsOf(tiers.get(2).label()).equals(List.of("2", "500")),
                "tier 2 is bought at $500, got %s",
                argsOf(tiers.get(2).label()));
        SpecSheet radiator = SpecSheets.of(ComponentItems.item(ComponentTrack.RADIATOR));
        expect(
                helper,
                argsOf(radiator.ratings().get(2).value()).equals(List.of("0.75")),
                "tier 2 of the radiator takes the heat down to 0.75, got %s",
                radiator.ratings().get(2));
        SpecSheet scanner = SpecSheets.of(ComponentItems.item(ComponentTrack.SCANNER));
        expect(
                helper,
                keyOf(scanner.ratings().getFirst().label()).equals("deepcharter.spec.tier.crafted"),
                "a scanner has no stock part, so its first row is the crafted tier, got %s",
                scanner.ratings().getFirst());
        SpecSheet liner = SpecSheets.of(ComponentItems.item(ComponentTrack.LINER));
        expect(
                helper,
                argsOf(liner.ratings().get(1).value()).equals(List.of("3")),
                "tier 2 of the liner rings every 3 slabs, got %s",
                liner.ratings().get(1));
        helper.succeed();
    }

    @GameTest
    public void aCraftedUnitsSheetShowsTheTierItFits(GameTestHelper helper) {
        SpecSheet unit = SpecSheets.of(item("drill_unit"));
        SpecSheet.Rating tier = unit.ratings().getFirst();
        expect(
                helper,
                keyOf(tier.label()).equals("deepcharter.spec.tier.crafted")
                        && argsOf(tier.value()).equals(List.of("2.8")),
                "a drill unit fits as tier 1, 2.8, got %s",
                tier);
        expect(
                helper,
                unit.ratings().stream()
                        .noneMatch(rating -> keyOf(rating.label()).equals("deepcharter.spec.tier.bought")),
                "a unit is not sold, so no row has a price: %s",
                unit.ratings());
        helper.succeed();
    }

    @GameTest
    public void aShopItemsSheetReadsItsPriceAndEffectFromTheShopAndRepairTuning(GameTestHelper helper) {
        for (Consumable consumable : Consumable.values()) {
            SpecSheet sheet = SpecSheets.of(RepairRegistry.item(consumable));
            expect(
                    helper,
                    argsOf(row(helper, sheet, "rating.price").value()).equals(List.of(figure(consumable.price()))),
                    "%s should show its price of $%s, got %s",
                    consumable,
                    consumable.price(),
                    sheet.ratings());
        }
        RepairTuning tuning = RepairTuning.DEFAULT;
        expect(
                helper,
                argsOf(row(helper, SpecSheets.of(RepairRegistry.item(Consumable.HULL_NANOBOTS)), "rating.repairs")
                                .value())
                        .equals(List.of(figure(tuning.nanobotHp()))),
                "nanobots show the hull they repair");
        expect(
                helper,
                argsOf(row(helper, SpecSheets.of(RepairRegistry.item(Consumable.DYNAMITE)), "rating.clears")
                                .value())
                        .equals(List.of("3 x 3 x 3")),
                "dynamite clears 3 x 3 x 3");
        expect(
                helper,
                argsOf(row(helper, SpecSheets.of(RepairRegistry.item(Consumable.PLASTIC_EXPLOSIVES)), "rating.clears")
                                .value())
                        .equals(List.of("5 x 5 x 5")),
                "plastic explosives clear 5 x 5 x 5");
        SpecSheet ore = SpecSheets.of(item(OreType.GOLDIUM.itemId().getPath()));
        expect(
                helper,
                argsOf(row(helper, ore, "rating.worth").value()).equals(List.of("250")),
                "goldium is worth $250 at a terminal, got %s",
                ore.ratings());
        helper.succeed();
    }

    @GameTest
    public void everyCompanyItemHasASheetAndAStockItemSaysItIsStock(GameTestHelper helper) {
        int company = 0;
        for (Item item : BuiltInRegistries.ITEM) {
            Identifier id = BuiltInRegistries.ITEM.getKey(item);
            if (!id.getNamespace().equals(DeepCharter.MOD_ID)) {
                continue;
            }
            company++;
            SpecSheet sheet = SpecSheets.of(item);
            expect(
                    helper,
                    keyOf(sheet.blurb()).equals("deepcharter.spec.item." + id.getPath()),
                    "%s has its own blurb, got %s",
                    id,
                    sheet.blurb());
            expect(helper, keyOf(sheet.kind()).equals("deepcharter.spec.kind.company"), "%s is Company issue", id);
            expect(
                    helper,
                    sheet.ratings().stream()
                            .anyMatch(rating -> keyOf(rating.label()).equals("deepcharter.spec.rating.stack")),
                    "%s is rated for its stack size at least: %s",
                    id,
                    sheet.ratings());
        }
        expect(helper, company > 0, "the mod registers items");
        SpecSheet stock = SpecSheets.of(Items.COAL);
        expect(
                helper,
                keyOf(stock.kind()).equals("deepcharter.spec.kind.stock")
                        && keyOf(stock.blurb()).equals("deepcharter.spec.stock"),
                "a stock item's sheet says it is stock, got %s",
                stock);
        expect(
                helper,
                argsOf(row(helper, stock, "rating.stack").value()).equals(List.of("64")),
                "coal stacks to 64, got %s",
                stock.ratings());
        expect(
                helper,
                stock.ratings().stream()
                        .anyMatch(rating -> keyOf(rating.label()).equals("deepcharter.spec.rating.pod_fuel")),
                "coal is pod fuel by the synced tag, got %s",
                stock.ratings());
        SpecSheet pickaxe = SpecSheets.of(Items.IRON_PICKAXE);
        expect(
                helper,
                argsOf(row(helper, pickaxe, "rating.durability").value()).equals(List.of("250")),
                "an iron pickaxe has 250 durability, got %s",
                pickaxe.ratings());
        helper.succeed();
    }

    /** A figure the way the sheet prints it: no trailing .0, and a thousands separator on money. */
    private static String figure(float value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Float.toString(value);
    }

    private static String figure(long dollars) {
        return String.format(java.util.Locale.ROOT, "%,d", dollars);
    }
}
