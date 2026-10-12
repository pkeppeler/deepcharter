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
	/** The layer concepts (#447, docs/design/layer-concepts-2.md): Grain, the user's pick, for the rock of layer 3, which has no block of its own until M3. */
	public static final String LAYER_CONCEPT_GRAIN = "layer_concept_grain";
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

	/** The salvage crate's other look (#245, #505, docs/design/salvage-concepts.md): a low sled crate under a tarp. The default, the freight crate, is the mod's own look. */
	public static final String SALVAGE_CRATE_B = "salvage_crate_b";
	/** The four looks of Company Rock (#503, docs/design/company-rock-concepts.md); the shipped steel plate is the mod's own look until one is picked. */
	public static final String COMPANY_ROCK_JOINT = "company_rock_joint";
	public static final String COMPANY_ROCK_STRAP = "company_rock_strap";
	public static final String COMPANY_ROCK_CRYSTAL = "company_rock_crystal";
	public static final String COMPANY_ROCK_FUSED = "company_rock_fused";

	/** The tow line as a braided steel cable (#508), the alternative to the mod's chain; it names the keys of {@code theme/tow.json} that change and redraws the texture. */
	public static final String TOW_CABLE_BRAID = "tow_cable_braid";

	@Override
	public void onInitialize() {
		// One shared pin for every test and evidence world that enters a layer (the layers' biomes list the Retained): no natural spawns.
		ServerLifecycleEvents.SERVER_STARTED.register(server -> server.getGameRules().set(GameRules.SPAWN_MOBS, false, server));
		for (String pack : new String[] {AMBER_CRT, BAD_CRT, RED_INK, TEXTURE_DENSITY_B, TEXTURE_DENSITY_C, TEXTURE_DENSITY_D,
				LAYER_CONCEPT_GRAIN, LAYER_CONCEPT_FORM_1, LAYER_CONCEPT_FORM_2, LAYER_CONCEPT_FORM_3,
				COCKPIT_OFF, COCKPIT_CAMERA, COCKPIT_CAMERA_HOLD,
				SALVAGE_CRATE_B, TOW_CABLE_BRAID,
				COMPANY_ROCK_JOINT, COMPANY_ROCK_STRAP, COMPANY_ROCK_CRYSTAL, COMPANY_ROCK_FUSED}) {
			ResourceLoader.registerBuiltinPack(Identifier.fromNamespaceAndPath("deepcharter-test", pack),
					FabricLoader.getInstance().getModContainer("deepcharter-test").orElseThrow(),
					Component.literal(pack + " (test pack)"), PackActivationType.NORMAL);
		}
	}
}
