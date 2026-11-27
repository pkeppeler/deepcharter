package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.client.outpost.PlacementReadoutHud;
import io.github.pkeppeler.deepcharter.client.theme.CockpitLook;
import io.github.pkeppeler.deepcharter.client.theme.ReadoutLook;
import io.github.pkeppeler.deepcharter.client.ui.HudKeepClear;
import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.ore.SlagBrick;
import io.github.pkeppeler.deepcharter.outpost.Placements;
import io.github.pkeppeler.deepcharter.outpost.Ratings;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks.Box;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.DrawnText;
import io.github.pkeppeler.deepcharter.test.support.DrawnText.Drawn;
import io.github.pkeppeler.deepcharter.test.support.PlacementStage;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.VanillaHudRows;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.HitResult;

/**
 * Client GameTest for the placement readout and the spec sheet of #989. Layers 1 and 2 ask nothing, so with a block in
 * hand and the crosshair on a block there, a rated one or not, the readout says nothing and never fades in; a block's
 * tooltip states its ratings; and the longest line the readout can say fits the narrowest GUI the cockpit draws at.
 * What the words are for a depth that asks something is {@code OutpostRatingsTest}'s, checked against an example row.
 * Where a depth has no rated material (the campaign's unbuilt depth, today) the readout is up, and the box {@link
 * HudKeepClear} gives for it is the box its text is drawn in, above the block readout's two lines, so a popup keeps
 * clear of what is drawn; it is gone once the readout is. It otherwise reads the HUD's state, not its pixels.
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

            expectTheReadoutKeepsItsBoxClear(context, singleplayer);

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

    /**
     * Unbuilt depth has no rated material, so a block in hand reads WILL NOT HOLD with a reason. The one rectangle {@link HudKeepClear} names for
     * it is worked out here from the theme and the font, holds every string the readout draws, and is gone when the block is put away.
     */
    private static void expectTheReadoutKeepsItsBoxClear(
            ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        singleplayer.getServer().runOnServer(server -> {
            PlacementStage.buildUnbuilt(server);
            PlacementStage.hold(server, new ItemStack(Items.OAK_PLANKS));
        });
        ClientWait.until(
                context,
                "the placement readout up in unbuilt depth and drawn",
                client -> client.player != null
                        && client.player.getY() < CampaignBands.LAYER_2.minY()
                        && PlacementReadoutHud.shown().isPresent()
                        && !drawn(client).isEmpty());
        context.runOnClient(client -> {
            int width = client.getWindow().getGuiScaledWidth();
            int height = client.getWindow().getGuiScaledHeight();
            List<HudKeepClear.Rect> mine = HudKeepClear.rects(client, width, height).stream()
                    .filter(rect -> rect.what().equals("placement readout"))
                    .toList();
            require(mine.size() == 1, "the placement readout keeps exactly one box clear, listed " + mine);
            HudKeepClear.Rect kept = mine.getFirst();
            Placements.Readout readout = PlacementReadoutHud.shown().orElseThrow();
            ReadoutLook look = ReadoutLook.current();
            int line = client.font.lineHeight + look.lineGap();
            int widest = Math.max(
                    client.font.width(readout.verdict().label()),
                    client.font.width(readout.reason().orElseThrow()));
            int expectedWidth = (int) Math.ceil(widest * (float) look.scale());
            require(
                    kept.x() == (width - expectedWidth) / 2 && kept.width() == expectedWidth,
                    "the kept box " + kept + " is centred and as wide as the wider line, " + expectedWidth);
            require(
                    kept.y() == look.placementTop(height, client.font.lineHeight, kept.height())
                            && kept.height() == (int) Math.ceil((client.font.lineHeight + line) * (float) look.scale()),
                    "the kept box " + kept + " is above the block readout's two lines and holds both lines");
            require(
                    kept.bottom() < look.blockBottom(height) - 2 * client.font.lineHeight,
                    "the kept box " + kept + " overlaps the two lines the block readout takes");
            require(
                    VanillaHudRows.clashes(kept, width, height).isEmpty(),
                    "the kept box " + kept + " overlaps vanilla's " + VanillaHudRows.clashes(kept, width, height));
            Box box = new Box(kept.x(), kept.y(), kept.width(), kept.height());
            for (Drawn text : drawn(client)) {
                require(
                        text.box().x() >= box.x()
                                && text.box().y() >= box.y()
                                && text.box().right() <= box.right()
                                && text.box().bottom() <= box.bottom(),
                        "the readout drew '" + text.shortText() + "' at " + text.box() + ", outside the box " + box
                                + " it keeps clear");
            }
        });
        singleplayer.getServer().runOnServer(server -> PlacementStage.hold(server, ItemStack.EMPTY));
        ClientWait.until(
                context,
                "the placement readout gone with the block put away",
                client -> PlacementReadoutHud.shown().isEmpty());
        require(
                context.computeOnClient(
                        client -> HudKeepClear.rects(
                                        client,
                                        client.getWindow().getGuiScaledWidth(),
                                        client.getWindow().getGuiScaledHeight())
                                .stream()
                                .noneMatch(rect -> rect.what().equals("placement readout"))),
                "a readout that is gone keeps nothing clear");
    }

    private static List<Drawn> drawn(Minecraft client) {
        return DrawnText.of(graphics -> PlacementReadoutHud.extract(graphics, client.getDeltaTracker()));
    }

    private static List<String> tooltip(ClientGameTestContext context, ItemStack stack) {
        return context.computeOnClient(client ->
                stack.getTooltipLines(Item.TooltipContext.of(client.level), client.player, TooltipFlag.NORMAL).stream()
                        .map(Component::getString)
                        .toList());
    }
}
