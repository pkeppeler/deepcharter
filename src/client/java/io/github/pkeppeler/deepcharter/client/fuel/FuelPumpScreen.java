package io.github.pkeppeler.deepcharter.client.fuel;

import io.github.pkeppeler.deepcharter.charter.CharterView;
import io.github.pkeppeler.deepcharter.client.charter.ClientCharter;
import io.github.pkeppeler.deepcharter.client.sound.TypewriterSound;
import io.github.pkeppeler.deepcharter.client.terminal.ShownPod;
import io.github.pkeppeler.deepcharter.client.terminal.TerminalNoticeLine;
import io.github.pkeppeler.deepcharter.client.terminal.TerminalViewScreen;
import io.github.pkeppeler.deepcharter.client.ui.AboutTip;
import io.github.pkeppeler.deepcharter.client.ui.CrtButton;
import io.github.pkeppeler.deepcharter.client.ui.CrtDraw;
import io.github.pkeppeler.deepcharter.client.ui.CrtScreen;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;
import io.github.pkeppeler.deepcharter.client.ui.Typewriter;
import io.github.pkeppeler.deepcharter.fuel.FuelPump;
import io.github.pkeppeler.deepcharter.fuel.FuelTuning;
import io.github.pkeppeler.deepcharter.outpost.OutpostRates;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.terminal.TerminalActionPayload;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.TerminalView;
import io.github.pkeppeler.deepcharter.terminal.TerminalWares;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The online screen of the fuel pump: the account, the tank of the pod parked at the pump, and the original's buttons, a few
 * fixed amounts and FILL UP. The screen shows what this client can see (the synced pod and charter) and greys out what would
 * certainly be refused; it never decides. The server checks every press and answers with the new view, which {@link #update}
 * takes in place.
 */
public final class FuelPumpScreen extends CrtScreen implements TerminalViewScreen {
    private static final int MARGIN = 24;
    private static final int BUTTON_WIDTH = 70;
    private static final int BUTTON_HEIGHT = 20;
    private static final int GAP = 6;

    private TerminalView view;
    private final Typewriter typewriter;
    private final List<CrtButton> buyButtons = new ArrayList<>();
    private @Nullable CrtButton fillButton;

    public FuelPumpScreen(TerminalView view) {
        super(Component.translatable(typeOf(view).block().getDescriptionId()));
        this.view = view;
        this.typewriter = typewriter(
                Component.translatable("screen.deepcharter.fuel_pump.online", price()), new TypewriterSound());
    }

    /** What a litre costs here: the server charges the price of the band the pump stands in, in the level that is the client's own. */
    private long price() {
        return OutpostRates.fuelPricePerLitre(
                Objects.requireNonNull(Minecraft.getInstance().level, "a pump screen opens in a world"),
                view.pos().getY());
    }

    private static TerminalType typeOf(TerminalView view) {
        return TerminalTypes.get(view.type())
                .orElseThrow(() -> new IllegalStateException("unknown terminal type " + view.type()));
    }

    @Override
    public boolean accepts(TerminalView other) {
        return view.pos().equals(other.pos()) && view.type().equals(other.type());
    }

    @Override
    public void update(TerminalView newer) {
        view = newer;
    }

    @Override
    public Typewriter typewriter() {
        return typewriter;
    }

    /** The room the view's risk line takes under the header, which the text below it moves down by. */
    private int noticeHeight() {
        return TerminalNoticeLine.height(font, view, contentWidth(MARGIN));
    }

    @Override
    protected void layout() {
        buyButtons.clear();
        int closeY = contentBottom(MARGIN) - BUTTON_HEIGHT;
        // The row of purchases and FILL UP takes the room there is, down to narrower buttons: at the smallest GUI (320
        // wide) it would leave the screen.
        int columns = FuelTuning.DEFAULT.purchaseSteps().size() + 1;
        int buttonWidth = Math.min(BUTTON_WIDTH, (contentWidth(MARGIN) - (columns - 1) * GAP) / columns);
        addRenderableWidget(new CrtButton(
                contentLeft(MARGIN),
                closeY,
                buttonWidth,
                BUTTON_HEIGHT,
                Component.translatable("screen.deepcharter.terminal.close"),
                button -> onClose()));
        int rowY = closeY - GAP - BUTTON_HEIGHT;
        Component buyAbout = TerminalWares.about(TerminalTypes.FUEL_PUMP, FuelPump.BUY.getPath());
        int x = contentLeft(MARGIN);
        for (int litres : FuelTuning.DEFAULT.purchaseSteps()) {
            buyButtons.add(addRenderableWidget(new CrtButton(
                            x,
                            rowY,
                            buttonWidth,
                            BUTTON_HEIGHT,
                            Component.translatable("screen.deepcharter.fuel_pump.buy", litres),
                            button -> buy(litres))
                    .about(buyAbout)));
            x += buttonWidth + GAP;
        }
        fillButton = addRenderableWidget(new CrtButton(
                        x,
                        rowY,
                        buttonWidth,
                        BUTTON_HEIGHT,
                        Component.translatable("screen.deepcharter.fuel_pump.fill"),
                        button -> fill())
                .about(TerminalWares.about(TerminalTypes.FUEL_PUMP, FuelPump.FILL.getPath())));
    }

    private void buy(int litres) {
        CompoundTag args = new CompoundTag();
        args.putInt(FuelPump.LITRES_KEY, litres);
        ClientPlayNetworking.send(new TerminalActionPayload(view.pos(), FuelPump.BUY, args));
    }

    private void fill() {
        ClientPlayNetworking.send(new TerminalActionPayload(view.pos(), FuelPump.FILL, new CompoundTag()));
    }

    /** The pod the screen shows: the one the server will fuel. */
    private Optional<PodEntity> pod() {
        if (minecraft == null || minecraft.level == null) {
            return Optional.empty();
        }
        return ShownPod.of(minecraft.level, view);
    }

    private long balance() {
        return ClientCharter.view().map(CharterView::balance).orElse(0L);
    }

    /** Greys out the buttons that would certainly be refused: no pod, no room, or too poor. FILL needs only a dollar. */
    private void refreshButtons(Optional<PodEntity> pod) {
        float room = pod.map(found -> PodStats.of(found).tankLitres() - FuelPump.litres(found))
                .orElse(0f);
        long price = price();
        List<Integer> steps = FuelTuning.DEFAULT.purchaseSteps();
        for (int i = 0; i < buyButtons.size(); i++) {
            int litres = steps.get(i);
            buyButtons.get(i).active = room >= litres - 0.001f && balance() >= litres * price;
        }
        CrtButton fill = Objects.requireNonNull(fillButton, "FILL UP button is built by layout() in init()");
        fill.active = room > 0.001f && balance() >= price;
    }

    /** The content area, which a row's tip stays inside. */
    public AboutTip.Bounds tipBounds() {
        return new AboutTip.Bounds(
                contentLeft(MARGIN),
                contentTop(MARGIN),
                contentRight(MARGIN),
                contentBottom(MARGIN),
                contentTop(MARGIN) + font.lineHeight + 14);
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
        TerminalNoticeLine.draw(graphics, font, view, contentLeft(MARGIN), contentTop(MARGIN), contentWidth(MARGIN));
        int below = drawTypewriter(
                graphics,
                typewriter,
                contentLeft(MARGIN),
                contentTop(MARGIN) + font.lineHeight + 14 + noticeHeight(),
                contentWidth(MARGIN));
        Optional<PodEntity> pod = pod();
        int y = below + GAP;
        CrtDraw.glowText(
                graphics,
                font,
                Component.translatable("screen.deepcharter.terminal.account", balance())
                        .getString(),
                contentLeft(MARGIN),
                y,
                tuning.phosphorColor());
        y += font.lineHeight + GAP;
        String tank = pod.map(found -> Component.translatable(
                                "screen.deepcharter.fuel_pump.tank",
                                String.format(Locale.ROOT, "%.1f", FuelPump.litres(found)),
                                String.format(
                                        Locale.ROOT, "%.0f", PodStats.of(found).tankLitres()))
                        .getString())
                .orElseGet(() -> Component.translatable("screen.deepcharter.fuel_pump.no_pod")
                        .getString());
        CrtDraw.glowText(graphics, font, tank, contentLeft(MARGIN), y, tuning.phosphorColor());
        refreshButtons(pod);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        AboutTip.draw(graphics, font, this, tipBounds(), mouseX, mouseY);
    }
}
