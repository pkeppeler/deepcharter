package io.github.pkeppeler.deepcharter.client.ui;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;

/**
 * The parts of the HUD that a popup must never cover. Each HUD part registers a {@link Source} from its own init, next to the code
 * that draws it, so a part drawn somewhere new moves its rectangle with it and a popup that places itself with {@link #rects} stays
 * clear of it.
 */
public final class HudKeepClear {
    private static final List<Source> SOURCES = new ArrayList<>();

    private HudKeepClear() {}

    /** A rectangle of the GUI, in GUI pixels, and what it is, for a failure that names the thing a popup would cover. */
    public record Rect(String what, int x, int y, int width, int height) {
        public int right() {
            return x + width;
        }

        public int bottom() {
            return y + height;
        }

        /** Whether the two share at least a pixel; rectangles that only touch do not. */
        public boolean overlaps(Rect other) {
            return x < other.right() && other.x < right() && y < other.bottom() && other.y < bottom();
        }

        /** This rectangle with {@code by} more on every side. */
        public Rect grow(int by) {
            return new Rect(what, x - by, y - by, width + 2 * by, height + 2 * by);
        }
    }

    /** One HUD part's rectangles on a GUI of the given size, as it draws on it now; adds nothing when it draws nothing. */
    @FunctionalInterface
    public interface Source {
        void add(Minecraft client, int guiWidth, int guiHeight, List<Rect> into);
    }

    /** Registers a HUD part. Called once from the part's init. */
    public static void register(Source source) {
        SOURCES.add(source);
    }

    /** Everything a popup must keep clear of on a GUI {@code guiWidth} x {@code guiHeight}, as the HUD draws it now. Client thread. */
    public static List<Rect> rects(Minecraft client, int guiWidth, int guiHeight) {
        List<Rect> rects = new ArrayList<>();
        for (Source source : SOURCES) {
            source.add(client, guiWidth, guiHeight, rects);
        }
        return rects;
    }
}
