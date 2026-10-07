package io.github.pkeppeler.deepcharter.layer;

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

/** Blocks of the layer feature. */
public final class LayerBlocks {
	private static final Identifier BREACH_CRUST_ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "breach_crust");

	/** The soft crust at each layer floor. Placeholder hardness; #28 owns how drills cross it. */
	public static final Block BREACH_CRUST = registerBlock(BREACH_CRUST_ID,
			key -> new Block(BlockBehaviour.Properties.of().setId(key).strength(5.0F, 6.0F).sound(SoundType.STONE).noLootTable()));

	private LayerBlocks() {
	}

	/** Loads the class, which registers the blocks. */
	public static void register() {
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
