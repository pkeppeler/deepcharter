package io.github.pkeppeler.deepcharter.test.support;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks.Box;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.tooltip.TooltipComponent;

/**
 * The GUI sprites a screen or the HUD draws, in the order drawn, with the box each covers on the screen in GUI pixels,
 * and the text tooltips it sets: what a layout test reads to find an effect's icon and its hover, which {@link
 * DrawnText} does not see. A sprite is recorded where it is blitted, at the size it is drawn, under no pose (the effect
 * lists draw none).
 */
public final class DrawnSprites {
    private DrawnSprites() {}

    /** A sprite the screen drew, and the box it covers. */
    public record Drawn(Identifier sprite, Box box) {}

    /** What a screen drew in one frame: its sprites in order, and the tooltips (a line each) it set for the frame. */
    public record Frame(List<Drawn> sprites, List<List<String>> tooltips) {}

    /** Renders {@code screen} once into a recorder and returns the sprites it blitted, in order. Client thread. */
    public static List<Drawn> of(Screen screen) {
        return of(screen, -1, -1).sprites();
    }

    /** Renders {@code screen} once with the mouse at ({@code mouseX}, {@code mouseY}). Client thread. */
    public static Frame of(Screen screen, int mouseX, int mouseY) {
        return record(recorder -> screen.extractRenderStateWithTooltipAndSubtitles(recorder, mouseX, mouseY, 0f));
    }

    /** Runs {@code draw} on a recorder and returns the sprites it blitted, for something that is not a screen, such as the HUD. Client thread. */
    public static List<Drawn> of(Consumer<GuiGraphicsExtractor> draw) {
        return record(draw).sprites();
    }

    private static Frame record(Consumer<GuiGraphicsExtractor> draw) {
        Recorder recorder = new Recorder(Minecraft.getInstance());
        draw.accept(recorder);
        return new Frame(List.copyOf(recorder.drawn), List.copyOf(recorder.tooltips));
    }

    private static final class Recorder extends GuiGraphicsExtractor {
        private final List<Drawn> drawn = new ArrayList<>();
        private final List<List<String>> tooltips = new ArrayList<>();

        Recorder(Minecraft client) {
            super(client, new GuiRenderState(), -1, -1);
        }

        @Override
        public void blitSprite(RenderPipeline pipeline, Identifier sprite, int x, int y, int width, int height) {
            drawn.add(new Drawn(sprite, new Box(x, y, width, height)));
        }

        /** The tinted overload, which the HUD's effect icons fade through. */
        @Override
        public void blitSprite(
                RenderPipeline pipeline, Identifier sprite, int x, int y, int width, int height, int color) {
            drawn.add(new Drawn(sprite, new Box(x, y, width, height)));
        }

        @Override
        public void setTooltipForNextFrame(
                Font font, List<Component> lines, Optional<TooltipComponent> extra, int x, int y) {
            tooltips.add(lines.stream().map(Component::getString).toList());
        }
    }
}
