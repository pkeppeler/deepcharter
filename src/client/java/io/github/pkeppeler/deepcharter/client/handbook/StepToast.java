package io.github.pkeppeler.deepcharter.client.handbook;

import io.github.pkeppeler.deepcharter.client.theme.Colors;
import io.github.pkeppeler.deepcharter.handbook.HandbookChapter;
import io.github.pkeppeler.deepcharter.handbook.HandbookChapters;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The popup of a finished step: a slip of handbook paper with a red head rule, "Directive complete" and the step's own line. It slides
 * in and out as every toast does and is the only trace on screen; nothing stays pinned.
 */
final class StepToast implements Toast {
    private final Component text;
    private Visibility visibility = Visibility.HIDE;

    private StepToast(Component text) {
        this.text = text;
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
                .ifPresent(text -> client.gui.toastManager().addToast(new StepToast(text)));
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

    @Override
    public int height() {
        return QuestMapTuning.current().toastHeight();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, Font font, long fullyVisibleForMs) {
        HandbookScreenTuning t = HandbookScreenTuning.current();
        QuestMapTuning q = QuestMapTuning.current();
        int w = width();
        int h = height();
        graphics.fill(0, 0, w, h, t.paperColor());
        int rule = Colors.withAlpha(t.paperEdgeColor(), t.ruleAlpha());
        for (int y = q.cardRuleTop(); y < h - q.cardRuleBottom(); y += t.ruleSpacing()) {
            graphics.fill(1, y, w - 1, y + 1, rule);
        }
        graphics.fill(0, 0, t.bindingWidth() / 2, h, t.bindingColor());
        PaperDraw.border(graphics, 0, 0, w, h, Colors.opaque(t.paperEdgeColor()));
        int left = t.bindingWidth() / 2 + q.toastInset();
        graphics.text(
                font,
                PaperDraw.ink(Component.translatable("deepcharter.handbook.quest.toast"), t.faintInkColor()),
                left,
                q.cardHeadTop(),
                Colors.opaque(t.faintInkColor()),
                false);
        graphics.fill(1, q.cardHeadRule(), w - 1, q.cardHeadRule() + 1, t.stampColor());
        int y = q.cardHeadRule() + 3;
        for (String line : QuestMap.clip(font, text.getString(), w - left - q.toastInset(), 2)) {
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
