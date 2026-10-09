package io.github.pkeppeler.deepcharter.ore;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

import io.github.pkeppeler.deepcharter.DeepCharter;

/**
 * Slag brick (#313), the lining of a bore. The ore processor fuses a pod's spoil into it, and a seated pilot places it round the
 * pod's slab. It is a plain full block, so lava neither flows into it nor replaces it. The rock a drill keeps as spoil is the
 * blocks in {@link #WASTE_ROCK}, which is data.
 */
public final class SlagBrick {
	/** The blocks a drill keeps as spoil when it bores them: stone and dirt. Ore is cargo, and a gas pocket vents. */
	public static final TagKey<Block> WASTE_ROCK = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "waste_rock"));

	public static final Block BLOCK = HazardBlocks.registerBlock(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "slag_brick"),
			key -> new Block(BlockBehaviour.Properties.of().setId(key).strength(2.0F, 12.0F).sound(SoundType.DEEPSLATE_BRICKS)));

	private SlagBrick() {
	}

	/** Loads the class, which registers the block and its item. */
	public static void register() {
	}

	public static Item item() {
		return BLOCK.asItem();
	}
}
