package io.github.pkeppeler.deepcharter.client.outpost;

import io.github.pkeppeler.deepcharter.client.sound.TypewriterSound;
import io.github.pkeppeler.deepcharter.client.ui.CrtButton;
import io.github.pkeppeler.deepcharter.client.ui.CrtDraw;
import io.github.pkeppeler.deepcharter.client.ui.CrtScreen;
import io.github.pkeppeler.deepcharter.client.ui.Typewriter;
import io.github.pkeppeler.deepcharter.outpost.IncidentReportPayload;
import io.github.pkeppeler.deepcharter.outpost.IncidentReportPayload.Line;
import io.github.pkeppeler.deepcharter.outpost.VisitCause;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The incident report of an outpost: what visits did while nobody was near, typed out when a member opens its terminal on return. It
 * decides nothing. FILED closes it and goes back to the screen it opened over, the terminal's.
 */
public final class IncidentReportScreen extends CrtScreen {
    private static final int MARGIN = 24;
    private static final int BUTTON_WIDTH = 70;
    private static final int BUTTON_HEIGHT = 16;
    private static final int BODY_GAP = 14;

    private final Typewriter body;
    private final @Nullable Screen previous;

    /** Shows {@code report}; FILED returns to {@code previous}, the screen this one opened over (none when it opened over the game). */
    public IncidentReportScreen(IncidentReportPayload report, @Nullable Screen previous) {
        super(Component.translatable("screen.deepcharter.incident_report.title", report.outpost()));
        this.previous = previous;
        this.body = typewriter(bodyOf(report), new TypewriterSound());
    }

    /** What the report says, one line each: the visits, why they came, what was taken or lost, and the heaps, or that nothing was lost. */
    private static Component bodyOf(IncidentReportPayload report) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("screen.deepcharter.incident_report.visits", report.visits()));
        for (VisitCause cause : report.causes()) {
            lines.add(Component.translatable("screen.deepcharter.incident_report." + cause.getSerializedName()));
        }
        for (Line line : report.lines()) {
            lines.add(Component.translatable(
                    "screen.deepcharter.incident_report." + line.kind().name().toLowerCase(Locale.ROOT),
                    line.count(),
                    nameOf(line)));
        }
        if (report.heaps() > 0) {
            lines.add(Component.translatable("screen.deepcharter.incident_report.heap", report.heaps()));
        }
        if (report.lines().isEmpty() && report.heaps() == 0) {
            lines.add(Component.translatable("screen.deepcharter.incident_report.none"));
        }
        Component joined = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            joined = joined.copy()
                    .append(i == 0 ? Component.empty() : Component.literal("\n"))
                    .append(lines.get(i));
        }
        return joined;
    }

    private static Component nameOf(Line line) {
        return BuiltInRegistries.ITEM
                .getOptional(line.item())
                .map(item -> new ItemStack(item).getHoverName())
                .orElseGet(() -> Component.literal(line.item().toString()));
    }

    @Override
    protected void layout() {
        addRenderableWidget(new CrtButton(
                contentLeft(MARGIN),
                contentBottom(MARGIN) - BUTTON_HEIGHT,
                BUTTON_WIDTH,
                BUTTON_HEIGHT,
                Component.translatable("screen.deepcharter.incident_report.filed"),
                button -> onClose()));
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(previous);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        CrtDraw.header(
                graphics,
                font,
                title.getString().toUpperCase(Locale.ROOT),
                contentLeft(MARGIN),
                contentTop(MARGIN),
                contentRight(MARGIN));
        drawTypewriter(
                graphics,
                body,
                contentLeft(MARGIN),
                contentTop(MARGIN) + font.lineHeight + BODY_GAP,
                contentWidth(MARGIN));
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }
}
