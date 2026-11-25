package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.fuel.FuelPumpScreen;
import io.github.pkeppeler.deepcharter.client.terminal.RepairScreen;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
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

/**
 * Evidence scenario "m2-terminal-framework" for #59: a charter opens the offline fuel pump terminal, shift-clicks its
 * two parts into their slots one by one (#1265), and the terminal comes online. Stills of the offline and the online
 * screen.
 */
public class TerminalFrameworkScenario extends EvidenceScenario {
    private static final int TICKS_PER_FRAME = 3;
    private static final int TYPING_FRAMES = 45;
    private static final int HOLD_FRAMES = 6;

    @Override
    protected String name() {
        return "m2-terminal-framework";
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
                TerminalTypes.FUEL_PUMP
                        .parts()
                        .forEach(part -> player.getInventory().add(new ItemStack(part)));
                return at;
            });

            context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(pump)));
            ClientWait.screen(context, RepairScreen.class);
            RepairScreen screen = context.computeOnClient(client -> ClientSession.screen(client, RepairScreen.class));
            for (int i = 0; i < TYPING_FRAMES && !screen.typewriter().done(); i++) {
                context.waitTicks(TICKS_PER_FRAME);
                frame(context);
            }
            hold(context);
            screenshot(context, "terminal-offline");

            for (Item part : TerminalTypes.FUEL_PUMP.parts()) {
                Slot held = context.computeOnClient(client -> screen.getMenu().slots.stream()
                        .filter(slot -> slot.container instanceof Inventory
                                && slot.getItem().is(part))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("the player carries no " + part)));
                SlotClicks.shiftClick(context, screen, held);
                hold(context);
            }

            ClientWait.screen(context, FuelPumpScreen.class);
            FuelPumpScreen online =
                    context.computeOnClient(client -> ClientSession.screen(client, FuelPumpScreen.class));
            for (int i = 0; i < TYPING_FRAMES && !online.typewriter().done(); i++) {
                context.waitTicks(TICKS_PER_FRAME);
                frame(context);
            }
            hold(context);
            screenshot(context, "terminal-online");
            context.setScreen(() -> null);
        }
    }

    private void hold(ClientGameTestContext context) {
        for (int i = 0; i < HOLD_FRAMES; i++) {
            context.waitTicks(TICKS_PER_FRAME);
            frame(context);
        }
    }
}
