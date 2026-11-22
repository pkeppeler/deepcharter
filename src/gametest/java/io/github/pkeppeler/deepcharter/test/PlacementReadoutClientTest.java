package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.client.outpost.PlacementReadoutHud;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.ore.SlagBrick;
import io.github.pkeppeler.deepcharter.outpost.Placements.Readout;
import io.github.pkeppeler.deepcharter.outpost.Placements.Verdict;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.PlacementStage;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Blocks;

/**
 * Client GameTest for the placement readout and the spec sheet of #989: the block in hand and the real crosshair hit give HOLDS HERE or WILL NOT HOLD
 * UNATTENDED at the depth the player stands in, nothing shows for an empty hand, a tool, a hidden HUD or an open screen, and a block's tooltip states its
 * ratings. What the words are is {@code OutpostRatingsTest}'s; this checks the hit, the hand and the gates on the real client, from the HUD's state, not its pixels.
 */
public class PlacementReadoutClientTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            ClientWait.until(
                    context,
                    "the player and level of the world",
                    client -> client.player != null && client.level != null);

            stage(context, singleplayer, 1);
            expectVerdict(context, singleplayer, new ItemStack(Items.OAK_PLANKS), Verdict.WILL_NOT_HOLD);
            expectVerdict(context, singleplayer, new ItemStack(LayerBlocks.PACKED_SHALE), Verdict.HOLDS_HERE);
            stage(context, singleplayer, 2);
            expectVerdict(context, singleplayer, new ItemStack(LayerBlocks.PACKED_SHALE), Verdict.WILL_NOT_HOLD);
            expectVerdict(context, singleplayer, new ItemStack(SlagBrick.item()), Verdict.HOLDS_HERE);

            ClientChecks.withHudHidden(
                    context,
                    () -> require(
                            context.computeOnClient(
                                    client -> PlacementReadoutHud.looked(client).isEmpty()),
                            "a hidden HUD should read nothing"));
            context.setScreen(() -> new PauseScreen(false));
            try {
                require(
                        context.computeOnClient(
                                client -> PlacementReadoutHud.looked(client).isEmpty()),
                        "an open screen should read nothing");
            } finally {
                context.setScreen(() -> null);
            }
            singleplayer.getServer().runOnServer(server -> PlacementStage.hold(server, new ItemStack(Items.STICK)));
            ClientWait.until(
                    context,
                    "no readout for a tool in hand",
                    client -> client.player != null
                            && client.player.getMainHandItem().is(Items.STICK)
                            && PlacementReadoutHud.looked(client).isEmpty());
            singleplayer.getServer().runOnServer(server -> PlacementStage.hold(server, ItemStack.EMPTY));
            ClientWait.until(
                    context,
                    "no readout for an empty hand",
                    client -> client.player != null
                            && client.player.getMainHandItem().isEmpty()
                            && PlacementReadoutHud.looked(client).isEmpty());

            List<String> slag = tooltip(context, new ItemStack(SlagBrick.item()));
            require(
                    slag.equals(List.of(
                            new ItemStack(SlagBrick.item()).getHoverName().getString(),
                            "Rated: heat 2, pressure 2, flooding 2, quake 2",
                            "Holds in layers 1, 2")),
                    "slag brick's tooltip should state its ratings, listed " + slag);
            List<String> stone = tooltip(context, new ItemStack(Blocks.STONE));
            require(stone.size() == 1, "a block no tag lists keeps its plain tooltip, listed " + stone);
        }
    }

    private static void stage(ClientGameTestContext context, TestSingleplayerContext singleplayer, int layer) {
        singleplayer.getServer().runOnServer(server -> PlacementStage.build(server, layer));
        ClientWait.until(context, "the player in layer " + layer, client -> TestLayers.clientIsIn(client, layer));
    }

    private static void expectVerdict(
            ClientGameTestContext context, TestSingleplayerContext singleplayer, ItemStack stack, Verdict verdict) {
        singleplayer.getServer().runOnServer(server -> PlacementStage.hold(server, stack.copy()));
        ClientWait.until(
                context,
                "the readout " + verdict + " for " + stack.getItem(),
                client -> client.player != null
                        && client.player.getMainHandItem().is(stack.getItem())
                        && PlacementReadoutHud.looked(client)
                                .map(Readout::verdict)
                                .filter(shown -> shown == verdict)
                                .isPresent(),
                client -> "the readout " + PlacementReadoutHud.looked(client));
    }

    private static List<String> tooltip(ClientGameTestContext context, ItemStack stack) {
        return context.computeOnClient(client ->
                stack.getTooltipLines(Item.TooltipContext.of(client.level), client.player, TooltipFlag.NORMAL).stream()
                        .map(Component::getString)
                        .toList());
    }
}
