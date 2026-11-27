package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.menu.MenuRefusalPayload;
import io.github.pkeppeler.deepcharter.menu.MirrorContainer;
import io.github.pkeppeler.deepcharter.menu.SlotRule;
import io.github.pkeppeler.deepcharter.menu.SlottedMenu;
import io.github.pkeppeler.deepcharter.terminal.RepairMenu;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalEvents;
import io.github.pkeppeler.deepcharter.terminal.TerminalRefusal;
import io.github.pkeppeler.deepcharter.terminal.TerminalTuning;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.Terminals;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.SettlingRooms;
import io.github.pkeppeler.deepcharter.test.support.SlotClicks;
import io.github.pkeppeler.deepcharter.test.support.WorldData;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ContainerListener;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Server GameTests for #1265: an unrepaired terminal opens a menu of part slots. A part goes in by shift-click or by a
 * slot click or a drag, a wrong item is refused with a reason the client is sent, a part in a slot never comes out,
 * closing keeps what went in with nothing duped or lost, the last part repairs the terminal, the right-click shortcut
 * still works, the client learns when the terminal unlocks, and walking away or losing the terminal closes the menu.
 *
 * <p>Each test swaps in a fresh {@link RepairState} and does all its work inside its first tick, as in
 * {@link TerminalFrameworkTest}. The clicks are the server menu's own {@code clicked}, which the click packet runs.
 */
public class TerminalPartSlotsTest {
    private static final TerminalType PUMP = TerminalTypes.FUEL_PUMP;
    private static final Item MOTOR = PUMP.parts().get(0);
    private static final Item VALVE = PUMP.parts().get(1);

    private static final List<String> REPAIRED_LOG = new ArrayList<>();
    private static final List<String> OPENED_LOG = new ArrayList<>();

    static {
        TerminalEvents.REPAIRED.register((server, type, charter, player) ->
                REPAIRED_LOG.add(type.id() + "/" + player.getGameProfile().name()));
        TerminalEvents.OPENED.register((server, type, player, pos) ->
                OPENED_LOG.add(type.id() + "/" + player.getGameProfile().name()));
    }

    private static void withFreshState(GameTestHelper helper, Consumer<RepairState> body) {
        RepairState fresh = new RepairState();
        WorldData.with(helper.getLevel().getServer(), RepairState.TYPE, fresh, () -> body.accept(fresh));
    }

    private static BlockPos place(GameTestHelper helper, TerminalType type) {
        BlockPos relative = new BlockPos(0, 1, 0);
        helper.setBlock(relative, type.block().defaultBlockState());
        return helper.absolutePos(relative);
    }

    /** A mock player in survival, two blocks from the terminal, on a charter of their own unless {@code outsider}. */
    private static MockPlayer standBy(GameTestHelper helper, String name, BlockPos pos, boolean outsider) {
        MockPlayer mock = MockPlayers.join(helper, name);
        mock.player().setGameMode(GameType.SURVIVAL);
        if (!outsider) {
            MinecraftServer server = helper.getLevel().getServer();
            String charter = name + " " + UUID.randomUUID().toString().substring(0, 8);
            expect(
                    helper,
                    Charters.found(server, mock.player().getUUID(), charter).isEmpty(),
                    "founding a charter for %s should succeed",
                    name);
        }
        Vec3 centre = Vec3.atCenterOf(pos);
        mock.teleportTo(
                helper.getLevel(),
                new Vec3(centre.x + 2, centre.y - mock.player().getEyeHeight(), centre.z),
                0,
                0);
        return mock;
    }

    private static RepairMenu open(GameTestHelper helper, ServerPlayer player, BlockPos pump) {
        Optional<TerminalRefusal> refusal = Terminals.open(player, pump);
        expect(helper, refusal.isEmpty(), "opening the offline pump should succeed, was refused: %s", refusal);
        if (!(player.containerMenu instanceof RepairMenu menu)) {
            throw failure(helper, "an offline terminal should open its repair menu, opened %s", player.containerMenu);
        }
        return menu;
    }

    private static int count(ServerPlayer player, Item item) {
        return SettlingRooms.count(player.getInventory(), item);
    }

    /** What the menu's part slot {@code index} shows once the server has broadcast. */
    private static ItemStack shown(RepairMenu menu, int index) {
        menu.broadcastChanges();
        return menu.ruleSlots().get(index).getItem();
    }

