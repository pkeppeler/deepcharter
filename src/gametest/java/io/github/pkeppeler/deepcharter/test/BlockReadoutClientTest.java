package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.charter.terminal.ContractTerminal;
import io.github.pkeppeler.deepcharter.client.layer.BlockReadoutHud;
import io.github.pkeppeler.deepcharter.client.theme.ReadoutLook;
import io.github.pkeppeler.deepcharter.client.ui.HudKeepClear;
import io.github.pkeppeler.deepcharter.layer.BlockReadouts.Readout;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.test.support.BlockReadoutStage;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks.Box;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.DrawnText;
import io.github.pkeppeler.deepcharter.test.support.DrawnText.Drawn;
import io.github.pkeppeler.deepcharter.test.support.VanillaHudRows;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
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
    /**
     * The GUI scales the box is checked at: the window has them all, from the smallest to the largest vanilla offers.
     */
    private static final int GUI_SCALES = 4;

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
            int nameWithoutHint = nameTop(context);
            show(
                    context,
                    singleplayer,
                    spot,
                    ContractTerminal.TYPE.block(),
                    Optional.of("hint.deepcharter.right_click_use"));
            require(
                    nameTop(context) == nameWithoutHint,
                    "the name moved from y " + nameWithoutHint + " to " + nameTop(context) + " when a hint came");
            show(context, singleplayer, spot, LayerBlocks.BREACH_CRUST, Optional.of("hint.deepcharter.pod_drill_only"));

            expectTheReadoutKeepsItsBoxClear(context);

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

    /**
     * The one rectangle {@link HudKeepClear} names for the readout is worked out from the theme and the font, and holds every string the readout
     * draws, at whatever GUI width the window gives (an odd one is where the text used to leave the box by a pixel).
     */
    private static void expectTheReadoutKeepsItsBoxClear(ClientGameTestContext context) {
        ClientWait.until(
                context,
                "the block readout up and drawn",
                client -> BlockReadoutHud.shown().isPresent() && !drawn(client).isEmpty());
        context.runOnClient(client -> {
            int width = client.getWindow().getGuiScaledWidth();
            int height = client.getWindow().getGuiScaledHeight();
            List<HudKeepClear.Rect> mine = HudKeepClear.rects(client, width, height).stream()
                    .filter(rect -> rect.what().equals("block readout"))
                    .toList();
            require(mine.size() == 1, "the block readout keeps exactly one box clear, listed " + mine);
            HudKeepClear.Rect kept = mine.getFirst();
            Readout readout = BlockReadoutHud.shown().orElseThrow();
            ReadoutLook look = ReadoutLook.current();
            int widest = Math.max(
                    client.font.width(readout.name()),
                    readout.hint().map(client.font::width).orElse(0));
            int expectedWidth = (int) Math.ceil(widest * look.scale());
            int lines = readout.hint().isPresent() ? 2 : 1;
            int expectedHeight =
                    (int) Math.ceil((lines * client.font.lineHeight + (lines - 1) * look.lineGap()) * look.scale());
            require(
                    kept.x() == (width - expectedWidth) / 2
                            && kept.width() == expectedWidth
                            && kept.y() == look.blockTop(height, client.font.lineHeight)
                            && kept.height() == expectedHeight,
                    "the kept box " + kept + " is centred, " + expectedWidth + " wide (the wider line) and "
                            + expectedHeight
                            + " high (its lines), with its name on the first line of the room above the hotbar");
            expectTheBoxClearsVanilla(client, readout);
            require(width % 2 == 1, "this check needs an odd GUI width to catch the half-pixel overflow, got " + width);
            List<Drawn> drawn = drawn(client);
            require(drawn.size() == lines, "the readout should draw " + lines + " strings, drew " + drawn);
            Box box = new Box(kept.x(), kept.y(), kept.width(), kept.height());
            for (Drawn text : drawn) {
                require(
                        text.box().x() >= box.x()
                                && text.box().y() >= box.y()
                                && text.box().right() <= box.right()
                                && text.box().bottom() <= box.bottom(),
                        "the readout drew '" + text.shortText() + "' at " + text.box() + ", outside the box " + box
                                + " it keeps clear");
            }
        });
    }

    /**
     * The readout's box, worked out for the GUI sizes the window gives at scales 1 to 4, overlaps none of vanilla's
     * rows above the hotbar (the held item's name and the action bar above it among them).
     */
    private static void expectTheBoxClearsVanilla(Minecraft client, Readout readout) {
        for (int scale = 1; scale <= GUI_SCALES; scale++) {
            int guiWidth = Math.ceilDiv(client.getWindow().getWidth(), scale);
            int guiHeight = Math.ceilDiv(client.getWindow().getHeight(), scale);
            List<HudKeepClear.Rect> mine = new ArrayList<>(HudKeepClear.rects(client, guiWidth, guiHeight));
            mine.removeIf(rect -> !rect.what().equals("block readout"));
            require(
                    mine.size() == 1,
                    "the block readout keeps one box clear at GUI scale " + scale + ", listed " + mine);
            HudKeepClear.Rect box = mine.getFirst();
            require(
                    VanillaHudRows.clashes(box, guiWidth, guiHeight).isEmpty(),
                    "at GUI scale " + scale + " (" + guiWidth + " x " + guiHeight + ") the block readout box " + box
                            + " overlaps vanilla's " + VanillaHudRows.clashes(box, guiWidth, guiHeight) + " for "
                            + readout.name().getString());
            require(box.y() >= 0, "at GUI scale " + scale + " the box " + box + " starts above the screen");
            require(
                    box.bottom() <= guiHeight - ReadoutLook.CLEAR_OF_VANILLA,
                    "at GUI scale " + scale + " the box " + box + " reaches below the held item's name");
        }
    }

    /** The y of the block readout's name line, from the box the readout keeps clear. */
    private static int nameTop(ClientGameTestContext context) {
        return context.computeOnClient(client ->
                HudKeepClear.rects(
                                client,
                                client.getWindow().getGuiScaledWidth(),
                                client.getWindow().getGuiScaledHeight())
                        .stream()
                        .filter(rect -> rect.what().equals("block readout"))
                        .findFirst()
                        .orElseThrow()
                        .y());
    }

    private static List<Drawn> drawn(Minecraft client) {
        return DrawnText.of(graphics -> BlockReadoutHud.extract(graphics, client.getDeltaTracker()));
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
