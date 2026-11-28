package io.github.pkeppeler.deepcharter.client.transmission;

import io.github.pkeppeler.deepcharter.client.ui.CrtButton;
import io.github.pkeppeler.deepcharter.client.ui.CrtDraw;
import io.github.pkeppeler.deepcharter.client.ui.CrtScreen;
import io.github.pkeppeler.deepcharter.client.ui.CrtText;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;
import io.github.pkeppeler.deepcharter.client.ui.ScrollList;
import io.github.pkeppeler.deepcharter.transmission.Transmission;
import io.github.pkeppeler.deepcharter.transmission.TransmissionCatalog;
import io.github.pkeppeler.deepcharter.transmission.TransmissionLogPayload;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * The comms log (#1615): every transmission the charter has sent this player, oldest first, each row saying when it
 * came. The chosen one's header and text read below the list, the wheel scrolling a long text, and REPLAY closes the
 * log and types it out again with its sounds, as it came. The rows are in the transmissions channel and nowhere else
 * (SPEC section 5): the log only shows what the server says has reached the player, so it spoils nothing, and a garbled
 * text is the same text it was.
 */
public final class TransmissionLogScreen extends CrtScreen {
    private static final int MARGIN = 24;
    private static final int ROW_HEIGHT = 14;
    private static final int ROW_GAP = 2;
    private static final int GAP = 8;
    private static final int BUTTON_WIDTH = 70;
    private static final int BUTTON_HEIGHT = 16;
    /** At most this many rows of the list are shown, so the chosen text keeps most of the screen. */
    private static final int MAX_ROWS = 5;

    private final TransmissionLogPayload log;
    private final List<Transmission> entries;
    private final ScrollList scroll = new ScrollList(
            "screen.deepcharter.transmission_log.rows", this::rowCount, this::visibleRows, this::rebuildWidgets);
    private int chosen;
    /** The first line of the chosen text drawn, which the wheel over the text moves. */
    private int textLine;

    public TransmissionLogScreen(TransmissionLogPayload log) {
        super(
                log.charter().isEmpty()
                        ? Component.translatable("screen.deepcharter.transmission_log.title")
                        : Component.translatable("screen.deepcharter.transmission_log.title_charter", log.charter()));
        this.log = log;
        // The client's catalog may lack an id a newer server sends: such a row is left out, never a throw.
        this.entries = log.received().stream()
                .map(TransmissionCatalog::find)
                .flatMap(Optional::stream)
                .toList();
        this.chosen = Math.max(0, entries.size() - 1);
        scroll.scrollTo(chosen);
    }

    /** The transmissions listed, oldest first. */
    // test-seam: the comms log test checks which rows are listed and in what order, which the rows' labels do not say
    public List<Transmission> entries() {
        return entries;
    }

    /** The transmission whose text is shown, if the log has any. */
    public Optional<Transmission> chosen() {
        return entries.isEmpty() ? Optional.empty() : Optional.of(entries.get(chosen));
    }

    /** When {@code transmission} came, as its row and the text's heading say it. */
    public static String when(Transmission transmission) {
        return Component.translatable(transmission.whenKey(), transmission.whenLayer())
                .getString();
    }

    /** The lines of the chosen text as wrapped to the screen, with the framing's closing line. */
    public List<String> chosenLines() {
        return chosen().map(this::linesOf).orElse(List.of());
    }

    private List<String> linesOf(Transmission transmission) {
        String text = TransmissionOverlay.body(log.entry(transmission.id()));
        return CrtText.wrap(font, text, contentWidth(MARGIN));
    }

    private int rowCount() {
        return entries.size();
    }

    private int visibleRows() {
        return Mth.clamp(entries.size(), 1, MAX_ROWS);
    }

    private int listTop() {
        return contentTop(MARGIN) + font.lineHeight + GAP + CrtTuning.current().headerRuleGap();
    }

    private int listBottom() {
        return listTop() + visibleRows() * (ROW_HEIGHT + ROW_GAP);
    }

    private int buttonY() {
        return contentBottom(MARGIN) - BUTTON_HEIGHT;
    }

    private int step() {
        return font.lineHeight + CrtTuning.current().lineSpacing();
    }

    /** The y the text's first line is drawn at, under the header line and the when line. */
    private int textTop() {
        return listBottom() + GAP + 2 * step();
    }

    /** How many lines of the text fit between its top and the buttons, at least one. */
    private int textRows() {
        // The last line needs only the font's height, not a whole step.
        return Math.max(1, (buttonY() - GAP - textTop() - font.lineHeight) / step() + 1);
    }

    private void choose(int index) {
        chosen = index;
        textLine = 0;
        rebuildWidgets();
    }

    @Override
    protected void layout() {
        scroll.beginLayout();
        int first = scroll.first();
        int width = contentWidth(MARGIN);
        for (int i = first; i < Math.min(entries.size(), first + visibleRows()); i++) {
            int index = i;
            Transmission transmission = entries.get(i);
            String label = String.format(Locale.ROOT, "%02d  %s", i + 1, when(transmission));
            CrtButton row = new CrtButton(
                            contentLeft(MARGIN),
                            listTop() + (i - first) * (ROW_HEIGHT + ROW_GAP),
                            width,
                            ROW_HEIGHT,
                            Component.literal(label),
                            pressed -> choose(index))
                    .marked(i == chosen);
            addRenderableWidget(scroll.shown(row));
        }
        CrtButton replay = new CrtButton(
                contentLeft(MARGIN),
                buttonY(),
                BUTTON_WIDTH,
                BUTTON_HEIGHT,
                Component.translatable("screen.deepcharter.transmission_log.replay"),
                pressed -> chosen().ifPresent(transmission -> {
                    onClose();
                    TransmissionOverlay.replay(log.entry(transmission.id()));
                }));
        replay.active = chosen().isPresent();
        addRenderableWidget(replay);
        addRenderableWidget(new CrtButton(
                contentLeft(MARGIN) + BUTTON_WIDTH + GAP,
                buttonY(),
                BUTTON_WIDTH,
                BUTTON_HEIGHT,
                Component.translatable("screen.deepcharter.terminal.close"),
                pressed -> onClose()));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        CrtTuning tuning = CrtTuning.current();
        int left = contentLeft(MARGIN);
        CrtDraw.header(
                graphics,
                font,
                title.getString().toUpperCase(Locale.ROOT),
                left,
                contentTop(MARGIN),
                contentRight(MARGIN));
        Optional<Transmission> shown = chosen();
        if (shown.isEmpty()) {
            String empty = Component.translatable(
                            log.charter().isEmpty()
                                    ? "screen.deepcharter.transmission_log.no_charter"
                                    : "screen.deepcharter.transmission_log.empty")
                    .getString();
            int y = listTop();
            for (String line : CrtText.wrap(font, empty, contentWidth(MARGIN))) {
                CrtDraw.glowText(graphics, font, line, left, y, tuning.phosphorColor());
                y += step();
            }
        } else {
            Transmission transmission = shown.get();
            int y = listBottom() + GAP;
            CrtDraw.glowText(
                    graphics,
                    font,
                    TransmissionOverlay.header(transmission),
                    left,
                    y,
                    TransmissionOverlay.headerColor(transmission.framing()));
            CrtDraw.glowText(graphics, font, when(transmission), left, y + step(), tuning.phosphorColor());
            List<String> lines = chosenLines();
            int rows = textRows();
            textLine = Mth.clamp(textLine, 0, Math.max(0, lines.size() - rows));
            int lineY = textTop();
            for (int i = textLine; i < Math.min(lines.size(), textLine + rows); i++) {
                CrtDraw.glowText(graphics, font, lines.get(i), left, lineY, tuning.phosphorColor());
                lineY += step();
            }
            if (lines.size() > rows) {
                // Right of the when line, which is short: the header above it can be as wide as the screen.
                String more = Component.translatable(
                                "screen.deepcharter.transmission_log.more",
                                textLine + 1,
                                Math.min(lines.size(), textLine + rows),
                                lines.size())
                        .getString();
                CrtDraw.glowText(
                        graphics,
                        font,
                        more,
                        contentRight(MARGIN) - CrtText.width(font, more),
                        y + step(),
                        tuning.dimColor());
            }
        }
        scroll.hint()
                .ifPresent(hint -> CrtDraw.glowText(
                        graphics,
                        font,
                        hint,
                        left + 2 * (BUTTON_WIDTH + GAP),
                        buttonY() + (BUTTON_HEIGHT - font.lineHeight) / 2,
                        tuning.dimColor()));
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public Component getNarrationMessage() {
        Component narration = scroll.narrate(super.getNarrationMessage());
        return chosen().<Component>map(transmission -> Component.literal(narration.getString() + ". "
                        + TransmissionOverlay.header(transmission) + ". " + when(transmission) + ". "
                        + String.join(" ", chosenLines())))
                .orElse(narration);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseY >= listBottom()) {
            if (scrollY == 0) {
                return false;
            }
            textLine -= (int) Math.signum(scrollY);
            return true;
        }
        return scroll.mouseScrolled(scrollY) || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        return scroll.keyPressed(event, this) || super.keyPressed(event);
    }
}