    private static void click(RepairMenu menu, ServerPlayer player, int index, ContainerInput input) {
        menu.clicked(index, 0, input, player);
    }

    @GameTest
    public void shiftClickPutsAPartIntoItsSlot(GameTestHelper helper) {
        withFreshState(helper, state -> {
            BlockPos pump = place(helper, PUMP);
            ServerPlayer player = standBy(helper, "ShiftClicker", pump, false).player();
            player.getInventory().add(new ItemStack(VALVE, 3));
            RepairMenu menu = open(helper, player, pump);

            click(menu, player, SlotClicks.inventorySlot(menu, VALVE).index, ContainerInput.QUICK_MOVE);

            expect(
                    helper,
                    state.inserted(PUMP).equals(List.of(VALVE)),
                    "shift-click should put the valve in, inserted %s",
                    state.inserted(PUMP));
            expect(helper, count(player, VALVE) == 2, "one valve goes in, two stay, held %s", count(player, VALVE));
            expect(helper, shown(menu, 1).is(VALVE), "the valve's slot shows it, got %s", shown(menu, 1));
            expect(helper, shown(menu, 0).isEmpty(), "the motor's slot stays empty, got %s", shown(menu, 0));
            expect(helper, menu.refusal().isEmpty(), "a part that went in is no refusal: %s", menu.refusal());
        });
        helper.succeed();
    }

    @GameTest
    public void aSlotClickPutsAPartIntoItsSlot(GameTestHelper helper) {
        withFreshState(helper, state -> {
            BlockPos pump = place(helper, PUMP);
            ServerPlayer player = standBy(helper, "Dragger", pump, false).player();
            player.getInventory().add(new ItemStack(MOTOR, 2));
            RepairMenu menu = open(helper, player, pump);

            click(menu, player, SlotClicks.inventorySlot(menu, MOTOR).index, ContainerInput.PICKUP);
            expect(helper, menu.getCarried().is(MOTOR), "picking up the motors, carried %s", menu.getCarried());
            click(menu, player, menu.ruleSlots().get(0).index, ContainerInput.PICKUP);

            expect(
                    helper,
                    state.inserted(PUMP).equals(List.of(MOTOR)),
                    "dropping a motor on its slot should put it in, inserted %s",
                    state.inserted(PUMP));
            expect(
                    helper,
                    menu.getCarried().is(MOTOR) && menu.getCarried().getCount() == 1,
                    "the slot takes one motor and the cursor keeps the other, carried %s",
                    menu.getCarried());
            expect(helper, shown(menu, 0).is(MOTOR), "the motor's slot shows it, got %s", shown(menu, 0));
        });
        helper.succeed();
    }

    @GameTest
    public void aWrongItemIsRefusedWithAReason(GameTestHelper helper) {
        withFreshState(helper, state -> {
            BlockPos pump = place(helper, PUMP);
            ServerPlayer player = standBy(helper, "Bungler", pump, false).player();
            player.getInventory().add(new ItemStack(Items.STICK));
            player.getInventory().add(new ItemStack(VALVE));
            RepairMenu menu = open(helper, player, pump);
            int motorSlot = menu.ruleSlots().get(0).index;

            click(menu, player, SlotClicks.inventorySlot(menu, Items.STICK).index, ContainerInput.PICKUP);
            click(menu, player, motorSlot, ContainerInput.PICKUP);
            expectRefusal(helper, menu, TerminalRefusal.NOT_A_PART, "a stick on the motor's slot");
            expect(helper, menu.getCarried().is(Items.STICK), "the refused stick stays on the cursor");
            putBack(menu, player);

            click(menu, player, SlotClicks.inventorySlot(menu, VALVE).index, ContainerInput.PICKUP);
            click(menu, player, motorSlot, ContainerInput.PICKUP);
            expectRefusal(helper, menu, TerminalRefusal.WRONG_SLOT, "the valve on the motor's slot");
            expect(helper, menu.getCarried().is(VALVE), "the refused valve stays on the cursor");
            putBack(menu, player);

            click(menu, player, SlotClicks.inventorySlot(menu, Items.STICK).index, ContainerInput.QUICK_MOVE);
            expectRefusal(helper, menu, TerminalRefusal.NOT_A_PART, "a shift-clicked stick");
            expect(helper, count(player, Items.STICK) == 1, "the shift-clicked stick stays in the inventory");
            expect(
                    helper,
                    state.inserted(PUMP).isEmpty()
                            && shown(menu, 0).isEmpty()
                            && shown(menu, 1).isEmpty(),
                    "nothing went in, inserted %s",
                    state.inserted(PUMP));
        });
        helper.succeed();
    }

