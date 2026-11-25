package io.github.pkeppeler.deepcharter.client.layer;

import io.github.pkeppeler.deepcharter.client.theme.ReadoutLook;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The block readout's on-screen state (#859): which readout is up and how opaque it is. The crosshair's block comes in each tick. A readout fades
 * in and, when the crosshair leaves it, out; a different block must hold the crosshair for the look's settle ticks before it replaces the one up,
 * which fades out meanwhile, so a sweep across many blocks never flickers and a run of blocks that read alike never blinks at all. It is the fade of
 * any readout above the hotbar, so a readout is any value with {@code equals}.
 */
public final class ReadoutFade<T> {
    /** Alphas this close to either end snap to it, so a fade of n ticks ends exactly on the nth. */
    private static final float SNAP = 1e-3f;

    private @Nullable T shown;
    private @Nullable T candidate;
    private int candidateTicks;
    private float previousAlpha;
    private float alpha;

    /** Advances one client tick: {@code looked} is what the crosshair rests on now. */
    public void tick(Optional<T> looked, ReadoutLook look) {
        previousAlpha = alpha;
        T wanted = looked.orElse(null);
        if (wanted == null) {
            candidate = null;
            fade(-1f / look.fadeOutTicks());
        } else if (wanted.equals(shown)) {
            candidate = null;
            fade(1f / look.fadeInTicks());
        } else {
            if (!wanted.equals(candidate)) {
                candidate = wanted;
                candidateTicks = 0;
            }
            candidateTicks++;
            if (candidateTicks >= look.settleTicks() && alpha <= 0f) {
                shown = wanted;
                candidate = null;
                fade(1f / look.fadeInTicks());
            } else {
                fade(-1f / look.fadeOutTicks());
            }
        }
    }

    private void fade(float step) {
        float next = alpha + step;
        alpha = next < SNAP ? 0f : next > 1f - SNAP ? 1f : next;
        if (alpha == 0f && candidate == null) {
            shown = null;
        }
    }

    /** Drops the readout at once, for a world the client has left. */
    public void reset() {
        shown = null;
        candidate = null;
        previousAlpha = 0f;
        alpha = 0f;
    }

    /** The readout on screen, if any. */
    public Optional<T> shown() {
        return Optional.ofNullable(shown);
    }

    /** How opaque it is, 0 to 1, between the last tick (0) and this one (1). */
    public float alpha(float partialTick) {
        return previousAlpha + (alpha - previousAlpha) * partialTick;
    }
}
