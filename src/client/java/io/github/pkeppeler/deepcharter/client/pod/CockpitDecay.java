package io.github.pkeppeler.deepcharter.client.pod;

import io.github.pkeppeler.deepcharter.client.pod.CockpitHud.Part;
import io.github.pkeppeler.deepcharter.client.theme.CockpitLook;
import io.github.pkeppeler.deepcharter.client.theme.CockpitLook.CameraLook;
import io.github.pkeppeler.deepcharter.client.theme.CockpitLook.DecayLook;
import io.github.pkeppeler.deepcharter.client.theme.PanelLook.Decal;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.SignalDecay;
import io.github.pkeppeler.deepcharter.pod.SignalDecay.Fault;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;

/**
 * The signal decay (#502) as the cockpit draws it in one frame: the {@link SignalDecay} intensity of the pod the local player sits, its seed and the time cell, and what
 * each part of the Ribbon does in that cell. Nothing is kept between frames: the same pod at the same game tick always draws the same fault, on every client. This
 * class only decides and paints; which instrument fails first, and when, is {@link SignalDecay}'s, and how it looks and how long a fault lasts is {@link DecayLook}'s.
 */
public final class CockpitDecay {
    /** A frame with no decay: every part reads true. */
    static final CockpitDecay CLEAR = new CockpitDecay(0, 0, 0, 0, null);

    private final double intensity;
    private final long seed;
    private final long cell;
    private final long tick;
    private final DecayLook look;

    private CockpitDecay(double intensity, long seed, long cell, long tick, DecayLook look) {
        this.intensity = intensity;
        this.seed = seed;
        this.cell = cell;
        this.tick = tick;
        this.look = look;
    }

    /** The decay of {@code pod} now. */
    public static CockpitDecay of(Minecraft client, PodEntity pod, CockpitLook look) {
        double intensity = SignalDecay.intensity(pod);
        if (intensity <= 0 || client.level == null) {
            return CLEAR;
        }
        long tick = client.level.getGameTime();
        return new CockpitDecay(
                intensity,
                pod.getUUID().getLeastSignificantBits(),
                Math.floorDiv(tick, (long) look.decay().cellTicks()),
                tick,
                look.decay());
    }

    /** The pod's decay now, 0 (none) to 1 (the blackout). */
    public double intensity() {
        return intensity;
    }

    public boolean blackout() {
        return intensity >= 1;
    }

    /** What {@code part} does in this cell. */
    public Fault fault(Part part) {
        return intensity <= 0 ? Fault.NONE : SignalDecay.fault(part.instrument(), intensity, seed, cell);
    }

    /** The value {@code part} shows for {@code real} (0 to 1): the real one, a false one in a jump, 0 when dark. */
    public double shown(Part part, double real) {
        return switch (fault(part)) {
            case NONE -> real;
            case JUMP -> SignalDecay.jump(part.instrument(), seed, cell);
            case DROP -> 0;
        };
    }

    /** Whether a lamp the decay may fake reads lit: {@code real} when true, a coin flip of this cell in a jump, dark when dark. {@code salt} tells two lamps apart. */
    public boolean lampShown(boolean real, int salt) {
        return switch (fault(Part.LAMPS)) {
            case NONE -> real;
            case JUMP -> SignalDecay.jump(Part.LAMPS.instrument(), seed, cell + 7919L * (salt + 1)) < 0.5;
            case DROP -> false;
        };
    }

    /** {@code text} with each digit another digit of this cell, for a readout that jumps; the same text in every other fault. */
    public String garbled(Part part, String text) {
        if (fault(part) != Fault.JUMP) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            out.append(
                    Character.isDigit(c)
                            ? (char) ('0'
                                    + (int) (SignalDecay.jump(part.instrument(), seed, cell + 31L * (i + 1)) * 10))
                            : c);
        }
        return out.toString();
    }

    public Component garbled(Part part, Component text) {
        String shown = garbled(part, text.getString());
        return fault(part) == Fault.JUMP ? Component.literal(shown).withStyle(text.getStyle()) : text;
    }

    /** Fills {@code area} with the dead colour: an instrument that is dark. */
    public void dead(GuiGraphicsExtractor graphics, Decal area) {
        int left = area.left(graphics.guiWidth());
        int top = area.top(graphics.guiHeight());
        graphics.fill(left, top, left + area.width(), top + area.height(), look.deadColor());
    }

    /**
     * Static over {@code area}, the scanner's window: specks and torn bands in proportion to the decay, a jump of the scanner at the heaviest. It is drawn from the pod's
     * seed and the tick, so a still taken at one tick is the same on every run.
     */
    public void noise(GuiGraphicsExtractor graphics, Decal area) {
        double level = fault(Part.SCANNER) == Fault.JUMP ? 1.0 : intensity * look.baseNoise();
        int left = area.left(graphics.guiWidth());
        int top = area.top(graphics.guiHeight());
        RandomSource random = RandomSource.create(seed * 31L + tick);
        int dots = (int) (level * look.staticDots());
        for (int i = 0; i < dots; i++) {
            int x = left + random.nextInt(Math.max(1, area.width() - look.dotSize() + 1));
            int y = top + random.nextInt(Math.max(1, area.height() - look.dotSize() + 1));
            graphics.fill(
                    x,
                    y,
                    x + look.dotSize(),
                    y + look.dotSize(),
                    CameraLook.withAlpha(
                            look.dotAlphaMin() + random.nextInt(256 - look.dotAlphaMin()), look.staticColor()));
        }
        int bands = (int) (level * look.tearBands());
        for (int i = 0; i < bands; i++) {
            int y = top + random.nextInt(Math.max(1, area.height() - 2));
            graphics.fill(
                    left,
                    y,
                    left + area.width(),
                    y + 1 + random.nextInt(2),
                    CameraLook.withAlpha(look.tearAlpha(), look.staticColor()));
        }
    }
}
