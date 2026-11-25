package io.github.pkeppeler.deepcharter.test.support;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks.Box;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.resources.Identifier;

/**
 * The GUI sprites a screen or the HUD draws, in the order drawn, with the box each covers on the screen in GUI pixels: what a layout test
 * reads to find an effect's icon, which {@link DrawnText} does not see. A sprite is recorded where it is blitted, at the size it is drawn,
 * under no pose (the effect lists draw none).
 */
public final class DrawnSprites {
    private DrawnSprites() {}

    /** A sprite the screen drew, and the box it covers. */
    public record Drawn(Identifier sprite, Box box) {}

    /** Renders {@code screen} once into a recorder and returns the sprites it blitted, in order. Client thread. */
    public static List<Drawn> of(Screen screen) {
        return of(recorder -> screen.extractRenderStateWithTooltipAndSubtitles(recorder, -1, -1, 0f));
    }

    /** Runs {@code draw} on a recorder and returns the sprites it blitted, for something that is not a screen, such as the HUD. Client thread. */
    public static List<Drawn> of(Consumer<GuiGraphicsExtractor> draw) {
        Recorder recorder = new Recorder(Minecraft.getInstance());
        draw.accept(recorder);
        return List.copyOf(recorder.drawn);
    }

    private static final class Recorder extends GuiGraphicsExtractor {
        private final List<Drawn> drawn = new ArrayList<>();

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
    }
}