    /** Puts what the cursor holds back in the inventory. */
    private static void putBack(RepairMenu menu, ServerPlayer player) {
        player.getInventory().add(menu.getCarried());
        menu.setCarried(ItemStack.EMPTY);
    }

    private static void expectRefusal(GameTestHelper helper, RepairMenu menu, TerminalRefusal expected, String what) {
        Optional<Component> want = Optional.of(expected.message());
        expect(helper, menu.refusal().equals(want), "%s should be refused as %s, got %s", what, want, menu.refusal());
    }

    @GameTest
    public void aPartBeforeItsTerminalIsUnlockedIsRefused(GameTestHelper helper) {
        withFreshState(helper, state -> {
            BlockPos processor = place(helper, TerminalTypes.ORE_PROCESSOR);
            ServerPlayer player = standBy(helper, "Hasty", processor, false).player();
            Item coil = TerminalTypes.ORE_PROCESSOR.parts().getFirst();
            player.getInventory().add(new ItemStack(coil));
            Terminals.open(player, processor);
            if (!(player.containerMenu instanceof RepairMenu menu)) {
                throw failure(helper, "a locked terminal still opens its repair menu, got %s", player.containerMenu);
            }
            expect(helper, !menu.unlocked(), "the processor is locked while the pump is broken");
            click(menu, player, SlotClicks.inventorySlot(menu, coil).index, ContainerInput.QUICK_MOVE);
            expectRefusal(helper, menu, TerminalRefusal.PREREQUISITE_UNREPAIRED, "a part of a locked terminal");
            expect(helper, count(player, coil) == 1, "the refused part stays with the player");
        });
        helper.succeed();
    }

    @GameTest
    public void aPartInASlotNeverComesOut(GameTestHelper helper) {
        withFreshState(helper, state -> {
            BlockPos pump = place(helper, PUMP);
            ServerPlayer player = standBy(helper, "Taker", pump, false).player();
            player.getInventory().add(new ItemStack(MOTOR));
            RepairMenu menu = open(helper, player, pump);
            click(menu, player, SlotClicks.inventorySlot(menu, MOTOR).index, ContainerInput.QUICK_MOVE);
            expect(helper, shown(menu, 0).is(MOTOR), "the motor went in");
            int motorSlot = menu.ruleSlots().get(0).index;

            click(menu, player, motorSlot, ContainerInput.PICKUP);
            click(menu, player, motorSlot, ContainerInput.QUICK_MOVE);
            click(menu, player, motorSlot, ContainerInput.THROW);
            click(menu, player, motorSlot, ContainerInput.SWAP);
            click(menu, player, motorSlot, ContainerInput.PICKUP_ALL);
            expect(
                    helper,
                    shown(menu, 0).is(MOTOR) && menu.getCarried().isEmpty() && count(player, MOTOR) == 0,
                    "no click takes the motor back: slot %s, carried %s, held %s",
                    shown(menu, 0),
                    menu.getCarried(),
                    count(player, MOTOR));
            player.closeContainer();

            ServerPlayer stranger = standBy(helper, "Stranger", pump, true).player();
            Optional<TerminalRefusal> refused = Terminals.open(stranger, pump);
            expect(
                    helper,
                    refused.equals(Optional.of(TerminalRefusal.NOT_ON_A_CHARTER)),
                    "a player on no charter cannot open the repair menu, got %s",
                    refused);
            expect(
                    helper,
                    stranger.containerMenu == stranger.inventoryMenu,
                    "the stranger has no menu open, has %s",
                    stranger.containerMenu);

            ServerPlayer rival = standBy(helper, "Rival", pump, false).player();
            RepairMenu theirs = open(helper, rival, pump);
            expect(helper, shown(theirs, 0).is(MOTOR), "another charter sees the motor in its slot");
            click(theirs, rival, theirs.ruleSlots().get(0).index, ContainerInput.PICKUP);
            click(theirs, rival, theirs.ruleSlots().get(0).index, ContainerInput.QUICK_MOVE);
            expect(
                    helper,
                    theirs.getCarried().isEmpty()
                            && count(rival, MOTOR) == 0
                            && shown(theirs, 0).is(MOTOR),
                    "another charter cannot take the motor out either");
            expect(helper, state.inserted(PUMP).equals(List.of(MOTOR)), "the motor is still in");
        });
        helper.succeed();
    }

