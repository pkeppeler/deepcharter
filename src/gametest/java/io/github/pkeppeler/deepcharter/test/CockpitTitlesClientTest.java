package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.client.pod.CockpitHud;
import io.github.pkeppeler.deepcharter.client.pod.CockpitReading;
import io.github.pkeppeler.deepcharter.client.pod.PodStatusHud;
import io.github.pkeppeler.deepcharter.client.theme.CockpitLook;
import io.github.pkeppeler.deepcharter.client.ui.HudKeepClear;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.ScannerPods;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.metadata.gui.GuiSpriteScaling;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * Client GameTest for the cockpit's titles (#1436), read from the pixels of a seated pilot's screen at GUI scale 2 and at GUI scale 1, where the font is smallest:
 * each gauge's tab holds its icon and its title in the gauge's colour, each number of the plate's tally comes after its gauge's icon in its colour, and each title
 * is a rectangle a popup keeps clear of. Where the titles sit and that they fit every GUI is {@link CockpitHudClientTest}'s.
 */
public class CockpitTitlesClientTest implements FabricClientGameTest {
    /** High in layer 1, far above the signal decay near its floor. */
    private static final int FEET_Y = TestLayers.worldY(1, 150);

    private static final int[] GUI_SCALES = {2, 1};
    /** A title in the game's font is some 40 GUI pixels of ink; half of that is the least a drawn title has. */
    private static final int TITLE_INK = 20;
    /** The least a drawn icon has: the icons light 12 (the cart) to 25 (fuel's drop) of their pixels, so two thirds of the sparsest, and a missing one none. */
    private static final int ICON_INK = 8;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40); // tick-wait: the new world's first ticks, as every cockpit test waits
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SURVIVAL);
                RoomCarver.carve(one, -4, 4, FEET_Y, FEET_Y + 8, -4, 4, Blocks.AIR);
                player.teleportTo(one, 0.5, FEET_Y, 0.5, Set.of(), 0f, 0f, true);
                PodEntity pod = TestEntities.create(PodRegistry.POD, one);
                pod.setPos(0.5, FEET_Y, 0.5);
                one.addFreshEntity(pod);
                // A scanner hangs the bezel, which a narrow GUI's right-hand titles ride over.
                ScannerPods.fit(server, player, pod, 1);
                if (!player.startRiding(pod)) {
                    throw new AssertionError("the player could not mount the pod");
                }
            });
            ClientWait.until(context, "the Ribbon over the seated pilot", CockpitHud::showsReadout);
            List<String> problems = new ArrayList<>();
            try {
                for (int scale : GUI_SCALES) {
                    context.runOnClient(client -> client.options.guiScale().set(scale));
                    ClientWait.until(
                            context,
                            "the GUI at scale " + scale,
                            client -> client.getWindow().getGuiScale() == scale && CockpitHud.showsReadout(client));
                    BufferedImage shot = shot(context, "cockpit-titles-gui" + scale);
                    problems.addAll(context.computeOnClient(client -> titleProblems(client, shot, scale)));
                    problems.addAll(context.computeOnClient(client -> tallyProblems(client, shot, scale)));
                    problems.addAll(context.computeOnClient(CockpitTitlesClientTest::keepClearProblems));
                }
            } finally {
                context.runOnClient(client -> client.options.guiScale().set(2));
            }
            if (!problems.isEmpty()) {
                throw new AssertionError("The cockpit's titles and the tally's icons: " + problems);
            }
        }
    }

    private static BufferedImage shot(ClientGameTestContext context, String name) {
        context.runOnClient(client -> client.gui.toastManager().clear());
        context.waitTicks(3); // tick-wait: the frames after the scale change and the cleared toast
        Path file = context.takeScreenshot(name);
        try {
            return ImageIO.read(file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Each title's tab holds its icon, then its title, both in the gauge's colour. */
    private static List<String> titleProblems(Minecraft client, BufferedImage shot, int scale) {
        List<String> problems = new ArrayList<>();
        CockpitLook look = CockpitLook.current();
        int width = client.getWindow().getGuiScaledWidth();
        int height = client.getWindow().getGuiScaledHeight();
        int inset = CockpitHud.spriteBorder(look.ribbon().sprite("tab")).left() + look.titleGap();
        for (CockpitHud.Meter gauge : CockpitHud.Meter.values()) {
            CockpitHud.Box box = CockpitHud.titleBox(client.font, look.ribbon(), look, gauge, width, height);
            int color = gauge.color(look);
            int iconLeft = box.left() + inset;
            int top = box.top() + inset;
            int icon = ink(shot, scale, color, iconLeft, top, look.iconWidth(), look.titleTextHeight());
            int textLeft = iconLeft + look.iconWidth() + look.iconGap();
            int title = ink(
                    shot,
                    scale,
                    color,
                    textLeft,
                    top,
                    box.left() + box.width() - inset - textLeft,
                    look.titleTextHeight());
            if (icon < ICON_INK * scale * scale || title < TITLE_INK * scale * scale) {
                problems.add("at GUI scale " + scale + " the " + name(gauge) + " title " + box + " has " + icon
                        + " screen pixels of icon and " + title + " of title in its colour");
            }
        }
        return problems;
    }

    /**
     * Each number of the tally comes after its icon, in the number's colour: hull, fuel and depth after their gauges' icons, and the bay's ore count, which is not
     * the LOAD gauge's weight, after the cart, which no gauge carries.
     */
    private static List<String> tallyProblems(Minecraft client, BufferedImage shot, int scale) {
        List<String> problems = new ArrayList<>();
        CockpitLook look = CockpitLook.current();
        CockpitLook.Layout layout = look.ribbon();
        int height = client.getWindow().getGuiScaledHeight();
        PodEntity pod = TestEntities.vehicle(ClientSession.player(client), PodEntity.class);
        List<CockpitHud.Row> rows = CockpitHud.fitting(
                CockpitHud.rows(
                        pod,
                        CockpitReading.of(pod, CockpitHud.heat()),
                        look,
                        PodStatusHud.readout(ClientSession.player(client), pod)),
                CockpitHud.roomForRows(client.font, layout, look, height));
        CockpitHud.Row tally = rows.getFirst();
        List<Optional<CockpitHud.Icon>> icons = tally.cells().stream()
                .map(cell -> Optional.ofNullable(cell.icon()))
                .toList();
        List<Optional<CockpitHud.Icon>> want = Stream.of(
                        CockpitHud.Meter.HULL.icon(),
                        CockpitHud.Meter.FUEL.icon(),
                        CockpitHud.Icon.CART,
                        CockpitHud.Meter.DEPTH.icon())
                .map(Optional::of)
                .toList();
        if (!icons.equals(want)) {
            problems.add("the tally's hull, fuel and depth carry their gauges' icons and its bay count the cart, got "
                    + icons);
        }
        if (Arrays.stream(CockpitHud.Meter.values()).anyMatch(gauge -> gauge.icon() == CockpitHud.Icon.CART)) {
            problems.add("the cart marks the bay's ore count and no gauge");
        }
        CockpitHud.Box plate = CockpitHud.plateBox(
                client.font, layout, look, rows, client.getWindow().getGuiScaledWidth(), height);
        GuiSpriteScaling.NineSlice.Border inset = CockpitHud.plateInset(layout, look);
        int x = plate.left() + inset.left();
        int y = plate.top() + inset.top();
        for (CockpitHud.Cell cell : tally.cells()) {
            PodStatusHud.Line part = cell.line();
            int icon = ink(shot, scale, part.color(), x, y, look.iconWidth(), look.titleTextHeight());
            if (icon < ICON_INK * scale * scale) {
                problems.add("at GUI scale " + scale + " the tally's "
                        + part.text().getString() + " has " + icon + " screen pixels of icon before it in its colour");
            }
            x += look.iconWidth() + look.iconGap() + client.font.width(part.text()) + look.tallyGap();
        }
        return problems;
    }

    /** Each title is a rectangle the HUD's popups keep clear of, the very rectangle it is drawn in. */
    private static List<String> keepClearProblems(Minecraft client) {
        List<String> problems = new ArrayList<>();
        CockpitLook look = CockpitLook.current();
        int width = client.getWindow().getGuiScaledWidth();
        int height = client.getWindow().getGuiScaledHeight();
        List<HudKeepClear.Rect> rects = HudKeepClear.rects(client, width, height);
        for (CockpitHud.Meter gauge : CockpitHud.Meter.values()) {
            CockpitHud.Box box = CockpitHud.titleBox(client.font, look.ribbon(), look, gauge, width, height);
            HudKeepClear.Rect want = new HudKeepClear.Rect(
                    "cockpit " + name(gauge) + " title", box.left(), box.top(), box.width(), box.height());
            if (!rects.contains(want)) {
                problems.add("on a GUI " + width + " x " + height + " popups keep clear of " + want + ", got " + rects);
            }
        }
        return problems;
    }

    private static String name(CockpitHud.Meter gauge) {
        return gauge.name().toLowerCase(Locale.ROOT);
    }

    /** How many screen pixels of the GUI rectangle ({@code left}, {@code top}, {@code width} x {@code height}) are near {@code color}. */
    private static int ink(BufferedImage shot, int scale, int color, int left, int top, int width, int height) {
        int count = 0;
        for (int y = top * scale; y < (top + height) * scale; y++) {
            for (int x = left * scale; x < (left + width) * scale; x++) {
                if (x >= 0 && y >= 0 && x < shot.getWidth() && y < shot.getHeight() && near(shot.getRGB(x, y), color)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static boolean near(int a, int b) {
        for (int shift = 0; shift <= 16; shift += 8) {
            if (Math.abs((a >> shift & 0xFF) - (b >> shift & 0xFF)) > 24) {
                return false;
            }
        }
        return true;
    }
}
