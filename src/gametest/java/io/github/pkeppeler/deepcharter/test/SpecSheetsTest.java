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
import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.PodBraceTuning;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodHeatShieldTuning;
import io.github.pkeppeler.deepcharter.pod.PodLights;
import io.github.pkeppeler.deepcharter.pod.PodLinerTuning;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodSounderTuning;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.repair.RepairRegistry;
import io.github.pkeppeler.deepcharter.repair.RepairStation;
import io.github.pkeppeler.deepcharter.repair.RepairTuning;
import io.github.pkeppeler.deepcharter.scanner.ScannerTuning;
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
import io.github.pkeppeler.deepcharter.upgrade.UpgradeTerminal;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeTuning;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.locale.Language;
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

    /** A funded charter's player beside a working terminal of {@code type}, and the terminal. */
    private record Terminal(MockPlayer buyer, ServerPlayer player, BlockPos pos, CharterId charter) {}

    /**
     * Repairs every terminal of the world (swapped for this tick only), puts a terminal of {@code type} down, and runs {@code body} with
     * a player of a funded charter standing beside it. Everything is inside the test's first tick, as a swapped record must be.
     */
    private static MockPlayer atTerminal(
            GameTestHelper helper, TerminalType type, String name, Consumer<Terminal> body) {
        MinecraftServer server = helper.getLevel().getServer();
        MockPlayer[] buyer = new MockPlayer[1];
        RepairState fresh = new RepairState();
        WorldData.with(server, RepairState.TYPE, fresh, () -> {
            for (TerminalType each : List.of(
                    TerminalTypes.FUEL_PUMP,
                    TerminalTypes.ORE_PROCESSOR,
                    TerminalTypes.UPGRADE_TERMINAL,
                    TerminalTypes.REPAIR_STATION)) {
                for (var part : each.parts()) {
                    fresh.insert(each, part).ifPresent(refusal -> {
                        throw failure(helper, "repairing %s: %s", each.id(), refusal);
                    });
                }
            }
            BlockPos relative = new BlockPos(0, 1, 0);
            helper.setBlock(relative, type.block().defaultBlockState());
            BlockPos pos = helper.absolutePos(relative);
            buyer[0] = join(helper, name);
            ServerPlayer player = buyer[0].player();
            player.setGameMode(GameType.SURVIVAL);
            expect(
                    helper,
                    Charters.found(server, player.getUUID(), name + " Co").isEmpty(),
                    "founding the charter should succeed");
            CharterId charter = Charters.charterOfOrThrow(server, player.getUUID())
                    .orElseThrow()
                    .id();
            expect(helper, Charters.deposit(server, charter, 10_000).isEmpty(), "funding the account should succeed");
            buyer[0].teleportTo(helper.getLevel(), Vec3.atCenterOf(pos).add(2, -player.getEyeHeight(), 0), 0, 0);
            body.accept(new Terminal(buyer[0], player, pos, charter));
        });
        return buyer[0];
    }

    @GameTest(maxTicks = 200)
    public void theFirstBuyAtTheRepairStationShowsTheSheetOnce(GameTestHelper helper) {
        Item bought = RepairRegistry.item(Consumable.BLASTING_CHARGE);
        MockPlayer buyer = atTerminal(helper, TerminalTypes.REPAIR_STATION, "SpecBuyer", terminal -> {
            CompoundTag args = new CompoundTag();
            args.putString(
                    RepairStation.ITEM_KEY, Consumable.BLASTING_CHARGE.itemId().toString());
            expect(
                    helper,
                    Terminals.act(terminal.player(), terminal.pos(), RepairStation.BUY, args)
                            .isEmpty(),
                    "the first buy is done");
            expect(
                    helper,
                    ReadMarks.isRead(terminal.player(), SpecSheetPopups.markOf(bought)),
                    "the first buy marks the sheet shown, in the same action");
            expect(
                    helper,
                    Terminals.act(terminal.player(), terminal.pos(), RepairStation.BUY, args)
                            .isEmpty(),
                    "the second buy is done");
        });
        // The mock records what the server sent on its own tick, so what it was sent is read after the swap has ended.
        helper.startSequence()
                .thenIdle(SETTLE)
                .thenExecute(() -> {
                    expectShown(helper, buyer, List.of(bought), "after two buys");
                    buyer.leave();
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 200)
    public void aPartBoughtAtTheUpgradeTerminalShowsThePartsSheetOnce(GameTestHelper helper) {
        Item part = ComponentItems.item(ComponentTrack.HULL);
        PodEntity[] pod = new PodEntity[1];
        MockPlayer buyer = atTerminal(helper, TerminalTypes.UPGRADE_TERMINAL, "SpecPartBuyer", terminal -> {
            // A Mole of the buyer's charter parked at the terminal, which is what the terminal fits a part to.
            pod[0] = helper.spawn(PodRegistry.POD, new Vec3(3.5, 1, 0.5));
            PodComponents.register(pod[0], terminal.charter());
            for (int tier = 2; tier <= 3; tier++) {
                CompoundTag args = new CompoundTag();
                args.putString(UpgradeTerminal.TRACK_KEY, ComponentTrack.HULL.id());
                args.putInt(UpgradeTerminal.TIER_KEY, tier);
                expect(
                        helper,
                        Terminals.act(terminal.player(), terminal.pos(), UpgradeTerminal.BUY, args)
                                .isEmpty(),
                        "buying a hull of tier %s",
                        tier);
            }
        });
        helper.startSequence()
                .thenIdle(SETTLE)
                .thenExecute(() -> {
                    expectShown(helper, buyer, List.of(part), "after buying a hull at tier 2 and then tier 3");
                    pod[0].discard();
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
                            && argsOf(row.value()).equals(List.of(figure(tuning.ratio(ComponentTrack.DRILL, tier)))),
                    "tier %s of the drill should read the tuning's ratio %s, got %s %s",
                    tier,
                    tuning.ratio(ComponentTrack.DRILL, tier),
                    keyOf(row.value()),
                    argsOf(row.value()));
        }
        // The figures themselves, written out, so a tuning that moves must move this too.
        expect(
                helper,
                argsOf(tiers.get(1).value()).equals(List.of("1.4")),
                "tier 1 drills at x1.4 (2.8 over the stock 2)");
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
        // A tier 1 scanner is the stock area (32 down), and the tiers above scale it by 1.5, 2 and 3
        // (ScannerTuning.area), not the
        // tuning's raw 0.5, 0.75, 1, 1.5.
        expect(
                helper,
                scanner.ratings().subList(0, 4).stream()
                        .map(rating -> argsOf(rating.value()))
                        .toList()
                        .equals(List.of(List.of("32"), List.of("48"), List.of("64"), List.of("96"))),
                "the scanner reads 32, 48, 64 and 96 down, got %s",
                scanner.ratings());
        SpecSheet hull = SpecSheets.of(ComponentItems.item(ComponentTrack.HULL));
        expect(
                helper,
                argsOf(hull.ratings().get(0).value()).equals(List.of("100"))
                        && argsOf(hull.ratings().get(2).value()).equals(List.of("300")),
                "a stock hull is 100 HP and tier 2 is three times that (30 over the stock 10), got %s",
                hull.ratings());
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
                        && argsOf(tier.value()).equals(List.of("1.4")),
                "a drill unit fits as tier 1, x1.4, got %s",
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
        SpecSheet canister = SpecSheets.of(RepairRegistry.item(Consumable.AIR_CANISTER));
        SpecSheet.Rating adds = row(helper, canister, "rating.adds");
        expect(
                helper,
                keyOf(adds.value()).equals("deepcharter.spec.unit.seconds")
                        && argsOf(adds.value()).equals(List.of("60"))
                        && argsOf(row(helper, canister, "rating.price").value()).equals(List.of("150")),
                "the air canister adds 60 S and costs $150, got %s",
                canister.ratings());
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
            // The spec sheet is client-only; this catches a missing blurb without a client test.
            expect(
                    helper,
                    Language.getInstance().has(keyOf(sheet.blurb())),
                    "%s's blurb %s is in the lang file",
                    id,
                    keyOf(sheet.blurb()));
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
                argsOf(row(helper, stock, "rating.stack").value()).equals(List.of("16")),
                "coal stacks to 16 (#1280), got %s",
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

    /**
     * The gate for the class of mistake where a sheet prints a table's raw number and not what gameplay does with it: for every track and
     * every tier a Prospector takes, a part is fitted to a real pod and the sheet's figure must be what the pod then has or does. The
     * tiers above the Prospector's cap cannot be fitted to any pod, so they are not checked here.
     */
    @GameTest
    public void everyTracksSheetFigureIsWhatAPodWithThatPartHas(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        PodEntity pod = helper.spawn(PodRegistry.PROSPECTOR, new Vec3(0.5, 1, 0.5));
        CharterId owner = CharterId.random();
        PodComponents.register(pod, owner);
        PodStats stock = PodStats.base();
        for (ComponentTrack track : ComponentTrack.values()) {
            SpecSheet sheet = SpecSheets.of(ComponentItems.item(track));
            boolean hasStock = UpgradeTuning.DEFAULT.value(track, 0) > 0f;
            for (int tier = 1; tier <= Math.min(track.maxTier(), Chassis.PROSPECTOR.tierCap()); tier++) {
                PodComponents.install(pod, ComponentItems.mint(server, track, tier, owner));
                int fitted = PodComponents.effectiveTier(pod, track);
                PodStats now = PodStats.of(pod);
                List<String> expected = switch (track) {
                    case DRILL -> List.of(figure(stock.ticksPerHardness() / now.ticksPerHardness()));
                    case HULL -> List.of(figure(now.maxHull()));
                    case ENGINE -> List.of(figure(now.enginePower()));
                    case FUEL_TANK -> List.of(figure(now.tankLitres()));
                    case CARGO_BAY -> List.of(Integer.toString(now.cargoSlots()));
                    case RADIATOR -> List.of(figure(PodComponents.radiatorRatio(pod)));
                    case SCANNER ->
                        List.of(Integer.toString(
                                ScannerTuning.DEFAULT.area(fitted).down()));
                    case LIGHTS -> List.of(Integer.toString(PodLights.partLevel(pod)));
                    case CONSOLE -> List.of(figure(UpgradeTuning.DEFAULT.ratio(track, fitted) * 100f));
                    case SPOIL_HOPPER -> List.of();
                    case LINER ->
                        List.of(Integer.toString(
                                PodLinerTuning.DEFAULT.tier(fitted).ringEverySlabs()));
                    case SOUNDER ->
                        List.of(Integer.toString(
                                PodSounderTuning.DEFAULT.tier(fitted).slabsBelow()));
                    case BRACE -> List.of(figure(PodBraceTuning.DEFAULT.hullPerOre()));
                    case HEAT_SHIELD ->
                        List.of(figure(PodHeatShieldTuning.DEFAULT.tier(fitted).hullPerBrick()));
                };
                SpecSheet.Rating row = sheet.ratings().get(hasStock ? tier : tier - 1);
                expect(
                        helper,
                        fitted == tier && argsOf(row.value()).equals(expected),
                        "%s at tier %s: a pod with the part is %s and the sheet reads %s",
                        track,
                        tier,
                        expected,
                        argsOf(row.value()));
            }
        }
        pod.discard();
        helper.succeed();
    }

    /** A figure the way the sheet prints it: no trailing .0, and a thousands separator on money. */
    private static String figure(float value) {
        float rounded = Math.round(value * 100f) / 100f;
        return rounded == Math.rint(rounded) ? Long.toString((long) rounded) : Float.toString(rounded);
    }

    private static String figure(long dollars) {
        return String.format(java.util.Locale.ROOT, "%,d", dollars);
    }
}
