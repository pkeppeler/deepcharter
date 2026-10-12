package io.github.pkeppeler.deepcharter.client.scanner;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.scanner.ScanSlice;
import io.github.pkeppeler.deepcharter.scanner.ScanSlice.Cell;

/**
 * The block-atlas sprite that draws a scanner cell: the one sprite the active resource pack's model of the block names as its particle
 * sprite, which is the texture the block breaks into. That is the block's own face for stone, ores, Company Rock and wreck blocks, and the
 * one-sprite summary the pack chose for a block with several faces. Reading it from the model, not the texture file, means a pack that
 * restyles a block restyles its scanner cell.
 *
 * <p>A block with no model, or whose model names the missing-texture sprite, has no usable sprite: {@link #of} is empty and the HUD
 * draws the theme's flat colour.
 */
final class ScannerSprites {
	/** Sprites by block for the model set they were read from; a resource reload bakes a new set, which drops them all. */
	private static final Map<Block, Optional<TextureAtlasSprite>> BY_BLOCK = new IdentityHashMap<>();
	private static BlockStateModelSet readFrom;

	private ScannerSprites() {
	}

	/** The sprite for the cell {@code (ahead, up)} of {@code slice}, which holds {@code cell}; empty for open space and for a cell with no usable sprite. */
	static Optional<TextureAtlasSprite> of(ScanSlice slice, Cell cell, int ahead, int up) {
		return switch (cell) {
			case Cell.Air air -> Optional.empty();
			case Cell.Rock rock -> ofBlock(slice.block(ahead, up));
			case Cell.Ore ore -> ofBlock(ore.block());
			case Cell.Lava lava -> lava();
			case Cell.LavaNear near -> lava();
			case Cell.Gas gas -> ofBlock(HazardBlocks.GAS_POCKET);
		};
	}

	private static Optional<TextureAtlasSprite> ofBlock(Block block) {
		BlockStateModelSet models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
		if (models != readFrom) {
			BY_BLOCK.clear();
			readFrom = models;
		}
		return BY_BLOCK.computeIfAbsent(block, b -> usable(models.getParticleMaterial(b.defaultBlockState()).sprite()));
	}

	/** Lava is a fluid: its sprite is the fluid model's still one, not a block model's. */
	private static Optional<TextureAtlasSprite> lava() {
		return usable(Minecraft.getInstance().getModelManager().getFluidStateModelSet().get(Blocks.LAVA.defaultBlockState().getFluidState()).stillMaterial().sprite());
	}

	private static Optional<TextureAtlasSprite> usable(TextureAtlasSprite sprite) {
		return sprite.contents().name().equals(MissingTextureAtlasSprite.getLocation()) ? Optional.empty() : Optional.of(sprite);
	}
}
