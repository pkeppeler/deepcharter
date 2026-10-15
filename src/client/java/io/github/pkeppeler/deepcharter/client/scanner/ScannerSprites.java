package io.github.pkeppeler.deepcharter.client.scanner;

import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.scanner.ScanSlice;
import io.github.pkeppeler.deepcharter.scanner.ScanSlice.Cell;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The block-atlas sprites that draw a scanner cell, bottom layer first: the layers the active resource pack's model of the block puts on its
 * {@value #FACE} face, which is the block as the world shows it from the side. A plain block is one sprite (stone, Company Rock, a wreck
 * block). An ore is two: the host rock, then the ore's own overlay of flecks, which is why the particle sprite, always the host, is not
 * enough. Reading the layers from the model, not the texture files, means a pack that restyles a block restyles its scanner cell.
 * The layers come from the model's first variant (a seeded pick), so a block state looks the same in every cell.
 *
 * <p>A block with no quads on that face (a model that is not a cube) uses its particle sprite. A block with no model, or whose sprites
 * are all the missing-texture sprite, has no usable layers: {@link #of} is empty and the HUD draws the theme's flat colour.
 */
final class ScannerSprites {
    private static final Direction FACE = Direction.NORTH;
    /** Layers by block state for the model set they were read from; a resource reload bakes a new set, which drops them all. */
    private static final Map<BlockState, List<TextureAtlasSprite>> BY_STATE = new IdentityHashMap<>();

    private static BlockStateModelSet readFrom;

    private ScannerSprites() {}

    /** The layers for the cell {@code (ahead, up)} of {@code slice}, which holds {@code cell}; empty for open space and for a cell with no usable sprite. */
    static List<TextureAtlasSprite> of(ScanSlice slice, Cell cell, int ahead, int up) {
        return switch (cell) {
            case Cell.Air air -> List.of();
            case Cell.Rock rock -> ofState(slice.state(ahead, up));
            case Cell.Ore ore -> ofState(ore.state());
            case Cell.Lava lava -> lava();
            case Cell.LavaNear near -> lava();
            case Cell.Gas gas -> ofState(HazardBlocks.GAS_POCKET.defaultBlockState());
        };
    }

    private static List<TextureAtlasSprite> ofState(BlockState state) {
        BlockStateModelSet models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        if (models != readFrom) {
            BY_STATE.clear();
            readFrom = models;
        }
        return BY_STATE.computeIfAbsent(state, s -> layers(models.get(s)));
    }

    private static List<TextureAtlasSprite> layers(BlockStateModel model) {
        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(RandomSource.create(0), parts);
        List<TextureAtlasSprite> layers = new ArrayList<>();
        for (BlockStateModelPart part : parts) {
            // Quads with a cull face and quads without one, which an overlay element usually is.
            for (Direction cull : new Direction[] {FACE, null}) {
                for (BakedQuad quad : part.getQuads(cull)) {
                    if (quad.direction() == FACE) {
                        layers.add(quad.materialInfo().sprite());
                    }
                }
            }
        }
        if (layers.isEmpty()) {
            layers.add(model.particleMaterial().sprite());
        }
        return layers.stream().distinct().filter(ScannerSprites::usable).toList();
    }

    /** Lava is a fluid: its sprite is the fluid model's still one, not a block model's. */
    private static List<TextureAtlasSprite> lava() {
        TextureAtlasSprite still = Minecraft.getInstance()
                .getModelManager()
                .getFluidStateModelSet()
                .get(Blocks.LAVA.defaultBlockState().getFluidState())
                .stillMaterial()
                .sprite();
        return usable(still) ? List.of(still) : List.of();
    }

    private static boolean usable(TextureAtlasSprite sprite) {
        return !sprite.contents().name().equals(MissingTextureAtlasSprite.getLocation());
    }
}
