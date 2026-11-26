package io.github.pkeppeler.deepcharter.client.upgrade;

import io.github.pkeppeler.deepcharter.client.charter.ClientCharter;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueState;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.RecipeConsoleScreen;
import io.github.pkeppeler.deepcharter.client.sound.TypewriterSound;
import io.github.pkeppeler.deepcharter.client.terminal.TerminalViewScreen;
import io.github.pkeppeler.deepcharter.client.theme.PanelLook;
import io.github.pkeppeler.deepcharter.client.ui.CrtButton;
import io.github.pkeppeler.deepcharter.client.ui.CrtDraw;
import io.github.pkeppeler.deepcharter.client.ui.CrtScreen;
import io.github.pkeppeler.deepcharter.client.ui.CrtText;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;
import io.github.pkeppeler.deepcharter.client.ui.ScrollList;
import io.github.pkeppeler.deepcharter.client.ui.Typewriter;
import io.github.pkeppeler.deepcharter.terminal.TerminalActionPayload;
import io.github.pkeppeler.deepcharter.terminal.TerminalView;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.github.pkeppeler.deepcharter.upgrade.PartUnits;
import io.github.pkeppeler.deepcharter.upgrade.TrackOffer;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeTerminal;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeTuning;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.locale.Language;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * The online screen of the upgrade terminal. The left column lists the tracks, closing their rows up to fit the screen,
 * with the tier now installed. The right column says what the chosen track does, what the pod has fitted and what the
 * next tier gains in numbers (#1440, {@link TrackOffer}), then lists the tiers for sale with their prices. The crafted
 * tier 1 is not a row: a track that waits for it shows its next purchase locked and a line naming the unit, which opens
 * the unit's recipe. A tier above the pod's cap is marked as working at the cap, so the player pays knowing it. The
 * screen decides nothing: the server checks every purchase, and answers with a new {@link UpgradeView}.
 *
 * <p>It asks the server for the parked pod when it opens, because the generic {@link TerminalView} carries none.
 */
public final class UpgradeScreen extends CrtScreen implements TerminalViewScreen {
    private static final int MARGIN = 16;
    /**
     * The rows are this far apart. The track list scrolls when the tracks do not fit under the header, so a part track added later never squeezes the rows: only the
     * tracks that fit are buttons, and the mouse wheel, Page Up and Page Down, or Down and Tab from the last row, move the list.
     */
    private static final int PITCH = 16;
    /** The plain CRT outlines each button, so its rows need a gap; a panel button has an edge of its own and its rows touch, which gives each label the room. */
    private static final int ROW_GAP = 2;

    private static final int LIST_TOP = 62;
    /** A label's room in its button: {@code buttonPad} and {@link PanelLook#LABEL_MARGIN}, 4 each side. */
    private static final int LABEL_ROOM = 8;

    private static final int TRACK_WIDTH = 104;
    private static final int TIER_MAX_WIDTH = 250;
    private static final int COLUMN_GAP = 6;
    private static final int CLOSE_WIDTH = 70;

    /** Space between the text over the tiers and the first tier. */
    private static final int TEXT_GAP = 2;

    /** A line the screen draws itself, where the last layout put it. */
    private record Text(String text, int x, int y) {}

    private TerminalView view;
    private ComponentTrack selected = ComponentTrack.values()[0];
    /** Where the scroll hint is drawn, beside Close: set by the last layout. */
    private int hintX;

    private int hintY;
    /** The chosen track's description, fitted part, next gain and note: set by the last layout. */
    private List<Text> texts = List.of();

    private final ScrollList scroll = new ScrollList(
            "screen.deepcharter.upgrade.scroll",
            () -> ComponentTrack.values().length,
            this::visibleTracks,
            this::rebuildWidgets);
    private final Typewriter typewriter;

    public UpgradeScreen(TerminalView view) {
        super(Component.translatable("block.deepcharter.upgrade_terminal"));
        this.view = view;
        this.typewriter = typewriter(Component.translatable("screen.deepcharter.upgrade.intro"), new TypewriterSound());
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

    /** The parked pod as the server sent it with the view. A repaired upgrade terminal carries it; the screen draws "no pod" if it is missing. */
    public Optional<UpgradeView> upgrade() {
        return view.feature(UpgradeView.class);
    }

    @Override
    public Typewriter typewriter() {
        return typewriter;
    }

    /** The y of the first row: the same distance below the top of the content as before the panel. */
    private int listTop() {
        return contentTop(MARGIN) + LIST_TOP - MARGIN;
    }

    @Override
    protected void layout() {
        int left = contentLeft(MARGIN);
        int rowGap = PanelLook.drawn() ? 0 : ROW_GAP;
        int rowHeight = PITCH - rowGap;
        int visible = visibleTracks();
        scroll.beginLayout();
        texts = new ArrayList<>();
        int trackWidth = Math.max(TRACK_WIDTH, widestTrackLabel() + LABEL_ROOM);
        int tierX = left + trackWidth + COLUMN_GAP;
        int tierWidth = Math.min(contentRight(MARGIN) - tierX, TIER_MAX_WIDTH);
        // Close sits under the tallest column a track can have, so it stays put whichever track is chosen and however
        // many
        // tracks the left column holds.
        int closeY = Arrays.stream(ComponentTrack.values())
                        .mapToInt(track -> rowsTop(track, tierWidth) + Math.max(track.maxTier() - 1, 2) * PITCH)
                        .max()
                        .orElse(listTop())
                + rowGap;
        hintX = tierX + CLOSE_WIDTH + COLUMN_GAP;
        hintY = closeY + (rowHeight - font.lineHeight) / 2;
        addRenderableWidget(new CrtButton(
                tierX,
                closeY,
                CLOSE_WIDTH,
                rowHeight,
                Component.translatable("screen.deepcharter.terminal.close"),
                button -> onClose()));
        Optional<UpgradeView.Pod> shownPod = upgrade().flatMap(UpgradeView::pod);
        if (shownPod.isEmpty()) {
            return;
        }
        UpgradeView.Pod pod = shownPod.get();
        for (UpgradeView.Slot slot : pod.slots()) {
            ComponentTrack track = slot.track();
            Component label =
                    Component.translatable("screen.deepcharter.upgrade.track", trackName(track), slot.installed());
            if (track.ordinal() < scroll.first() || track.ordinal() >= scroll.first() + visible) {
                continue;
            }
            addRenderableWidget(scroll.shown(new CrtButton(
                            left,
                            listTop() + (track.ordinal() - scroll.first()) * PITCH,
                            trackWidth,
                            rowHeight,
                            label,
                            pressed -> select(track))
                    .marked(track == selected)));
        }
        TrackOffer offer = TrackOffer.of(pod, selected);
        int y = listTop();
        for (String line : about(selected, tierWidth)) {
            texts.add(new Text(line, tierX, y));
            y += lineStep();
        }
        texts.add(new Text(offer.fittedLine().getString(), tierX, y));
        y += lineStep();
        Optional<Component> next = offer.nextLine();
        if (next.isPresent()) {
            texts.add(new Text(next.get().getString(), tierX, y));
        }
        int row = rowsTop(selected, tierWidth);
        long balance = ClientCharter.view().map(charter -> charter.balance()).orElse(0L);
        for (int tier : offer.forSale()) {
            long price = UpgradeTuning.DEFAULT.price(selected, tier);
            Component label = Component.translatable("screen.deepcharter.upgrade.buy", tier, price, offer.capped(tier));
            ComponentTrack track = selected;
            CrtButton button = addRenderableWidget(
                    new CrtButton(tierX, row, tierWidth, rowHeight, label, pressed -> buy(track, tier)));
            // An unregistered pod (no serial) takes no parts: they would be void.
            button.active = balance >= price && !pod.serial().isEmpty();
            row += PITCH;
        }
        if (offer.lock() == TrackOffer.Lock.FIT_FIRST) {
            int tier = offer.next().orElseThrow();
            CrtButton locked = addRenderableWidget(new CrtButton(
                    tierX,
                    row,
                    tierWidth,
                    rowHeight,
                    Component.translatable(
                            "screen.deepcharter.upgrade.locked", tier, UpgradeTuning.DEFAULT.price(selected, tier)),
                    pressed -> {}));
            locked.active = false;
            row += PITCH;
        }
        Optional<Component> unlock = offer.lockLine();
        if (unlock.isPresent()) {
            ComponentTrack track = selected;
            CrtButton recipe = addRenderableWidget(
                    new CrtButton(tierX, row, tierWidth, rowHeight, unlock.get(), pressed -> openRecipe(track)));
            // The recipe console needs room of its own; a screen too small for it keeps the line as words.
            recipe.active = RecipeConsoleScreen.fits(width, height);
            row += PITCH;
        }
        int noteY = row + rowGap;
        for (String line : note()) {
            texts.add(new Text(line, tierX, noteY));
            noteY += font.lineHeight;
        }
    }

    /**
     * The y of the first row of the track's tiers: under its description, the fitted part and the next purchase's gain.
     */
    private int rowsTop(ComponentTrack track, int tierWidth) {
        return listTop() + (about(track, tierWidth).size() + 2) * lineStep() + TEXT_GAP;
    }

    /** The track's description, wrapped to the tier column. */
    private List<String> about(ComponentTrack track, int tierWidth) {
        return CrtText.wrap(
                font,
                Component.translatable("screen.deepcharter.upgrade.about." + track.id())
                        .getString(),
                tierWidth);
    }

    private int lineStep() {
        return font.lineHeight + CrtTuning.current().lineSpacing();
    }

    /** How many tracks fit between the header and the bottom of the content, at least one and at most all of them. */
    public int visibleTracks() {
        int space = contentBottom(MARGIN) - listTop() + (PanelLook.drawn() ? 0 : ROW_GAP);
        return Mth.clamp(space / PITCH, 1, ComponentTrack.values().length);
    }

    /** The narrator also tells which tracks are shown, so that a player who cannot see the screen knows that there are more. */
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

    /** The widest track label in the terminal font, so that its button can leave the label its room however the names are worded. */
    private int widestTrackLabel() {
        return Arrays.stream(ComponentTrack.values())
                .mapToInt(track -> CrtText.width(
                        font,
                        Component.translatable("screen.deepcharter.upgrade.track", trackName(track), track.maxTier())
                                .getString()))
                .max()
                .orElse(0);
    }

    private static String trackName(ComponentTrack track) {
        return Component.translatable("deepcharter.upgrade.track." + track.id()).getString();
    }

    private void select(ComponentTrack track) {
        selected = track;
        rebuildWidgets();
    }

    /** Opens the recipe of the track's crafted unit over this screen, which comes back when the console closes. */
    private void openRecipe(ComponentTrack track) {
        Minecraft.getInstance()
                .gui
                .setScreen(new RecipeConsoleScreen(this, PartUnits.unit(track), CatalogueState.Mode.RECIPES));
    }

    private void buy(ComponentTrack track, int tier) {
        CompoundTag args = new CompoundTag();
        args.putString(UpgradeTerminal.TRACK_KEY, track.id());
        args.putInt(UpgradeTerminal.TIER_KEY, tier);
        ClientPlayNetworking.send(new TerminalActionPayload(view.pos(), UpgradeTerminal.BUY, args));
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
        int below = drawTypewriter(
                graphics,
                typewriter,
                contentLeft(MARGIN),
                contentTop(MARGIN) + font.lineHeight + 10,
                contentWidth(MARGIN));
        CrtDraw.glowText(graphics, font, status(), contentLeft(MARGIN), below, tuning.phosphorColor());
        scroll.hint().ifPresent(hint -> CrtDraw.glowText(graphics, font, hint, hintX, hintY, tuning.phosphorColor()));
        for (Text text : texts) {
            CrtDraw.glowText(graphics, font, text.text(), text.x(), text.y(), tuning.phosphorColor());
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    /** What the chosen track's top tier does that its price does not say, one string for each line; none for a track with no note (#712: the heat shield's pack-first order). */
    private List<String> note() {
        String key = "screen.deepcharter.upgrade.note." + selected.id();
        if (!Language.getInstance().has(key)) {
            return List.of();
        }
        return List.of(Component.translatable(key).getString().split("\n"));
    }

    /** The pod, its tier cap and the account, or why there is no pod. */
    private String status() {
        Optional<UpgradeView> shown = upgrade();
        if (shown.isEmpty() || shown.get().pod().isEmpty()) {
            return Component.translatable(
                            shown.isPresent() && shown.get().foreignPod()
                                    ? "screen.deepcharter.upgrade.foreign_pod"
                                    : "screen.deepcharter.upgrade.no_pod")
                    .getString();
        }
        String account = ClientCharter.view()
                .map(charter -> Component.translatable("screen.deepcharter.terminal.account", charter.balance())
                        .getString())
                .orElse("");
        UpgradeView.Pod pod = shown.get().pod().get();
        Component line = pod.serial().isEmpty()
                ? Component.translatable("screen.deepcharter.upgrade.pod_unregistered", pod.cap())
                : Component.translatable("screen.deepcharter.upgrade.pod", pod.serial(), pod.cap());
        return line.getString() + "   " + account;
    }
}
