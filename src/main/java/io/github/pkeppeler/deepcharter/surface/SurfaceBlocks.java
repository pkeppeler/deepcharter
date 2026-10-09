package io.github.pkeppeler.deepcharter.surface;

import java.util.function.Function;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

import io.github.pkeppeler.deepcharter.DeepCharter;

/**
 * The blocks of the surface. The surface material rule ({@code worldgen/material_rule/surface.json}) places all of them. Their
 * looks are blockstate variants in the resource pack: how many textures a block has is data, not code.
 */
public final class SurfaceBlocks {
	/** Loose plains topsoil. */
	public static final Block REGOLITH = registerBlock(id("regolith"),
			key -> new Block(BlockBehaviour.Properties.of().setId(key).strength(0.5F).sound(SoundType.GRAVEL)));

	/** The packed layer under the topsoil. */
	public static final Block REGOLITH_PACKED = registerBlock(id("regolith_packed"),
			key -> new Block(BlockBehaviour.Properties.of().setId(key).strength(0.8F).sound(SoundType.GRAVEL)));

	/** Mesa topsoil. */
	public static final Block OCHRE_REGOLITH = registerBlock(id("ochre_regolith"),
			key -> new Block(BlockBehaviour.Properties.of().setId(key).strength(0.5F).sound(SoundType.GRAVEL)));

	/** The rust-red rock under the packed layer, down to where vanilla stone starts. */
	public static final Block REGOLITH_ROCK = registerBlock(id("regolith_rock"),
			key -> new Block(BlockBehaviour.Properties.of().setId(key).strength(1.5F, 6.0F).sound(SoundType.STONE)));

	/** Black basalt outcrops. */
	public static final Block BASALT_OUTCROP = registerBlock(id("basalt_outcrop"),
			key -> new Block(BlockBehaviour.Properties.of().setId(key).strength(2.0F, 6.0F).sound(SoundType.BASALT)));

	private SurfaceBlocks() {
	}

	/** Loads the class, which registers the blocks. */
	public static void register() {
	}

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path);
	}

	private static Block registerBlock(Identifier id, Function<ResourceKey<Block>, Block> factory) {
		ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, id);
		Block block = Registry.register(BuiltInRegistries.BLOCK, blockKey, factory.apply(blockKey));
		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, id);
		Registry.register(BuiltInRegistries.ITEM, itemKey,
				new BlockItem(block, new Item.Properties().setId(itemKey).useBlockDescriptionPrefix()));
		return block;
	}
}
