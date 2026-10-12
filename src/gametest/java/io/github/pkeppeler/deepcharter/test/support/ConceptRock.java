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
 * A stand-in for Company Rock for the concept round (#503, docs/design/company-rock-concepts.md): the same block with {@code noOcclusion()}, so a
 * stud, strap or crystal is lit from its neighbours and not drawn black. Registered by the test mod only, in an entrypoint because a block can only be
 * registered while mods initialise.
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
