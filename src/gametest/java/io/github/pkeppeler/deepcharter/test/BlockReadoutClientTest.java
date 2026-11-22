package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.charter.terminal.ContractTerminal;
import io.github.pkeppeler.deepcharter.client.layer.BlockReadoutHud;
import io.github.pkeppeler.deepcharter.layer.BlockReadouts.Readout;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.test.support.BlockReadoutStage;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import java.util.Optional;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Client GameTest for the block readout (#859): the real crosshair hit puts the block's name, and for a terminal its hint, on the HUD; air, a
 * hidden HUD and an open screen read nothing. What the words are is {@code BlockReadoutsTest}'s; this checks the hit, the fade and the gates on the
 * real client. It reads the HUD's state, not its pixels.
 */
public class BlockReadoutClientTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            ClientWait.until(
                    context,
                    "the player and level of the world",
                    client -> client.player != null && client.level != null);
            BlockPos spot = singleplayer.getServer().computeOnServer(BlockReadoutStage::build);

            show(context, singleplayer, spot, Blocks.STONE, Optional.empty());
            show(
                    context,
                    singleplayer,
                    spot,
                    ContractTerminal.TYPE.block(),
                    Optional.of("hint.deepcharter.right_click_use"));
            show(context, singleplayer, spot, LayerBlocks.BREACH_CRUST, Optional.of("hint.deepcharter.pod_drill_only"));

            ClientChecks.withHudHidden(context, () -> {
                ClientWait.until(
                        context,
                        "no readout with the HUD hidden",
                        client -> BlockReadoutHud.shown().isEmpty());
                require(
                        context.computeOnClient(
                                client -> BlockReadoutHud.looked(client).isEmpty()),
                        "a hidden HUD should read nothing");
            });
            ClientWait.until(
                    context,
                    "the readout back with the HUD shown",
                    client -> BlockReadoutHud.shown().isPresent());

            context.setScreen(() -> new PauseScreen(false));
            try {
                require(
                        context.computeOnClient(
                                client -> BlockReadoutHud.looked(client).isEmpty()),
                        "an open screen should read nothing");
                ClientWait.until(
                        context,
                        "no readout under a screen",
                        client -> BlockReadoutHud.shown().isEmpty());
            } finally {
                context.setScreen(() -> null);
            }

            singleplayer
                    .getServer()
                    .runOnServer(server -> BlockReadoutStage.place(server, spot, Blocks.AIR.defaultBlockState()));
            ClientWait.until(
                    context,
                    "no readout for air",
                    client -> BlockReadoutHud.shown().isEmpty());
        }
    }

    private static void show(
            ClientGameTestContext context,
            TestSingleplayerContext singleplayer,
            BlockPos spot,
            Block block,
            Optional<String> hintKey) {
        singleplayer
                .getServer()
                .runOnServer(server -> BlockReadoutStage.place(server, spot, block.defaultBlockState()));
        ClientWait.until(
                context,
                "the readout of " + block.getDescriptionId() + " with hint " + hintKey,
                client -> BlockReadoutHud.shown()
                        .filter(readout -> says(readout, block.getDescriptionId(), hintKey))
                        .isPresent());
    }

    private static boolean says(Readout readout, String nameKey, Optional<String> hintKey) {
        return key(readout.name()).equals(nameKey)
                && readout.hint().map(BlockReadoutClientTest::key).equals(hintKey);
    }

    private static String key(Component component) {
        return component.getContents() instanceof TranslatableContents translatable ? translatable.getKey() : "";
    }
}
