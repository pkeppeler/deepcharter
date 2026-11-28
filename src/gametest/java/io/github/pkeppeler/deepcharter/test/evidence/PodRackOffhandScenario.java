package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.market.OreProcessorScreen;
import io.github.pkeppeler.deepcharter.client.pod.PodScreen;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodLining;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.SlotClicks;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.UseEntity;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Evidence scenario "pod-rack-offhand" for #1649 and #1670. A charter's Mole with a spoil hopper is parked at the ore
 * processor with 30 spoil in the hopper and 13 slag brick in its rack. The pod screen shows the rack and the hopper
 * against their sizes, and the offhand slot beside the pack: empty, with its tooltip, then holding torches after the
 * swap-hands key over them. At the processor the title row shows the rack and the pack; MAKE SLAG BRICK fuses the 30
 * spoil into 15 bricks, 11 to fill the rack and 4 to the pack, and the row shows the new counts. Stills of each step.
 */
public class PodRackOffhandScenario extends EvidenceScenario {
    private static final int TICKS_PER_FRAME = 3;
    private static final int TYPING_FRAMES = 60;
    private static final int HOLD_FRAMES = 8;
    /** Where the cursor waits: off the window, so no slot shows a tooltip. */
    private static final double OFF_SCREEN = -10_000;

    private static final int SPOIL = 30;
    private static final int RACKED = 13;

    @Override
    protected String name() {
        return "pod-rack-offhand";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40);
            BlockPos processor = singleplayer.getServer().computeOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                if (Charters.found(server, player.getUUID(), "Riggs and Sons").isPresent()) {
                    throw new AssertionError("founding should succeed");
                }
                CharterId charter = Charters.charterOfOrThrow(server, player.getUUID())
                        .orElseThrow()
                        .id();
                BlockPos at = player.blockPosition().relative(Direction.EAST, 2);
                server.overworld()
                        .setBlock(at, TerminalTypes.ORE_PROCESSOR.block().defaultBlockState(), 3);
                RepairState state = RepairState.get(server);
                // The processor comes online once the fuel pump before it is repaired too.
                for (TerminalType type : List.of(TerminalTypes.FUEL_PUMP, TerminalTypes.ORE_PROCESSOR)) {
                    type.parts().forEach(part -> state.insert(type, part));
                }
                PodEntity pod = TestEntities.create(PodRegistry.POD, player.level());
                pod.setPos(player.position().add(0, 0, 4));
                player.level().addFreshEntity(pod);
                PodComponents.register(pod, charter);
                PodComponents.installAndDropReplaced(
                        pod, ComponentItems.mint(server, ComponentTrack.SPOIL_HOPPER, 1, charter));
                PodLining.modify(pod, lining -> new PodLining.State(SPOIL, RACKED, 0, false, false));
                player.getInventory().add(new ItemStack(Items.TORCH, 8));
                player.setShiftKeyDown(true);
                UseEntity.on(player, player.level(), InteractionHand.MAIN_HAND, pod);
                player.setShiftKeyDown(false);
                return at;
            });

            ClientWait.screen(context, PodScreen.class);
            PodScreen pod = context.computeOnClient(client -> ClientSession.screen(client, PodScreen.class));
            context.getInput().setCursorPos(OFF_SCREEN, OFF_SCREEN);
            for (int i = 0; i < TYPING_FRAMES && !pod.typewriter().done(); i++) {
                context.waitTicks(TICKS_PER_FRAME);
                frame(context);
            }
            hold(context);
            screenshot(context, "pod-screen-rack");

            SlotClicks.hover(
                    context,
                    pod,
                    context.computeOnClient(client -> pod.getMenu().offhandSlot()));
            hold(context);
            screenshot(context, "offhand-empty-tooltip");

            SlotClicks.hover(context, pod, SlotClicks.inventorySlot(context, pod, Items.TORCH));
            context.getInput().pressKey(options -> options.keySwapOffhand);
            ClientWait.until(
                    context,
                    "the torches in the offhand",
                    client -> ClientSession.player(client).getOffhandItem().is(Items.TORCH));
            context.getInput().setCursorPos(OFF_SCREEN, OFF_SCREEN);
            hold(context);
            screenshot(context, "offhand-filled");
            context.setScreen(() -> null);

            context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(processor)));
            ClientWait.screen(context, OreProcessorScreen.class);
            OreProcessorScreen screen =
                    context.computeOnClient(client -> ClientSession.screen(client, OreProcessorScreen.class));
            for (int i = 0; i < TYPING_FRAMES && !screen.typewriter().done(); i++) {
                context.waitTicks(TICKS_PER_FRAME);
                frame(context);
            }
            hold(context);
            screenshot(context, "processor-before-fuse");

            context.clickScreenButton("MAKE SLAG BRICK");
            ClientWait.until(
                    context,
                    "the full rack and four bricks in the pack on the title row",
                    client -> screen.bricksLine().contains("RACK 24/24")
                            && screen.bricksLine().contains("PACK 4"),
                    client -> "brick line '" + screen.bricksLine() + "'");
            hold(context);
            screenshot(context, "processor-after-fuse");
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
