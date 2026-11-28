package io.github.pkeppeler.deepcharter.client.repair;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.client.charter.ClientCharter;
import io.github.pkeppeler.deepcharter.client.sound.TypewriterSound;
import io.github.pkeppeler.deepcharter.client.terminal.ShownPod;
import io.github.pkeppeler.deepcharter.client.terminal.TerminalViewScreen;
import io.github.pkeppeler.deepcharter.client.theme.PanelLook;
import io.github.pkeppeler.deepcharter.client.ui.AboutTip;
import io.github.pkeppeler.deepcharter.client.ui.CrtButton;
import io.github.pkeppeler.deepcharter.client.ui.CrtDraw;
import io.github.pkeppeler.deepcharter.client.ui.CrtScreen;
import io.github.pkeppeler.deepcharter.client.ui.CrtText;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;
import io.github.pkeppeler.deepcharter.client.ui.HoveredItemScreen;
import io.github.pkeppeler.deepcharter.client.ui.ScrollList;
import io.github.pkeppeler.deepcharter.client.ui.Typewriter;
import io.github.pkeppeler.deepcharter.fuel.EngineSoot;
import io.github.pkeppeler.deepcharter.fuel.FuelTuning;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.repair.FlueScrape;
import io.github.pkeppeler.deepcharter.repair.RepairRegistry;
import io.github.pkeppeler.deepcharter.repair.RepairStation;
import io.github.pkeppeler.deepcharter.repair.RepairTuning;
import io.github.pkeppeler.deepcharter.suit.SuitRegistry;
import io.github.pkeppeler.deepcharter.suit.SuitShop;
import io.github.pkeppeler.deepcharter.suit.SuitTrack;
import io.github.pkeppeler.deepcharter.suit.SuitTracks;
import io.github.pkeppeler.deepcharter.terminal.TerminalActionPayload;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.TerminalView;
import io.github.pkeppeler.deepcharter.terminal.TerminalWares;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The online repair station: the hull repair buttons, then the shop, in one list under the header. The list is longer than a
 * small screen, so it scrolls: only the rows that fit are buttons, and the mouse wheel or Page Up and Page Down move the
 * list. The screen never decides anything: it sends the press and the server checks it, charges the account and answers with
 * a new {@link TerminalView}.
 */
public final class RepairStationScreen extends CrtScreen implements TerminalViewScreen, HoveredItemScreen {
    private static final int MARGIN = 24;
    private static final int COLUMN_WIDTH = 190;
    private static final int BUTTON_HEIGHT = 20;
    private static final int GAP = 6;
    /** On the machine panel the content is 16 pixels shorter than the plain CRT's, so the rows are 16 high (a face of 12 for capitals 6 high) and 4 apart: four still fit. */
    private static final int PANEL_BUTTON_HEIGHT = 16;

    private static final int PANEL_ROW_GAP = 4;
    private static final int CLOSE_WIDTH = 90;
    private static final int[] REPAIR_STEPS = {10, 25, 50};

    /**
     * One button of the list: what it says, the action it sends, whether it can be pressed (a track at its top tier
     * cannot), what the ware does, which the tip over the list shows while the player points at it (#1645), and the item
     * it sells, whose sheet the spec sheet key opens (#1651), or null when it sells none.
     */
    private record Row(
            Component label,
            Identifier action,
            CompoundTag args,
            boolean active,
            Optional<Component> about,
            @Nullable Item item) {
        /** A row that can be pressed and says what its ware does. */
        Row(Component label, Identifier action, CompoundTag args, Component about) {
            this(label, action, args, true, Optional.of(about), null);
        }

        /** A row that sells {@code item}: it can be pressed, says what the item does, and the spec sheet key opens its sheet. */
        static Row sells(Component label, Identifier action, CompoundTag args, Component about, Item item) {
            return new Row(label, action, args, true, Optional.of(about), item);
        }

        /** A row that cannot be pressed now, but still says what its ware does. */
        static Row inactive(Component label, Identifier action, CompoundTag args, Component about) {
            return new Row(label, action, args, false, Optional.of(about), null);
        }

        /** A row that sells nothing and says nothing: it only reports that the suit's log cannot be read. */
        static Row silent(Component label, Identifier action, CompoundTag args) {
            return new Row(label, action, args, false, Optional.empty(), null);
        }
    }

    /** One line of text beside the list, where it is drawn: in the theme's phosphor colour, or its dim colour when {@code dim}. */
    public record TextLine(String text, int x, int y, boolean dim) {}

