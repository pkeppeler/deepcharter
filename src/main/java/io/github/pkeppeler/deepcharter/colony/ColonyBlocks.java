package io.github.pkeppeler.deepcharter.colony;

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
 * Blocks of the colony feature. Each is Company property and, like bedrock in survival, unbreakable: hardness -1, so hands cannot
 * break it and a pod's drill refuses any bore that holds it, and a blast no vanilla block survives.
 */
public final class ColonyBlocks {
	/** The Conduit's casing, drawn as a connected casing (its blockstate names the model, ADR 0037). */
	public static final Block CONDUIT = register("conduit", SoundType.METAL, Block::new);

	/** The Company's caged sodium lamp, lit or dark. Nothing places it yet: the colony rebuild (#244) and lighting (#248) will. */
	public static final Block COMPANY_LAMP = register("company_lamp", SoundType.LANTERN, properties -> new CompanyLamp(properties.noOcclusion()));

	private ColonyBlocks() {
	}

	/** Loads the class, which registers the blocks. */
	public static void register() {
	}

	private static Block register(String path, SoundType sound, Function<BlockBehaviour.Properties, Block> factory) {
		Identifier id = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path);
		ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, id);
		Block block = Registry.register(BuiltInRegistries.BLOCK, blockKey, factory.apply(BlockBehaviour.Properties.of()
				.setId(blockKey).strength(-1.0F, 3_600_000.0F).sound(sound).noLootTable()));
		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, id);
		Registry.register(BuiltInRegistries.ITEM, itemKey, new BlockItem(block, new Item.Properties().setId(itemKey).useBlockDescriptionPrefix()));
		return block;
	}
}
