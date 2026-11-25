package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.client.layer.BlockReadoutHud;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.test.support.BlockReadoutStage;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.EvidencePins;
import io.github.pkeppeler.deepcharter.test.support.HandBreakChecks;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

/**
 * Evidence scenario "hand-break" (#858, ADR 0069): a survival player with a diamond pick brute-forces a block of
 * breach crust on the stage of the block readout. The stills are the start (the readout says the pick would burn out,
 * the pick's durability bar full), the middle (sparks, the action bar line, the crack well along, the bar shorter)
 * and the break (the crust gone, the bar all but empty); the frames show it advancing. A minute of real mining is 1200
 * ticks, so a frame comes every {@link #TICKS_PER_FRAME}.
 */
public class HandBreakScenario extends EvidenceScenario {
    private static final int TICKS_PER_FRAME = 30;
    private static final int MINING_LIMIT_TICKS = 1600;
    private static final int MIDDLE_TICKS = 700;
    private static final int HOLD_FRAMES = 6;

    @Override
    protected String name() {
        return "hand-break";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            awaitWorld(context);
            BlockPos spot = singleplayer.getServer().computeOnServer(server -> {
                BlockPos crust = BlockReadoutStage.build(server);
                BlockReadoutStage.place(server, crust, LayerBlocks.BREACH_CRUST.defaultBlockState());
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SURVIVAL);
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
                return crust;
            });
            context.getInput().lookAt(spot);
            ClientWait.until(
                    context,
                    "the readout of the crust, with the diamond pick in hand",
                    client -> BlockReadoutHud.shown()
                            .filter(readout -> readout.name().equals(LayerBlocks.BREACH_CRUST.getName()))
                            .isPresent());
            for (int i = 0; i < HOLD_FRAMES; i++) {
                context.waitTicks(TICKS_PER_FRAME / 5); // tick-wait: the readout's fade-in is a few ticks
                frame(context);
            }
            screenshot(context, "start");

            // The test client's mouse is not grabbed, so the attack button is held by a pin: the game's own mining then
            // runs, with the crack, the
            // swing and the abort that a driven start and continue could not keep up for a minute.
            EvidencePins.attackHeld = true;
            try {
                boolean middle = false;
                for (int tick = 0; tick < MINING_LIMIT_TICKS; tick++) {
                    context.waitTicks(1); // tick-wait: the mining is the game's own, one step a tick
                    if (tick % TICKS_PER_FRAME == 0) {
                        frame(context);
                    }
                    if (!middle && tick >= MIDDLE_TICKS) {
                        // The still must show the work, so the scenario fails, naming what is missing, when it does
                        // not.
                        HandBreakChecks.requireWorkShows(
                                context,
                                tick,
                                () -> singleplayer
                                        .getServer()
                                        .computeOnServer(server -> server.getPlayerList()
                                                .getPlayers()
                                                .getFirst()
                                                .getMainHandItem()
                                                .getDamageValue()),
                                () -> singleplayer
                                        .getServer()
                                        .computeOnServer(server -> HandBreakChecks.serverFacts(server, spot)));
                        screenshot(context, "middle");
                        middle = true;
                    }
                    if (singleplayer
                            .getServer()
                            .computeOnServer(server ->
                                    server.overworld().getBlockState(spot).isAir())) {
                        // The break, the crust gone and the pick nearly spent.
                        context.waitTicks(2); // tick-wait: the client shows the removal a tick after the server
                        frame(context);
                        screenshot(context, "break");
                        return;
                    }
                }
            } finally {
                EvidencePins.attackHeld = false;
            }
            throw new AssertionError("the crust at " + spot.toShortString() + " was not broken in " + MINING_LIMIT_TICKS
                    + " ticks with a diamond pick");
        }
    }
}
