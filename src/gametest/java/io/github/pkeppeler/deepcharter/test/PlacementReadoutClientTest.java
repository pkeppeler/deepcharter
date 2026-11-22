package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.client.outpost.PlacementReadoutHud;
import io.github.pkeppeler.deepcharter.client.theme.CockpitLook;
import io.github.pkeppeler.deepcharter.client.theme.ReadoutLook;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.ore.SlagBrick;
import io.github.pkeppeler.deepcharter.outpost.Placements;
import io.github.pkeppeler.deepcharter.outpost.Ratings;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.PlacementStage;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.HitResult;

/**
 * Client GameTest for the placement readout and the spec sheet of #989. Layers 1 and 2 ask nothing, so with a block in hand and the crosshair on a
 * block there, a rated one or not, the readout says nothing and never fades in; a block's tooltip states its ratings; and the longest line the
 * readout can say fits the narrowest GUI the cockpit draws at. What the words are for a depth that asks something is {@code OutpostRatingsTest}'s,
 * checked against an example row. It reads the HUD's state, not its pixels.
 */
public class PlacementReadoutClientTest implements FabricClientGameTest {
    /** Space the readout leaves each side of its widest line in the narrowest GUI, in GUI pixels. */
    private static final int MARGIN = 8;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            ClientWait.until(
                    context,
                    "the player and level of the world",
                    client -> client.player != null && client.level != null);

            for (int layer : new int[] {1, 2}) {
                singleplayer.getServer().runOnServer(server -> PlacementStage.build(server, layer));
                ClientWait.until(
                        context, "the player in layer " + layer, client -> TestLayers.clientIsIn(client, layer));
                for (Item held : List.of(Items.OAK_PLANKS, LayerBlocks.PACKED_SHALE.asItem(), SlagBrick.item())) {
                    singleplayer.getServer().runOnServer(server -> PlacementStage.hold(server, new ItemStack(held)));
                    ClientWait.until(
                            context,
                            held + " in hand in layer " + layer + " with the crosshair on the aimed block",
                            client -> client.player != null
                                    && client.player.getMainHandItem().is(held)
                                    && client.hitResult != null
                                    && client.hitResult.getType() == HitResult.Type.BLOCK);
                    require(
                            context.computeOnClient(
                                    client -> PlacementReadoutHud.looked(client).isEmpty()),
                            held + " in layer " + layer + " asks nothing, so the readout should say nothing");
                    require(
                            PlacementReadoutHud.shown().isEmpty(),
                            held + " in layer " + layer + " should leave nothing on the HUD");
                }
            }

            List<String> slag = tooltip(context, new ItemStack(SlagBrick.item()));
            require(
                    slag.equals(List.of(
                            new ItemStack(SlagBrick.item()).getHoverName().getString(),
                            "Rated: heat 2, pressure 2, flooding 2, quake 2")),
                    "slag brick's tooltip should state its ratings, listed " + slag);
            List<String> stone = tooltip(context, new ItemStack(Blocks.STONE));
            require(stone.size() == 1, "a block rated nothing keeps its plain tooltip, listed " + stone);

            // The longest reason: a block rated nothing against a depth that asks level 1 of all four axes.
            Component worst = Placements.readout(Optional.of(new Ratings(1, 1, 1, 1)), Blocks.STONE.defaultBlockState())
                    .orElseThrow()
                    .reason()
                    .orElseThrow();
            double width = context.computeOnClient(
                    client -> client.font.width(worst) * ReadoutLook.current().scale());
            int room = CockpitLook.current().minGuiWidth() - 2 * MARGIN;
            require(
                    width <= room,
                    "the longest readout line, '" + worst.getString() + "', is " + width + " px wide, over the " + room
                            + " px that fits the narrowest cockpit GUI");
        }
    }

    private static List<String> tooltip(ClientGameTestContext context, ItemStack stack) {
        return context.computeOnClient(client ->
                stack.getTooltipLines(Item.TooltipContext.of(client.level), client.player, TooltipFlag.NORMAL).stream()
                        .map(Component::getString)
                        .toList());
    }
}
