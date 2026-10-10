package io.github.pkeppeler.deepcharter.test.support;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.pack.PackActivationType;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Registers the resource packs under {@code src/gametest/resources/resourcepacks/} as built-in packs that nothing turns on by itself: a
 * test turns one on with {@link ClientPacks}, as a player does in the pack screen. Used by the UI theme test and its evidence scenario.
 */
public final class TestPacks implements ModInitializer {
	/** Recolours the CRT terminals from phosphor green to amber, naming only the keys that change. */
	public static final String AMBER_CRT = "amber_crt";

	/** Has one bad colour in {@code crt.json}, so a reload with it must fail and name the pack. */
	public static final String BAD_CRT = "bad_crt";
	/** Turns the handbook's ink red. */
	public static final String RED_INK = "red_ink";
	/** Texture density B (#336, docs/design/texture-density.md): a richer 16x for the rock, the ores, Company rock and the surface. */
	public static final String TEXTURE_DENSITY_B = "texture_density_b";
	/** Texture density C: 32x for the same blocks and the terminals. */
	public static final String TEXTURE_DENSITY_C = "texture_density_c";
	/** Texture density D: B, with crystals and a glint standing out of every ore. */
	public static final String TEXTURE_DENSITY_D = "texture_density_d";
	/** The layer concepts (#241, docs/design/layer-concepts.md): the rock, Company Rock and breach crust of layers 1 and 2 as A, Strata. */
	public static final String LAYER_CONCEPT_A = "layer_concept_a";
	/** Layer concept B, Fractured. */
	public static final String LAYER_CONCEPT_B = "layer_concept_b";
	/** Layer concept C, Columnar. */
	public static final String LAYER_CONCEPT_C = "layer_concept_c";

	@Override
	public void onInitialize() {
		for (String pack : new String[] {AMBER_CRT, BAD_CRT, RED_INK, TEXTURE_DENSITY_B, TEXTURE_DENSITY_C, TEXTURE_DENSITY_D,
				LAYER_CONCEPT_A, LAYER_CONCEPT_B, LAYER_CONCEPT_C}) {
			ResourceLoader.registerBuiltinPack(Identifier.fromNamespaceAndPath("deepcharter-test", pack),
					FabricLoader.getInstance().getModContainer("deepcharter-test").orElseThrow(),
					Component.literal(pack + " (test pack)"), PackActivationType.NORMAL);
		}
	}
}
