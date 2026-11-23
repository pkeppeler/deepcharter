package io.github.pkeppeler.deepcharter.client.handbook;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.theme.Colors;
import io.github.pkeppeler.deepcharter.client.ui.HudKeepClear;
import io.github.pkeppeler.deepcharter.handbook.HandbookChapter;
import io.github.pkeppeler.deepcharter.handbook.HandbookChapters;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * The popup of a finished step: a slip of handbook paper with a red head rule, "Directive complete" and the step's own line. The line
 * wraps to the slip's width and the slip is as tall as its lines. It slides in from the left edge and out again, and is the only trace
 * on screen; nothing stays pinned.
 *
 * <p>It never covers the HUD (#1030). It sits in the left column, at the highest place where it is clear of every rectangle that
 * {@link HudKeepClear} lists and of the slips above it; it moves down when a part of the HUD grows under it. Where the left column has
 * no such place (a long step beside the navigator's sonar scope) it takes the right column, where vanilla's toasts are, and slides in
 * from the right edge there (#1075). On a GUI with no such place in either column it draws nothing and its time does not run, until
 * there is one or {@link QuestMapTuning#toastWaitMillis} are up. The toast manager still queues it, times it and clears it.
 */
public final class StepToast implements Toast {
    private static final List<StepToast> PLACED = new ArrayList<>();

    private final List<String> lines;
    private Visibility visibility = Visibility.HIDE;
    /** Where the slip was put when {@link #placedAtMillis} says; null when it found no clear place. */
    private HudKeepClear.@Nullable Rect placement;

    private boolean inRightColumn;

    private long placedAtMillis = Long.MIN_VALUE;
    private long lastUpdateMillis = -1;
    private long waitedMillis;
    private boolean gaveUp;

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

    /** Where a slip is and which column it is in; the column is not worked out from the rectangle, which two columns can share. */
    public record Placement(HudKeepClear.Rect slip, boolean right) {}

    /**
     * The top left of a {@code width} x {@code height} slip on a GUI {@code guiWidth} x {@code guiHeight}: against the left margin, at
     * the highest place that is clear of every rectangle of {@code avoid} by the tuned gap and inside the bottom margin; failing
     * that, the same against the right margin; empty when neither column has one.
     */
    public static Optional<Placement> place(
            int guiWidth, int guiHeight, int width, int height, List<HudKeepClear.Rect> avoid) {
        QuestMapTuning q = QuestMapTuning.current();
        if (width > guiWidth - 2 * q.toastMargin()) {
            return Optional.empty();
        }
        return inColumn(q.toastMargin(), guiHeight, width, height, avoid)
                .map(slip -> new Placement(slip, false))
                .or(() -> inColumn(guiWidth - q.toastMargin() - width, guiHeight, width, height, avoid)
                        .map(slip -> new Placement(slip, true)));
    }

    private static Optional<HudKeepClear.Rect> inColumn(
            int left, int guiHeight, int width, int height, List<HudKeepClear.Rect> avoid) {
        QuestMapTuning q = QuestMapTuning.current();
        TreeSet<Integer> tops = new TreeSet<>(List.of(q.toastMargin()));
        for (HudKeepClear.Rect rect : avoid) {
            tops.add(rect.bottom() + q.toastGap());
        }
        for (int top : tops) {
            if (top + height > guiHeight - q.toastMargin()) {
                break;
            }
            HudKeepClear.Rect slip = new HudKeepClear.Rect("step popup", left, top, width, height);
            if (avoid.stream().noneMatch(rect -> rect.grow(q.toastGap()).overlaps(slip))) {
                return Optional.of(slip);
            }
        }
        return Optional.empty();
    }

    /**
     * Where the slip is, or empty when it found no clear place at its last frame or has not been placed for {@code toastStaleMillis}
     * (it was cleared or its time is up): a slip is placed again in every frame it is on screen.
     */
    public Optional<HudKeepClear.Rect> placement() {
        return Util.getMillis() - placedAtMillis <= QuestMapTuning.current().toastStaleMillis()
                ? Optional.ofNullable(placement)
                : Optional.empty();
    }

    @Override
    public Visibility getWantedVisibility() {
        return visibility;
    }

    @Override
    public void update(ToastManager manager, long fullyVisibleForMs) {
        QuestMapTuning q = QuestMapTuning.current();
        long now = Util.getMillis();
        if (lastUpdateMillis >= 0 && placement == null) {
            waitedMillis += now - lastUpdateMillis;
        }
        lastUpdateMillis = now;
        if (waitedMillis >= q.toastWaitMillis() && !gaveUp) {
            gaveUp = true;
            DeepCharter.LOGGER.info(
                    "A step popup found no place clear of the HUD in {} ms and was dropped: {}",
                    waitedMillis,
                    String.join(" ", lines));
        }
        visibility = fullyVisibleForMs - waitedMillis < q.toastMillis() * manager.getNotificationDisplayTimeMultiplier()
                        && waitedMillis < q.toastWaitMillis()
                ? Visibility.SHOW
                : Visibility.HIDE;
    }

    /** Slides in from the left edge in the left column, from the right edge in the right. Settles first: the manager asks for x before y. */
    @Override
    public float xPos(int guiWidth, float visiblePortion) {
        int margin = QuestMapTuning.current().toastMargin();
        if (settle() != null && inRightColumn) {
            return guiWidth - (width() + margin) * visiblePortion;
        }
        return margin - width() * (1 - visiblePortion);
    }

    /** Puts the slip where it is clear of the HUD and of the slips already put this frame, and says so in {@link #placement()}. */
    @Override
    public float yPos(int firstSlotIndex) {
        HudKeepClear.Rect slip = settle();
        return slip == null ? 0 : slip.y();
    }

    /** Works out {@link #placement} for this frame and records it; null when there is no place, in which case nothing is drawn. */
    private HudKeepClear.@Nullable Rect settle() {
        Minecraft client = Minecraft.getInstance();
        int guiWidth = client.getWindow().getGuiScaledWidth();
        int guiHeight = client.getWindow().getGuiScaledHeight();
        List<HudKeepClear.Rect> avoid = HudKeepClear.rects(client, guiWidth, guiHeight);
        PLACED.removeIf(other -> other == this || other.placement().isEmpty());
        PLACED.forEach(other -> other.placement().ifPresent(avoid::add));
        Optional<Placement> placed = place(guiWidth, guiHeight, width(), height(), avoid);
        placement = placed.map(Placement::slip).orElse(null);
        inRightColumn = placed.map(Placement::right).orElse(false);
        placedAtMillis = Util.getMillis();
        if (placement != null) {
            PLACED.add(this);
        }
        return placement;
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
        if (placement != null) {
            draw(graphics, font);
        }
    }

    /** The slip at the origin of {@code graphics}, which the toast manager has moved to where {@link #yPos} and {@link #xPos} put it. */
    public void draw(GuiGraphicsExtractor graphics, Font font) {
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
