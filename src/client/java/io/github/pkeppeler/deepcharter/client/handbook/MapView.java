package io.github.pkeppeler.deepcharter.client.handbook;

import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/** Where the player left one chapter's flowchart: how far it is dragged, how far it is zoomed, and the step picked on its card. */
final class MapView {
    double panX;
    double panY;
    double zoom = 1;
    /** The step the index card shows; empty until the player picks one, when it is the current step. */
    @Nullable
    Identifier selected;

    /** Drags the flowchart by {@code dx}, {@code dy}, leaving at least {@code margin} pixels of a content of {@code width} x {@code height} in the view. */
    void pan(double dx, double dy, int viewWidth, int viewHeight, int contentWidth, int contentHeight, int margin) {
        panX = clampPan(panX + dx, viewWidth, contentWidth, margin);
        panY = clampPan(panY + dy, viewHeight, contentHeight, margin);
    }

    /** Zooms by {@code factor} within {@code min} to {@code max}, keeping the content under ({@code anchorX}, {@code anchorY}) in place. */
    void zoom(double factor, double anchorX, double anchorY, double min, double max) {
        double next = Math.max(min, Math.min(max, zoom * factor));
        double ratio = next / zoom;
        panX = anchorX - (anchorX - panX) * ratio;
        panY = anchorY - (anchorY - panY) * ratio;
        zoom = next;
    }

    /** Keeps {@code margin} pixels of the content in view, at its current zoom. */
    void clamp(int viewWidth, int viewHeight, int contentWidth, int contentHeight, int margin) {
        panX = clampPan(panX, viewWidth, contentWidth, margin);
        panY = clampPan(panY, viewHeight, contentHeight, margin);
    }

    private double clampPan(double pan, int view, int content, int margin) {
        double low = Math.min(0, margin - content * zoom);
        double high = Math.max(0, view - margin);
        return Math.max(low, Math.min(high, pan));
    }
}
