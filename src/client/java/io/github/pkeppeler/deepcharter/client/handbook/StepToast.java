package io.github.pkeppeler.deepcharter.client.handbook;

import io.github.pkeppeler.deepcharter.client.theme.Colors;
import io.github.pkeppeler.deepcharter.handbook.HandbookChapter;
import io.github.pkeppeler.deepcharter.handbook.HandbookChapters;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

/**
 * The popup of a finished step: a slip of handbook paper with a red head rule, "Directive complete" and the step's own line. The line
 * wraps to the slip's width and the slip is as tall as its lines. It slides in and out as every toast does and is the only trace on
 * screen; nothing stays pinned.
 */
public final class StepToast implements Toast {
    private final List<String> lines;
    private Visibility visibility = Visibility.HIDE;

    private StepToast(List<String> lines) {
        this.lines = lines;
    }

    /** The popup of a step whose line is {@code text}. Client thread. */
    public static StepToast of(Component text) {
        Font font = Minecraft.getInstance().font;
        return new StepToast(
                font.getSplitter().splitLines(Component.literal(text.getString()), lineWidth(), Style.EMPTY).stream()
                        .map(FormattedText::getString)
                        .toList());
    }

    private static int textLeft() {
        return HandbookScreenTuning.current().bindingWidth() / 2
                + QuestMapTuning.current().toastInset();
    }

    private static int lineWidth() {
        QuestMapTuning q = QuestMapTuning.current();
        return q.toastWidth() - textLeft() - q.toastInset();
    }

    private static int textTop() {
        return QuestMapTuning.current().cardHeadRule() + 3;
    }

    /** Shows the popup of directive {@code id} if the client's chapters list it. */
    static void show(Minecraft client, Identifier id) {
        if (client.getConnection() == null) {
            return;
        }
        HandbookChapters.all(client.getConnection().registryAccess()).stream()
                .flatMap(chapter -> chapter.value().directives().stream())
                .filter(entry -> entry.id().equals(id))
                .findFirst()
                .map(HandbookChapter.Entry::text)
                .ifPresent(text -> client.gui.toastManager().addToast(of(text)));
    }

    @Override
    public Visibility getWantedVisibility() {
        return visibility;
    }

    @Override
    public void update(ToastManager manager, long fullyVisibleForMs) {
        visibility = fullyVisibleForMs
                        < QuestMapTuning.current().toastMillis() * manager.getNotificationDisplayTimeMultiplier()
                ? Visibility.SHOW
                : Visibility.HIDE;
    }

    @Override
    public int width() {
        return QuestMapTuning.current().toastWidth();
    }

    /** At least the tuned height; taller when the lines and the space under them need it. */
    @Override
    public int height() {
        QuestMapTuning q = QuestMapTuning.current();
        int lineHeight = Minecraft.getInstance().font.lineHeight + 1;
        return Math.max(q.toastHeight(), textTop() + lines.size() * lineHeight - 1 + q.cardBottomPad());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, Font font, long fullyVisibleForMs) {
        HandbookScreenTuning t = HandbookScreenTuning.current();
        QuestMapTuning q = QuestMapTuning.current();
        int w = width();
        int h = height();
        graphics.fill(0, 0, w, h, t.paperColor());
        PaperDraw.ruledLines(graphics, 0, w, q.cardRuleTop(), h - q.cardRuleBottom());
        graphics.fill(0, 0, t.bindingWidth() / 2, h, t.bindingColor());
        PaperDraw.border(graphics, 0, 0, w, h, Colors.opaque(t.paperEdgeColor()));
        int left = textLeft();
        graphics.text(
                font,
                PaperDraw.ink(Component.translatable("deepcharter.handbook.quest.toast"), t.faintInkColor()),
                left,
                q.cardHeadTop(),
                Colors.opaque(t.faintInkColor()),
                false);
        graphics.fill(1, q.cardHeadRule(), w - 1, q.cardHeadRule() + 1, t.stampColor());
        int y = textTop();
        for (String line : lines) {
            graphics.text(
                    font,
                    PaperDraw.ink(Component.literal(line), t.inkColor()),
                    left,
                    y,
                    Colors.opaque(t.inkColor()),
                    false);
            y += font.lineHeight + 1;
        }
    }
}
