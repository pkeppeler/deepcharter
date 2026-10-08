package io.github.pkeppeler.deepcharter.colony;

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

/** Blocks of the colony feature. */
public final class ColonyBlocks {
	private static final Identifier CONDUIT_ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "conduit");

	/**
	 * The Conduit's casing. Like bedrock in survival: hardness -1, so hands cannot break it and a pod's drill refuses any bore
	 * that holds it, and a blast no vanilla block survives.
	 */
	public static final Block CONDUIT = register(CONDUIT_ID);

	private ColonyBlocks() {
	}

	/** Loads the class, which registers the blocks. */
	public static void register() {
	}

	private static Block register(Identifier id) {
		ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, id);
		Block block = Registry.register(BuiltInRegistries.BLOCK, blockKey, new Block(BlockBehaviour.Properties.of()
				.setId(blockKey).strength(-1.0F, 3_600_000.0F).sound(SoundType.METAL).noLootTable()));
		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, id);
		Registry.register(BuiltInRegistries.ITEM, itemKey, new BlockItem(block, new Item.Properties().setId(itemKey).useBlockDescriptionPrefix()));
		return block;
	}
}
