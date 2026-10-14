package io.github.pkeppeler.deepcharter.client.scanner;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.theme.ScannerLook;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.scanner.ScanArea;
import io.github.pkeppeler.deepcharter.scanner.ScanSlice;
import io.github.pkeppeler.deepcharter.scanner.ScanSlice.Cell;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The scanner's cells as one picture: every known cell of a slice is the block's texture scaled down to the cell's size on screen,
 * with the theme's shade, lift or tint over it, and the picture is one texture drawn with one blit. Drawing each cell as its own blit of
 * the block atlas was measured at 282 ms a frame against 0.30 ms with no scanner (#537, 7,857 cells at the largest tier, where main's fill() per cell
 * costs the same); one blit costs the same at any scan size, and the picture is rebuilt only when the scanner rescans, every few ticks, or
 * when the resources reload.
 *
 * <p>A cell drawn in fewer screen pixels than {@link ScannerLook#oreMarkBelowPixels()} cannot show a texture. There an ore cell is one flat mark of its
 * ore's colour (see {@link #oreMark}), which stays easy to spot, and rock takes the darker small-cell shade; every other cell keeps its texture.
 *
 * <p>A cell's pixels are read from the active resource pack's texture file for the sprite that {@link ScannerSprites} names, because the block
 * atlas lives on the graphics card and cannot be read back. The first frame of an animated texture is the cell's. Each texture is
 * scaled to the cell by a small supersample (see {@link #scale(int[], int, int, int)}), so the grain stays where it is and keeps its contrast. The scaled tiles are kept until the resources reload.
 */
final class ScannerMap {
    private static final Logger LOGGER = LoggerFactory.getLogger("ScannerMap");
    private static final TagKey<Block> GOLD_ORES =
            TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("c", "ores/gold"));
    private static final Identifier ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "scanner_map");

    /** The alpha byte of an opaque ARGB colour: a mask, not a colour. */
    private static final int OPAQUE = 255 << 24;

    private static final int SUPERSAMPLE = 2;

    private static DynamicTexture texture;
    private static int textureWidth;
    private static int textureHeight;
    /** What the texture shows, so that a frame with the same slice at the same size and look draws without a rebuild. */
    private static ScanSlice builtFrom;

    private static int builtPixels;
    private static ScannerLook builtLook;
    /** The model set the picture was read from: a resource reload bakes a new one, and a held slice must then be drawn again from the new pack. */
    private static BlockStateModelSet builtModels;

    /** Scaled tiles by sprite and size, for the model set they were read from; a resource reload bakes a new set, which drops them. A sprite with no readable file is an empty entry. */
    private static final Map<TileKey, Optional<int[]>> TILES = new HashMap<>();

    private static BlockStateModelSet tilesFrom;

    private record TileKey(List<Identifier> sprites, int pixels, int backdrop) {}

    private ScannerMap() {}

    /** Draws the known cells of {@code slice}, {@code cell} GUI pixels each, the top left of the area at ({@code left}, {@code top}). Air is left as it is. */
    static void draw(GuiGraphicsExtractor graphics, ScanSlice slice, ScannerLook look, int cell, int left, int top) {
        ScanArea area = slice.area();
        int pixels = cell * Minecraft.getInstance().getWindow().getGuiScale();
        BlockStateModelSet models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        if (slice != builtFrom || pixels != builtPixels || !look.equals(builtLook) || models != builtModels) {
            build(slice, look, pixels, models);
            builtFrom = slice;
            builtPixels = pixels;
            builtLook = look;
            builtModels = models;
        }
        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                ID,
                left,
                top,
                0f,
                0f,
                area.columns() * cell,
                area.rows() * cell,
                textureWidth,
                textureHeight,
                textureWidth,
                textureHeight);
    }

    /** Drops the picture and everything kept for it, when the player leaves the world: the texture can be large and nothing draws it until the next scan. */
    static void release() {
        if (texture != null) {
            Minecraft.getInstance().getTextureManager().release(ID);
            texture = null;
        }
        textureWidth = 0;
        textureHeight = 0;
        builtFrom = null;
        builtLook = null;
        builtModels = null;
        TILES.clear();
        tilesFrom = null;
    }

    private static void build(ScanSlice slice, ScannerLook look, int pixels, BlockStateModelSet models) {
        ScanArea area = slice.area();
        int width = area.columns() * pixels;
        int height = area.rows() * pixels;
        Minecraft client = Minecraft.getInstance();
        if (models != tilesFrom) {
            TILES.clear();
            tilesFrom = models;
        }
        if (texture == null || width != textureWidth || height != textureHeight) {
            if (texture != null) {
                client.getTextureManager().release(ID);
            }
            texture = new DynamicTexture(() -> "deepcharter scanner map", width, height, true);
            client.getTextureManager().register(ID, texture);
            textureWidth = width;
            textureHeight = height;
        }
        NativeImage image = texture.getPixels();
        boolean small = pixels < look.oreMarkBelowPixels();
        for (int up = area.up(); up >= -area.down(); up--) {
            for (int ahead = -area.halfWidth(); ahead <= area.halfWidth(); ahead++) {
                Cell found = slice.cell(ahead, up);
                int x = (ahead + area.halfWidth()) * pixels;
                int y = (area.up() - up) * pixels;
                if (found instanceof Cell.Air) {
                    fillTile(image, x, y, pixels, 0);
                } else if (small && found instanceof Cell.Ore ore) {
                    fillTile(image, x, y, pixels, oreMark(look, ore));
                } else {
                    Optional<int[]> tile = tile(ScannerSprites.of(slice, found, ahead, up), pixels, look.airColor());
                    if (tile.isEmpty()) {
                        fillTile(image, x, y, pixels, flatColour(look, found));
                    } else {
                        copyTile(
                                image,
                                x,
                                y,
                                pixels,
                                tile.get(),
                                overlay(look, found, small),
                                pixels >= look.oreOutlineMinPixels() ? outline(look, found) : 0);
                    }
                }
            }
        }
        texture.upload();
    }

    private static void fillTile(NativeImage image, int x, int y, int pixels, int argb) {
        for (int dy = 0; dy < pixels; dy++) {
            for (int dx = 0; dx < pixels; dx++) {
                image.setPixel(x + dx, y + dy, argb);
            }
        }
    }

    /** The tile with {@code overlay} laid over it, and a one-pixel frame of {@code outline} round its edge ({@code 0}: none). */
    private static void copyTile(NativeImage image, int x, int y, int pixels, int[] tile, int overlay, int outline) {
        for (int dy = 0; dy < pixels; dy++) {
            for (int dx = 0; dx < pixels; dx++) {
                int argb = over(overlay, tile[dy * pixels + dx]);
                boolean edge = dx == 0 || dy == 0 || dx == pixels - 1 || dy == pixels - 1;
                image.setPixel(x + dx, y + dy, edge ? over(outline, argb) : argb);
            }
        }
    }

    /** The colour laid over a cell's texture, {@code small} when cells are too small to read a texture ({@link ScannerLook#oreMarkBelowPixels()}); transparent for a cell that has none. */
    private static int overlay(ScannerLook look, Cell cell, boolean small) {
        return switch (cell) {
            case Cell.Air air -> 0;
            case Cell.Rock rock -> small ? look.smallRockShadeColor() : look.rockShadeColor();
            case Cell.Lava lava -> look.lavaTintColor();
            case Cell.LavaNear near -> look.lavaNearShadeColor();
            case Cell.Gas gas -> look.gasTintColor();
            case Cell.Ore ore -> isGold(ore) ? look.goldOreLiftColor() : look.oreLiftColor();
        };
    }

    /** The colour of the one-pixel frame round a cell; transparent for a cell that has none. */
    private static int outline(ScannerLook look, Cell cell) {
        return cell instanceof Cell.Ore ore ? (isGold(ore) ? look.goldOreOutlineColor() : look.oreOutlineColor()) : 0;
    }

    /** The flat mark an ore cell is drawn as when cells are too small to read a texture: the mod's ore in its own colour, any other ore in the generic ore colours. */
    private static int oreMark(ScannerLook look, Cell.Ore ore) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(ore.block());
        for (OreType type : OreType.values()) {
            if (type.blockId().equals(id)) {
                return look.oreMarkColors().get(type);
            }
        }
        return flatColour(look, ore);
    }

    /** Opaque ARGB for a cell with no usable sprite; ore gets its own colour per ore, falling back to a generic one. */
    private static int flatColour(ScannerLook look, Cell cell) {
        return switch (cell) {
            case Cell.Air air -> look.airColor();
            case Cell.Rock rock -> look.rockColor();
            case Cell.Lava lava -> look.lavaColor();
            case Cell.LavaNear near -> look.lavaNearColor();
            case Cell.Gas gas -> look.gasColor();
            case Cell.Ore ore -> isGold(ore) ? look.goldOreColor() : look.oreColor();
        };
    }

    private static boolean isGold(Cell.Ore ore) {
        return ore.block().defaultBlockState().is(GOLD_ORES);
    }

    /** {@code top} (ARGB, any alpha) laid over the opaque {@code bottom}: an opaque ARGB. */
    static int over(int top, int bottom) {
        int alpha = top >>> 24;
        if (alpha == 0) {
            return bottom;
        }
        int result = OPAQUE;
        for (int shift = 0; shift <= 16; shift += 8) {
            int blended = (((top >> shift) & 0xFF) * alpha + ((bottom >> shift) & 0xFF) * (255 - alpha) + 127) / 255;
            result |= blended << shift;
        }
        return result;
    }

    /** The layers laid over each other, bottom first, over {@code backdrop}, each scaled to {@code pixels} square; empty when there are none or one cannot be read. */
    private static Optional<int[]> tile(List<TextureAtlasSprite> layers, int pixels, int backdrop) {
        if (layers.isEmpty()) {
            return Optional.empty();
        }
        return TILES.computeIfAbsent(
                new TileKey(
                        layers.stream().map(sprite -> sprite.contents().name()).toList(), pixels, backdrop),
                key -> compose(layers, pixels, backdrop));
    }

    private static Optional<int[]> compose(List<TextureAtlasSprite> layers, int pixels, int backdrop) {
        int[] tile = new int[pixels * pixels];
        Arrays.fill(tile, OPAQUE | backdrop);
        for (TextureAtlasSprite layer : layers) {
            Optional<int[]> scaled = read(layer, pixels);
            if (scaled.isEmpty()) {
                return Optional.empty();
            }
            for (int i = 0; i < tile.length; i++) {
                tile[i] = over(scaled.get()[i], tile[i]);
            }
        }
        return Optional.of(tile);
    }

    /** The first frame of the sprite's texture file in the active packs, scaled to {@code pixels} square, with alpha; empty when the file cannot be read. */
    private static Optional<int[]> read(TextureAtlasSprite sprite, int pixels) {
        Identifier name = sprite.contents().name();
        Identifier file = name.withPrefix("textures/").withSuffix(".png");
        try (InputStream stream = Minecraft.getInstance()
                        .getResourceManager()
                        .getResourceOrThrow(file)
                        .open();
                NativeImage source = NativeImage.read(stream)) {
            return Optional.of(
                    scale(source, sprite.contents().width(), sprite.contents().height(), pixels));
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("The scanner cannot read {} to draw it: {}", file, e.toString());
            return Optional.empty();
        }
    }

    /**
     * The top left {@code frameWidth} by {@code frameHeight} pixels of {@code source} scaled to {@code size} square by supersample. ARGB: each result pixel's
     * alpha is how much of the frame under it is not see-through.
     */
    static int[] scale(NativeImage source, int frameWidth, int frameHeight, int size) {
        int[] argb = new int[frameWidth * frameHeight];
        for (int y = 0; y < frameHeight; y++) {
            for (int x = 0; x < frameWidth; x++) {
                argb[y * frameWidth + x] = source.getPixel(x, y);
            }
        }
        return scale(argb, frameWidth, frameHeight, size);
    }

    /**
     * As {@link #scale(NativeImage, int, int, int)}, for the pixels of the frame, row by row. Each result pixel is the mean of a
     * {@value #SUPERSAMPLE} by {@value #SUPERSAMPLE} grid of points over the part of the frame it covers: smoother than one point, where a
     * speck of the grain is kept or lost by luck, and crisper than the mean of the whole part, which flattens a fine grain to grey.
     */
    static int[] scale(int[] argb, int frameWidth, int frameHeight, int size) {
        int[] scaled = new int[size * size];
        for (int oy = 0; oy < size; oy++) {
            for (int ox = 0; ox < size; ox++) {
                double red = 0;
                double green = 0;
                double blue = 0;
                double alpha = 0;
                for (int gy = 0; gy < SUPERSAMPLE; gy++) {
                    int sy = Math.min(frameHeight - 1, (int) ((oy + (gy + 0.5) / SUPERSAMPLE) * frameHeight / size));
                    for (int gx = 0; gx < SUPERSAMPLE; gx++) {
                        int sx = Math.min(frameWidth - 1, (int) ((ox + (gx + 0.5) / SUPERSAMPLE) * frameWidth / size));
                        int pixel = argb[sy * frameWidth + sx];
                        double a = (pixel >>> 24) / 255.0;
                        // Colour counts by its alpha, so a see-through pixel does not drag the cell towards black.
                        red += a * ((pixel >> 16) & 0xFF);
                        green += a * ((pixel >> 8) & 0xFF);
                        blue += a * (pixel & 0xFF);
                        alpha += a;
                    }
                }
                int colour = alpha == 0
                        ? 0
                        : ((int) Math.round(red / alpha) << 16)
                                | ((int) Math.round(green / alpha) << 8)
                                | (int) Math.round(blue / alpha);
                int coverage = (int) Math.round(255 * alpha / (SUPERSAMPLE * SUPERSAMPLE));
                scaled[oy * size + ox] = coverage << 24 | colour;
            }
        }
        return scaled;
    }
}
