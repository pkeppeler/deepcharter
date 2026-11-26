package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.client.pod.CockpitHud;
import io.github.pkeppeler.deepcharter.client.pod.CockpitReading;
import io.github.pkeppeler.deepcharter.client.pod.PodStatusHud;
import io.github.pkeppeler.deepcharter.client.theme.CockpitLook;
import io.github.pkeppeler.deepcharter.client.theme.HudLook;
import io.github.pkeppeler.deepcharter.client.ui.HudKeepClear;
import io.github.pkeppeler.deepcharter.fuel.EngineSoot;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.ScreenInk;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.VanillaHudRows;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.metadata.gui.GuiSpriteScaling;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * Client GameTest for the line that says what the engine burns (#1438). The plain readout always has it, under the fuel: pump fuel,
 * or the solid fuel still in the tank by name and litres, and it changes back when that has burned. The cockpit's plate draws the
 * solid fuel's row, after the fuel's icon in the fuel's colour, read from the pixels at GUI scales 2 and 1; it is never dropped for
 * room, the plate that holds it is what popups keep clear of, and with the widest name it fits the narrowest cockpit, as the plain
 * readout fits the two smallest GUIs clear of vanilla's rows.
 */
public class BurningFuelHudClientTest implements FabricClientGameTest {
    /** High in layer 1, far above the signal decay near its floor. */
    private static final int FEET_Y = TestLayers.worldY(1, 150);

    private static final String PUMP = "hud.deepcharter.pod.burns_pump";
    private static final String SOLID = "hud.deepcharter.pod.burns_solid";
    private static final Identifier CHARCOAL = Identifier.withDefaultNamespace("charcoal");
    private static final int[] GUI_SCALES = {2, 1};
    private static final int[][] SMALL_GUIS = {{320, 240}, {427, 240}};
    /** A row of the plate in the game's font is well over 60 GUI pixels of ink; half of that is the least a drawn row has. */
    private static final int TEXT_INK = 30;
    /** The least a drawn icon has: the fuel's drop lights 25 of its pixels. */
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
                pod.setNoGravity(true);
                one.addFreshEntity(pod);
                if (!player.startRiding(pod)) {
                    throw new AssertionError("the player could not mount the pod");
                }
            });
            ClientWait.until(context, "the Ribbon over the seated pilot", CockpitHud::showsReadout);
            List<String> problems = new ArrayList<>();
            try {
                expectBurning(context, singleplayer, 60f, EngineSoot.State.CLEAN, "BURNING PUMP FUEL");
                problems.addAll(context.computeOnClient(client -> plateProblems(client, false)));

                expectBurning(
                        context,
                        singleplayer,
                        60f,
                        new EngineSoot.State(0f, 3f, 3f, Optional.of(CHARCOAL)),
                        "BURNING CHARCOAL 3 L");
                for (int scale : GUI_SCALES) {
                    context.runOnClient(client -> client.options.guiScale().set(scale));
                    ClientWait.until(
                            context,
                            "the GUI at scale " + scale,
                            client -> client.getWindow().getGuiScale() == scale && CockpitHud.showsReadout(client));
                    BufferedImage shot = ScreenInk.shot(context, "burning-charcoal-gui" + scale);
                    problems.addAll(context.computeOnClient(client -> plateProblems(client, true)));
                    problems.addAll(context.computeOnClient(client -> inkProblems(client, shot, scale)));
                }
                context.runOnClient(client -> client.options.guiScale().set(2));

                // The widest line: a mix, double figures (never more than the stock tank's 10 L), and the soot line
                // under it.
                expectBurning(
                        context,
                        singleplayer,
                        100f,
                        new EngineSoot.State(11f, 12f, 12f, Optional.empty()),
                        "BURNING SOLID FUEL 10 L");
                problems.addAll(context.computeOnClient(BurningFuelHudClientTest::fitProblems));

                // The charcoal has burned: pump fuel again, and the plate's row is gone.
                expectBurning(context, singleplayer, 60f, EngineSoot.State.CLEAN, "BURNING PUMP FUEL");
                problems.addAll(context.computeOnClient(client -> plateProblems(client, false)));

                // An empty tank burns nothing.
                setTank(singleplayer, 0f, new EngineSoot.State(0f, 3f, 3f, Optional.of(CHARCOAL)));
                ClientWait.until(
                        context,
                        "no burning line for an empty tank",
                        client ->
                                pod(client).fuel() == 0f && burningLines(client).isEmpty());
            } finally {
                context.runOnClient(client -> client.options.guiScale().set(2));
            }
            if (!problems.isEmpty()) {
                throw new AssertionError("The line of what the engine burns: " + problems);
            }
        }
    }

    private static PodEntity pod(Minecraft client) {
        return TestEntities.vehicle(ClientSession.player(client), PodEntity.class);
    }

    private static void setTank(TestSingleplayerContext singleplayer, float fuel, EngineSoot.State state) {
        singleplayer.getServer().runOnServer(server -> {
            PodEntity pod =
                    TestEntities.vehicle(server.getPlayerList().getPlayers().getFirst(), PodEntity.class);
            pod.setFuel(fuel);
            pod.setAttached(EngineSoot.STATE, Versioned.of(state));
        });
    }

    /** The readout's lines that say what burns. */
    private static List<PodStatusHud.Line> burningLines(Minecraft client) {
        return PodStatusHud.readout(ClientSession.player(client), pod(client)).stream()
                .filter(line ->
                        key(line.text()).equals(PUMP) || key(line.text()).equals(SOLID))
                .toList();
    }

    private static String key(Component text) {
        return text.getContents() instanceof TranslatableContents contents ? contents.getKey() : "";
    }

    /** Sets the tank, then waits until the readout has one line of what burns, {@code text}, right under the fuel. */
    private static void expectBurning(
            ClientGameTestContext context,
            TestSingleplayerContext singleplayer,
            float fuel,
            EngineSoot.State state,
            String text) {
        setTank(singleplayer, fuel, state);
        ClientWait.until(context, "the readout says " + text, client -> {
            List<PodStatusHud.Line> readout = PodStatusHud.readout(ClientSession.player(client), pod(client));
            List<PodStatusHud.Line> burning = burningLines(client);
            int fuelLine =
                    readout.stream().map(line -> key(line.text())).toList().indexOf("hud.deepcharter.pod.fuel");
            return burning.size() == 1
                    && burning.getFirst().text().getString().equals(text)
                    && readout.indexOf(burning.getFirst()) == fuelLine + 1;
        });
    }

    private static List<CockpitHud.Row> rows(Minecraft client) {
        PodEntity pod = pod(client);
        return CockpitHud.rows(
                pod,
                CockpitReading.of(pod, CockpitHud.heat()),
                CockpitLook.current(),
                PodStatusHud.readout(ClientSession.player(client), pod));
    }

    private static Optional<CockpitHud.Row> solidRow(List<CockpitHud.Row> rows) {
        return rows.stream()
                .filter(row -> key(row.first().text()).equals(SOLID))
                .findFirst();
    }

    /**
     * The plate has the solid fuel's row exactly when {@code solid}, never the pump's; the row is the fuel's icon and the line in the fuel's colour; a plate
     * with room for one row keeps it; and the plate popups keep clear of is the one that holds it.
     */
    private static List<String> plateProblems(Minecraft client, boolean solid) {
        List<String> problems = new ArrayList<>();
        CockpitLook look = CockpitLook.current();
        List<CockpitHud.Row> rows = rows(client);
        if (rows.stream().anyMatch(row -> key(row.first().text()).equals(PUMP))) {
            problems.add("the plate leaves pump fuel to the tally, got " + rows);
        }
        Optional<CockpitHud.Row> row = solidRow(rows);
        if (row.isPresent() != solid) {
            problems.add("the plate has a row for solid fuel " + (solid ? "in" : "out of") + " the tank: " + rows);
            return problems;
        }
        if (row.isEmpty()) {
            return problems;
        }
        CockpitHud.Cell cell = row.get().cells().getFirst();
        if (row.get().cells().size() != 1
                || cell.icon() != CockpitHud.Meter.FUEL.icon()
                || cell.line().color() != look.fuelColor()) {
            problems.add("the solid fuel's row is the fuel's icon and its line in the fuel's colour, got " + row.get());
        }
        if (solidRow(CockpitHud.fitting(rows, 1)).isEmpty()) {
            problems.add("a plate with no room for notes keeps the solid fuel's row, as it keeps the tally");
        }
        int width = client.getWindow().getGuiScaledWidth();
        int height = client.getWindow().getGuiScaledHeight();
        CockpitHud.Box plate = CockpitHud.plateBox(
                client.font,
                look.ribbon(),
                look,
                CockpitHud.fitting(rows, CockpitHud.roomForRows(client.font, look.ribbon(), look, height)),
                width,
                height);
        HudKeepClear.Rect want = new HudKeepClear.Rect(
                "cockpit readout plate", plate.left(), plate.top(), plate.width(), plate.height());
        if (!HudKeepClear.rects(client, width, height).contains(want)) {
            problems.add("popups keep clear of the plate that holds the solid fuel's row " + want);
        }
        return problems;
    }

    /** The solid fuel's row is drawn: the fuel's icon, then its words, in the fuel's colour. */
    private static List<String> inkProblems(Minecraft client, BufferedImage shot, int scale) {
        CockpitLook look = CockpitLook.current();
        CockpitLook.Layout layout = look.ribbon();
        int height = client.getWindow().getGuiScaledHeight();
        List<CockpitHud.Row> rows =
                CockpitHud.fitting(rows(client), CockpitHud.roomForRows(client.font, layout, look, height));
        Optional<CockpitHud.Row> found = solidRow(rows);
        if (found.isEmpty()) {
            return List.of("at GUI scale " + scale + " the plate draws no row for the solid fuel: " + rows);
        }
        CockpitHud.Row row = found.get();
        CockpitHud.Box plate = CockpitHud.plateBox(
                client.font, layout, look, rows, client.getWindow().getGuiScaledWidth(), height);
        GuiSpriteScaling.NineSlice.Border inset = CockpitHud.plateInset(layout, look);
        int x = plate.left() + inset.left();
        int y = plate.top()
                + inset.top()
                + rows.indexOf(row)
                        * (client.font.lineHeight + HudLook.current().podStatusLineGap());
        int icon = ScreenInk.count(shot, scale, look.fuelColor(), x, y, look.iconWidth(), look.titleTextHeight());
        int textLeft = x + look.iconWidth() + look.iconGap();
        int text = ScreenInk.count(
                shot,
                scale,
                look.fuelColor(),
                textLeft,
                y,
                client.font.width(row.first().text()),
                look.titleTextHeight());
        if (icon < ICON_INK * scale * scale || text < TEXT_INK * scale * scale) {
            return List.of("at GUI scale " + scale + " the solid fuel's row at y " + y + " has " + icon
                    + " screen pixels of icon and " + text + " of words in the fuel's colour");
        }
        return List.of();
    }

    /** With the widest line the plate fits the narrowest cockpit, and the plain readout the two smallest GUIs, clear of vanilla's rows. */
    private static List<String> fitProblems(Minecraft client) {
        List<String> problems = new ArrayList<>();
        CockpitLook look = CockpitLook.current();
        CockpitHud.Box plate =
                CockpitHud.plateBox(client.font, look.ribbon(), look, rows(client), look.minGuiWidth(), 240);
        if (plate.left() < 0 || plate.left() + plate.width() > look.minGuiWidth()) {
            problems.add("the plate spans " + plate.left() + " to " + (plate.left() + plate.width()) + " of a "
                    + look.minGuiWidth() + " px GUI");
        }
        HudLook hud = HudLook.current();
        List<PodStatusHud.Line> readout = PodStatusHud.readout(ClientSession.player(client), pod(client));
        int widest = readout.stream()
                .mapToInt(line -> client.font.width(line.text()))
                .max()
                .orElse(0);
        HudKeepClear.Rect box = new HudKeepClear.Rect(
                "pod readout",
                hud.podStatusMargin(),
                hud.podStatusMargin(),
                widest,
                readout.size() * (client.font.lineHeight + hud.podStatusLineGap()) - hud.podStatusLineGap());
        for (int[] gui : SMALL_GUIS) {
            if (box.right() > gui[0] || box.bottom() > gui[1]) {
                problems.add("the readout " + box + " leaves the GUI " + gui[0] + " x " + gui[1]);
            }
            for (HudKeepClear.Rect row : VanillaHudRows.rects(gui[0], gui[1])) {
                if (box.overlaps(row)) {
                    problems.add("the readout " + box + " runs into vanilla's " + row.what() + " on " + gui[0] + " x "
                            + gui[1]);
                }
            }
        }
        return problems;
    }
}
