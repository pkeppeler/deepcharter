package io.github.pkeppeler.deepcharter.ore;

import java.util.function.Function;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

import io.github.pkeppeler.deepcharter.DeepCharter;

/**
 * The blocks of the hazard bands (SPEC section 10) and the tags that say how they behave. Lava is vanilla's.
 */
public final class HazardBlocks {
	/** What a gas blast may clear: ordinary rock, ore and gas, and nothing a player built. */
	public static final TagKey<Block> NATURAL_ROCK = tag("natural_rock");
	/** What a pod drill refuses to bore, whatever its hardness. */
	public static final TagKey<Block> UNDIGGABLE = tag("undiggable");

	/** Unbreakable by hand (hardness -1) and, through {@link #UNDIGGABLE}, by a drill. */
	public static final Block COMPANY_ROCK = registerBlock(id("company_rock"),
			key -> new Block(BlockBehaviour.Properties.of().setId(key).strength(-1.0F, 3600000.0F).sound(SoundType.STONE).noLootTable()));

	/** Looks like stone, which is the point: it vents when it is mined (see {@link GasHazard}). */
	public static final Block GAS_POCKET = registerBlock(id("gas_pocket"),
			key -> new Block(BlockBehaviour.Properties.of().setId(key).strength(1.5F, 6.0F).sound(SoundType.STONE).noLootTable()));

	private HazardBlocks() {
	}

	/** Loads the class, which registers the blocks. */
	public static void register() {
	}

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path);
	}

	private static TagKey<Block> tag(String path) {
		return TagKey.create(Registries.BLOCK, id(path));
	}

	static Block registerBlock(Identifier id, Function<ResourceKey<Block>, Block> factory) {
		ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, id);
		Block block = Registry.register(BuiltInRegistries.BLOCK, blockKey, factory.apply(blockKey));
		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, id);
		Registry.register(BuiltInRegistries.ITEM, itemKey,
				new BlockItem(block, new Item.Properties().setId(itemKey).useBlockDescriptionPrefix()));
		return block;
	}
}
