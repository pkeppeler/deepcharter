package io.github.pkeppeler.deepcharter.client.hangar;

import io.github.pkeppeler.deepcharter.client.charter.ClientCharter;
import io.github.pkeppeler.deepcharter.client.sound.TypewriterSound;
import io.github.pkeppeler.deepcharter.client.terminal.TerminalViewScreen;
import io.github.pkeppeler.deepcharter.client.ui.CrtButton;
import io.github.pkeppeler.deepcharter.client.ui.CrtDraw;
import io.github.pkeppeler.deepcharter.client.ui.CrtScreen;
import io.github.pkeppeler.deepcharter.client.ui.CrtText;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;
import io.github.pkeppeler.deepcharter.client.ui.Typewriter;
import io.github.pkeppeler.deepcharter.hangar.HangarTerminal;
import io.github.pkeppeler.deepcharter.hangar.HangarTuning;
import io.github.pkeppeler.deepcharter.hangar.HangarView;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.terminal.TerminalActionPayload;
import io.github.pkeppeler.deepcharter.terminal.TerminalView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The online screen of the hangar console: assemble the player's one Mole from four parts, restore or scrap the wreck
 * nearest the console, or write off the player's own wreck. The prices are the tuning's, and the server decides every
 * press. A line under the prices says where the player's Mole stands. The server's answer to an action is a new view,
 * which this screen takes in place.
 */
public final class HangarScreen extends CrtScreen implements TerminalViewScreen {
    private static final int MARGIN = 24;
    private static final int BUTTON_WIDTH = 260;
    private static final int BUTTON_HEIGHT = 20;
    private static final int GAP = 4;
    private static final int CLOSE_WIDTH = 90;
    private static final int TICKS_PER_MINUTE = 1200;
    private static final int MILLIS_PER_TICK = 50;
    private static final int HALF_WIDTH = (BUTTON_WIDTH - GAP) / 2;

    /** One wrapped line of the restore prices, where it is drawn. */
    private record PriceLine(String text, int x, int y) {}

    private TerminalView view;
    private List<PriceLine> priceLines = List.of();
    private List<PriceLine> advanceLines = List.of();
    private List<PriceLine> moleLines = List.of();
    /** The wall-clock time at which the armed Write Off lapses, 0 when it is not armed. */
    private long armedUntilMillis;

    private final Typewriter typewriter;

    public HangarScreen(TerminalView view) {
        super(Component.translatable(HangarTerminal.TYPE.block().getDescriptionId()));
        this.view = view;
        this.armedUntilMillis = armedUntil(view);
        this.typewriter =
                typewriter(Component.translatable("screen.deepcharter.hangar.welcome"), new TypewriterSound());
    }

    @Override
    public boolean accepts(TerminalView other) {
        return view.pos().equals(other.pos()) && view.type().equals(other.type());
    }

    @Override
    public void update(TerminalView newer) {
        view = newer;
        armedUntilMillis = armedUntil(newer);
        rebuildWidgets();
    }

    @Override
    public Typewriter typewriter() {
        return typewriter;
    }

    @Override
    protected void layout() {
        HangarTuning tuning = HangarTuning.DEFAULT;
        int closeY = contentBottom(MARGIN) - BUTTON_HEIGHT;
        int left = contentLeft(MARGIN);
        addRenderableWidget(new CrtButton(
                left,
                closeY,
                CLOSE_WIDTH,
                BUTTON_HEIGHT,
                Component.translatable("screen.deepcharter.terminal.close"),
                button -> onClose()));
        // The restore prices are text under the restore button, wrapped to the screen, so no price can run off
        // its edge.
        String catalyst =
                OreRegistry.stack(tuning.catalyst()).getHoverName().getString().toUpperCase(Locale.ROOT);
        HangarTuning.RestoreCost mole = tuning.restoreCost(Chassis.MOLE);
        HangarTuning.RestoreCost prospector = tuning.restoreCost(Chassis.PROSPECTOR);
        String prices = Component.translatable(
                        "screen.deepcharter.hangar.restore_prices",
                        mole.money(),
                        prospector.money(),
                        prospector.catalysts(),
                        catalyst)
                .getString();
        // The advance is a sentence of its own under the prices, and shows only while the charter has some of it left.
        int advanceLeft =
                view.feature(HangarView.class).map(HangarView::advanceLeft).orElse(0);
        List<String> advance = advanceLeft > 0
                ? wrap(Component.translatable("screen.deepcharter.hangar.advance", advanceLeft, catalyst)
                        .getString())
                : List.of();
        HangarView hangar = view.feature(HangarView.class).orElse(new HangarView(0, HangarView.MoleState.NONE, 0, 0));
        List<String> status = wrap(moleLine(hangar));
        List<String> wrapped = wrap(prices);
        int lineHeight = font.lineHeight + CrtTuning.current().lineSpacing();
        int moleY = closeY - GAP - status.size() * lineHeight;
        // A blank line's gap above the status, so it reads apart from the prices and the advance.
        int pricesY = moleY - GAP - (wrapped.size() + advance.size()) * lineHeight;
        priceLines = placed(wrapped, pricesY, lineHeight);
        advanceLines = placed(advance, pricesY + wrapped.size() * lineHeight, lineHeight);
        moleLines = placed(status, moleY, lineHeight);
        // Two rows of two buttons: the stack of four would reach the header on the smallest GUI.
        int lowerY = pricesY - GAP - BUTTON_HEIGHT;
        int upperY = lowerY - GAP - BUTTON_HEIGHT;
        int rightX = left + HALF_WIDTH + GAP;
        addRenderableWidget(new CrtButton(
                left,
                upperY,
                HALF_WIDTH,
                BUTTON_HEIGHT,
                Component.translatable("screen.deepcharter.hangar.assemble"),
                button -> send(HangarTerminal.ASSEMBLE_MOLE)));
        // The wreck the server restores or scraps is the nearest one, so the lines under the buttons name the price of
        // each chassis.
        addRenderableWidget(new CrtButton(
                rightX,
                upperY,
                HALF_WIDTH,
                BUTTON_HEIGHT,
                Component.translatable("screen.deepcharter.hangar.restore"),
                button -> send(HangarTerminal.RESTORE_WRECK)));
        addRenderableWidget(new CrtButton(
                left,
                lowerY,
                HALF_WIDTH,
                BUTTON_HEIGHT,
                Component.translatable("screen.deepcharter.hangar.scrap"),
                button -> send(HangarTerminal.SCRAP_WRECK)));
        addRenderableWidget(new CrtButton(
                rightX,
                lowerY,
                HALF_WIDTH,
                BUTTON_HEIGHT,
                armed()
                        ? Component.translatable("screen.deepcharter.hangar.write_off_confirm", tuning.writeOffCost())
                        : Component.translatable("screen.deepcharter.hangar.write_off", tuning.writeOffCost()),
                button -> send(HangarTerminal.WRITE_OFF)));
    }

