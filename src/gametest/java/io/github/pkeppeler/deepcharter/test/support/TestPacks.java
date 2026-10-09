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
	/**
	 * Ore overlay B1 (#354, docs/design/texture-density-2.md): B's clumps as a cutout layer over the real stone. Each overlay pack
	 * redraws only the ores, over vanilla stone's own texture, and replaces nothing of vanilla.
	 */
	public static final String TEXTURE_OVERLAY_B1 = "texture_overlay_b1";
	/** Ore overlay B2: smaller, sparser clumps, tinted toward the stone's greys. */
	public static final String TEXTURE_OVERLAY_B2 = "texture_overlay_b2";
	/** Ore overlay B3: seams that follow the stone's grain and run off the face. */
	public static final String TEXTURE_OVERLAY_B3 = "texture_overlay_b3";
	/** Ore overlay B4: clumps sunk in a soft dark socket, with a glint. */
	public static final String TEXTURE_OVERLAY_B4 = "texture_overlay_b4";

	@Override
	public void onInitialize() {
		for (String pack : new String[] {AMBER_CRT, BAD_CRT, RED_INK, TEXTURE_DENSITY_B, TEXTURE_DENSITY_C, TEXTURE_DENSITY_D,
				TEXTURE_OVERLAY_B1, TEXTURE_OVERLAY_B2, TEXTURE_OVERLAY_B3, TEXTURE_OVERLAY_B4}) {
			ResourceLoader.registerBuiltinPack(Identifier.fromNamespaceAndPath("deepcharter-test", pack),
					FabricLoader.getInstance().getModContainer("deepcharter-test").orElseThrow(),
					Component.literal(pack + " (test pack)"), PackActivationType.NORMAL);
		}
	}
}
