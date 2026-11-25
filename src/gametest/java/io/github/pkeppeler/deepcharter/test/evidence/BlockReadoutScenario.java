package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.charter.terminal.ContractTerminal;
import io.github.pkeppeler.deepcharter.client.layer.BlockReadoutHud;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.test.support.BlockReadoutStage;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Evidence scenario "block-readout" (#859): the name, and where there is one the hint, that appears above the hotbar. A
 * stone platform stands in the sky above the player and one block at a time is set three blocks in front of them: a
 * plain block (name only), a contract terminal, breach crust and Company rock (name and hint). A still with an item
 * just taken in hand shows the readout above vanilla's held-item name, then a last still looks at nothing.
 */
public class BlockReadoutScenario extends EvidenceScenario {
    private static final int HOLD_FRAMES = 6;
    private static final int TICKS_PER_FRAME = 3;
    /** Ticks for the held item's name to come up after the hand changes, well inside the two seconds it shows for. */
    private static final int HELD_NAME_TICKS = 4;

    /** A block to look at and the still's name. */
    private record Subject(String still, Block block) {}

    private static final List<Subject> SUBJECTS = List.of(
            new Subject("plain-block", Blocks.STONE),
            new Subject("terminal", ContractTerminal.TYPE.block()),
            new Subject("breach-crust", LayerBlocks.BREACH_CRUST),
            new Subject("company-rock", HazardBlocks.COMPANY_ROCK));

    @Override
    protected String name() {
        return "block-readout";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            awaitWorld(context);
            BlockPos spot = singleplayer.getServer().computeOnServer(BlockReadoutStage::build);
            for (Subject subject : SUBJECTS) {
                singleplayer
                        .getServer()
                        .runOnServer(server -> BlockReadoutStage.place(
                                server, spot, subject.block().defaultBlockState()));
                ClientWait.until(
                        context,
                        "the readout of " + subject.block().getDescriptionId() + " fully shown",
                        client -> BlockReadoutHud.shown()
                                .filter(readout ->
                                        readout.name().equals(subject.block().getName()))
                                .isPresent());
                // The fade-in is a few ticks; hold past it so the still is the settled readout.
                hold(context);
                screenshot(context, subject.still());
            }
            // The readout beside vanilla's held-item name, which shows for two seconds after the hand changes.
            singleplayer.getServer().runOnServer(server -> {
                BlockReadoutStage.place(
                        server, spot, ContractTerminal.TYPE.block().defaultBlockState());
                Inventory inventory =
                        server.getPlayerList().getPlayers().getFirst().getInventory();
                inventory.setItem(inventory.getSelectedSlot(), new ItemStack(Items.IRON_PICKAXE));
            });
            ClientWait.until(
                    context,
                    "the held item's name up",
                    client -> client.player != null
                            && client.player.getMainHandItem().is(Items.IRON_PICKAXE));
            context.waitTicks(HELD_NAME_TICKS); // tick-wait: the name fades in on client ticks
            screenshot(context, "held-item-name");
            singleplayer
                    .getServer()
                    .runOnServer(server -> BlockReadoutStage.place(server, spot, Blocks.AIR.defaultBlockState()));
            ClientWait.until(
                    context,
                    "the readout gone for air",
                    client -> BlockReadoutHud.shown().isEmpty());
            hold(context);
            screenshot(context, "nothing");
        }
    }

    private void hold(ClientGameTestContext context) {
        for (int i = 0; i < HOLD_FRAMES; i++) {
            context.waitTicks(TICKS_PER_FRAME);
            frame(context);
        }
    }
}
