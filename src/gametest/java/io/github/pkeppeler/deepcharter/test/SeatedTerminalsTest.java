package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.fuel.FuelPump;
import io.github.pkeppeler.deepcharter.handbook.HandbookRegistry;
import io.github.pkeppeler.deepcharter.market.OreProcessor;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.terminal.RepairMenu;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.SeatedTerminalPayload;
import io.github.pkeppeler.deepcharter.terminal.SeatedTerminals;
import io.github.pkeppeler.deepcharter.terminal.TerminalActionPayload;
import io.github.pkeppeler.deepcharter.terminal.TerminalBlock;
import io.github.pkeppeler.deepcharter.terminal.TerminalEvents;
import io.github.pkeppeler.deepcharter.terminal.TerminalRefusal;
import io.github.pkeppeler.deepcharter.terminal.TerminalTuning;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.TerminalViewPayload;
import io.github.pkeppeler.deepcharter.terminal.Terminals;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.UseEntity;
import io.github.pkeppeler.deepcharter.test.support.WorldData;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Server GameTests for #1447: a seated pilot opens the terminal in reach that they face, through the seat
 * payload, and the terminal's reach is measured from the pod's box for anyone seated in a pod. Every case
 * acts inside its first tick, because the repair state it swaps in lives only for that tick.
 *
 * <p>A case that asks the server to pick a terminal has a batch of its own: the pick reads every terminal round the
 * pod, and a neighbour's in the same batch stands within reach of it.
 */
public class SeatedTerminalsTest {
    private static final double REACH = TerminalTuning.DEFAULT.maxDistance();
    private static final List<TerminalType> COLONY = List.of(
            TerminalTypes.FUEL_PUMP,
            TerminalTypes.ORE_PROCESSOR,
            TerminalTypes.UPGRADE_TERMINAL,
            TerminalTypes.REPAIR_STATION);
    /** Where the pod parks, in the test's own coordinates: the middle of its 13 by 13 floor. */
    private static final Vec3 PARKED = new Vec3(6.5, 1, 6.5);

    private static final AtomicInteger NAMES = new AtomicInteger();
    private static final int EMPTY_SLOT = 8;
    private static final int HANDBOOK_SLOT = 7;
    private static final int TOO_FAR_BLOCKS = 20;
    /** Blocks on each axis from a Prospector's position to the pump's middle in the corner case. */
    private static final double CORNER = 5.67;

    /** A terminal opened for a player, as plain values: an event listener stays registered for the whole run. */
    private record Opened(UUID player, Identifier type, BlockPos pos) {}

    private record Refused(UUID player, TerminalRefusal refusal) {}

    private static final List<Opened> OPENED = new CopyOnWriteArrayList<>();
    private static final List<Refused> REFUSED = new CopyOnWriteArrayList<>();

    static {
        TerminalEvents.OPENED.register(
                (server, type, player, pos) -> OPENED.add(new Opened(player.getUUID(), type.id(), pos.immutable())));
        TerminalEvents.REFUSED.register((player, refusal) -> REFUSED.add(new Refused(player.getUUID(), refusal)));
    }

