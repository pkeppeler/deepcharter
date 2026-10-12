package io.github.pkeppeler.deepcharter.test.support;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.pack.PackActivationType;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * Pins natural spawning off in every world a test starts, and registers the resource packs under {@code src/gametest/resources/resourcepacks/} as built-in packs that nothing turns on by itself: a
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
	/** The cockpit HUD is off ({@code theme/cockpit.json}'s {@code enabled} 0): the plain HUD shows while seated. The Ribbon is the mod's own look (#247). */
	public static final String COCKPIT_OFF = "cockpit_off";
	/** The front camera is on the pod (#448): a stand-in for the part, which a later build buys at the upgrade terminal. */
	public static final String COCKPIT_CAMERA = "cockpit_camera";
	/** The front camera, with static that does not fade, so a still can hold a hit's static. */
	public static final String COCKPIT_CAMERA_HOLD = "cockpit_camera_hold";

	/** The salvage crate's other shapes (#245, docs/design/salvage-concepts.md): a low sled crate under a tarp, and a stove-in crate. The default, the freight crate, is the mod's own look. */
	public static final String SALVAGE_CRATE_B = "salvage_crate_b";
	public static final String SALVAGE_CRATE_C = "salvage_crate_c";
	/** The fungal stalk's other shapes: a bare reed, and a beaded puffball. The default, the shelf fungus, is the mod's own look. */
	public static final String FUNGAL_STALK_A = "fungal_stalk_a";
	public static final String FUNGAL_STALK_C = "fungal_stalk_c";

	@Override
	public void onInitialize() {
		// One shared pin for every test and evidence world that enters a layer (the layers' biomes list the Retained): no natural spawns.
		ServerLifecycleEvents.SERVER_STARTED.register(server -> server.getGameRules().set(GameRules.SPAWN_MOBS, false, server));
		for (String pack : new String[] {AMBER_CRT, BAD_CRT, RED_INK, TEXTURE_DENSITY_B, TEXTURE_DENSITY_C, TEXTURE_DENSITY_D,
				LAYER_CONCEPT_GRAIN, LAYER_CONCEPT_BEDDED, LAYER_CONCEPT_MOTTLED, LAYER_CONCEPT_FORM_1, LAYER_CONCEPT_FORM_2, LAYER_CONCEPT_FORM_3,
				COCKPIT_OFF, COCKPIT_CAMERA, COCKPIT_CAMERA_HOLD,
				SALVAGE_CRATE_B, SALVAGE_CRATE_C, FUNGAL_STALK_A, FUNGAL_STALK_C}) {
			ResourceLoader.registerBuiltinPack(Identifier.fromNamespaceAndPath("deepcharter-test", pack),
					FabricLoader.getInstance().getModContainer("deepcharter-test").orElseThrow(),
					Component.literal(pack + " (test pack)"), PackActivationType.NORMAL);
		}
	}
}
