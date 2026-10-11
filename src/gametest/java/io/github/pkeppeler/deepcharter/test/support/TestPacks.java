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
	/** The layer concepts (#447, docs/design/layer-concepts-2.md): the texture of the rock of layers 1 to 3 as fine clustered grain. */
	public static final String LAYER_CONCEPT_GRAIN = "layer_concept_grain";
	/** Layer concept texture: bedding streaks that run across blocks. */
	public static final String LAYER_CONCEPT_BEDDED = "layer_concept_bedded";
	/** Layer concept texture: large mottles and veins. */
	public static final String LAYER_CONCEPT_MOTTLED = "layer_concept_mottled";
	/** The Company Rock and breach crust of layer 1, in its formation, Strata. A block has one blockstate, so a scene turns on its layer's form. */
	public static final String LAYER_CONCEPT_FORM_1 = "layer_concept_form_1";
	/** The Company Rock and breach crust of layer 2, Fractured. */
	public static final String LAYER_CONCEPT_FORM_2 = "layer_concept_form_2";
	/** The Company Rock and breach crust of layer 3, Columnar. */
	public static final String LAYER_CONCEPT_FORM_3 = "layer_concept_form_3";
	/** The cockpit HUD concepts (#444, docs/design/cockpit-concepts.md): each pack sets {@code theme/cockpit.json}'s {@code style} to one option. A, Dials. */
	public static final String COCKPIT_DIALS = "cockpit_dials";
	/** Cockpit concept B, Strip. */
	public static final String COCKPIT_STRIP = "cockpit_strip";
	/** Cockpit concept C, Consoles. */
	public static final String COCKPIT_CONSOLES = "cockpit_consoles";

	@Override
	public void onInitialize() {
		for (String pack : new String[] {AMBER_CRT, BAD_CRT, RED_INK, TEXTURE_DENSITY_B, TEXTURE_DENSITY_C, TEXTURE_DENSITY_D,
				LAYER_CONCEPT_GRAIN, LAYER_CONCEPT_BEDDED, LAYER_CONCEPT_MOTTLED, LAYER_CONCEPT_FORM_1, LAYER_CONCEPT_FORM_2, LAYER_CONCEPT_FORM_3,
				COCKPIT_DIALS, COCKPIT_STRIP, COCKPIT_CONSOLES}) {
			ResourceLoader.registerBuiltinPack(Identifier.fromNamespaceAndPath("deepcharter-test", pack),
					FabricLoader.getInstance().getModContainer("deepcharter-test").orElseThrow(),
					Component.literal(pack + " (test pack)"), PackActivationType.NORMAL);
		}
	}
}
