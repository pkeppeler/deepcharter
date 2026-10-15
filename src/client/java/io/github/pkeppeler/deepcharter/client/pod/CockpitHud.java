package io.github.pkeppeler.deepcharter.client.pod;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.market.AccountHud;
import io.github.pkeppeler.deepcharter.client.pod.PodStatusHud.Kind;
import io.github.pkeppeler.deepcharter.client.pod.PodStatusHud.Line;
import io.github.pkeppeler.deepcharter.client.scanner.ScannerHud;
import io.github.pkeppeler.deepcharter.client.theme.CockpitLook;
import io.github.pkeppeler.deepcharter.client.theme.CockpitLook.Gauge;
import io.github.pkeppeler.deepcharter.client.theme.CockpitLook.Layout;
import io.github.pkeppeler.deepcharter.client.theme.HudLook;
import io.github.pkeppeler.deepcharter.client.theme.PanelLook.Decal;
import io.github.pkeppeler.deepcharter.client.ui.CrtDraw;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodFuel;
import io.github.pkeppeler.deepcharter.pod.PodSeat;
import io.github.pkeppeler.deepcharter.pod.SignalDecay.Fault;
import io.github.pkeppeler.deepcharter.pod.SignalDecay.Instrument;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The cockpit HUD (#247, {@link CockpitLook}): while the local player sits in a pod and the GUI is big enough for the Ribbon, the ribbon along the top edge, its gauges and
 * warning lamps, the scanner window hung from its end and the readout plate under that are the HUD, and the vanilla hotbar, hearts and held item name are not drawn. The
 * action bar stays: the game says things there that a pilot must read (a grab on the hull, a refusal), and the Ribbon has no other place for them. On foot, and with a GUI
 * smaller than the Ribbon needs (or a pack that sets {@code enabled} to 0), nothing here draws or hides anything.
 *
 * <p>Every piece goes through {@link #draw}, so the signal decay near the bottom of a layer (#502, {@link CockpitDecay}) wraps one place and not each gauge. It darkens a
 * gauge or the scanner's window, draws static over the scanner, makes a gauge or the plate's numbers jump, and swaps the plate for a tag. It never touches the hull lamp or
 * the burning lamp, which stay true so that a pilot who dies in the dark still sees what killed them, and the scanner's cells are drawn by {@link ScannerHud} unchanged.
 */
public final class CockpitHud {
    /** How many warning lamps the Ribbon has: low fuel, low hull, burning, and any other warning of the readout. */
    public static final int LAMPS = 4;

    private static final Identifier ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "cockpit");
    /** The vanilla elements a seated pilot does not get: the cluster of hotbar, health, armour, hunger, air, experience and the held item's name. */
    public static final List<Identifier> HIDDEN_WHILE_SEATED = List.of(
            VanillaHudElements.HOTBAR,
            VanillaHudElements.ARMOR_BAR,
            VanillaHudElements.HEALTH_BAR,
            VanillaHudElements.FOOD_BAR,
            VanillaHudElements.AIR_BAR,
            VanillaHudElements.MOUNT_HEALTH,
            VanillaHudElements.INFO_BAR,
            VanillaHudElements.EXPERIENCE_LEVEL,
            VanillaHudElements.HELD_ITEM_TOOLTIP);

    /** The pieces of the Ribbon that move or can fail, each drawn through {@link #draw}, and the instrument of the signal decay that fails with it. */
    public enum Part {
        FUEL(Instrument.FUEL),
        HULL(Instrument.HULL),
        CARGO(Instrument.CARGO),
        DEPTH(Instrument.DEPTH),
        HEAT(Instrument.HEAT),
        LAMPS(Instrument.LAMPS),
        SCANNER(Instrument.SCANNER),
        PLATE(Instrument.PLATE);

        private final Instrument instrument;

        Part(Instrument instrument) {
            this.instrument = instrument;
        }

        public Instrument instrument() {
            return instrument;
        }
    }

    private static double heat;

    private CockpitHud() {}

    public static void init() {
        for (Identifier element : HIDDEN_WHILE_SEATED) {
            HudElementRegistry.replaceElement(element, original -> (graphics, deltaTracker) -> {
                if (seated(Minecraft.getInstance()).isEmpty()) {
                    original.extractRenderState(graphics, deltaTracker);
                }
            });
        }
        // After the vanilla overlays (the vignette darkens the corners of whatever is under it, and the scanner sits in
        // one) and the front camera's feed, which is the first element,
        // and before the crosshair and everything the other features draw (the altimeter, a transmission), which stay
        // over the Ribbon.
        HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS, ID, CockpitHud::extract);
        ClientTickEvents.END_CLIENT_TICK.register(CockpitHud::tick);
    }

    /** The layout in force when the local player sits in a pod and the Ribbon fits the GUI; empty on foot, with the cockpit off, or on a GUI too small for it. */
    public static Optional<Layout> seated(Minecraft client) {
        if (client.player == null || !(client.player.getVehicle() instanceof PodEntity)) {
            return Optional.empty();
        }
        CockpitLook look = CockpitLook.current();
        return look.fits(
                        client.getWindow().getGuiScaledWidth(),
                        client.getWindow().getGuiScaledHeight())
                ? Optional.of(look.ribbon())
                : Optional.empty();
    }

    /** Whether the cockpit's plate shows the text readout: the local player is the pilot of a pod and the Ribbon fits. The plain HUD's readout and account line stay quiet then. */
    public static boolean showsReadout(Minecraft client) {
        LocalPlayer player = client.player;
        return player != null
                && seated(client).isPresent()
                && player.getVehicle() instanceof PodEntity pod
                && PodSeat.showsPodStatus(pod, player);
    }

    /** The heat gauge's reading, 0 to 1: the burning of the hull, eased. */
    public static double heat() {
        return heat;
    }

    private static void tick(Minecraft client) {
        if (client.player == null || !(client.player.getVehicle() instanceof PodEntity pod)) {
            heat = 0;
            return;
        }
        CockpitLook look = CockpitLook.current();
        heat = pod.hullBurning()
                ? Math.min(1.0, heat + look.heatRisePerTick())
                : Math.max(0.0, heat - look.heatFallPerTick());
    }

    private static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        PodEntity pod = seatedPod(client);
        Optional<Layout> seated = seated(client);
        if (player == null || pod == null || seated.isEmpty()) {
            return;
        }
        Layout layout = seated.get();
        CockpitLook look = CockpitLook.current();
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        CockpitDecay decay = CockpitDecay.of(client, pod, look);
        decal(graphics, layout.sprite("part_a"), layout.band(), width, height);
        // A pod with no scanner has no map to show, so it hangs no bezel either. The bezel is the cab's, not the
        // signal's: it stays when the window goes dark.
        if (ScannerHud.hasMap()) {
            draw(
                    graphics,
                    decay,
                    Part.SCANNER,
                    layout.scanner(),
                    () -> ScannerHud.drawInWindow(graphics, client, layout.scanner()));
            decal(graphics, layout.sprite("cap"), layout.cap(), width, height);
            if (decay.fault(Part.SCANNER) != Fault.DROP) {
                ScannerHud.drawTitleUnder(graphics, client, layout.scanner());
            }
        }
        if (!showsReadout(client)) {
            return;
        }
        CockpitReading reading = CockpitReading.of(pod, heat);
        List<Line> lines = PodStatusHud.readout(player, pod);
        boolean fuelLow = PodFuel.isLow(pod.fuel());
        boolean hullLow = reading.hull() <= look.hullLowFraction();
        draw(
                graphics,
                decay,
                Part.FUEL,
                layout.fuel().area(),
                () -> gauge(
                        graphics,
                        layout.fuel(),
                        decay.shown(Part.FUEL, reading.fuel()),
                        fuelLow ? look.lowColor() : look.fuelColor(),
                        look));
        draw(
                graphics,
                decay,
                Part.HULL,
                layout.hull().area(),
                () -> gauge(
                        graphics,
                        layout.hull(),
                        decay.shown(Part.HULL, reading.hull()),
                        hullLow ? look.lowColor() : look.hullColor(),
                        look));
        draw(
                graphics,
                decay,
                Part.CARGO,
                layout.cargo().area(),
                () -> gauge(
                        graphics, layout.cargo(), decay.shown(Part.CARGO, reading.cargo()), look.cargoColor(), look));
        draw(
                graphics,
                decay,
                Part.DEPTH,
                layout.depth().area(),
                () -> gauge(
                        graphics, layout.depth(), decay.shown(Part.DEPTH, reading.depth()), look.depthColor(), look));
        draw(
                graphics,
                decay,
                Part.HEAT,
                layout.heat().area(),
                () -> gauge(graphics, layout.heat(), decay.shown(Part.HEAT, reading.heat()), look.heatColor(), look));
        draw(
                graphics,
                decay,
                Part.LAMPS,
                null,
                () -> lamps(
                        graphics,
                        layout.lamps(),
                        decayedLamps(decay, lampsLit(fuelLow, hullLow, pod.hullBurning(), lines)),
                        new int[] {look.lowColor(), look.lowColor(), look.heatColor(), look.depthColor()},
                        look));
        draw(
                graphics,
                decay,
                Part.PLATE,
                null,
                () -> plate(graphics, client, layout, look, decay, rows(pod, reading, look, lines)));
    }

    private static @Nullable PodEntity seatedPod(Minecraft client) {
        LocalPlayer player = client.player;
        return player != null && player.getVehicle() instanceof PodEntity pod ? pod : null;
    }

    /**
     * Draws one piece of the Ribbon in the signal decay's frame: a part that is dark is not drawn and its {@code slot} is filled with the dead colour (a piece with no slot, the
     * lamps and the plate, handles its own dark), and the scanner's window gets its static. A part that jumps draws through {@link CockpitDecay#shown}.
     */
    private static void draw(
            GuiGraphicsExtractor graphics, CockpitDecay decay, Part part, @Nullable Decal slot, Runnable piece) {
        if (slot != null && decay.fault(part) == Fault.DROP) {
            decay.dead(graphics, slot);
            return;
        }
        piece.run();
        if (part == Part.SCANNER && decay.intensity() > 0) {
            decay.noise(graphics, Objects.requireNonNull(slot, "the scanner is drawn into its window"));
        }
    }

    /**
     * The lamps as the decay lets them read: the fuel lamp and the alert lamp go dark or show a false state, and the hull lamp and the burning lamp never change, so a pilot
     * who is dying in the dark still sees why.
     */
    public static boolean[] decayedLamps(CockpitDecay decay, boolean[] lit) {
        return new boolean[] {decay.lampShown(lit[0], 0), lit[1], lit[2], decay.lampShown(lit[3], 1)};
    }

    /** The altimeter's reading as the decay lets it read: gone when the depth gauge is dark, its digits jumping when it jumps. Not seated in a pod, the reading as given; empty only on a drop-out of the depth gauge. */
    public static Optional<Component> altimeter(Minecraft client, Component reading) {
        PodEntity pod = seatedPod(client);
        if (pod == null || seated(client).isEmpty()) {
            return Optional.of(reading);
        }
        CockpitDecay decay = CockpitDecay.of(client, pod, CockpitLook.current());
        return decay.fault(Part.DEPTH) == Fault.DROP
                ? Optional.empty()
                : Optional.of(decay.garbled(Part.DEPTH, reading));
    }

    private static void decal(GuiGraphicsExtractor graphics, Identifier sprite, Decal decal, int width, int height) {
        if (decal.on()) {
            CrtDraw.sprite(graphics, sprite, decal.left(width), decal.top(height), decal.width(), decal.height());
        }
    }

    /** Draws one bar gauge at {@code value} (0 to 1): segments of {@code color} lit from its start, at least one when the value is above 0, over unlit ones. */
    private static void gauge(GuiGraphicsExtractor graphics, Gauge gauge, double value, int color, CockpitLook look) {
        if (!gauge.on()) {
            return;
        }
        int left = gauge.area().left(graphics.guiWidth());
        int top = gauge.area().top(graphics.guiHeight());
        int width = gauge.area().width();
        int height = gauge.area().height();
        int lit = (int) Math.ceil(value * gauge.segments() - 1e-9);
        switch (gauge.kind()) {
            case ACROSS -> {
                for (int i = 0; i < gauge.segments(); i++) {
                    int from = left + i * width / gauge.segments();
                    int to = left + (i + 1) * width / gauge.segments() - 1;
                    graphics.fill(from, top, to, top + height, i < lit ? color : look.offColor());
                }
            }
            case UP -> {
                for (int i = 0; i < gauge.segments(); i++) {
                    int bottom = top + height - i * height / gauge.segments();
                    int from = top + height - (i + 1) * height / gauge.segments() + 1;
                    graphics.fill(left, from, left + width, bottom, i < lit ? color : look.offColor());
                }
            }
        }
    }

    /** Which of the {@link #LAMPS} are lit: low fuel, low hull, the hull burning, and any line of the readout that is a warning ({@link Kind#ALERT}). */
    public static boolean[] lampsLit(boolean fuelLow, boolean hullLow, boolean burning, List<Line> readout) {
        return new boolean[] {fuelLow, hullLow, burning, readout.stream().anyMatch(line -> line.kind() == Kind.ALERT)};
    }

    /** The warning lamps: {@link #LAMPS} squares in a row (a slot wider than it is tall) or a column, each lit in its colour or off. */
    private static void lamps(
            GuiGraphicsExtractor graphics, Decal slot, boolean[] lit, int[] colors, CockpitLook look) {
        if (!slot.on()) {
            return;
        }
        boolean row = slot.width() >= slot.height();
        int size = ((row ? slot.width() : slot.height()) - look.lampGap() * (LAMPS - 1)) / LAMPS;
        int left = slot.left(graphics.guiWidth());
        int top = slot.top(graphics.guiHeight());
        for (int k = 0; k < LAMPS; k++) {
            int offset = k * (size + look.lampGap());
            int x = row ? left + offset : left;
            int y = row ? top : top + offset;
            graphics.fill(
                    x,
                    y,
                    row ? x + size : left + slot.width(),
                    row ? top + slot.height() : y + size,
                    lit[k] ? colors[k] : look.offColor());
        }
    }

    /** One row of the plate: parts drawn side by side, each in its colour, {@link CockpitLook#tallyGap} apart. */
    public record Row(List<Line> parts) {
        public static Row of(Line line) {
            return new Row(List.of(line));
        }

        int width(Font font, int gap) {
            int width = gap * (parts.size() - 1);
            for (Line part : parts) {
                width += font.width(part.text());
            }
            return width;
        }
    }

    /**
     * The rows of the plate: the tally on one row (hull, fuel, cargo and depth, each in its gauge's colour), the account line, and under them every other line of the readout,
     * so the numbers stay where they are as lines come and go.
     */
    public static List<Row> rows(PodEntity pod, CockpitReading reading, CockpitLook look, List<Line> readout) {
        boolean fuelLow = PodFuel.isLow(pod.fuel());
        boolean hullLow = reading.hull() <= look.hullLowFraction();
        List<Row> rows = new ArrayList<>(List.of(new Row(List.of(
                new Line(
                        Component.translatable(
                                "hud.deepcharter.pod.tally_hull", Math.round(pod.hull()), Math.round(pod.maxHull())),
                        hullLow ? look.lowColor() : look.hullColor()),
                new Line(
                        Component.translatable("hud.deepcharter.pod.tally_fuel", Math.round(pod.fuel())),
                        fuelLow ? look.lowColor() : look.fuelColor()),
                new Line(Component.translatable("hud.deepcharter.pod.tally_cargo", pod.cargoUsed()), look.cargoColor()),
                new Line(
                        Component.translatable("hud.deepcharter.pod.tally_depth", Math.round(pod.getY())),
                        look.depthColor())))));
        AccountHud.text()
                .ifPresent(
                        text -> rows.add(Row.of(new Line(text, HudLook.current().accountColor()))));
        readout.stream().filter(line -> line.kind() != Kind.BASE).map(Row::of).forEach(rows::add);
        return rows;
    }

    /**
     * The rows that fit in {@code maxRows}: when there are more, the notes ({@link Kind#INFO}) go from the last up, and a warning is never dropped, so a short GUI loses the
     * slag count before it loses a crust warning.
     */
    public static List<Row> fitting(List<Row> rows, int maxRows) {
        List<Row> kept = new ArrayList<>(rows);
        for (int i = kept.size() - 1; i > 0 && kept.size() > maxRows; i--) {
            if (kept.get(i).parts().getFirst().kind() == Kind.INFO) {
                kept.remove(i);
            }
        }
        return kept;
    }

    /** Where the plate is on the screen. */
    public record Box(int left, int top, int width, int height) {}

    /** The plate that holds {@code rows}, as big as its longest row and the number of rows, hung from the layout's readout anchor. */
    public static Box plateBox(
            Font font, Layout layout, CockpitLook look, List<Row> rows, int guiWidth, int guiHeight) {
        int widest = 0;
        for (Row row : rows) {
            widest = Math.max(widest, row.width(font, look.tallyGap()));
        }
        int width = widest + 2 * look.platePad();
        int height = rows.size() * rowPitch(font) - HudLook.current().podStatusLineGap() + 2 * look.platePad();
        return new Box(layout.readout().left(guiWidth, width), layout.readout().top(guiHeight, height), width, height);
    }

    /** How many rows the plate has room for under its anchor on a GUI {@code guiHeight} high. */
    public static int roomForRows(Font font, Layout layout, CockpitLook look, int guiHeight) {
        int room = guiHeight
                - layout.readout().top(guiHeight, 0)
                - 2 * look.platePad()
                + HudLook.current().podStatusLineGap();
        return Math.max(1, room / rowPitch(font));
    }

    private static int rowPitch(Font font) {
        return font.lineHeight + HudLook.current().podStatusLineGap();
    }

    /**
     * The readout, on a plate that is as big as its longest row and the number of rows. In the signal decay a plate that is dark is a one-row tag that says why
     * ({@link #tag}), and one that jumps shows its numbers wrong.
     */
    private static void plate(
            GuiGraphicsExtractor graphics,
            Minecraft client,
            Layout layout,
            CockpitLook look,
            CockpitDecay decay,
            List<Row> all) {
        Font font = client.font;
        List<Row> rows = decay.fault(Part.PLATE) == Fault.DROP
                ? List.of(tag(decay, look))
                : jumped(decay, fitting(all, roomForRows(font, layout, look, graphics.guiHeight())));
        Box box = plateBox(font, layout, look, rows, graphics.guiWidth(), graphics.guiHeight());
        CrtDraw.sprite(graphics, layout.sprite("plate"), box.left(), box.top(), box.width(), box.height());
        int y = box.top() + look.platePad();
        for (Row row : rows) {
            int x = box.left() + look.platePad();
            for (Line part : row.parts()) {
                graphics.text(font, part.text(), x, y, part.color());
                x += font.width(part.text()) + look.tallyGap();
            }
            y += rowPitch(font);
        }
    }

    /** The one line that stands in for a dark plate: the signal is failing, or in the blackout that something is on the line. Not a fault of its own: it is always drawn. */
    public static Row tag(CockpitDecay decay, CockpitLook look) {
        return decay.blackout()
                ? Row.of(new Line(
                        Component.translatable("hud.deepcharter.pod.signal_lost"),
                        look.decay().lostColor()))
                : Row.of(new Line(
                        Component.translatable("hud.deepcharter.pod.signal_failing"),
                        look.decay().tagColor()));
    }

    private static List<Row> jumped(CockpitDecay decay, List<Row> rows) {
        if (decay.fault(Part.PLATE) != Fault.JUMP) {
            return rows;
        }
        return rows.stream()
                .map(row -> new Row(row.parts().stream()
                        .map(part -> new Line(decay.garbled(Part.PLATE, part.text()), part.color()))
                        .toList()))
                .toList();
    }
}