    @GameTest
    public void closingMidRepairKeepsWhatWentInAndLosesNothing(GameTestHelper helper) {
        withFreshState(helper, state -> {
            BlockPos pump = place(helper, PUMP);
            ServerPlayer player = standBy(helper, "Quitter", pump, false).player();
            player.getInventory().add(new ItemStack(MOTOR, 2));
            player.getInventory().add(new ItemStack(VALVE));
            RepairMenu menu = open(helper, player, pump);
            click(menu, player, SlotClicks.inventorySlot(menu, MOTOR).index, ContainerInput.PICKUP);
            click(menu, player, menu.ruleSlots().get(0).index, ContainerInput.PICKUP);
            click(menu, player, SlotClicks.inventorySlot(menu, VALVE).index, ContainerInput.PICKUP);
            // The spare motor on the cursor swapped places with the valve, which rides the cursor when the
            // screen closes.
            expect(helper, menu.getCarried().is(VALVE), "the valve rides the cursor, carried %s", menu.getCarried());

            player.closeContainer();

            expect(helper, player.containerMenu == player.inventoryMenu, "the menu is closed");
            expect(
                    helper,
                    state.inserted(PUMP).equals(List.of(MOTOR)) && !state.repaired(PUMP),
                    "the motor stays in after the close, inserted %s",
                    state.inserted(PUMP));
            expect(
                    helper,
                    count(player, MOTOR) == 1 && count(player, VALVE) == 1,
                    "the spare motor and the valve are back in the inventory: motors %s, valves %s",
                    count(player, MOTOR),
                    count(player, VALVE));

            RepairMenu again = open(helper, player, pump);
            expect(
                    helper,
                    shown(again, 0).is(MOTOR) && shown(again, 1).isEmpty(),
                    "reopened, the motor's slot is full and the valve's empty: %s, %s",
                    shown(again, 0),
                    shown(again, 1));
        });
        helper.succeed();
    }

    @GameTest
    public void theLastPartBringsTheTerminalOnline(GameTestHelper helper) {
        withFreshState(helper, state -> {
            BlockPos pump = place(helper, PUMP);
            ServerPlayer player = standBy(helper, "Finisher", pump, false).player();
            player.getInventory().add(new ItemStack(MOTOR));
            player.getInventory().add(new ItemStack(VALVE));
            REPAIRED_LOG.clear();
            OPENED_LOG.clear();
            RepairMenu menu = open(helper, player, pump);

            click(menu, player, SlotClicks.inventorySlot(menu, MOTOR).index, ContainerInput.QUICK_MOVE);
            expect(helper, REPAIRED_LOG.isEmpty() && menu.stillValid(player), "one part of two repairs nothing");
            click(menu, player, SlotClicks.inventorySlot(menu, VALVE).index, ContainerInput.QUICK_MOVE);

            String who = PUMP.id() + "/Finisher";
            expect(helper, state.repaired(PUMP), "both parts in repair the pump");
            expect(helper, REPAIRED_LOG.equals(List.of(who)), "REPAIRED fires once, log %s", REPAIRED_LOG);
            expect(helper, !menu.stillValid(player), "the repair menu of a repaired terminal is done");
            // What the player's next tick does with a menu that is no longer valid.
            player.closeContainer();
            expect(
                    helper,
                    OPENED_LOG.equals(List.of(who, who)) && player.containerMenu == player.inventoryMenu,
                    "closing the finished menu opens the terminal online, opened %s, menu %s",
                    OPENED_LOG,
                    player.containerMenu);
        });
        helper.succeed();
    }

    @GameTest
    public void usingAPartOnTheTerminalPutsItIn(GameTestHelper helper) {
        withFreshState(helper, state -> {
            BlockPos pump = place(helper, PUMP);
            ServerPlayer player = standBy(helper, "Shortcut", pump, false).player();
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(MOTOR, 2));
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pump), Direction.UP, pump, false);

            helper.getLevel()
                    .getBlockState(pump)
                    .useItemOn(player.getMainHandItem(), helper.getLevel(), player, InteractionHand.MAIN_HAND, hit);