    /** Runs {@code body} with the first {@code repaired} colony terminals repaired, then restores the world's. */
    private static void withRepaired(GameTestHelper helper, int repaired, Runnable body) {
        RepairState fresh = new RepairState();
        for (TerminalType type : COLONY.subList(0, repaired)) {
            type.parts().forEach(part -> fresh.insert(type, part));
        }
        WorldData.with(helper.getLevel().getServer(), RepairState.TYPE, fresh, body);
    }

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x <= 13; x++) {
            for (int z = 0; z <= 13; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
    }

    /**
     * The floor, with everything above it cleared to air: the barrier walls the test runner puts round each 8 by 8
     * test would stand between the pod and a terminal past them, and the seated pick needs a clear line. Only for a
     * case in a batch of its own, as the cleared ground reaches past the test's own walls.
     */
    private static void openGround(GameTestHelper helper) {
        floor(helper);
        for (int x = 0; x <= 13; x++) {
            for (int y = 1; y <= 6; y++) {
                for (int z = 0; z <= 13; z++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
    }

    /** Places a whole terminal (both halves) of {@code type} at {@code relative} and returns its lower half. */
    private static BlockPos terminal(GameTestHelper helper, TerminalType type, BlockPos relative) {
        BlockPos pos = helper.absolutePos(relative);
        TerminalBlock.place(helper.getLevel(), pos, type.block().defaultBlockState(), 3);
        return pos;
    }

    private static MockPlayer charterMember(GameTestHelper helper, String role) {
        MockPlayer mock = outsider(helper, role);
        if (Charters.found(
                        helper.getLevel().getServer(),
                        mock.player().getUUID(),
                        "Seated " + role + " " + NAMES.incrementAndGet())
                .isPresent()) {
            throw failure(helper, "founding a charter for %s should succeed", role);
        }
        return mock;
    }

    private static MockPlayer outsider(GameTestHelper helper, String role) {
        MockPlayer mock = MockPlayers.join(helper, "seated-" + role + "-" + NAMES.incrementAndGet());
        mock.player().setGameMode(GameType.SURVIVAL);
        mock.announceChannel(TerminalViewPayload.TYPE);
        // The handbook every player is given sits in the first slot: an empty hand is another.
        mock.player().getInventory().setSelectedSlot(EMPTY_SLOT);
        return mock;
    }

    private static PodEntity pod(GameTestHelper helper) {
        return helper.spawn(PodRegistry.POD, PARKED);
    }

    private static void board(GameTestHelper helper, MockPlayer mock, PodEntity pod) {
        if (!mock.player().startRiding(pod, true, false)) {
            throw failure(
                    helper,
                    "%s should board the pod",
                    mock.player().getGameProfile().name());
        }
        pod.positionRider(mock.player());
    }

    /** Turns the player's eyes to the middle of the terminal at {@code pos}. */
    private static void face(ServerPlayer player, BlockPos pos) {
        player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(pos));
    }

    private static List<Opened> openedBy(ServerPlayer player) {
        return OPENED.stream()
                .filter(open -> open.player().equals(player.getUUID()))
                .toList();
    }

    private static List<Refused> refusedFor(ServerPlayer player) {
        return REFUSED.stream()
                .filter(refused -> refused.player().equals(player.getUUID()))
                .toList();
    }

    /** Expects the seat requests so far to have opened nothing and been refused once, as none in reach. */
    private static void refusedNoneInReach(GameTestHelper helper, ServerPlayer player, String what) {
        expect(
                helper,
                openedBy(player).isEmpty()
                        && refusedFor(player)
                                .equals(List.of(new Refused(player.getUUID(), TerminalRefusal.NONE_IN_REACH))),
                "%s, opened %s, refused %s",
                what,
                openedBy(player),
                refusedFor(player));
    }

    private static boolean saw(MockPlayer mock, String key) {
        return mock.actionBarMessages().stream()
                .map(Component::getContents)
                .anyMatch(contents -> contents instanceof TranslatableContents translatable
                        && translatable.getKey().equals(key));
    }

    /** Asks from the seat while facing {@code pos}, and expects that terminal of {@code type} to open. */
    private static void opensFacing(GameTestHelper helper, MockPlayer mock, BlockPos pos, TerminalType type) {
        face(mock.player(), pos);
        int before = openedBy(mock.player()).size();
        mock.send(SeatedTerminalPayload.INSTANCE);
        List<Opened> opened = openedBy(mock.player());
        Opened expected = new Opened(mock.player().getUUID(), type.id(), pos);
        expect(
                helper,
                opened.size() == before + 1 && opened.getLast().equals(expected),
                "facing %s from the seat should open it, opened %s",
                type.id(),
                opened.subList(before, opened.size()));
    }

    private static double eyeDistance(ServerPlayer player, BlockPos pos) {
        return player.getEyePosition().distanceTo(Vec3.atCenterOf(pos));
    }

    private static void discard(Entity... entities) {
        for (Entity entity : entities) {
            entity.discard();
        }
    }

    /**
     * The pump stands past the pilot's on-foot reach but within the pod's, the processor beside it and the repair
     * station, still unrepaired, behind: each opens from the seat when faced, the station as its repair menu, and that
     * menu holds while the pod is in reach and closes leaving the pilot seated. Caught: a seat request that opens
     * nothing, a reach that stays measured from the eyes, a pick that ignores the look, and a close that dismounts.
     */
    @GameTest(environment = "deepcharter-test:seated_terminals_open")
    public void aSeatedPilotOpensTheTerminalTheyFaceInReach(GameTestHelper helper) {
        openGround(helper);
        BlockPos pump = terminal(helper, TerminalTypes.FUEL_PUMP, new BlockPos(12, 1, 7));
        BlockPos processor = terminal(helper, TerminalTypes.ORE_PROCESSOR, new BlockPos(1, 1, 6));
        BlockPos station = terminal(helper, TerminalTypes.REPAIR_STATION, new BlockPos(6, 1, 1));
        MockPlayer pilot = charterMember(helper, "pilot");
        PodEntity pod = pod(helper);
        try {
            withRepaired(helper, 2, () -> {
                board(helper, pilot, pod);
                ServerPlayer player = pilot.player();
                expect(
                        helper,
                        eyeDistance(player, pump) > REACH,
                        "the pump must stand past on-foot reach of the pilot's eyes, is %s",
                        eyeDistance(player, pump));

                opensFacing(helper, pilot, pump, TerminalTypes.FUEL_PUMP);
                opensFacing(helper, pilot, processor, TerminalTypes.ORE_PROCESSOR);
                opensFacing(helper, pilot, station, TerminalTypes.REPAIR_STATION);
                expect(
                        helper,
                        player.containerMenu instanceof RepairMenu menu
                                && menu.type() == TerminalTypes.REPAIR_STATION
                                && menu.stillValid(player),
                        "the unrepaired station should hold its repair menu open, got %s",
                        player.containerMenu);

                player.closeContainer();
                expect(
                        helper,
                        player.getVehicle() == pod,
                        "closing the repair menu should leave the pilot seated, rides %s",
                        player.getVehicle());

                opensFacing(helper, pilot, station, TerminalTypes.REPAIR_STATION);
                pod.setPos(pod.position().add(0, 0, REACH + 2));
                pod.positionRider(player);
                expect(
                        helper,
                        !player.containerMenu.stillValid(player),
                        "the repair menu should close once the pod leaves reach of the station");
                player.closeContainer();
            });
            // The mock reads what the server sent it on its own tick.
            helper.startSequence()
                    .thenWaitUntil(() -> expect(
                            helper,
                            pilot.payloadsOf(TerminalViewPayload.class).stream()
                                    .anyMatch(view -> view.view().pos().equals(pump)),
                            "the pump's view should reach the pilot, got %s",
                            pilot.payloadsOf(TerminalViewPayload.class)))
                    .thenExecute(() -> discard(pod))
                    .thenSucceed();
        } catch (RuntimeException thrown) {
            discard(pod);
            throw thrown;
        }
    }

    /**
     * A terminal just past the pod's reach is refused with a line and opens nothing; the pod a hair nearer opens it.
     * Caught: a reach of more than {@link TerminalTuning#maxDistance()} from the box, and a silent refusal.
     */
    @GameTest(environment = "deepcharter-test:seated_terminals_none")
    public void nothingInReachIsRefusedWithALine(GameTestHelper helper) {
        openGround(helper);
        // The pump's middle is 13.5 east, 0.05 past reach of the Mole's east face at 7.45.
        BlockPos pump = terminal(helper, TerminalTypes.FUEL_PUMP, new BlockPos(13, 1, 6));
        MockPlayer pilot = charterMember(helper, "far");
        PodEntity pod = pod(helper);
        try {
            withRepaired(helper, 1, () -> {
                board(helper, pilot, pod);
                ServerPlayer player = pilot.player();
                face(player, pump);
                double gap = pod.getBoundingBox().distanceToSqr(Vec3.atCenterOf(pump));
                expect(helper, gap > REACH * REACH, "the pump must stand past reach of the pod, is %s", Math.sqrt(gap));
                pilot.send(SeatedTerminalPayload.INSTANCE);
                refusedNoneInReach(helper, player, "nothing in reach should open nothing and be refused as such");

                pod.setPos(pod.position().add(0.1, 0, 0));
                pod.positionRider(player);
                opensFacing(helper, pilot, pump, TerminalTypes.FUEL_PUMP);
            });
            // The mock reads what the server sent it on its own tick.
            helper.startSequence()
                    .thenWaitUntil(() -> expect(
                            helper,
                            saw(pilot, TerminalRefusal.NONE_IN_REACH.translationKey()),
                            "the pilot should be told there is no terminal in reach, got %s",
                            pilot.actionBarMessages()))
                    .thenExecute(() -> discard(pod))
                    .thenSucceed();
        } catch (RuntimeException thrown) {
            discard(pod);
            throw thrown;
        }
    }

    /**
     * A pilot on no charter is refused from the seat as they are on foot. Caught: a seat path that skips the charter
     * check.
     */
    @GameTest(environment = "deepcharter-test:seated_terminals_outsider")
    public void aPilotOnNoCharterIsRefusedAsOnFoot(GameTestHelper helper) {
        openGround(helper);
        BlockPos pump = terminal(helper, TerminalTypes.FUEL_PUMP, new BlockPos(10, 1, 6));
        MockPlayer pilot = outsider(helper, "outsider");
        PodEntity pod = pod(helper);
        try {
            withRepaired(helper, 1, () -> {
                ServerPlayer player = pilot.player();
                Vec3 centre = Vec3.atCenterOf(pump);
                pilot.teleportTo(helper.getLevel(), centre.add(-2, -player.getEyeHeight(), 0), 0, 0);
                Optional<TerminalRefusal> onFoot = Terminals.open(player, pump);
                expect(
                        helper,
                        onFoot.equals(Optional.of(TerminalRefusal.NOT_ON_A_CHARTER)),
                        "on foot an outsider is refused as not on a charter, got %s",
                        onFoot);

                board(helper, pilot, pod);
                face(player, pump);
                pilot.send(SeatedTerminalPayload.INSTANCE);
                expect(
                        helper,
                        openedBy(player).isEmpty(),
                        "an outsider's seat request should open nothing, opened %s",
                        openedBy(player));
                expect(
                        helper,
                        refusedFor(player)
                                .equals(List.of(
                                        new Refused(player.getUUID(), TerminalRefusal.NOT_ON_A_CHARTER),
                                        new Refused(player.getUUID(), TerminalRefusal.NOT_ON_A_CHARTER))),
                        "from the seat an outsider gets the on-foot refusal, got %s",
                        refusedFor(player));
            });
            helper.succeed();
        } finally {
            discard(pod);
        }
    }

    /**
     * The service apron of #1435: the pump, the processor and the repair station face one parking spot, each within
     * reach of the Mole's box and within the parking radius of the pod. From that one spot all three open, each when
     * faced, and each counts the pod as parked. Caught: a pick by distance alone (only the nearest would ever open).
     */
    @GameTest(environment = "deepcharter-test:seated_terminals_apron")
    public void theApronOpensAllThreeServiceTerminalsFromOneSpot(GameTestHelper helper) {
        openGround(helper);
        BlockPos pump = terminal(helper, TerminalTypes.FUEL_PUMP, new BlockPos(11, 1, 6));
        BlockPos processor = terminal(helper, TerminalTypes.ORE_PROCESSOR, new BlockPos(6, 1, 2));
        BlockPos station = terminal(helper, TerminalTypes.REPAIR_STATION, new BlockPos(2, 1, 6));
        MockPlayer pilot = charterMember(helper, "apron");
        PodEntity pod = pod(helper);
        try {
            withRepaired(helper, COLONY.size(), () -> {
                board(helper, pilot, pod);
                for (BlockPos pos : List.of(pump, processor, station)) {
                    expect(
                            helper,
                            Terminals.isParkedAt(pod, pos),
                            "the pod on the apron should count as parked at %s",
                            pos);
                }
                opensFacing(helper, pilot, pump, TerminalTypes.FUEL_PUMP);
                opensFacing(helper, pilot, processor, TerminalTypes.ORE_PROCESSOR);
                opensFacing(helper, pilot, station, TerminalTypes.REPAIR_STATION);
            });
            helper.succeed();
        } finally {
            discard(pod);
        }
    }

    /**
     * From the seat, past on-foot reach, the pilot fills the tank at the pump and sells the cargo at the processor,
     * through the action payload a screen sends. Caught: actions that still measure reach from the eyes.
     */
    @GameTest(environment = "deepcharter-test:seated_terminals_actions")
    public void aSeatedPilotRefuelsAndSells(GameTestHelper helper) {
        openGround(helper);
        BlockPos pump = terminal(helper, TerminalTypes.FUEL_PUMP, new BlockPos(12, 1, 7));
        BlockPos processor = terminal(helper, TerminalTypes.ORE_PROCESSOR, new BlockPos(0, 1, 5));
        MockPlayer pilot = charterMember(helper, "trader");
        PodEntity pod = pod(helper);
        MinecraftServer server = helper.getLevel().getServer();
        try {
            withRepaired(helper, 2, () -> {
                ServerPlayer player = pilot.player();
                CharterId charter = Charters.charterOfOrThrow(server, player.getUUID())
                        .orElseThrow()
                        .id();
                expect(helper, Charters.deposit(server, charter, 50).isEmpty(), "the deposit should succeed");
                PodComponents.register(pod, charter);
                pod.setFuel(0f);
                pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.IRONIUM));
                board(helper, pilot, pod);
                for (BlockPos pos : List.of(pump, processor)) {
                    expect(
                            helper,
                            eyeDistance(player, pos) > REACH,
                            "%s must stand past on-foot reach of the pilot's eyes, is %s",
                            pos,
                            eyeDistance(player, pos));
                }

                pilot.send(new TerminalActionPayload(pump, FuelPump.FILL, new CompoundTag()));
                expect(helper, pod.fuel() > 0f, "filling up from the seat should fuel the pod");
                long afterFuel = balance(server, charter);
                expect(helper, afterFuel < 50, "the fuel should be paid for, the balance is %s", afterFuel);

                pilot.send(new TerminalActionPayload(processor, OreProcessor.SELL_CARGO, new CompoundTag()));
                expect(
                        helper,
                        balance(server, charter) == afterFuel + OreType.IRONIUM.value()
                                && pod.cargo().entries().isEmpty(),
                        "selling the cargo from the seat should pay %s and empty it, balance %s, cargo %s",
                        OreType.IRONIUM.value(),
                        balance(server, charter),
                        pod.cargo().entries());
            });
            helper.succeed();
        } finally {
            discard(pod);
        }
    }

    private static long balance(MinecraftServer server, CharterId charter) {
        return Charters.findOrThrow(server, charter).orElseThrow().account();
    }

    /**
     * On foot, reach is still measured from the eyes, exactly {@link TerminalTuning#maxDistance()} in and a hair past
     * it out, even with a pod standing between the player and the terminal. Caught: a reach measured from a nearby
     * pod, or from the eyes of a seated player only.
     */
    @GameTest
    public void onFootReachIsUnchanged(GameTestHelper helper) {
        floor(helper);
        BlockPos pump = terminal(helper, TerminalTypes.FUEL_PUMP, new BlockPos(12, 1, 6));
        MockPlayer walker = charterMember(helper, "walker");
        PodEntity pod = pod(helper);
        try {
            withRepaired(helper, 1, () -> {
                ServerPlayer player = walker.player();
                Vec3 centre = Vec3.atCenterOf(pump);
                walker.teleportTo(helper.getLevel(), centre.add(-REACH, -player.getEyeHeight(), 0), 0, 0);
                Optional<TerminalRefusal> atReach = Terminals.open(player, pump);
                expect(helper, atReach.isEmpty(), "exactly at reach on foot should open, got %s", atReach);
                walker.teleportTo(helper.getLevel(), centre.add(-REACH - 0.01, -player.getEyeHeight(), 0), 0, 0);
                Optional<TerminalRefusal> past = Terminals.open(player, pump);
                expect(
                        helper,
                        past.equals(Optional.of(TerminalRefusal.TOO_FAR)),
                        "a hair past reach on foot, beside a pod nearer the pump, should be too far, got %s",
                        past);
            });
            helper.succeed();
        } finally {
            discard(pod);
        }
    }

    /**
     * The navigator in a Prospector's second seat opens a terminal from the seat as the pilot does. Caught: a seat
     * request served only for the pod's controlling passenger.
     */
    @GameTest(environment = "deepcharter-test:seated_terminals_navigator")
    public void aProspectorNavigatorOpensATerminal(GameTestHelper helper) {
        openGround(helper);
        BlockPos pump = terminal(helper, TerminalTypes.FUEL_PUMP, new BlockPos(12, 1, 6));
        MockPlayer pilot = charterMember(helper, "prospector-pilot");
        MockPlayer navigator = charterMember(helper, "navigator");
        PodEntity pod = helper.spawn(PodRegistry.PROSPECTOR, PARKED);
        try {
            withRepaired(helper, 1, () -> {
                board(helper, pilot, pod);
                board(helper, navigator, pod);
                expect(
                        helper,
                        pod.getControllingPassenger() == pilot.player(),
                        "the first to board should pilot the Prospector");
                opensFacing(helper, navigator, pump, TerminalTypes.FUEL_PUMP);
            });
            helper.succeed();
        } finally {
            discard(pod);
        }
    }

    /**
     * A seat request from a player on foot, or riding something that is not a pod, is ignored: no terminal, no
     * refusal, no line, though a terminal stands within on-foot reach. Caught: a payload trusted without a seat.
     */
    @GameTest
    public void aSeatRequestNotFromAPodIsIgnored(GameTestHelper helper) {
        floor(helper);
        BlockPos pump = terminal(helper, TerminalTypes.FUEL_PUMP, new BlockPos(8, 1, 6));
        MockPlayer walker = charterMember(helper, "forger");
        Entity boat = helper.spawn(EntityTypes.OAK_BOAT, PARKED);
        try {
            withRepaired(helper, 1, () -> {
                ServerPlayer player = walker.player();
                walker.teleportTo(helper.getLevel(), Vec3.atCenterOf(pump).add(-2, -player.getEyeHeight(), 0), 0, 0);
                face(player, pump);
                walker.send(SeatedTerminalPayload.INSTANCE);
                if (!player.startRiding(boat, true, false)) {
                    throw failure(helper, "the player should board the boat");
                }
                face(player, pump);
                walker.send(SeatedTerminalPayload.INSTANCE);
                expect(
                        helper,
                        openedBy(player).isEmpty() && refusedFor(player).isEmpty(),
                        "a seat request not from a pod should be ignored, opened %s, refused %s",
                        openedBy(player),
                        refusedFor(player));
                Optional<TerminalRefusal> byHand = Terminals.open(player, pump);
                expect(helper, byHand.isEmpty(), "the pump itself should open by hand from there, got %s", byHand);
                // The control: a refusal the mock does read, sent after the ignored requests.
                Optional<TerminalRefusal> far = Terminals.open(player, pump.east(TOO_FAR_BLOCKS));
                expect(
                        helper,
                        far.equals(Optional.of(TerminalRefusal.TOO_FAR)),
                        "the control should be too far, got %s",
                        far);
            });
            // The mock reads what the server sent it on its own tick: once the control's line is in, any line of the
            // ignored requests would be before it.
            helper.startSequence()
                    .thenWaitUntil(() -> expect(
                            helper,
                            saw(walker, TerminalRefusal.TOO_FAR.translationKey()),
                            "the control's line should arrive, got %s",
                            walker.actionBarMessages()))
                    .thenExecute(() -> expect(
                            helper,
                            walker.actionBarMessages().size() == 1,
                            "an ignored request says nothing, got %s",
                            walker.actionBarMessages()))
                    .thenExecute(() -> discard(boat))
                    .thenSucceed();
        } catch (RuntimeException thrown) {
            discard(boat);
            throw thrown;
        }
    }

    /**
     * The client asks for a terminal only on a main-hand use of the pod it sits in, with both hands empty or only the
     * handbook in the main hand: a held item in either hand, the off hand's own use, a crouch, another pod, or a
     * player on foot keep the use they had. Caught: a check that drops any one of those.
     */
    @GameTest
    public void onlyAnEmptyHandUseOfYourOwnPodAsksForATerminal(GameTestHelper helper) {
        floor(helper);
        MockPlayer pilot = charterMember(helper, "hands");
        PodEntity pod = pod(helper);
        PodEntity other = helper.spawn(PodRegistry.POD, PARKED.add(4, 0, 0));
        try {
            ServerPlayer player = pilot.player();
            expect(
                    helper,
                    !SeatedTerminals.wantsTerminal(player, InteractionHand.MAIN_HAND, pod),
                    "on foot, a use of a pod boards it");
            board(helper, pilot, pod);
            expect(
                    helper,
                    SeatedTerminals.wantsTerminal(player, InteractionHand.MAIN_HAND, pod),
                    "seated, an empty-hand use of the pod asks for a terminal");
            // A mock's charter kit lands before the handbook the join tick gives, so the test hands it over itself.
            player.getInventory().setItem(HANDBOOK_SLOT, new ItemStack(HandbookRegistry.HANDBOOK));
            player.getInventory().setSelectedSlot(HANDBOOK_SLOT);
            expect(
                    helper,
                    SeatedTerminals.wantsTerminal(player, InteractionHand.MAIN_HAND, pod),
                    "the handbook in the main hand counts as empty: it has no use on a pod");
            player.getInventory().setSelectedSlot(EMPTY_SLOT);
            player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.COAL));
            expect(
                    helper,
                    !SeatedTerminals.wantsTerminal(player, InteractionHand.MAIN_HAND, pod),
                    "fuel in the off hand keeps its use on the pod");
            player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            expect(
                    helper,
                    !SeatedTerminals.wantsTerminal(player, InteractionHand.OFF_HAND, pod),
                    "the off hand's use keeps its own meaning");
            expect(
                    helper,
                    !SeatedTerminals.wantsTerminal(player, InteractionHand.MAIN_HAND, other),
                    "a use of another pod keeps its own meaning");
            player.setShiftKeyDown(true);
            expect(
                    helper,
                    !SeatedTerminals.wantsTerminal(player, InteractionHand.MAIN_HAND, pod),
                    "a crouched use opens the cargo hold, as before");
            player.setShiftKeyDown(false);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COAL));
            expect(
                    helper,
                    !SeatedTerminals.wantsTerminal(player, InteractionHand.MAIN_HAND, pod),
                    "a held item keeps its use on the pod");
            helper.succeed();
        } finally {
            discard(pod, other);
        }
    }

    /**
     * From the seat, with the main hand empty and fuel in the off hand, the use refuels the pod as before: the
     * client does not ask for a terminal, so the off hand's use reaches the pod. Caught: a predicate that ignores
     * the off hand.
     */
    @GameTest
    public void offHandFuelStillRefuelsFromTheSeat(GameTestHelper helper) {
        floor(helper);
        MockPlayer pilot = charterMember(helper, "stoker");
        PodEntity pod = pod(helper);
        try {
            ServerPlayer player = pilot.player();
            board(helper, pilot, pod);
            pod.setFuel(0f);
            player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.COAL));
            expect(
                    helper,
                    !SeatedTerminals.wantsTerminal(player, InteractionHand.MAIN_HAND, pod),
                    "with fuel in the off hand the client should not ask for a terminal");
            UseEntity.on(player, helper.getLevel(), InteractionHand.MAIN_HAND, pod);
            UseEntity.on(player, helper.getLevel(), InteractionHand.OFF_HAND, pod);
            expect(helper, pod.fuel() > 0f, "the off hand's fuel should refuel the pod from the seat");
            expect(
                    helper,
                    player.getOffhandItem().isEmpty(),
                    "the coal should be used up, the off hand holds %s",
                    player.getOffhandItem());
            helper.succeed();
        } finally {
            discard(pod);
        }
    }

    /**
     * A Prospector whose box corner is within reach of the pump, but whose position is past the parking radius, does
     * not reach it: the pump would not serve it as parked. A nudge nearer makes it both parked and in reach. Caught:
     * a seated reach measured from the box alone.
     */
    @GameTest(environment = "deepcharter-test:seated_terminals_corner")
    public void aProspectorCornerPastParkingDoesNotReach(GameTestHelper helper) {
        openGround(helper);
        BlockPos pump = terminal(helper, TerminalTypes.FUEL_PUMP, new BlockPos(12, 1, 12));
        MockPlayer pilot = charterMember(helper, "corner");
        // 5.67 blocks short of the pump's middle on both axes: the box corner is 5.97 from it, the position 8.03.
        PodEntity pod = helper.spawn(PodRegistry.PROSPECTOR, new Vec3(12.5 - CORNER, 1, 12.5 - CORNER));
        try {
            withRepaired(helper, 1, () -> {
                board(helper, pilot, pod);
                ServerPlayer player = pilot.player();
                double box = Math.sqrt(pod.getBoundingBox().distanceToSqr(Vec3.atCenterOf(pump)));
                expect(helper, box <= REACH, "the Prospector's corner should be within reach of the pump, is %s", box);
                expect(
                        helper,
                        !Terminals.isParkedAt(pod, pump),
                        "the Prospector should not count as parked at the pump");
                expect(helper, !Terminals.inReach(player, pump), "a pod not parked at the pump should not reach it");
                face(player, pump);
                pilot.send(SeatedTerminalPayload.INSTANCE);
                refusedNoneInReach(helper, player, "the pump should be out of reach");

                pod.setPos(pod.position().add(0.1, 0, 0.1));
                pod.positionRider(player);
                expect(helper, Terminals.isParkedAt(pod, pump), "nudged nearer, the Prospector is parked at the pump");
                opensFacing(helper, pilot, pump, TerminalTypes.FUEL_PUMP);
            });
            helper.succeed();
        } finally {
            discard(pod);
        }
    }

    /**
     * A terminal in reach behind a wall is not picked from the seat: a pod under the colony cannot trade through the
     * rock. Take the wall away and it opens. Caught: a pick with no line of sight.
     */
    @GameTest(environment = "deepcharter-test:seated_terminals_wall")
    public void aTerminalBehindAWallIsNotOpened(GameTestHelper helper) {
        openGround(helper);
        BlockPos pump = terminal(helper, TerminalTypes.FUEL_PUMP, new BlockPos(11, 1, 6));
        List<BlockPos> wall = new ArrayList<>();
        for (int y = 1; y <= 5; y++) {
            for (int z = 3; z <= 10; z++) {
                wall.add(new BlockPos(9, y, z));
            }
        }
        wall.forEach(pos -> helper.setBlock(pos, Blocks.STONE));
        MockPlayer pilot = charterMember(helper, "walled");
        PodEntity pod = pod(helper);
        try {
            withRepaired(helper, 1, () -> {
                board(helper, pilot, pod);
                ServerPlayer player = pilot.player();
                expect(helper, Terminals.inReach(player, pump), "the walled pump should be within reach of the pod");
                face(player, pump);
                pilot.send(SeatedTerminalPayload.INSTANCE);
                refusedNoneInReach(helper, player, "a pump behind a wall should not open from the seat");

                wall.forEach(pos -> helper.setBlock(pos, Blocks.AIR));
                opensFacing(helper, pilot, pump, TerminalTypes.FUEL_PUMP);
            });
            helper.succeed();
        } finally {
            discard(pod);
        }
    }
}
