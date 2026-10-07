package io.github.pkeppeler.deepcharter.ore;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

import io.github.pkeppeler.deepcharter.DeepCharter;

/**
 * Registers one ore item and one ore block per {@link OreType}, and the cargo menu. The blocks are what the drill
 * hits until #63 places ores in the world; no vanilla ore is ever cargo.
 */
public final class OreRegistry {
	private static final Map<OreType, Item> ITEMS = new EnumMap<>(OreType.class);
	private static final Map<OreType, Block> BLOCKS = new EnumMap<>(OreType.class);

	static {
		for (OreType type : OreType.values()) {
			ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, type.itemId());
			ITEMS.put(type, Registry.register(BuiltInRegistries.ITEM, itemKey,
					new OreItem(type, new Item.Properties().setId(itemKey).stacksTo(1))));
			ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, type.blockId());
			Block block = Registry.register(BuiltInRegistries.BLOCK, blockKey,
					new Block(BlockBehaviour.Properties.of().setId(blockKey).strength(3.0F, 3.0F).sound(SoundType.STONE)));
			BLOCKS.put(type, block);
			ResourceKey<Item> blockItemKey = ResourceKey.create(Registries.ITEM, type.blockId());
			Registry.register(BuiltInRegistries.ITEM, blockItemKey,
					new BlockItem(block, new Item.Properties().setId(blockItemKey).useBlockDescriptionPrefix()));
		}
	}

	public static final MenuType<OreCargoMenu> CARGO_MENU = Registry.register(BuiltInRegistries.MENU,
			Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "cargo"),
			new MenuType<>(OreCargoMenu::new, FeatureFlags.VANILLA_SET));

	private OreRegistry() {
	}

	/** Loads the class, which registers everything. */
	public static void register() {
	}

	public static Item item(OreType type) {
		return ITEMS.get(type);
	}

	public static Block block(OreType type) {
		return BLOCKS.get(type);
	}

	/** A new stack of one ore. */
	public static ItemStack stack(OreType type) {
		return new ItemStack(item(type));
	}

	/** The ore a drilled block yields, if it is one of ours. */
	public static Optional<OreType> typeOf(Block block) {
		return BLOCKS.entrySet().stream().filter(entry -> entry.getValue() == block).map(Map.Entry::getKey).findFirst();
	}

	/** The ore a stack holds, if it is one of ours. */
	public static Optional<OreType> typeOf(ItemStack stack) {
		return stack.getItem() instanceof OreItem ore ? Optional.of(ore.type()) : Optional.empty();
	}
}
