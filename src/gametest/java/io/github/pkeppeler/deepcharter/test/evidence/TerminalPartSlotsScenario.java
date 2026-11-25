package io.github.pkeppeler.deepcharter.test.evidence;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.fuel.FuelPumpScreen;
import io.github.pkeppeler.deepcharter.client.terminal.RepairScreen;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.test.support.ClientCursor;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.ContainerScreenFields;
import io.github.pkeppeler.deepcharter.test.support.RealInput;
import io.github.pkeppeler.deepcharter.test.support.SlotClicks;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Evidence scenario "terminal-part-slots" for #1265: a charter opens the offline fuel pump, whose two part slots show
 * their
 * parts as ghosts. A stick dragged onto the motor's slot is refused with the reason on screen; the pump motor is
 * dragged into
 * its slot; the fuel valve is shift-clicked into its own; and the pump comes online. Stills of the empty slots, the
 * refusal,
 * the motor in, and the online screen.
 */
public class TerminalPartSlotsScenario extends EvidenceScenario {
    private static final int TICKS_PER_FRAME = 3;
    private static final int TYPING_FRAMES = 60;
    private static final int HOLD_FRAMES = 8;
    /** Frames a dragged item takes to cross from its inventory slot to a part slot. */
    private static final int DRAG_FRAMES = 10;
    /** Where the cursor waits while the intro types: off the window, so no slot shows a tooltip. */
    private static final double OFF_SCREEN = -10_000;

    @Override
    protected String name() {
        return "terminal-part-slots";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            BlockPos pump = singleplayer.getServer().computeOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                if (Charters.found(server, player.getUUID(), "Riggs and Sons").isPresent()) {
                    throw new AssertionError("founding should succeed");
                }
                BlockPos at = player.blockPosition().relative(Direction.EAST, 2);
                server.overworld().setBlock(at, TerminalTypes.FUEL_PUMP.block().defaultBlockState(), 3);
                player.getInventory().add(new ItemStack(Items.STICK));
                TerminalTypes.FUEL_PUMP
                        .parts()
                        .forEach(part -> player.getInventory().add(new ItemStack(part)));
                return at;
            });
            Item motor = TerminalTypes.FUEL_PUMP.parts().get(0);
            Item valve = TerminalTypes.FUEL_PUMP.parts().get(1);

            context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(pump)));
            ClientWait.screen(context, RepairScreen.class);
            RepairScreen screen = context.computeOnClient(client -> ClientSession.screen(client, RepairScreen.class));
            Slot motorSlot = screen.getMenu().ruleSlots().get(0);
            context.getInput().setCursorPos(OFF_SCREEN, OFF_SCREEN); // no slot hovered, so no tooltip
            for (int i = 0; i < TYPING_FRAMES && !screen.typewriter().done(); i++) {
                context.waitTicks(TICKS_PER_FRAME);
                frame(context);
            }
            hold(context);
            screenshot(context, "slots-empty");

            drag(context, screen, inventorySlot(context, screen, Items.STICK), motorSlot);
            ClientWait.until(
                    context,
                    "the refusal on screen",
                    client -> screen.getMenu().refusal().isPresent());
            hold(context);
            screenshot(context, "wrong-item-refused");
            SlotClicks.click(context, screen, inventorySlot(context, screen, Items.AIR));

            drag(context, screen, inventorySlot(context, screen, motor), motorSlot);
            ClientWait.until(
                    context,
                    "the motor in, on the server",
                    client -> singleplayer
                            .getServer()
                            .computeOnServer(server -> RepairState.get(server).inserted(TerminalTypes.FUEL_PUMP))
                            .contains(motor));
            hold(context);
            screenshot(context, "motor-in");

            Slot valveInInventory = inventorySlot(context, screen, valve);
            SlotClicks.hover(context, screen, valveInInventory);
            hold(context);
            SlotClicks.shiftClick(context, screen, valveInInventory);
            // The repaired pump opens its own screen (#69).
            ClientWait.screen(context, FuelPumpScreen.class);
            FuelPumpScreen online =
                    context.computeOnClient(client -> ClientSession.screen(client, FuelPumpScreen.class));
            for (int i = 0; i < TYPING_FRAMES && !online.typewriter().done(); i++) {
                context.waitTicks(TICKS_PER_FRAME);
                frame(context);
            }
            hold(context);
            screenshot(context, "pump-online");
            context.setScreen(() -> null);
        }
    }

    /** Picks up what {@code from} holds, carries it across in frames, and drops it on {@code to}. */
    private void drag(ClientGameTestContext context, RepairScreen screen, Slot from, Slot to) {
        SlotClicks.click(context, screen, from);
        int left = ContainerScreenFields.intField(screen, "leftPos");
        int top = ContainerScreenFields.intField(screen, "topPos");
        for (int i = 1; i <= DRAG_FRAMES; i++) {
            double share = (double) i / DRAG_FRAMES;
            ClientCursor.moveTo(
                    context, left + from.x + 8 + (to.x - from.x) * share, top + from.y + 8 + (to.y - from.y) * share);
            frame(context);
        }
        RealInput.click(context, InputConstants.MOUSE_BUTTON_LEFT);
    }

    /**
     * The slot of the player's inventory in {@code screen} that holds {@code item}; {@code Items.AIR} finds an empty
     * one.
     */
    private static Slot inventorySlot(ClientGameTestContext context, RepairScreen screen, Item item) {
        return context.computeOnClient(client -> screen.getMenu().slots.stream()
                .filter(slot -> slot.container instanceof Inventory
                        && (item == Items.AIR ? !slot.hasItem() : slot.getItem().is(item)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no inventory slot holds " + item)));
    }

    private void hold(ClientGameTestContext context) {
        for (int i = 0; i < HOLD_FRAMES; i++) {
            context.waitTicks(TICKS_PER_FRAME);
            frame(context);
        }
    }
}