    private TerminalView view;
    /** The labels of the rows as the list was last built, so {@link #tick} rebuilds it only when one changes. */
    private List<Component> builtLabels = List.of();
    /** The shown buttons of the rows that sell an item, and the item, as the list was last built. */
    private final Map<CrtButton, Item> rowItems = new HashMap<>();

    private final ScrollList scroll =
            new ScrollList("screen.deepcharter.repair.scroll", this::rowCount, this::visibleRows, this::rebuildWidgets);
    private final Typewriter typewriter;

    public RepairStationScreen(TerminalView view) {
        super(Component.translatable(TerminalTypes.REPAIR_STATION.block().getDescriptionId()));
        this.view = view;
        this.typewriter = typewriter(Component.translatable("screen.deepcharter.repair.intro"), new TypewriterSound());
    }

    @Override
    public boolean accepts(TerminalView other) {
        return view.pos().equals(other.pos()) && view.type().equals(other.type());
    }

    @Override
    public void update(TerminalView newer) {
        view = newer;
        rebuildWidgets();
    }

    @Override
    public Typewriter typewriter() {
        return typewriter;
    }

    /**
     * The soot rows change with no answer from the server (the soot, the balance and the scrape's countdown are
     * synced), so the list is rebuilt when a label it shows has changed.
     */
    @Override
    public void tick() {
        super.tick();
        List<Component> labels = rows().stream().map(Row::label).toList();
        if (!labels.equals(builtLabels)) {
            rebuildWidgets();
        }
    }

