package io.github.pkeppeler.deepcharter.client.pod;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.pod.PodStatusHud.Kind;
import io.github.pkeppeler.deepcharter.client.pod.PodStatusHud.Line;
import io.github.pkeppeler.deepcharter.client.scanner.ScannerHud;
import io.github.pkeppeler.deepcharter.client.theme.CockpitLook;
import io.github.pkeppeler.deepcharter.client.theme.CockpitLook.Gauge;
import io.github.pkeppeler.deepcharter.client.theme.CockpitLook.Layout;
import io.github.pkeppeler.deepcharter.client.theme.HudLook;
import io.github.pkeppeler.deepcharter.client.theme.PanelLook.Decal;
import io.github.pkeppeler.deepcharter.client.ui.CrtDraw;
import io.github.pkeppeler.deepcharter.client.ui.HudKeepClear;
import io.github.pkeppeler.deepcharter.layer.Altimeter;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodFuel;
import io.github.pkeppeler.deepcharter.pod.PodSeat;
import io.github.pkeppeler.deepcharter.pod.SignalDecay.Fault;
import io.github.pkeppeler.deepcharter.pod.SignalDecay.Instrument;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.metadata.gui.GuiMetadataSection;
import net.minecraft.client.resources.metadata.gui.GuiSpriteScaling;
import net.minecraft.data.AtlasIds;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The cockpit HUD (#247, {@link CockpitLook}): while the local player sits in a pod and the GUI is big enough for the Ribbon, the ribbon along the top edge, its gauges and
 * warning lamps, the scanner window hung from its end and the readout plate under that are the HUD, and the vanilla hearts, food, armour, air and experience are not drawn.
 * The vanilla hotbar and the held item's name stay (#1457): the pilot picks dynamite and the other consumables from the seat, so it must see the slot it is on
 * ({@link SeatedHotbar} lists it for {@link HudKeepClear}, and the plate stops short of it). The action bar stays too: the game says things there that a pilot must read
 * (a grab on the hull, a refusal), and the Ribbon has no other place for them. On foot, and with a GUI smaller than the Ribbon needs (or a pack that sets {@code enabled}
 * to 0), nothing here draws or hides anything.
 *
 * <p>Every piece goes through {@link #draw}, so the signal decay near the bottom of a layer (#502, {@link CockpitDecay}) wraps one place and not each gauge. It darkens a
 * gauge or the scanner's window, draws static over the scanner, makes a gauge or the plate's numbers jump, and swaps the plate for a tag. It never touches the hull lamp,
 * the burning lamp or the overloaded lamp, which stay true so that a pilot who dies or sticks in the dark still sees why, and the scanner's cells are drawn by
 * {@link ScannerHud} unchanged.
 *
 * <p>The cargo slot's gauge reads the pod's load against its climb limit (#1449, {@link CockpitReading#load}): a mark in the limit colour after
 * {@link Layout#loadLimit} segments, and the bar in that colour at and over it.
 *
 * <p>Each gauge is titled (#1436, a playtester could not tell what H or the plate's coloured numbers were): a tab under its bar, riding the band's lower edge, holds
 * the gauge's icon and its name in the gauge's colour ({@link Meter}). Each number of the plate's tally carries an icon, so the plate needs no words to stay small:
 * hull, fuel and depth their gauge's, and the bay's ore count the {@link Icon#CART}.
 *
 * <p>While the pod sinks fast, a SINK plate hangs under the band's middle (#1432, {@link CockpitReading#sink}, coloured
 * by {@link #sinkColor}). It reads the depth gauge's signal, as the altimeter does. Its title is the plate's own, a bar
 * beside it, so it needs no tab.
 */
public final class CockpitHud {
    /** How many warning lamps the Ribbon has: low fuel, low hull, burning, any other warning of the readout, and overloaded. */
    public static final int LAMPS = 5;

    private static final Identifier ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "cockpit");
    /**
     * The vanilla elements a seated pilot does not get: health, armour, hunger, air and experience, which the pod's gauges stand in for. The hotbar and the held item's name
     * are not here (#1457): the pilot uses consumables from the selected slot.
     */
    public static final List<Identifier> HIDDEN_WHILE_SEATED = List.of(
            VanillaHudElements.ARMOR_BAR,
            VanillaHudElements.HEALTH_BAR,
            VanillaHudElements.FOOD_BAR,
            VanillaHudElements.AIR_BAR,
            VanillaHudElements.MOUNT_HEALTH,
            VanillaHudElements.INFO_BAR,
            VanillaHudElements.EXPERIENCE_LEVEL);

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

    /**
     * The icons a gauge's title and the plate's tally carry, each a GUI sprite {@code icon_<name>} ({@link Layout#sprite}) in white that is drawn tinted. A gauge's
     * is its {@link Meter#icon}; the {@link #CART} is no gauge's: it marks the tally's count of the ore in the bay, which is not the LOAD gauge's weight.
     */
    public enum Icon {
        FUEL,
        HULL,
        WEIGHT,
        DEPTH,
        HEAT,
        CART;

        String sprite() {
            return "icon_" + name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * The gauges, in the order they sit on the band: each one's {@link Part}, its title's lang key under {@code hud.deepcharter.cockpit.}, its icon, its colour when
     * it is not in warning (its title's and its icon's) and its bar. The cargo slot's gauge reads the load (#1449).
     */
    public enum Meter {
        FUEL(Part.FUEL, "fuel", Icon.FUEL, CockpitLook::fuelColor, Layout::fuel),
        HULL(Part.HULL, "hull", Icon.HULL, CockpitLook::hullColor, Layout::hull),
        CARGO(Part.CARGO, "load", Icon.WEIGHT, CockpitLook::cargoColor, Layout::cargo),
        DEPTH(Part.DEPTH, "depth", Icon.DEPTH, CockpitLook::depthColor, Layout::depth),
        HEAT(Part.HEAT, "heat", Icon.HEAT, CockpitLook::heatColor, Layout::heat);

        private final Part part;
        private final String title;
        private final Icon icon;
        private final ToIntFunction<CockpitLook> color;
        private final Function<Layout, Gauge> bar;

        Meter(Part part, String title, Icon icon, ToIntFunction<CockpitLook> color, Function<Layout, Gauge> bar) {
            this.part = part;
            this.title = title;
            this.icon = icon;
            this.color = color;
            this.bar = bar;
        }

        public Part part() {
            return part;
        }

        /** The gauge's title, as the tab under its bar shows it. */
        public Component title() {
            return Component.translatable("hud.deepcharter.cockpit." + title);
        }

        public Icon icon() {
            return icon;
        }

        public int color(CockpitLook look) {
            return color.applyAsInt(look);
        }

        public Gauge bar(Layout layout) {
            return bar.apply(layout);
        }
    }

    private static double heat;
    /** Client ticks the SINK plate stays up: reset while the pod sinks past its show share, counted down after. */
    private static int sinkHold;

    private CockpitHud() {}

    public static void init() {
        for (Identifier element : HIDDEN_WHILE_SEATED) {
            // keep-clear-ok: this takes vanilla's own bars away while seated; it draws nothing of its own
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
        HudKeepClear.register(CockpitHud::keepClear);
        SeatedHotbar.init();
    }

    /** The layout in force when the local player sits in a pod and the Ribbon fits the GUI; empty on foot, with the cockpit off, or on a GUI too small for it. */
    public static Optional<Layout> seated(Minecraft client) {
        return seated(
                client,
                client.getWindow().getGuiScaledWidth(),
                client.getWindow().getGuiScaledHeight());
    }

    /** {@link #seated(Minecraft)} on a GUI {@code guiWidth} x {@code guiHeight}, which the client's window need not have. */
    public static Optional<Layout> seated(Minecraft client, int guiWidth, int guiHeight) {
        if (client.player == null || !(client.player.getVehicle() instanceof PodEntity)) {
            return Optional.empty();
        }
        CockpitLook look = CockpitLook.current();
        return look.fits(guiWidth, guiHeight) ? Optional.of(look.ribbon()) : Optional.empty();
    }

    /** Whether the cockpit's plate shows the text readout: the local player is the pilot of a pod and the Ribbon fits. The plain HUD's readout stays quiet then. */
    public static boolean showsReadout(Minecraft client) {
        return showsReadout(
                client,
                client.getWindow().getGuiScaledWidth(),
                client.getWindow().getGuiScaledHeight());
    }

    /** {@link #showsReadout(Minecraft)} on a GUI {@code guiWidth} x {@code guiHeight}. */
    public static boolean showsReadout(Minecraft client, int guiWidth, int guiHeight) {
        LocalPlayer player = client.player;
        return player != null
                && seated(client, guiWidth, guiHeight).isPresent()
                && player.getVehicle() instanceof PodEntity pod
                && PodSeat.showsPodStatus(pod, player);
    }

    /**
     * What a popup keeps clear of while the cockpit is up: the ribbon, which holds the gauges, the lamps and the altimeter's recess,
     * the scanner's bezel when the pod has a map, and, with the readout, each gauge's title and the plate where it hangs.
     */
    private static void keepClear(Minecraft client, int guiWidth, int guiHeight, List<HudKeepClear.Rect> into) {
        Optional<Layout> seated = seated(client, guiWidth, guiHeight);
        PodEntity pod = seatedPod(client);
        if (seated.isEmpty() || pod == null || client.player == null) {
            return;
        }
        Layout layout = seated.get();
        into.add(rect("cockpit ribbon", layout.band(), guiWidth, guiHeight));
        if (ScannerHud.hasMap()) {
            into.add(rect("cockpit scanner window", layout.scanner(), guiWidth, guiHeight));
            into.add(rect("cockpit scanner bezel", layout.cap(), guiWidth, guiHeight));
        }
        if (showsReadout(client, guiWidth, guiHeight)) {
            CockpitLook look = CockpitLook.current();
            for (Meter gauge : Meter.values()) {
                if (gauge.bar(layout).on()) {
                    into.add(titleBox(client.font, layout, look, gauge, guiWidth, guiHeight)
                            .rect("cockpit " + gauge.name().toLowerCase(Locale.ROOT) + " title"));
                }
            }
            if (sinkShown(pod)) {
                into.add(sinkBox(client.font, layout, look, guiWidth, guiHeight).rect("cockpit sink"));
            }
            List<Row> rows = plateRows(
                    client.font,
                    layout,
                    look,
                    rows(pod, CockpitReading.of(pod, heat), look, PodStatusHud.readout(client.player, pod)),
                    SeatedHotbar.slots(client, guiWidth, guiHeight),
                    guiWidth,
                    guiHeight);
            into.add(plateBox(client.font, layout, look, rows, guiWidth, guiHeight)
                    .rect("cockpit readout plate"));
        }
    }

    private static HudKeepClear.Rect rect(String what, Decal decal, int guiWidth, int guiHeight) {
        return new HudKeepClear.Rect(what, decal.left(guiWidth), decal.top(guiHeight), decal.width(), decal.height());
    }

    /** The heat gauge's reading, 0 to 1: the burning of the hull, eased. */
    public static double heat() {
        return heat;
    }

    private static void tick(Minecraft client) {
        if (client.player == null || !(client.player.getVehicle() instanceof PodEntity pod)) {
            heat = 0;
            sinkHold = 0;
            return;
        }
        CockpitLook look = CockpitLook.current();
        heat = pod.hullBurning()
                ? Math.min(1.0, heat + look.heatRisePerTick())
                : Math.max(0.0, heat - look.heatFallPerTick());
        if (pod.onGround()) {
            sinkHold = 0;
        } else if (CockpitReading.of(pod, heat).sink() >= look.sink().showShare()) {
            sinkHold = look.sink().holdTicks() + 1;
        } else if (sinkHold > 0) {
            sinkHold--;
        }
    }

    public static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
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
        bar(graphics, decay, layout, Meter.FUEL, reading.fuel(), fuelLow, look);
        bar(graphics, decay, layout, Meter.HULL, reading.hull(), hullLow, look);
        draw(
                graphics,
                decay,
                Part.CARGO,
                layout.cargo().area(),
                () -> loadGauge(graphics, layout, decay, reading, look));
        bar(graphics, decay, layout, Meter.DEPTH, reading.depth(), false, look);
        bar(graphics, decay, layout, Meter.HEAT, reading.heat(), false, look);
        titles(graphics, client.font, layout, look);
        if (sinkShown(pod) && decay.fault(Part.DEPTH) != Fault.DROP) {
            double bar = Math.min(
                    1.0, reading.sink() * look.sink().limit() / look.sink().segments());
            sinkReadout(graphics, client.font, layout, look, decay.shown(Part.DEPTH, bar));
        }
        draw(
                graphics,
                decay,
                Part.LAMPS,
                null,
                () -> lamps(
                        graphics,
                        layout.lamps(),
                        decayedLamps(decay, lampsLit(fuelLow, hullLow, pod.hullBurning(), reading.overloaded(), lines)),
                        new int[] {
                            look.lowColor(), look.lowColor(), look.heatColor(), look.depthColor(), look.lowColor()
                        },
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
     * The lamps as the decay lets them read: the fuel lamp and the alert lamp go dark or show a false state, and the hull lamp, the burning lamp and the overloaded
     * lamp never change, so a pilot who is dying or stuck in the dark still sees why.
     */
    public static boolean[] decayedLamps(CockpitDecay decay, boolean[] lit) {
        return new boolean[] {decay.lampShown(lit[0], 0), lit[1], lit[2], decay.lampShown(lit[3], 1), lit[4]};
    }

    /** The altimeter's reading as the decay lets it read: gone when the depth gauge is dark, its digits jumping when it jumps. Not seated in a pod, the reading as given; empty only on a drop-out of the depth gauge. */
    public static Optional<Component> altimeter(Minecraft client, int guiWidth, int guiHeight, Component reading) {
        PodEntity pod = seatedPod(client);
        if (pod == null || seated(client, guiWidth, guiHeight).isEmpty()) {
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

    /** {@code meter}'s bar at {@code value} in its colour, or the low colour when {@code low}, through {@link #draw} in the signal decay's frame. */
    private static void bar(
            GuiGraphicsExtractor graphics,
            CockpitDecay decay,
            Layout layout,
            Meter meter,
            double value,
            boolean low,
            CockpitLook look) {
        Gauge bar = meter.bar(layout);
        int color = low ? look.lowColor() : meter.color(look);
        draw(
                graphics,
                decay,
                meter.part(),
                bar.area(),
                () -> gauge(graphics, bar, decay.shown(meter.part(), value), color, look));
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

    /**
     * Which of the {@link #LAMPS} are lit: low fuel, low hull, the hull burning, any line of the readout that is a warning ({@link Kind#ALERT}), and a load the rotor
     * cannot climb with.
     */
    public static boolean[] lampsLit(
            boolean fuelLow, boolean hullLow, boolean burning, boolean overloaded, List<Line> readout) {
        return new boolean[] {
            fuelLow, hullLow, burning, readout.stream().anyMatch(line -> line.kind() == Kind.ALERT), overloaded
        };
    }

    /**
     * The load gauge: the load as a share of the bar, which runs to {@code segments / loadLimit} of the climb limit, in the gauge's colour under the limit and the limit
     * colour at and over it, and the limit's mark, a line in the limit colour through the gap before segment {@code loadLimit} and a pixel past the bar each side.
     */
    private static void loadGauge(
            GuiGraphicsExtractor graphics,
            Layout layout,
            CockpitDecay decay,
            CockpitReading reading,
            CockpitLook look) {
        Gauge gauge = layout.cargo();
        if (!gauge.on()) {
            return;
        }
        double share = Math.min(1.0, reading.load() * layout.loadLimit() / gauge.segments());
        gauge(
                graphics,
                gauge,
                decay.shown(Part.CARGO, share),
                reading.overloaded() ? look.lowColor() : look.cargoColor(),
                look);
        int left = gauge.area().left(graphics.guiWidth());
        int top = gauge.area().top(graphics.guiHeight());
        int mark = left + layout.loadLimit() * gauge.area().width() / gauge.segments() - 1;
        graphics.fill(mark, top - 1, mark + 1, top + gauge.area().height() + 1, look.lowColor());
    }

    /**
     * Where {@code gauge}'s title tab is: its icon, {@link CockpitLook#iconGap} and its title in the game's font (the terminal font's 6 pixels read as "I NAD" at GUI
     * scale 1), {@link CockpitLook#titleGap} inside the tab sprite's border, as tall as the font's capitals ({@link CockpitLook#titleTextHeight}); centred under
     * the bar and {@link Layout#titleY} under the band's top, so it rides the band's lower edge (on a narrow GUI, the joint of the band and the scanner's bezel).
     */
    public static Box titleBox(Font font, Layout layout, CockpitLook look, Meter gauge, int guiWidth, int guiHeight) {
        GuiSpriteScaling.NineSlice.Border border = spriteBorder(layout.sprite("tab"));
        int width = border.left()
                + border.right()
                + 2 * look.titleGap()
                + look.iconWidth()
                + look.iconGap()
                + font.width(gauge.title());
        int height = border.top() + border.bottom() + 2 * look.titleGap() + look.titleTextHeight();
        Decal area = gauge.bar(layout).area();
        int left = area.left(guiWidth) + Math.floorDiv(area.width() - width, 2);
        return new Box(left, layout.band().top(guiHeight) + layout.titleY(), width, height);
    }

    /** Each gauge's title: the cab's labels, not the signal's, so they stay when a gauge goes dark. */
    private static void titles(GuiGraphicsExtractor graphics, Font font, Layout layout, CockpitLook look) {
        GuiSpriteScaling.NineSlice.Border border = spriteBorder(layout.sprite("tab"));
        for (Meter gauge : Meter.values()) {
            if (!gauge.bar(layout).on()) {
                continue;
            }
            Box box = titleBox(font, layout, look, gauge, graphics.guiWidth(), graphics.guiHeight());
            CrtDraw.sprite(graphics, layout.sprite("tab"), box.left(), box.top(), box.width(), box.height());
            int x = box.left() + border.left() + look.titleGap();
            int y = box.top() + border.top() + look.titleGap();
            int color = gauge.color(look);
            icon(graphics, layout, look, gauge.icon(), x, y, color);
            graphics.text(font, gauge.title(), x + look.iconWidth() + look.iconGap(), y, color);
        }
    }

    /** {@code icon} at ({@code x}, {@code y}), its white tinted {@code color}. */
    private static void icon(
            GuiGraphicsExtractor graphics, Layout layout, CockpitLook look, Icon icon, int x, int y, int color) {
        graphics.blitSprite(
                RenderPipelines.GUI_TEXTURED,
                layout.sprite(icon.sprite()),
                x,
                y,
                look.iconWidth(),
                look.titleTextHeight(),
                color);
    }

    /** Whether the SINK plate is up: the pod is airborne and sank past the show share within the plate's hold time. */
    public static boolean sinkShown(PodEntity pod) {
        return sinkHold > 0 && !pod.onGround();
    }

    private static Component sinkTitleText() {
        return Component.translatable("hud.deepcharter.cockpit.sink");
    }

    /**
     * Where the SINK plate is: the title and the bar side by side on a tab, {@link CockpitLook#titleGap} past the tab sprite's
     * border, as tall as the taller of the font's capitals and the bar, centred under the band (so under the
     * altimeter's recess) and hung from its lower edge.
     */
    public static Box sinkBox(Font font, Layout layout, CockpitLook look, int guiWidth, int guiHeight) {
        GuiSpriteScaling.NineSlice.Border border = spriteBorder(layout.sprite("tab"));
        CockpitLook.SinkLook sink = look.sink();
        int width = font.width(sinkTitleText())
                + sink.barGap()
                + sink.barWidth()
                + border.left()
                + border.right()
                + 2 * look.titleGap();
        int height = Math.max(look.titleTextHeight(), sink.barHeight())
                + border.top()
                + border.bottom()
                + 2 * look.titleGap();
        Decal band = layout.band();
        return new Box(
                band.left(guiWidth) + (band.width() - width) / 2, band.top(guiHeight) + band.height(), width, height);
    }

    /**
     * The SINK readout's colour at a sink of {@code share} of the buffer: the hull gauge's, {@code ribbonWarn} from the
     * warning share, the warning colour past 1.
     */
    public static int sinkColor(double share, CockpitLook look) {
        if (share > 1.0) {
            return look.lowColor();
        }
        return share >= look.sink().warnShare() ? look.sink().warnColor() : look.hullColor();
    }

    /**
     * The SINK plate with its bar at {@code bar} (0 to 1, the full bar {@code segments / limit} of the buffer): its
     * title, and its bar lit to it with the buffer marked in the warning colour.
     */
    private static void sinkReadout(
            GuiGraphicsExtractor graphics, Font font, Layout layout, CockpitLook look, double bar) {
        Box box = sinkBox(font, layout, look, graphics.guiWidth(), graphics.guiHeight());
        CrtDraw.sprite(graphics, layout.sprite("tab"), box.left(), box.top(), box.width(), box.height());
        GuiSpriteScaling.NineSlice.Border border = spriteBorder(layout.sprite("tab"));
        CockpitLook.SinkLook sink = look.sink();
        int color = sinkColor(bar * sink.segments() / sink.limit(), look);
        int x = box.left() + border.left() + look.titleGap();
        int y = box.top() + border.top() + look.titleGap();
        int content = Math.max(look.titleTextHeight(), sink.barHeight());
        graphics.text(font, sinkTitleText(), x, y + (content - look.titleTextHeight()) / 2, color);
        int left = x + font.width(sinkTitleText()) + sink.barGap();
        int top = y + (content - sink.barHeight()) / 2;
        int lit = (int) Math.ceil(bar * sink.segments() - 1e-9);
        for (int i = 0; i < sink.segments(); i++) {
            int from = left + i * sink.barWidth() / sink.segments();
            int to = left + (i + 1) * sink.barWidth() / sink.segments() - 1;
            graphics.fill(from, top, to, top + sink.barHeight(), i < lit ? color : look.offColor());
        }
        int mark = left + sink.limit() * sink.barWidth() / sink.segments() - 1;
        graphics.fill(mark, top - 1, mark + 1, top + sink.barHeight() + 1, look.lowColor());
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

    /** One cell of a plate's row: its line, after {@code icon} when it has one, both in the line's colour. */
    public record Cell(Line line, @Nullable Icon icon) {
        int width(Font font, CockpitLook look) {
            return font.width(line.text()) + (icon == null ? 0 : look.iconWidth() + look.iconGap());
        }
    }

    /** One row of the plate: cells drawn side by side, {@link CockpitLook#tallyGap} apart. Only the tally's cells carry icons. */
    public record Row(List<Cell> cells) {
        public static Row of(Line line) {
            return new Row(List.of(new Cell(line, null)));
        }

        /** The first cell's line, whose kind is the row's. */
        public Line first() {
            return cells.getFirst().line();
        }

        public int width(Font font, CockpitLook look) {
            int width = look.tallyGap() * (cells.size() - 1);
            for (Cell cell : cells) {
                width += cell.width(font, look);
            }
            return width;
        }
    }

    /**
     * The rows of the plate: the tally on one row (hull, fuel and depth each in its gauge's colour after its gauge's icon, and the bay's ore count in the cargo colour
     * after the {@link Icon#CART}), and under it every other line of the readout, so the numbers stay where they are as lines come and go.
     */
    public static List<Row> rows(PodEntity pod, CockpitReading reading, CockpitLook look, List<Line> readout) {
        boolean fuelLow = PodFuel.isLow(pod.fuel());
        boolean hullLow = reading.hull() <= look.hullLowFraction();
        List<Row> rows = new ArrayList<>(List.of(new Row(List.of(
                new Cell(
                        new Line(
                                Component.translatable(
                                        "hud.deepcharter.pod.tally_hull",
                                        Math.round(pod.hull()),
                                        Math.round(pod.maxHull())),
                                hullLow ? look.lowColor() : look.hullColor()),
                        Meter.HULL.icon()),
                new Cell(
                        new Line(
                                Component.translatable("hud.deepcharter.pod.tally_fuel", Math.round(pod.fuel())),
                                fuelLow ? look.lowColor() : look.fuelColor()),
                        Meter.FUEL.icon()),
                new Cell(
                        new Line(
                                Component.translatable("hud.deepcharter.pod.tally_cargo", pod.cargoUsed()),
                                look.cargoColor()),
                        Icon.CART),
                new Cell(
                        new Line(
                                Component.translatable(
                                        "hud.deepcharter.pod.tally_depth",
                                        Altimeter.readingNumber(pod.level(), pod.getBlockY())),
                                look.depthColor()),
                        Meter.DEPTH.icon())))));
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
            if (kept.get(i).first().kind() == Kind.INFO) {
                kept.remove(i);
            }
        }
        return kept;
    }

    /** Where the plate is on the screen. */
    public record Box(int left, int top, int width, int height) {
        public HudKeepClear.Rect rect(String what) {
            return new HudKeepClear.Rect(what, left, top, width, height);
        }
    }

    /**
     * The space from the plate's edge to its text: the plate sprite's nine-slice border on each side, which the sprite's own metadata gives (so a pack that draws a
     * thicker border keeps its text clear of it), and {@link CockpitLook#plateGap} past it. A sprite that is not cut in nine has no border.
     */
    public static GuiSpriteScaling.NineSlice.Border plateInset(Layout layout, CockpitLook look) {
        GuiSpriteScaling.NineSlice.Border border = spriteBorder(layout.sprite("plate"));
        int gap = look.plateGap();
        return new GuiSpriteScaling.NineSlice.Border(
                border.left() + gap, border.top() + gap, border.right() + gap, border.bottom() + gap);
    }

    /** A GUI sprite's nine-slice border, from the sprite's own metadata; none for a sprite that is not cut in nine. */
    public static GuiSpriteScaling.NineSlice.Border spriteBorder(Identifier sprite) {
        GuiSpriteScaling scaling = Minecraft.getInstance()
                .getAtlasManager()
                .getAtlasOrThrow(AtlasIds.GUI)
                .getSprite(sprite)
                .contents()
                .getAdditionalMetadata(GuiMetadataSection.TYPE)
                .orElse(GuiMetadataSection.DEFAULT)
                .scaling();
        return scaling instanceof GuiSpriteScaling.NineSlice nine
                ? nine.border()
                : new GuiSpriteScaling.NineSlice.Border(0, 0, 0, 0);
    }

    /** The plate that holds {@code rows}, as big as its longest row and the number of rows, hung from the layout's readout anchor. */
    public static Box plateBox(
            Font font, Layout layout, CockpitLook look, List<Row> rows, int guiWidth, int guiHeight) {
        int widest = 0;
        for (Row row : rows) {
            widest = Math.max(widest, row.width(font, look));
        }
        GuiSpriteScaling.NineSlice.Border inset = plateInset(layout, look);
        int width = widest + inset.left() + inset.right();
        int height = rows.size() * rowPitch(font) - HudLook.current().podStatusLineGap() + inset.top() + inset.bottom();
        return new Box(layout.readout().left(guiWidth, width), layout.readout().top(guiHeight, height), width, height);
    }

    /**
     * The rows of {@code all} the plate shows on a GUI {@code guiWidth} x {@code guiHeight}: as many as fit under its anchor ({@link #fitting}), and fewer when the plate
     * would then reach down into one of {@code hotbar}, what vanilla draws along the bottom edge ({@link SeatedHotbar#slots}), which a narrow GUI puts under the plate's
     * columns (#1457). A warning is never dropped, so a pile of warnings can still reach it.
     */
    public static List<Row> plateRows(
            Font font,
            Layout layout,
            CockpitLook look,
            List<Row> all,
            List<HudKeepClear.Rect> hotbar,
            int guiWidth,
            int guiHeight) {
        List<Row> rows = fitting(all, roomForRows(font, layout, look, guiHeight, guiHeight));
        HudKeepClear.Rect plate =
                plateBox(font, layout, look, rows, guiWidth, guiHeight).rect("plate");
        OptionalInt floor = hotbar.stream()
                .filter(plate::overlaps)
                .mapToInt(HudKeepClear.Rect::y)
                .min();
        return floor.isEmpty() ? rows : fitting(all, roomForRows(font, layout, look, guiHeight, floor.getAsInt()));
    }

    /** How many rows the plate has room for between its anchor on a GUI {@code guiHeight} high and {@code bottom}. */
    private static int roomForRows(Font font, Layout layout, CockpitLook look, int guiHeight, int bottom) {
        GuiSpriteScaling.NineSlice.Border inset = plateInset(layout, look);
        int room = bottom
                - layout.readout().top(guiHeight, 0)
                - inset.top()
                - inset.bottom()
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
                : jumped(
                        decay,
                        plateRows(
                                font,
                                layout,
                                look,
                                all,
                                SeatedHotbar.slots(client, graphics.guiWidth(), graphics.guiHeight()),
                                graphics.guiWidth(),
                                graphics.guiHeight()));
        Box box = plateBox(font, layout, look, rows, graphics.guiWidth(), graphics.guiHeight());
        CrtDraw.sprite(graphics, layout.sprite("plate"), box.left(), box.top(), box.width(), box.height());
        GuiSpriteScaling.NineSlice.Border inset = plateInset(layout, look);
        int y = box.top() + inset.top();
        for (Row row : rows) {
            int x = box.left() + inset.left();
            for (Cell cell : row.cells()) {
                Line line = cell.line();
                Icon icon = cell.icon();
                if (icon != null) {
                    icon(graphics, layout, look, icon, x, y, line.color());
                    x += look.iconWidth() + look.iconGap();
                }
                graphics.text(font, line.text(), x, y, line.color());
                x += font.width(line.text()) + look.tallyGap();
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
                .map(row -> new Row(row.cells().stream()
                        .map(cell -> new Cell(
                                new Line(
                                        decay.garbled(Part.PLATE, cell.line().text()),
                                        cell.line().color()),
                                cell.icon()))
                        .toList()))
                .toList();
    }
}