            expect(
                    helper,
                    state.inserted(PUMP).equals(List.of(MOTOR)) && count(player, MOTOR) == 1,
                    "using a motor on the pump puts one in, inserted %s, held %s",
                    state.inserted(PUMP),
                    count(player, MOTOR));
            expect(
                    helper,
                    player.containerMenu instanceof RepairMenu,
                    "and opens the repair menu, opened %s",
                    player.containerMenu);
            if (player.containerMenu instanceof SlottedMenu menu) {
                menu.broadcastChanges();
                expect(helper, menu.ruleSlots().get(0).getItem().is(MOTOR), "the menu shows the motor in");
            }
        });
        helper.succeed();
    }

    @GameTest
    public void aPrerequisiteRepairedWhileOpenUnlocksTheClientsSlots(GameTestHelper helper) {
        withFreshState(helper, state -> {
            BlockPos processor = place(helper, TerminalTypes.ORE_PROCESSOR);
            ServerPlayer player = standBy(helper, "Patient", processor, false).player();
            Item coil = TerminalTypes.ORE_PROCESSOR.parts().getFirst();
            Terminals.open(player, processor);
            if (!(player.containerMenu instanceof RepairMenu server)) {
                throw failure(helper, "a locked terminal still opens its repair menu, got %s", player.containerMenu);
            }
            RepairMenu client = new RepairMenu(
                    server.containerId,
                    player.getInventory(),
                    new RepairMenu.Opening(processor, TerminalTypes.ORE_PROCESSOR.id(), false));
            server.addSlotListener(new ContainerListener() {
                @Override
                public void slotChanged(AbstractContainerMenu menu, int slot, ItemStack stack) {}

                @Override
                public void dataChanged(AbstractContainerMenu menu, int id, int value) {
                    client.setData(id, value);
                }
            });
            expect(
                    helper,
                    !client.unlocked() && !client.ruleSlots().getFirst().mayPlace(new ItemStack(coil)),
                    "the client's menu refuses the coil while the pump is broken");

            // Someone else repairs the pump while the menu is open.
            PUMP.parts().forEach(part -> state.insert(PUMP, part));
            server.broadcastChanges();

            expect(helper, server.unlocked() && client.unlocked(), "the server tells the client the pump is repaired");
            expect(
                    helper,
                    client.ruleSlots()
                            .getFirst()
                            .rule()
                            .refusal(new ItemStack(coil))
                            .isEmpty(),
                    "the client's menu now predicts the coil goes in, refusal %s",
                    client.ruleSlots().getFirst().rule().refusal(new ItemStack(coil)));
        });
        helper.succeed();
    }

    @GameTest
    public void aRefusalIsSentToTheClientsMenu(GameTestHelper helper) {
        int[] container = new int[1];
        MockPlayer[] mock = new MockPlayer[1];
        withFreshState(helper, state -> {
            BlockPos pump = place(helper, PUMP);
            mock[0] = standBy(helper, "Told", pump, false);
            mock[0].announceChannel(MenuRefusalPayload.TYPE);
            ServerPlayer player = mock[0].player();
            player.getInventory().add(new ItemStack(Items.STICK));
            RepairMenu menu = open(helper, player, pump);
            container[0] = menu.containerId;
            click(menu, player, SlotClicks.inventorySlot(menu, Items.STICK).index, ContainerInput.QUICK_MOVE);
            expectRefusal(helper, menu, TerminalRefusal.NOT_A_PART, "a shift-clicked stick");
        });
        helper.succeedWhen(() -> expect(
                helper,
                mock[0].payloadsOf(MenuRefusalPayload.class)
                        .equals(List.of(new MenuRefusalPayload(container[0], TerminalRefusal.NOT_A_PART.message()))),
                "the server sends its refusal to the client's menu, sent %s",
                mock[0].payloadsOf(MenuRefusalPayload.class)));
    }

    @GameTest
    public void aDragAcrossSlotsPutsOnePartInItsOwnSlot(GameTestHelper helper) {
        withFreshState(helper, state -> {
            BlockPos pump = place(helper, PUMP);
            ServerPlayer player = standBy(helper, "Spreader", pump, false).player();
            player.getInventory().add(new ItemStack(MOTOR, 2));
            RepairMenu menu = open(helper, player, pump);
            Slot empty = SlotClicks.inventorySlot(menu, Items.AIR);
            click(menu, player, SlotClicks.inventorySlot(menu, MOTOR).index, ContainerInput.PICKUP);
            expect(helper, menu.getCarried().getCount() == 2, "carrying both motors, carried %s", menu.getCarried());

            // A left-button drag: start, one pass over each slot, end.
            int charitable = 0;
            menu.clicked(
                    AbstractContainerMenu.SLOT_CLICKED_OUTSIDE,
                    AbstractContainerMenu.getQuickcraftMask(AbstractContainerMenu.QUICKCRAFT_HEADER_START, charitable),
                    ContainerInput.QUICK_CRAFT,
                    player);
            for (int index :
                    new int[] {menu.ruleSlots().get(0).index, menu.ruleSlots().get(1).index, empty.index}) {
                menu.clicked(
                        index,
                        AbstractContainerMenu.getQuickcraftMask(
                                AbstractContainerMenu.QUICKCRAFT_HEADER_CONTINUE, charitable),
                        ContainerInput.QUICK_CRAFT,
                        player);
            }
            menu.clicked(
                    AbstractContainerMenu.SLOT_CLICKED_OUTSIDE,
                    AbstractContainerMenu.getQuickcraftMask(AbstractContainerMenu.QUICKCRAFT_HEADER_END, charitable),
                    ContainerInput.QUICK_CRAFT,
                    player);

            expect(
                    helper,
                    state.inserted(PUMP).equals(List.of(MOTOR)),
                    "the drag puts one motor in, inserted %s",
                    state.inserted(PUMP));
            expect(helper, shown(menu, 0).is(MOTOR), "the motor's slot shows it, got %s", shown(menu, 0));
            expect(helper, shown(menu, 1).isEmpty(), "the valve's slot stays empty, got %s", shown(menu, 1));
            int held = count(player, MOTOR) + menu.getCarried().getCount();
            expect(
                    helper,
                    held + state.inserted(PUMP).size() == 2,
                    "no motor is made or lost: %s held or carried, %s in",
                    held,
                    state.inserted(PUMP).size());
        });
        helper.succeed();
    }

    @GameTest
    public void walkingAwayClosesTheMenu(GameTestHelper helper) {
        MockPlayer[] mock = new MockPlayer[1];
        withFreshState(helper, state -> {
            BlockPos pump = place(helper, PUMP);
            mock[0] = standBy(helper, "Wanderer", pump, false);
            ServerPlayer player = mock[0].player();
            RepairMenu menu = open(helper, player, pump);
            expect(helper, menu.stillValid(player), "the menu is valid beside the terminal");
            Vec3 far = Vec3.atCenterOf(pump).add(TerminalTuning.DEFAULT.maxDistance() + 4, 0, 0);
            mock[0].teleportTo(helper.getLevel(), far, 0, 0);
            expect(helper, !menu.stillValid(player), "out of reach, the menu is no longer valid");
        });
        helper.succeedWhen(() -> expect(
                helper,
                mock[0].player().containerMenu == mock[0].player().inventoryMenu,
                "the player's next tick closes it, open %s",
                mock[0].player().containerMenu));
    }

    @GameTest
    public void aRemovedTerminalClosesTheMenu(GameTestHelper helper) {
        MockPlayer[] mock = new MockPlayer[1];
        withFreshState(helper, state -> {
            BlockPos pump = place(helper, PUMP);
            mock[0] = standBy(helper, "Bystander", pump, false);
            ServerPlayer player = mock[0].player();
            RepairMenu menu = open(helper, player, pump);
            expect(helper, menu.stillValid(player), "the menu is valid while the terminal stands");
            helper.setBlock(helper.relativePos(pump), Blocks.AIR.defaultBlockState());
            expect(helper, !menu.stillValid(player), "with the terminal gone, the menu is no longer valid");
        });
        helper.succeedWhen(() -> expect(
                helper,
                mock[0].player().containerMenu == mock[0].player().inventoryMenu,
                "the player's next tick closes it, open %s",
                mock[0].player().containerMenu));
    }

    @GameTest
    public void aSlotThatMirrorsSavedStateMustLock(GameTestHelper helper) {
        boolean refused = false;
        try {
            new MirrorMenu(SlotRule.of(stack -> true, stack -> Component.empty()));
        } catch (IllegalArgumentException expected) {
            refused = true;
        }
        expect(helper, refused, "a non-locking slot over a mirror container must be refused");
        new MirrorMenu(SlotRule.of(stack -> true, stack -> Component.empty()).locking());
        helper.succeed();
    }

    /** A menu with one typed slot over a {@link MirrorContainer}. */
    private static final class MirrorMenu extends SlottedMenu {
        MirrorMenu(SlotRule rule) {
            super(RepairMenu.TYPE, 0);
            addRuleSlot(new MirrorContainer(1), 0, 0, 0, rule);
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }
    }
}