    private List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        for (int hp : REPAIR_STEPS) {
            long cost = hp * RepairTuning.DEFAULT.repairCostPerHp();
            CompoundTag args = new CompoundTag();
            args.putInt(RepairStation.HP_KEY, hp);
            rows.add(new Row(
                    Component.translatable("screen.deepcharter.repair.repair", hp, cost),
                    RepairStation.REPAIR,
                    args,
                    about(RepairStation.REPAIR)));
        }
        rows.add(new Row(
                Component.translatable("screen.deepcharter.repair.total"),
                RepairStation.REPAIR_TOTAL,
                new CompoundTag(),
                about(RepairStation.REPAIR_TOTAL)));
        rows.addAll(sootRows());
        rows.addAll(suitRows());
        for (Consumable consumable : Consumable.values()) {
            CompoundTag args = new CompoundTag();
            args.putString(RepairStation.ITEM_KEY, consumable.itemId().toString());
            Item item = RepairRegistry.item(consumable);
            String name = new ItemStack(item).getHoverName().getString().toUpperCase(Locale.ROOT);
            Component label = consumable.perPurchase() > 1
                    ? Component.translatable(
                            "screen.deepcharter.repair.buy_pack", name, consumable.perPurchase(), consumable.price())
                    : Component.translatable("screen.deepcharter.repair.buy", name, consumable.price());
            rows.add(Row.sells(label, RepairStation.BUY, args, about(consumable), item));
        }
        return rows;
    }

    /**
     * The rows of the engine's soot (#1280), from the synced attachments and the charter's balance. The service row
     * shows once the nearest pod has a litre or more: what the account's money clears and costs, or, short of a litre's
     * price, the price and nothing to press. The scrape row shows only while the pod is in limp mode at the colony,
     * counting down while a scrape runs.
     */
    private List<Row> sootRows() {
        List<Row> rows = new ArrayList<>();
        Optional<PodEntity> shown = nearbyPod();
        long soot = shown.map(pod -> Math.round(EngineSoot.soot(pod))).orElse(0);
        if (soot < 1) {
            return rows;
        }
        PodEntity pod = shown.get();
        long account = ClientCharter.view().map(charter -> charter.balance()).orElse(0L);
        rows.add(EngineSoot.quote(pod, account)
                .map(quote -> new Row(
                        quote.all()
                                ? Component.translatable("screen.deepcharter.repair.service", soot, quote.cost())
                                : Component.translatable(
                                        "screen.deepcharter.repair.service_part", quote.litres(), soot, quote.cost()),
                        RepairStation.SERVICE_ENGINE,
                        new CompoundTag(),
                        about(RepairStation.SERVICE_ENGINE)))
                .orElseGet(() -> Row.inactive(
                        Component.translatable(
                                "screen.deepcharter.repair.service_unpaid",
                                FuelTuning.DEFAULT.soot().servicePerLitre()),
                        RepairStation.SERVICE_ENGINE,
                        new CompoundTag(),
                        about(RepairStation.SERVICE_ENGINE))));
        if (EngineSoot.limpAtColony(pod)) {
            rows.add(FlueScrape.secondsLeft(pod)
                    .map(seconds -> Row.inactive(
                            Component.translatable("screen.deepcharter.repair.scraping", seconds),
                            RepairStation.SCRAPE_FLUE,
                            new CompoundTag(),
                            about(RepairStation.SCRAPE_FLUE)))
                    .orElseGet(() -> new Row(
                            Component.translatable("screen.deepcharter.repair.scrape"),
                            RepairStation.SCRAPE_FLUE,
                            new CompoundTag(),
                            about(RepairStation.SCRAPE_FLUE))));
        }
        return rows;
    }

    /**
     * One row for each suit track (#1064): the next tier and its price, from the tracks the server sent, or the track's name and "(TOP)"
     * with nothing to press once it is fully fitted. A suit log that cannot be read shows one inactive row that says so.
     */
    private static List<Row> suitRows() {
        LocalPlayer player = Minecraft.getInstance().player;
        List<Row> rows = new ArrayList<>();
        if (player == null) {
            return rows;
        }
        Optional<SuitTracks> held = Versioned.readable(player, SuitRegistry.TRACKS);
        if (held.isEmpty()) {
            rows.add(Row.silent(
                    Component.translatable("hud.deepcharter.suit.unreadable"), SuitShop.SUIT_BUY, new CompoundTag()));
            return rows;
        }
        for (SuitTrack track : SuitTrack.values()) {
            Component name = Component.translatable("hud.deepcharter.suit.track." + track.id());
            int current = held.get().tier(track);
            if (current >= track.maxTier()) {
                rows.add(Row.inactive(
                        Component.translatable("screen.deepcharter.repair.suit_top", name),
                        SuitShop.SUIT_BUY,
                        new CompoundTag(),
                        about(track)));
                continue;
            }
            CompoundTag args = new CompoundTag();
            args.putString(SuitShop.TRACK_KEY, track.id());
            args.putInt(SuitShop.TIER_KEY, current + 1);
            rows.add(new Row(
                    Component.translatable(
                            "screen.deepcharter.repair.suit",
                            name,
                            current + 1,
                            String.format(Locale.ROOT, "%,d", track.price(current + 1))),
                    SuitShop.SUIT_BUY,
                    args,
                    about(track)));
        }
        return rows;
    }

    /** What a ware does: the line {@code TerminalWares} holds for it, the spec sheet's own for an item. */
    private static Component about(Identifier action) {
        return TerminalWares.about(TerminalTypes.REPAIR_STATION, RepairStation.ware(action));
    }

    private static Component about(Consumable consumable) {
        return TerminalWares.about(TerminalTypes.REPAIR_STATION, RepairStation.ware(consumable));
    }

    private static Component about(SuitTrack track) {
        return TerminalWares.about(TerminalTypes.REPAIR_STATION, SuitShop.ware(track));
    }

    private static int buttonHeight() {
        return PanelLook.drawn() ? PANEL_BUTTON_HEIGHT : BUTTON_HEIGHT;
    }

    private static int rowGap() {
        return PanelLook.drawn() ? PANEL_ROW_GAP : GAP;
    }

    /** The y where the intro starts, below the header. */
    private int introTop() {
        return contentTop(MARGIN) + font.lineHeight + 14;
    }

    private int lineStep() {
        return font.lineHeight + CrtTuning.current().lineSpacing();
    }

    /** The intro as wrapped once it has typed out in full, one entry per line, where each is drawn. */
    public List<TextLine> introLines() {
        List<String> wrapped = CrtText.wrap(font, typewriter.text(), contentWidth(MARGIN));
        List<TextLine> lines = new ArrayList<>();
        for (int i = 0; i < wrapped.size(); i++) {
            lines.add(new TextLine(wrapped.get(i), contentLeft(MARGIN), introTop() + i * lineStep(), false));
        }
        return lines;
    }

    /** The y below the hull line once the intro has typed out in full, so the list does not move while it types. */
    private int headerBottom() {
        int introBottom = introTop() + Math.max(introLines().size(), 1) * lineStep();
        return introBottom + 2 * (GAP + font.lineHeight);
    }

    /** The y where the first row starts. */
    public int listTop() {
        return headerBottom() + GAP;
    }

    /** CLOSE sits near the bottom edge, so that the list gets the space above it. */
    private int closeY() {
        return contentBottom(GAP) - buttonHeight();
    }

    /** How many rows fit between the header and CLOSE, at least one. */
    public int visibleRows() {
        int space = closeY() - rowGap() - listTop();
        return Math.max(1, (space + rowGap()) / (buttonHeight() + rowGap()));
    }

    /** All the rows of the list, shown or not. */
    public int rowCount() {
        return rows().size();
    }

    @Override
    protected void layout() {
        List<Row> rows = rows();
        builtLabels = rows.stream().map(Row::label).toList();
        rowItems.clear();
        int visible = visibleRows();
        scroll.beginLayout();
        int firstRow = scroll.first();
        for (int i = firstRow; i < Math.min(rows.size(), firstRow + visible); i++) {
            Row row = rows.get(i);
            CrtButton button = new CrtButton(
                    contentLeft(MARGIN),
                    listTop() + (i - firstRow) * (buttonHeight() + rowGap()),
                    COLUMN_WIDTH,
                    buttonHeight(),
                    row.label(),
                    pressed ->
                            ClientPlayNetworking.send(new TerminalActionPayload(view.pos(), row.action(), row.args())));
            row.about().ifPresent(button::about);
            button.active = row.active();
            addRenderableWidget(scroll.shown(button));
            Item item = row.item();
            if (item != null) {
                rowItems.put(button, item);
            }
        }
        addRenderableWidget(new CrtButton(
                contentLeft(MARGIN),
                closeY(),
                CLOSE_WIDTH,
                buttonHeight(),
                Component.translatable("screen.deepcharter.terminal.close"),
                button -> onClose()));
    }

    @Override
    public @Nullable Item hoveredItem() {
        for (Map.Entry<CrtButton, Item> row : rowItems.entrySet()) {
            if (row.getKey().visible && row.getKey().isHovered()) {
                return row.getValue();
            }
        }
        return null;
    }

    /** The account line, the hull line and, when the list is longer than the screen, which rows are shown, as drawn. */
    public List<TextLine> textLines() {
        String account = ClientCharter.view()
                .map(charter -> Component.translatable("screen.deepcharter.terminal.account", charter.balance())
                        .getString())
                .orElse("");
        Component hull = nearbyPod()
                .map(pod -> Component.translatable(
                        "screen.deepcharter.repair.hull", Math.round(pod.hull()), Math.round(pod.maxHull())))
                .orElse(Component.translatable("screen.deepcharter.repair.no_pod"));
        int hullY = headerBottom() - font.lineHeight;
        List<TextLine> lines = new ArrayList<>();
        lines.add(new TextLine(account, contentLeft(MARGIN), hullY - GAP - font.lineHeight, false));
        lines.add(new TextLine(hull.getString().toUpperCase(Locale.ROOT), contentLeft(MARGIN), hullY, false));
        scroll.hint()
                .ifPresent(hint -> lines.add(new TextLine(
                        hint,
                        contentLeft(MARGIN) + CLOSE_WIDTH + GAP,
                        closeY() + (buttonHeight() - font.lineHeight) / 2,
                        true)));
        return lines;
    }

    /** The narrator also tells which rows are shown, so that a player who cannot see the screen knows that there are more. */
    @Override
    public Component getNarrationMessage() {
        return scroll.narrate(super.getNarrationMessage());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return scroll.mouseScrolled(scrollY) || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        return scroll.keyPressed(event, this) || super.keyPressed(event);
    }

    /** The pod the screen shows: the one the server will repair. */
    private Optional<PodEntity> nearbyPod() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return Optional.empty();
        }
        return ShownPod.of(client.level, view);
    }

    /** The content area, which a row's tip stays inside. */
    public AboutTip.Bounds tipBounds() {
        return new AboutTip.Bounds(
                contentLeft(MARGIN),
                contentTop(MARGIN),
                contentRight(MARGIN),
                contentBottom(MARGIN),
                introTop(),
                introTop() + Math.max(introLines().size(), 1) * lineStep());
    }

    /** The readouts the tip must not cover: the account, the hull and, while the list scrolls, which rows are shown. */
    public List<AboutTip.Rect> tipReadouts() {
        return textLines().stream()
                .map(line -> AboutTip.Rect.ofText(font, line.text(), line.x(), line.y()))
                .toList();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        CrtTuning tuning = CrtTuning.current();
        CrtDraw.header(
                graphics,
                font,
                title.getString().toUpperCase(Locale.ROOT),
                contentLeft(MARGIN),
                contentTop(MARGIN),
                contentRight(MARGIN));
        drawTypewriter(graphics, typewriter, contentLeft(MARGIN), introTop(), contentWidth(MARGIN));
        for (TextLine line : textLines()) {
            CrtDraw.glowText(
                    graphics,
                    font,
                    line.text(),
                    line.x(),
                    line.y(),
                    line.dim() ? PanelLook.dimText(tuning.dimColor()) : tuning.phosphorColor());
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        AboutTip.draw(graphics, font, this, tipBounds(), tipReadouts(), mouseX, mouseY);
    }
}
