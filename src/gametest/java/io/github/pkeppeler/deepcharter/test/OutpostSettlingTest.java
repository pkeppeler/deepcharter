package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.colony.ColonyKit;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.outpost.IncidentReportPayload;
import io.github.pkeppeler.deepcharter.outpost.IncidentReportPayload.Kind;
import io.github.pkeppeler.deepcharter.outpost.OutpostBuilder;
import io.github.pkeppeler.deepcharter.outpost.OutpostData;
import io.github.pkeppeler.deepcharter.outpost.OutpostSettling;
import io.github.pkeppeler.deepcharter.outpost.OutpostVisits;
import io.github.pkeppeler.deepcharter.outpost.OutpostVisits.Ledger;
import io.github.pkeppeler.deepcharter.outpost.OutpostVisits.Pending;
import io.github.pkeppeler.deepcharter.outpost.OutpostVisits.Report;
import io.github.pkeppeler.deepcharter.outpost.OutpostVisits.Survey;
import io.github.pkeppeler.deepcharter.outpost.SettlingTuning;
import io.github.pkeppeler.deepcharter.outpost.SettlingTuning.LayerRates;
import io.github.pkeppeler.deepcharter.outpost.OutpostShell;
import io.github.pkeppeler.deepcharter.outpost.OutpostShell.Shell;
import io.github.pkeppeler.deepcharter.outpost.OutpostShell.Terrain;
import io.github.pkeppeler.deepcharter.outpost.ShellTuning;
import io.github.pkeppeler.deepcharter.outpost.VisitBreaks;
import io.github.pkeppeler.deepcharter.outpost.VisitBreaks.Broken;
import io.github.pkeppeler.deepcharter.outpost.VisitCause;
import io.github.pkeppeler.deepcharter.outpost.VisitRoll;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.Terminals;
import io.github.pkeppeler.deepcharter.test.support.CampaignEnvironments;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.SettlingRooms;
import io.github.pkeppeler.deepcharter.test.support.SettlingRooms.Room;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import io.github.pkeppeler.deepcharter.test.support.UnreadableChecks;
import io.github.pkeppeler.deepcharter.test.support.WorldData;
import io.github.pkeppeler.deepcharter.wreck.SalvageHeaps;
import io.github.pkeppeler.deepcharter.wreck.WreckRegistry;
import io.github.pkeppeler.deepcharter.wreck.WreckState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Server GameTests for #1004, away settling (ADR 0079): the roll, the ledger, what a visit takes, the hold while a player is near, the
 * alert, the salvage heap, the incident report, and the saved formats. A room is carved in layer 1 and settled with a synthetic game time,
 * so no test writes a clock; a full-probability tuning ({@link SettlingRooms#SURE_RETAINED}) makes a visit count exact.
 */
public class OutpostSettlingTest {
    private static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + 400;

    private static Item item(Identifier id) {
        return BuiltInRegistries.ITEM.getValue(id);
    }

    private static Identifier idOf(Item item) {
        return BuiltInRegistries.ITEM.getKey(item);
    }

    /** The sequence of a test that carved {@code rooms}: it starts once each one's chunks are entity-ticking and its zone is loaded. */
    private static GameTestSequence whenLoaded(GameTestHelper helper, Room... rooms) {
        boolean[] ticking = new boolean[rooms.length];
        List<BlockPos> floors = new ArrayList<>();
        for (Room room : rooms) {
            floors.add(room.floor());
        }
        FarChunks.awaitEntityTicking(helper, rooms[0].level(), floors, index -> ticking[index] = true);
        return helper.startSequence().thenWaitUntil(() -> {
            for (int i = 0; i < rooms.length; i++) {
                expect(helper, ticking[i], "waiting for the chunk of room %s to tick entities", i);
                SettlingRooms.requireLoaded(helper, rooms[i]);
            }
        });
    }

    private static Ledger settle(Room room, Ledger ledger, int windows, SettlingTuning tuning) {
        return OutpostSettling.settle(room.level(), room.outpost(), ledger, SettlingRooms.after(windows), tuning);
    }

    // ----- the roll -----

    @GameTest
    public void rollingManyWindowsEqualsRollingThemInPiecesAndNothingToLoseRollsNothing(GameTestHelper helper) {
        SettlingTuning tuning = SettlingTuning.DEFAULT;
        long seed = VisitRoll.outpostSeed(1234L, new CharterId(new java.util.UUID(1, 2)), 1);
        Survey everything = new Survey(true, 3, 100, 10, 5);
        LayerRates rates = new LayerRates(6.0, 3.0, 2.0);
        Pending whole = VisitRoll.roll(seed, 10, 910, everything, rates, tuning);
        Pending pieces = VisitRoll.roll(seed, 10, 77, everything, rates, tuning)
                .plus(VisitRoll.roll(seed, 77, 300, everything, rates, tuning), Integer.MAX_VALUE)
                .plus(VisitRoll.roll(seed, 300, 301, everything, rates, tuning), Integer.MAX_VALUE)
                .plus(VisitRoll.roll(seed, 301, 910, everything, rates, tuning), Integer.MAX_VALUE);
        expect(helper, whole.equals(pieces), "rolling [10, 910) gave %s, in pieces %s", whole, pieces);
        expect(
                helper,
                whole.equals(VisitRoll.roll(seed, 10, 910, everything, rates, tuning)),
                "the same windows must roll the same visits");
        // 900 windows at 6 a day (0.3 a window), 3 a day (0.15) and 2 a day (0.1): the means are 270, 135 and 90.
        expect(helper, whole.retained() > 200 && whole.retained() < 340, "retained visits %s", whole);
        expect(helper, whole.sleepers() > 90 && whole.sleepers() < 180, "sleeper visits %s", whole);
        expect(helper, whole.recovery() > 55 && whole.recovery() < 130, "recovery visits %s", whole);
        Pending elsewhere = VisitRoll.roll(seed + 1, 10, 910, everything, rates, tuning);
        expect(helper, !whole.equals(elsewhere), "another outpost must roll other visits, both rolled %s", whole);
        expect(helper, VisitRoll.roll(seed, 5, 5, everything, rates, tuning).isEmpty(), "no windows, no visits");

        // An outpost with nothing to lose rolls nothing, family by family.
        expect(
                helper,
                VisitRoll.roll(seed, 0, 900, new Survey(false, 3, 100, 10, 5), rates, tuning)
                                .retained()
                        == 0,
                "a lit outpost has no Retained visits");
        expect(
                helper,
                VisitRoll.roll(seed, 0, 900, new Survey(true, 3, 0, 10, 5), rates, tuning)
                                .retained()
                        == 0,
                "an outpost with no stored fuel has no Retained visits");
        expect(
                helper,
                VisitRoll.roll(seed, 0, 900, new Survey(true, 0, 100, 10, 5), rates, tuning)
                                .sleepers()
                        == 0,
                "a silent outpost has no Sleeper's visits");
        expect(
                helper,
                VisitRoll.roll(seed, 0, 900, new Survey(true, 3, 100, 10, 0), rates, tuning)
                                .sleepers()
                        == 0,
                "an outpost with nothing breakable has no Sleeper's visits");
        expect(
                helper,
                VisitRoll.roll(seed, 0, 900, new Survey(true, 3, 100, 0, 5), rates, tuning)
                                .recovery()
                        == 0,
                "an outpost with no stored ore has no Recovery visits");
        helper.succeed();
    }

    @GameTest
    public void theTuningIsValidatedAndEveryBuiltLayerHasRates(GameTestHelper helper) {
        SettlingTuning tuning = SettlingTuning.DEFAULT;
        expect(helper, tuning.chancePerWindow(2.0) == 0.1, "2 visits a day over 1200-tick windows is 0.1 a window");
        expect(helper, tuning.chancePerWindow(1000) == 1.0, "the chance never passes 1");
        expect(helper, tuning.ratesOf(99).equals(LayerRates.NONE), "a layer with no entry has no visits");
        expect(helper, tuning.ratesOf(0).equals(LayerRates.NONE), "the surface has no visits");
        int built = LayerChain.count(helper.getLevel().registryAccess());
        expect(
                helper,
                tuning.layers().size() == built,
                "the tuning has rates for %s layers, the world has %s",
                tuning.layers().size(),
                built);
        try {
            new LayerRates(-1, 0, 0);
            throw failure(helper, "a negative rate must be refused");
        } catch (IllegalArgumentException expected) {
            // the message names the rate
        }
        helper.succeed();
    }

    // ----- fuel -----

    @GameTest(environment = CampaignEnvironments.D, maxTicks = MAX_TICKS)
    public void aDarkHallLosesAShareOfItsFuelEveryVisitAndALitHallLosesNone(GameTestHelper helper) {
        Room dark = SettlingRooms.stage(helper, 1, SettlingRooms.nobody());
        Room lit = SettlingRooms.stage(helper, 2, SettlingRooms.nobody());
        SettlingRooms.stock(dark, Items.COAL, 64);
        SettlingRooms.stock(lit, Items.COAL, 64);
        BlockPos lamp = lit.floor().offset(0, 3, 0);
        RoomCarver.carve(lit.level(), lamp, lamp, Blocks.GLOWSTONE.defaultBlockState());
        // The lamp's light is made on a background thread, so it is awaited under a wall-clock Deadline, not counted
        // in ticks.
        FarChunks.Deadline[] litBy = {null};
        boolean[] reached = {false};
        TestTicks.each(helper, () -> {
            if (litBy[0] == null || reached[0]) {
                return;
            }
            int light = lit.level().getBrightness(LightLayer.BLOCK, lit.floor().above());
            litBy[0].await(
                    helper,
                    lit.level(),
                    light >= 3,
                    () -> "the lamp's light did not reach the lit hall (is " + light + ")");
            reached[0] = light >= 3;
        });
        whenLoaded(helper, dark, lit)
                .thenExecute(() -> litBy[0] = FarChunks.deadline())
                .thenWaitUntil(() -> expect(helper, reached[0], "waiting for the lamp's light to reach the lit hall"))
                .thenExecute(() -> {
                    // 3 visits at 64 coal: ceil(0.25 * 64) = 16, then ceil(0.25 * 48) = 12, then ceil(0.25 * 36) = 9.
                    Ledger after = settle(dark, dark.ledger(), 3, SettlingRooms.SURE_RETAINED);
                    int left = SettlingRooms.count(dark.storage(), Items.COAL);
                    expect(helper, left == 27, "3 visits should leave 27 of 64 coal, %s are left", left);
                    expect(helper, after.unread().visits() == 3, "3 visits, the report says %s", after.unread());
                    expect(
                            helper,
                            after.unread().taken().equals(Map.of(idOf(Items.COAL), 37)),
                            "the report should say 37 coal was taken, it says %s",
                            after.unread());
                    expect(
                            helper,
                            after.unread().causes().equals(Set.of(VisitCause.DARK)),
                            "visits of a dark hall say it was dark: %s",
                            after.unread());
                    expect(helper, after.pending().isEmpty(), "a loaded zone meets every visit: %s", after.pending());
                    expect(helper, after.settledTo() == 3, "settled to window %s", after.settledTo());
                    expect(
                            helper,
                            after.survey().map(Survey::fuelItems).equals(Optional.of(27)),
                            "the survey is taken again after the visits: %s",
                            after.survey());

                    Ledger litAfter = settle(lit, lit.ledger(), 3, SettlingRooms.SURE_RETAINED);
                    expect(
                            helper,
                            SettlingRooms.count(lit.storage(), Items.COAL) == 64,
                            "a lit hall loses nothing, %s coal left",
                            SettlingRooms.count(lit.storage(), Items.COAL));
                    expect(helper, litAfter.unread().isEmpty(), "a lit hall has nothing to report: %s", litAfter);
                    expect(helper, litAfter.unread().causes().isEmpty(), "a lit hall names no cause: %s", litAfter);
                    expect(
                            helper,
                            litAfter.survey().map(Survey::dark).equals(Optional.of(false)),
                            "the survey says the lit hall is lit: %s",
                            litAfter.survey());
                    expect(helper, litAfter.settledTo() == 3, "the lit hall's windows pass all the same");

                    // The shipped rates: a visit never takes more than the cap, and the take is the same however it
                    // is counted.
                    dark.storage().clearContent();
                    // The same property as before the stacks became 16: every visit takes the cap, never the share. It
                    // was 1,728 coal (27 stacks of 64) over 200 windows, about 20 visits and 320 coal. A barrel holds
                    // 432 now, so 100 windows (about 10 visits, 160 coal) leave the stock above the 64 under which a
                    // quarter of it is less than the cap.
                    int full = dark.storage().getContainerSize() * Items.COAL.getDefaultMaxStackSize();
                    SettlingRooms.stock(dark, Items.COAL, full);
                    Ledger shipped = settle(dark, dark.ledger(), 100, SettlingTuning.DEFAULT);
                    int visits = shipped.unread().visits();
                    int taken = full - SettlingRooms.count(dark.storage(), Items.COAL);
                    expect(helper, visits > 0, "100 windows at 2 a day should bring a visit");
                    expect(
                            helper,
                            taken == visits * SettlingTuning.DEFAULT.maxFuelItemsPerVisit(),
                            "%s visits at a capped 16 coal each should take %s, %s were taken",
                            visits,
                            visits * SettlingTuning.DEFAULT.maxFuelItemsPerVisit(),
                            taken);
                })
                .thenSucceed();
    }

    /** The Retained come for what they can walk up to: fuel in a hall whose air is a room is out of reach, and the same hall opened loses it. */
    @GameTest(environment = CampaignEnvironments.D, maxTicks = MAX_TICKS)
    public void aClosedShellKeepsTheRetainedOutOfStorageAndAnOpenedOneDoesNot(GameTestHelper helper) {
        Room sealed = SettlingRooms.stageSealed(helper, 20, SettlingRooms.nobody());
        Room opened = SettlingRooms.stage(helper, 21, SettlingRooms.nobody());
        SettlingRooms.stock(sealed, Items.COAL, 64);
        SettlingRooms.stock(opened, Items.COAL, 64);
        whenLoaded(helper, sealed, opened)
                .thenExecute(() -> {
                    Ledger closed = settle(sealed, sealed.ledger(), 3, SettlingRooms.SURE_RETAINED);
                    expect(
                            helper,
                            SettlingRooms.count(sealed.storage(), Items.COAL) == 64,
                            "a closed shell keeps the Retained out: %s of 64 coal left",
                            SettlingRooms.count(sealed.storage(), Items.COAL));
                    expect(
                            helper,
                            closed.survey().map(Survey::fuelItems).equals(Optional.of(0))
                                    && closed.unread().isEmpty(),
                            "fuel out of reach is not counted and brings no visit: %s",
                            closed);
                    Ledger reached = settle(opened, opened.ledger(), 3, SettlingRooms.SURE_RETAINED);
                    expect(
                            helper,
                            SettlingRooms.count(opened.storage(), Items.COAL) == 27
                                    && reached.unread().visits() == 3,
                            "the opened hall loses its fuel to 3 visits: %s coal left, %s",
                            SettlingRooms.count(opened.storage(), Items.COAL),
                            reached.unread());
                })
                .thenSucceed();
    }

    @GameTest(environment = CampaignEnvironments.D, maxTicks = MAX_TICKS)
    public void aNearbyPlayerHoldsTheVisitsAndASpectatorOrAFarPlayerDoesNot(GameTestHelper helper) {
        Room room = SettlingRooms.stage(helper, 3, SettlingRooms.nobody());
        SettlingRooms.stock(room, Items.COAL, 64);
        MockPlayer mock = MockPlayers.join(helper, "settling-near");
        SettlingTuning near5 = SettlingRooms.tuning(5, new LayerRates(20, 0, 0));
        Vec3 middle = Vec3.atCenterOf(room.floor());
        whenLoaded(helper, room)
                .thenExecute(() -> {
                    mock.player().setGameMode(GameType.CREATIVE);
                    mock.teleportTo(room.level(), middle.add(5, 0, 0), 0f, 0f);
                    Ledger held = settle(room, room.ledger(), 5, near5);
                    expect(
                            helper,
                            SettlingRooms.count(room.storage(), Items.COAL) == 64
                                    && held.unread().isEmpty(),
                            "a player exactly 5 blocks away is near: nothing is taken, %s",
                            held);
                    expect(helper, held.settledTo() == 5, "the time with a player near passes unrolled: %s", held);
                    mock.teleportTo(room.level(), middle.add(5.01, 0, 0), 0f, 0f);
                    Ledger far = settle(room, held, 7, near5);
                    expect(
                            helper,
                            far.unread().visits() == 2,
                            "a player a hair past 5 blocks is not near: 2 windows, 2 visits, %s",
                            far.unread());
                    mock.teleportTo(room.level(), middle, 0f, 0f);
                    Ledger holding = settle(room, far, 9, near5);
                    expect(
                            helper,
                            holding.unread().visits() == 2 && holding.settledTo() == 9,
                            "a player in the hall holds the visits again: %s",
                            holding);
                    mock.player().setGameMode(GameType.SPECTATOR);
                    Ledger spectated = settle(room, holding, 12, near5);
                    expect(
                            helper,
                            spectated.unread().visits() == 5,
                            "a spectator in the hall does not hold: 3 more visits, %s",
                            spectated.unread());
                })
                .thenSucceed();
    }

    @GameTest(environment = CampaignEnvironments.D, maxTicks = MAX_TICKS)
    public void onlineMembersAreToldOnceAndOthersAreNot(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        MockPlayer member = MockPlayers.join(helper, "settling-member");
        MockPlayer other = MockPlayers.join(helper, "settling-other");
        Charters.found(
                        server,
                        member.player().getUUID(),
                        "Alerts " + java.util.UUID.randomUUID().toString().substring(0, 8))
                .ifPresent(refusal -> {
                    throw failure(helper, "could not found a charter: %s", refusal);
                });
        CharterId charter = Charters.charterOfOrThrow(server, member.player().getUUID())
                .orElseThrow()
                .id();
        Room room = SettlingRooms.stage(helper, 4, charter);
        SettlingRooms.stock(room, Items.COAL, 64);
        whenLoaded(helper, room)
                .thenExecute(() -> {
                    // A window with no visit: nothing to say.
                    settle(room, room.ledger(), 0, SettlingRooms.SURE_RETAINED);
                    settle(room, room.ledger(), 3, SettlingRooms.SURE_RETAINED);
                })
                .thenIdle(3)
                .thenExecute(() -> {
                    List<Component> told = member.chatMessages().stream()
                            .filter(message -> message.getContents() instanceof TranslatableContents contents
                                    && contents.getKey().startsWith("message.deepcharter.outpost."))
                            .toList();
                    expect(helper, told.size() == 1, "the member should be told once, was told %s", told);
                    expect(
                            helper,
                            told.getFirst().getContents() instanceof TranslatableContents contents
                                    && contents.getKey().equals("message.deepcharter.outpost.disturbance")
                                    && contents.getArgs().length == 1
                                    && contents.getArgs()[0].equals(1),
                            "the alert should name Outpost 1: %s",
                            told);
                    List<Component> overheard = other.chatMessages().stream()
                            .filter(message -> message.getContents() instanceof TranslatableContents contents
                                    && contents.getKey().startsWith("message.deepcharter.outpost."))
                            .toList();
                    expect(helper, overheard.isEmpty(), "a player of no charter must not be told: %s", overheard);
                })
                .thenSucceed();
    }

    // ----- what a visit leaves alone -----

    @GameTest(environment = CampaignEnvironments.D, maxTicks = MAX_TICKS)
    public void terminalsHeapsPodsAndWrecksAreNeverTouched(GameTestHelper helper) {
        Room room = SettlingRooms.stage(helper, 5, SettlingRooms.nobody());
        BlockPos heapAt = room.floor().offset(-3, 1, 3);
        BlockPos pump = room.floor().offset(-3, 1, -3);
        room.level().setBlock(heapAt, WreckRegistry.HEAP.defaultBlockState(), 3);
        room.level().setBlock(pump, TerminalTypes.FUEL_PUMP.block().defaultBlockState(), 3);
        Container heap = SettlingRooms.containerAt(room.level(), heapAt);
        heap.setItem(0, new ItemStack(Items.COAL, Items.COAL.getDefaultMaxStackSize()));
        PodEntity pod = TestEntities.create(PodRegistry.POD, room.level());
        pod.setPos(Vec3.atBottomCenterOf(room.floor().offset(0, 1, -2)));
        room.level().addFreshEntity(pod);
        pod.setFuel(50f);
        PodEntity wreck = TestEntities.create(PodRegistry.POD, room.level());
        wreck.setPos(Vec3.atBottomCenterOf(room.floor().offset(2, 1, -2)));
        room.level().addFreshEntity(wreck);
        wreck.setAttached(WreckRegistry.STATE, Versioned.of(WreckState.WRECKED));
        wreck.setFuel(40f);
        whenLoaded(helper, room)
                .thenExecute(() -> {
                    // Only the heap holds fuel: the survey sees none, so nothing is rolled.
                    Ledger only = settle(room, room.ledger(), 50, SettlingRooms.SURE_RETAINED);
                    expect(
                            helper,
                            only.survey().map(Survey::fuelItems).equals(Optional.of(0)),
                            "a heap's fuel is not stored fuel: %s",
                            only.survey());
                    expect(helper, only.unread().isEmpty() && only.pending().isEmpty(), "nothing to lose: %s", only);
                    SettlingRooms.stock(room, Items.COAL, 64);
                    Ledger drained = settle(room, only, 100, SettlingRooms.SURE_RETAINED);
                    expect(
                            helper,
                            SettlingRooms.count(room.storage(), Items.COAL) < 64
                                    && drained.unread().visits() > 0,
                            "the barrel is the visit's target: %s",
                            drained);
                    expect(
                            helper,
                            SettlingRooms.count(heap, Items.COAL) == Items.COAL.getDefaultMaxStackSize(),
                            "the heap keeps its stack of coal, it holds %s",
                            SettlingRooms.count(heap, Items.COAL));
                    expect(
                            helper,
                            room.level().getBlockState(pump).is(TerminalTypes.FUEL_PUMP.block()),
                            "a terminal is never touched");
                    expect(helper, pod.fuel() == 50f, "a pod's tank is not storage, it holds %s", pod.fuel());
                    expect(helper, wreck.fuel() == 40f, "a wreck's tank is not storage, it holds %s", wreck.fuel());
                    expect(
                            helper,
                            !pod.isRemoved()
                                    && !wreck.isRemoved()
                                    && Versioned.readable(wreck, WreckRegistry.STATE)
                                            .equals(Optional.of(WreckState.WRECKED)),
                            "a pod and a wreck stay as they are");
                })
                .thenSucceed();
    }

    @GameTest(environment = CampaignEnvironments.D, maxTicks = MAX_TICKS)
    public void oreIsTakenOnlyWhereTheTuningGivesARecoveryRateAndTheDearestGoesFirst(GameTestHelper helper) {
        Room room = SettlingRooms.stage(helper, 6, SettlingRooms.nobody());
        Item ironium = item(OreType.IRONIUM.itemId());
        Item goldium = item(OreType.GOLDIUM.itemId());
        Container storage = room.storage();
        // Ore does not stack: five ironium take five slots.
        SettlingRooms.stock(room, ironium, 5);
        storage.setItem(10, new ItemStack(goldium, 1));
        whenLoaded(helper, room)
                .thenExecute(() -> {
                    Ledger shipped = settle(room, room.ledger(), 2000, SettlingTuning.DEFAULT);
                    expect(
                            helper,
                            SettlingRooms.count(storage, ironium) == 5 && SettlingRooms.count(storage, goldium) == 1,
                            "the shipped tuning has no Recovery rate: the ore stays, the barrel holds %s ironium and %s goldium (%s)",
                            SettlingRooms.count(storage, ironium),
                            SettlingRooms.count(storage, goldium),
                            ironium);
                    expect(
                            helper,
                            shipped.survey().map(Survey::oreItems).equals(Optional.of(6)),
                            "the survey still sees the ore: %s",
                            shipped.survey());
                    // One window at one visit a window: 4 ore, the dearest first.
                    Ledger sure = settle(room, room.ledger(), 1, SettlingRooms.SURE_RECOVERY);
                    expect(
                            helper,
                            SettlingRooms.count(storage, goldium) == 0 && SettlingRooms.count(storage, ironium) == 2,
                            "a visit takes the goldium then 3 ironium, the barrel holds %s goldium and %s ironium",
                            SettlingRooms.count(storage, goldium),
                            SettlingRooms.count(storage, ironium));
                    expect(
                            helper,
                            sure.unread().taken().equals(Map.of(idOf(goldium), 1, idOf(ironium), 3)),
                            "the report says what was removed: %s",
                            sure.unread());
                })
                .thenSucceed();
    }

    // ----- the salvage heap -----

    @GameTest(environment = CampaignEnvironments.D, maxTicks = MAX_TICKS)
    public void aBrokenStorageBlockSpillsIntoAHeapThatKeepsEverythingAndDropsNothing(GameTestHelper helper) {
        Room room = SettlingRooms.stage(helper, 7, SettlingRooms.nobody());
        SettlingRooms.stock(room, Items.COAL, 100);
        whenLoaded(helper, room)
                .thenExecute(() -> {
                    ServerLevel level = room.level();
                    int heaps = SalvageHeaps.spill(level, room.barrel(), List.of(new ItemStack(Items.BARREL)));
                    expect(helper, heaps == 1, "one heap should hold a barrel's worth, %s were made", heaps);
                    expect(
                            helper,
                            level.getBlockState(room.barrel()).is(WreckRegistry.HEAP),
                            "the barrel's cell should be a salvage heap, it is %s",
                            level.getBlockState(room.barrel()));
                    Container heap = SettlingRooms.containerAt(level, room.barrel());
                    expect(
                            helper,
                            SettlingRooms.count(heap, Items.COAL) == 100
                                    && SettlingRooms.count(heap, Items.BARREL) == 1,
                            "the heap should keep the 100 coal and the barrel, it holds %s coal and %s barrels",
                            SettlingRooms.count(heap, Items.COAL),
                            SettlingRooms.count(heap, Items.BARREL));
                    expectNoItemEntities(helper, room);

                    // More than a heap holds: the rest goes to a heap beside it, and still nothing drops.
                    BlockPos second = room.floor().offset(3, 1, -3);
                    level.setBlock(second, Blocks.BARREL.defaultBlockState(), 3);
                    List<ItemStack> flood = new ArrayList<>();
                    for (int i = 0; i < 60; i++) {
                        flood.add(new ItemStack(Items.COBBLESTONE, 64));
                    }
                    int made = SalvageHeaps.spill(level, second, flood);
                    expect(helper, made == 2, "60 stacks need two heaps, %s were made", made);
                    int held = 0;
                    for (BlockPos at : BlockPos.betweenClosed(second.offset(-1, -1, -1), second.offset(1, 1, 1))) {
                        if (level.getBlockEntity(at) instanceof Container container
                                && level.getBlockState(at).is(WreckRegistry.HEAP)) {
                            held += SettlingRooms.count(container, Items.COBBLESTONE);
                        }
                    }
                    expect(
                            helper,
                            held == 60 * 64,
                            "the heaps should hold all %s cobblestone, they hold %s",
                            60 * 64,
                            held);
                    expectNoItemEntities(helper, room);
                })
                .thenSucceed();
    }

    @GameTest(environment = CampaignEnvironments.D, maxTicks = MAX_TICKS)
    public void aSleepersVisitBreaksWhatTheDepthDoesNotHoldAndSpillsStorageIntoAHeap(GameTestHelper helper) {
        Room room = SettlingRooms.stage(helper, 8, SettlingRooms.nobody());
        SettlingRooms.stock(room, Items.COAL, 100);
        whenLoaded(helper, room)
                .thenExecute(() -> {
                    ServerLevel level = room.level();
                    BlockPos floor = room.floor();
                    BlockPos cobble = floor.offset(3, 1, -3);
                    BlockPos plate = floor.offset(-3, 1, -3);
                    BlockPos pump = floor.offset(-3, 1, 3);
                    BlockPos heap = floor.offset(0, 1, 3);
                    level.setBlock(cobble, Blocks.COBBLESTONE.defaultBlockState(), 3);
                    level.setBlock(plate, ColonyKit.RIVETED_PLATE.defaultBlockState(), 3);
                    level.setBlock(pump, TerminalTypes.FUEL_PUMP.block().defaultBlockState(), 3);
                    level.setBlock(heap, WreckRegistry.HEAP.defaultBlockState(), 3);
                    BoundingBox hall = new BoundingBox(
                            floor.getX() - 4,
                            floor.getY() + 1,
                            floor.getZ() - 4,
                            floor.getX() + 4,
                            floor.getY() + 4,
                            floor.getZ() + 4);
                    Predicate<BlockState> holds = state -> state.is(ColonyKit.RIVETED_PLATE);
                    Broken broken = VisitBreaks.apply(level, shell(level, hall, holds), 1, 10, RandomSource.create(7));
                    expect(
                            helper,
                            level.getBlockState(cobble).isAir(),
                            "an unheld block is broken, it is %s",
                            level.getBlockState(cobble));
                    expect(
                            helper,
                            broken.lost().equals(Map.of(idOf(Items.COBBLESTONE), 1)),
                            "the lost block is named: %s",
                            broken.lost());
                    expect(
                            helper,
                            level.getBlockState(room.barrel()).is(WreckRegistry.HEAP) && broken.heaps() == 1,
                            "the barrel spilled into a heap: %s, %s heaps",
                            level.getBlockState(room.barrel()),
                            broken.heaps());
                    Container spilled = SettlingRooms.containerAt(level, room.barrel());
                    expect(
                            helper,
                            SettlingRooms.count(spilled, Items.COAL) == 100
                                    && SettlingRooms.count(spilled, Items.BARREL) == 1,
                            "the heap keeps the 100 coal and the barrel");
                    expect(helper, level.getBlockState(plate).is(ColonyKit.RIVETED_PLATE), "a held block stays");
                    expect(helper, level.getBlockState(pump).is(TerminalTypes.FUEL_PUMP.block()), "a terminal stays");
                    expect(helper, level.getBlockState(heap).is(WreckRegistry.HEAP), "a heap stays");
                    expect(helper, broken.changed().isPresent(), "the changed box is reported");
                    expectNoItemEntities(helper, room);

                    // A visit breaks at most maxBlocks: 5 blocks, 2 visits of 2, one is left.
                    for (int i = 0; i < 5; i++) {
                        level.setBlock(floor.offset(-2 + i, 2, 0), Blocks.COBBLESTONE.defaultBlockState(), 3);
                    }
                    Broken some = VisitBreaks.apply(
                            level,
                            shell(level, hall, holds.or(state -> state.is(WreckRegistry.HEAP))),
                            2,
                            2,
                            RandomSource.create(9));
                    int left = 0;
                    for (int i = 0; i < 5; i++) {
                        left += level.getBlockState(floor.offset(-2 + i, 2, 0)).is(Blocks.COBBLESTONE) ? 1 : 0;
                    }
                    expect(
                            helper,
                            left == 1 && some.lost().equals(Map.of(idOf(Items.COBBLESTONE), 4)),
                            "2 visits of at most 2 blocks leave one of 5 blocks: %s left, lost %s",
                            left,
                            some.lost());
                    expectNoItemEntities(helper, room);
                })
                .thenSucceed();
    }

    @GameTest(environment = CampaignEnvironments.D, maxTicks = MAX_TICKS)
    public void breakingAHeapDropsEveryItemItHoldsAndTheHeapItself(GameTestHelper helper) {
        Room room = SettlingRooms.stage(helper, 9, SettlingRooms.nobody());
        SettlingRooms.stock(room, Items.COAL, 100);
        whenLoaded(helper, room)
                .thenExecute(() -> {
                    ServerLevel level = room.level();
                    SalvageHeaps.spill(level, room.barrel(), List.of(new ItemStack(Items.COBBLESTONE, 40)));
                    expect(
                            helper,
                            level.getBlockState(room.barrel()).is(WreckRegistry.HEAP),
                            "the barrel's cell should be a heap holding 100 coal and 40 cobblestone");
                    level.destroyBlock(room.barrel(), true);
                    expect(helper, level.getBlockState(room.barrel()).isAir(), "the heap is broken");
                    // entity-query: not applicable, this is src/gametest: the room's chunks are entity-ticking
                    List<ItemEntity> dropped =
                            level.getEntitiesOfClass(ItemEntity.class, new AABB(room.floor()).inflate(12));
                    int coal = 0;
                    int cobblestone = 0;
                    for (ItemEntity entity : dropped) {
                        ItemStack stack = entity.getItem();
                        if (stack.is(Items.COAL)) {
                            coal += stack.getCount();
                        } else if (stack.is(Items.COBBLESTONE)) {
                            cobblestone += stack.getCount();
                        } else {
                            throw failure(helper, "only what the heap held drops, not %s", stack);
                        }
                    }
                    expect(
                            helper,
                            coal == 100 && cobblestone == 40,
                            "every item drops: 100 coal and 40 cobblestone, got %s and %s from %s entities",
                            coal,
                            cobblestone,
                            dropped.size());
                })
                .thenSucceed();
    }

    @GameTest(environment = CampaignEnvironments.D, maxTicks = MAX_TICKS)
    public void anyPlayerEvenOneOutsideTheCharterCanOpenAHeap(GameTestHelper helper) {
        Room room = SettlingRooms.stage(helper, 10, SettlingRooms.nobody());
        MockPlayer stranger = MockPlayers.join(helper, "heap-stranger");
        stranger.player().setGameMode(GameType.SURVIVAL);
        whenLoaded(helper, room)
                .thenExecute(() -> {
                    ServerLevel level = room.level();
                    SalvageHeaps.spill(level, room.barrel(), List.of(new ItemStack(Items.COBBLESTONE)));
                    stranger.teleportTo(
                            level, Vec3.atBottomCenterOf(room.floor().above()), 0f, 0f);
                    expect(
                            helper,
                            Charters.readableCharterOf(
                                            helper.getLevel().getServer(),
                                            stranger.player().getUUID())
                                    .isEmpty(),
                            "the stranger is in no charter");
                    level.getBlockState(room.barrel())
                            .useWithoutItem(
                                    level,
                                    stranger.player(),
                                    new BlockHitResult(
                                            Vec3.atCenterOf(room.barrel()), Direction.UP, room.barrel(), false));
                    expect(
                            helper,
                            stranger.player().containerMenu instanceof ChestMenu menu
                                    && menu.getRowCount() == 6
                                    && menu.getContainer() == level.getBlockEntity(room.barrel()),
                            "the heap's menu should be open for a player of no charter, it is %s",
                            stranger.player().containerMenu);
                })
                .thenSucceed();
    }

    /** The shell of {@code zone}, whose candidates are the blocks {@code holds} does not hold, bar the ones no visit ever touches. */
    private static Shell shell(ServerLevel level, BoundingBox zone, Predicate<BlockState> holds) {
        return OutpostShell.survey(
                Terrain.of(level),
                zone,
                ShellTuning.DEFAULT,
                state -> !state.isAir() && !state.is(OutpostSettling.NEVER_SETTLED) && !holds.test(state));
    }

    private static void expectNoItemEntities(GameTestHelper helper, Room room) {
        // entity-query: not applicable, this is src/gametest: the room's chunks are entity-ticking
        List<ItemEntity> dropped =
                room.level().getEntitiesOfClass(ItemEntity.class, new AABB(room.floor()).inflate(12));
        expect(helper, dropped.isEmpty(), "no item may drop: %s", dropped);
    }

    // ----- the incident report -----

    @GameTest(environment = CampaignEnvironments.D, maxTicks = MAX_TICKS)
    public void theReportGoesOnceToTheFirstMemberWhoOpensAHallTerminalAndNotToAGuest(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel one = TestLayers.level(helper, 1);
        BlockPos floor = SettlingRooms.kitFloor(one, 9300);
        MockPlayer owner = MockPlayers.join(helper, "report-owner");
        MockPlayer guest = MockPlayers.join(helper, "report-guest");
        for (MockPlayer mock : List.of(owner, guest)) {
            mock.player().setGameMode(GameType.SURVIVAL);
            mock.announceChannel(IncidentReportPayload.TYPE);
            Charters.found(
                            server,
                            mock.player().getUUID(),
                            "Report " + java.util.UUID.randomUUID().toString().substring(0, 8))
                    .ifPresent(refusal -> {
                        throw failure(helper, "could not found a charter: %s", refusal);
                    });
        }
        CharterId ownerCharter = Charters.charterOfOrThrow(
                        server, owner.player().getUUID())
                .orElseThrow()
                .id();
        owner.teleportTo(one, Vec3.atBottomCenterOf(floor.above()), 0f, 0f);
        OutpostVisits visits = new OutpostVisits();
        Identifier coal = idOf(Items.COAL);
        Report report = new Report(3, Map.of(coal, 37), Map.of(idOf(Items.BARREL), 1), 1, Set.of(VisitCause.DARK));
        boolean[] ticking = {false};
        FarChunks.awaitEntityTicking(helper, one, floor, () -> ticking[0] = true);
        helper.startSequence()
                .thenWaitUntil(() -> expect(helper, ticking[0], "waiting for the hall's chunk to tick entities"))
                .thenExecute(() -> WorldData.swap(server)
                        .with(OutpostData.TYPE, new OutpostData())
                        .with(OutpostVisits.TYPE, visits)
                        .run(() -> {
                            Optional<Component> refused =
                                    OutpostBuilder.build(owner.player(), one, floor, Direction.UP);
                            expect(helper, refused.isEmpty(), "the kit should build, it said %s", refused);
                            BlockPos pump = floor.offset(-2, 1, -4);
                            guest.teleportTo(one, Vec3.atBottomCenterOf(pump.south(2)), 0f, 0f);
                            Charters.setOutpostsShared(server, owner.player().getUUID(), true);
                            visits.put(new Ledger(ownerCharter, 1, 0, Optional.empty(), Pending.NONE, report));
                            expect(
                                    helper,
                                    Terminals.open(guest.player(), pump).isEmpty(),
                                    "the guest should be able to open a shared hall's pump");
                            expect(
                                    helper,
                                    visits.find(ownerCharter, 1)
                                            .orElseThrow()
                                            .unread()
                                            .equals(report),
                                    "a guest does not read the owner's report");
                            expect(
                                    helper,
                                    Terminals.open(owner.player(), pump.above()).isEmpty(),
                                    "the owner should open the pump by its upper half");
                            expect(
                                    helper,
                                    visits.find(ownerCharter, 1)
                                            .orElseThrow()
                                            .unread()
                                            .isEmpty(),
                                    "reading the report clears it");
                            expect(
                                    helper,
                                    Terminals.open(owner.player(), pump).isEmpty(),
                                    "the owner should open it again");
                        }))
                .thenIdle(3)
                .thenExecute(() -> {
                    List<IncidentReportPayload> sent = owner.payloadsOf(IncidentReportPayload.class);
                    expect(helper, sent.size() == 1, "the owner should be sent the report once, got %s", sent);
                    IncidentReportPayload payload = sent.getFirst();
                    expect(
                            helper,
                            payload.outpost() == 1 && payload.visits() == 3 && payload.heaps() == 1,
                            "the report names outpost 1, 3 visits and 1 heap: %s",
                            payload);
                    expect(
                            helper,
                            payload.causes().equals(List.of(VisitCause.DARK)),
                            "the report says the hall was dark: %s",
                            payload.causes());
                    expect(
                            helper,
                            payload.lines()
                                    .equals(List.of(
                                            new IncidentReportPayload.Line(coal, 37, Kind.FUEL),
                                            new IncidentReportPayload.Line(idOf(Items.BARREL), 1, Kind.BROKEN))),
                            "the lines should say 37 coal taken and a barrel lost: %s",
                            payload.lines());
                    expect(
                            helper,
                            guest.payloadsOf(IncidentReportPayload.class).isEmpty(),
                            "a guest is sent no report: %s",
                            guest.payloadsOf(IncidentReportPayload.class));
                })
                .thenSucceed();
    }

    // ----- saved formats -----

    @GameTest
    public void theLedgerFormatIsPinnedAndUnreadableDataIsSafe(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        CompoundTag expected = parse(
                helper,
                "{version:1,ledgers:[{charter:[I;1,2,3,4],layer:1,settled_to:100L,survey:{dark:1b,noise:2,fuel_items:3,"
                        + "ore_items:4,breakable:5},pending:{retained:1,sleepers:2,recovery:3},unread:{visits:4,"
                        + "taken:{\"minecraft:coal\":5},broken:{\"minecraft:stone\":6},heaps:7,causes:[\"dark\"]}}]}");
        OutpostVisits.CODEC
                .parse(NbtOps.INSTANCE, expected)
                .ifSuccess(visits -> {
                    CharterId charter = new CharterId(new java.util.UUID(0x0000000100000002L, 0x0000000300000004L));
                    Ledger ledger = visits.find(charter, 1).orElseThrow();
                    Ledger want = new Ledger(
                            charter,
                            1,
                            100,
                            Optional.of(new Survey(true, 2, 3, 4, 5)),
                            new Pending(1, 2, 3),
                            new Report(
                                    4,
                                    Map.of(Identifier.parse("minecraft:coal"), 5),
                                    Map.of(Identifier.parse("minecraft:stone"), 6),
                                    7,
                                    Set.of(VisitCause.DARK)));
                    expect(helper, ledger.equals(want), "the pinned ledger decodes to %s, expected %s", ledger, want);
                    expect(
                            helper,
                            OutpostVisits.CODEC
                                    .encodeStart(NbtOps.INSTANCE, visits)
                                    .getOrThrow()
                                    .equals(expected),
                            "the ledger writes back as it was read: %s",
                            OutpostVisits.CODEC
                                    .encodeStart(NbtOps.INSTANCE, visits)
                                    .getOrThrow());
                })
                .ifError(error -> {
                    throw failure(helper, "the pinned ledger did not decode: %s", error);
                });
        Map<String, Runnable> paths = new LinkedHashMap<>();
        paths.put("sweep", () -> OutpostSettling.settleAll(server));
        UnreadableChecks.assertSavedDataNoThrow(helper, "away settling", server, OutpostVisits.TYPE, paths);
        UnreadableChecks.assertSavedDataNoThrow(helper, "away settling", server, OutpostData.TYPE, paths);
        helper.succeed();
    }

    /** A version 1 outpost was built in a format 1 world, where layer 1 is a dimension of its own (ADR 0059, ADR 0079). */
    private static final Identifier V1_DIMENSION = Identifier.fromNamespaceAndPath("deepcharter", "layer_1");

    @GameTest
    public void aVersionOneOutpostListLoadsWithItsLayersDimensionAndSavesAsVersionTwo(GameTestHelper helper) {
        CompoundTag v1 = parse(helper, "{version:1,outposts:[{charter:[I;1,2,3,4],layer:1,centre:[I;10,20,30]}]}");
        OutpostData data = OutpostData.CODEC.parse(NbtOps.INSTANCE, v1).getOrThrow();
        OutpostData.Outpost outpost = data.all().getFirst();
        CharterId charter = new CharterId(new java.util.UUID(0x0000000100000002L, 0x0000000300000004L));
        expect(
                helper,
                data.all().size() == 1 && outpost.charter().equals(charter),
                "the outpost loads: %s",
                data.all());
        expect(
                helper,
                outpost.dimension().identifier().equals(V1_DIMENSION),
                "a version 1 outpost stands in the dimension of its layer (a world before the campaign world), not %s",
                outpost.dimension());
        expect(helper, outpost.centre().equals(new BlockPos(10, 20, 30)), "the middle is kept: %s", outpost.centre());
        CompoundTag saved = (CompoundTag)
                OutpostData.CODEC.encodeStart(NbtOps.INSTANCE, data).getOrThrow();
        expect(
                helper,
                saved.getInt("version").orElse(0) == OutpostData.VERSION && OutpostData.VERSION == 2,
                "it saves as version 2: %s",
                saved);
        CompoundTag expectedV2 = parse(
                helper,
                "{version:2,outposts:[{charter:[I;1,2,3,4],layer:1,dimension:\""
                        + V1_DIMENSION
                        + "\",centre:[I;10,20,30]}]}");
        expect(helper, saved.equals(expectedV2), "version 2 pinned: %s", saved);
        OutpostData again = OutpostData.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
        expect(helper, again.all().equals(data.all()), "version 2 reads back the same: %s", again.all());
        helper.succeed();
    }

    private static CompoundTag parse(GameTestHelper helper, String snbt) {
        try {
            return TagParser.parseCompoundFully(snbt);
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            throw failure(helper, "bad SNBT %s: %s", snbt, e.getMessage());
        }
    }
}
