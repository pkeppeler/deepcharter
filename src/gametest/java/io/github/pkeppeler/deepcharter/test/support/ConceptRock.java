package io.github.pkeppeler.deepcharter.test.support;

import net.fabricmc.api.ModInitializer;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * A stand-in for Company Rock that the Company Rock concept round (#503, docs/design/company-rock-concepts.md) places for the options that are
 * more than a cube. It is Company Rock with {@code noOcclusion()}, the way the mod's other shaped blocks are: the game lights a quad that is not
 * on a block's own face from the block's own cell, an opaque cell holds no light, so a stud, a strap or a crystal on an opaque block is drawn black.
 * A block that lets light in has the cell lit from its neighbours. The shipped Company Rock stays opaque until a look is picked; the pick's build
 * sets {@code noOcclusion()} on it if the look needs it. The test packs of the round draw this block's blockstate. Registered by the test mod
 * only, so no asset test asks for its files and the game never places it. A block can only be registered while mods initialise, which is why
 * this is an entrypoint.
 */
public final class ConceptRock implements ModInitializer {
	public static final Block BLOCK = register();

	private static Block register() {
		ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("deepcharter_test", "company_rock"));
		return Registry.register(BuiltInRegistries.BLOCK, key,
				new Block(BlockBehaviour.Properties.of().setId(key).strength(-1.0F, 3600000.0F).sound(SoundType.STONE).noOcclusion().noLootTable()));
	}

	@Override
	public void onInitialize() {
		// The registration above runs when the class loads.
	}
}