    /** One sentence on where the player's one Mole stands, so a refused button has its reason on the screen. */
    private String moleLine(HangarView hangar) {
        if (armed()) {
            return Component.translatable(
                            "screen.deepcharter.hangar.mole.armed",
                            HangarTuning.DEFAULT.writeOffCost(),
                            HangarTuning.DEFAULT.writeOffTicks() / TICKS_PER_MINUTE)
                    .getString();
        }
        return switch (hangar.mole()) {
            case NONE ->
                Component.translatable("screen.deepcharter.hangar.mole.none").getString();
            case WORKING ->
                Component.translatable("screen.deepcharter.hangar.mole.working").getString();
            case WRECKED ->
                Component.translatable("screen.deepcharter.hangar.mole.wrecked").getString();
            case WRITING_OFF ->
                Component.translatable(
                                "screen.deepcharter.hangar.mole.writing_off",
                                (hangar.writeOffTicksLeft() + TICKS_PER_MINUTE - 1) / TICKS_PER_MINUTE)
                        .getString();
        };
    }

    /** The wall-clock time at which the armed Write Off in {@code shown} lapses, 0 when it is not armed. */
    private static long armedUntil(TerminalView shown) {
        int ticks =
                shown.feature(HangarView.class).map(HangarView::armedTicksLeft).orElse(0);
        return ticks > 0 ? System.currentTimeMillis() + ticks * (long) MILLIS_PER_TICK : 0;
    }

    /** True from the first press on Write Off until the second must come: the screen then shows the terms. */
    private boolean armed() {
        return armedUntilMillis > System.currentTimeMillis();
    }

    private List<String> wrap(String text) {
        return CrtText.wrap(font, text, contentWidth(MARGIN));
    }

    private List<PriceLine> placed(List<String> lines, int y, int lineHeight) {
        int left = contentLeft(MARGIN);
        List<PriceLine> placed = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            placed.add(new PriceLine(lines.get(i), left, y + i * lineHeight));
        }
        return List.copyOf(placed);
    }

    /** The y below the header text once the welcome has typed out in full: the welcome lines, then the account line. */
    // test-seam: the account line is drawn blank in a world without a charter, so no drawn text marks where the header
    // ends; the layout test pins that no button starts above it
    public int headerBottom() {
        int welcomeLines =
                CrtText.wrap(font, typewriter.text(), contentWidth(MARGIN)).size();
        int welcomeBottom = contentTop(MARGIN)
                + font.lineHeight
                + 14
                + Math.max(welcomeLines, 1)
                        * (font.lineHeight + CrtTuning.current().lineSpacing());
        return welcomeBottom + GAP + font.lineHeight;
    }

    private void send(Identifier action) {
        ClientPlayNetworking.send(new TerminalActionPayload(view.pos(), action, new CompoundTag()));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        if (armedUntilMillis != 0 && !armed()) {
            // The window lapsed: the button and the status go back to what they were.
            armedUntilMillis = 0;
            rebuildWidgets();
        }
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
        for (PriceLine line : priceLines) {
            CrtDraw.glowText(graphics, font, line.text(), line.x(), line.y(), tuning.phosphorColor());
        }
        for (PriceLine line : advanceLines) {
            CrtDraw.glowText(graphics, font, line.text(), line.x(), line.y(), tuning.phosphorColor());
        }
        for (PriceLine line : moleLines) {
            CrtDraw.glowText(graphics, font, line.text(), line.x(), line.y(), tuning.phosphorColor());
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }
}
