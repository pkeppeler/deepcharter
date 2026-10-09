package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.List;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import io.github.pkeppeler.deepcharter.test.evidence.TextureTestWorld.Variant;
import io.github.pkeppeler.deepcharter.test.support.TestPacks;

/**
 * Evidence scenario "texture-overlays" for #354 (docs/design/texture-density-2.md): the ore overlay styles B1 to B4, each a test
 * pack that draws an ore as vanilla stone's own texture with our ore art over it, and A, the mod as it ships, for reference. The
 * views ({@link TextureTestWorld}): the lamp-lit cavern wall with every ore, the wall close up, the wall with the lamp out, the shaft
 * wall at mid distance, and the seam wall, where every ore sits in plain stone, whole and close up.
 */
public class TextureOverlayScenario extends EvidenceScenario {
	/** The variants, in order, with the test pack each turns on (none for A). */
	private static final List<Variant> VARIANTS = List.of(new Variant("a", null), new Variant("b1", TestPacks.TEXTURE_OVERLAY_B1),
			new Variant("b2", TestPacks.TEXTURE_OVERLAY_B2), new Variant("b3", TestPacks.TEXTURE_OVERLAY_B3),
			new Variant("b4", TestPacks.TEXTURE_OVERLAY_B4));

	@Override
	protected String name() {
		return "texture-overlays";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		TextureTestWorld.open(context, still -> {
			screenshot(context, still);
			frame(context);
		}, world -> {
			world.buildCavern();
			world.buildShaft();
			world.buildSeamWall();
			world.eachVariant(VARIANTS, variant -> {
				world.cavernWall(variant);
				world.shaftWall(variant);
				world.seamWall(variant);
			});
		});
	}
}
