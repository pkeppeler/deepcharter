package io.github.pkeppeler.deepcharter.client.terminal;

import io.github.pkeppeler.deepcharter.client.charter.ClientCharter;
import io.github.pkeppeler.deepcharter.client.sound.TypewriterSound;
import io.github.pkeppeler.deepcharter.client.ui.CrtButton;
import io.github.pkeppeler.deepcharter.client.ui.CrtDraw;
import io.github.pkeppeler.deepcharter.client.ui.CrtScreen;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;
import io.github.pkeppeler.deepcharter.client.ui.Typewriter;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.TerminalView;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * The generic online terminal screen: it greets the charter and shows its account. A terminal feature replaces it with
 * its own
 * through {@link TerminalScreens#register}. An unrepaired terminal opens its {@link RepairScreen} instead.
 */
public final class TerminalScreen extends CrtScreen implements TerminalViewScreen {
    private static final int MARGIN = 24;
    private static final int BUTTON_HEIGHT = 20;
    private static final int GAP = 6;
    private static final int CLOSE_WIDTH = 90;

    private TerminalView view;
    private final Typewriter typewriter;

    public TerminalScreen(TerminalView view) {
        super(Component.translatable(TerminalTypes.get(view.type())
                .orElseThrow(() -> new IllegalStateException("unknown terminal type " + view.type()))
                .block()
                .getDescriptionId()));
        this.view = view;
        this.typewriter =
                typewriter(Component.translatable("screen.deepcharter.terminal.online"), new TypewriterSound());
    }

    /** True when {@code other} is the same terminal, still online, so this screen can take it with {@link #update}. */
    @Override
    public boolean accepts(TerminalView other) {
        return view.pos().equals(other.pos()) && view.type().equals(other.type()) && other.repaired();
    }

    /** Shows a newer view of the same terminal, keeping the typewriter where it is. */
    @Override
    public void update(TerminalView newer) {
        view = newer;
        rebuildWidgets();
    }

    @Override
    public Typewriter typewriter() {
        return typewriter;
    }

    @Override
    protected void layout() {
        addRenderableWidget(new CrtButton(
                contentLeft(MARGIN),
                contentBottom(MARGIN) - BUTTON_HEIGHT,
                CLOSE_WIDTH,
                BUTTON_HEIGHT,
                Component.translatable("screen.deepcharter.terminal.close"),
                button -> onClose()));
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
                contentTop(MARGIN) + font.lineHeight + 14,
                contentWidth(MARGIN));
        String account = ClientCharter.view()
                .map(charter -> Component.translatable("screen.deepcharter.terminal.account", charter.balance())
                        .getString())
                .orElse("");
        CrtDraw.glowText(graphics, font, account, contentLeft(MARGIN), below + GAP, tuning.phosphorColor());
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }
}
