package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.List;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import io.github.pkeppeler.deepcharter.test.evidence.TextureTestWorld.Variant;

/**
 * Evidence scenario "ore-look" for #367 (docs/design/ores.md): the ore look as it ships, each ore the host stone's own texture with
 * the ore art over it. The views ({@link TextureTestWorld}): the lamp-lit cavern wall with every ore, the wall close up, the wall with
 * the lamp out, the shaft wall at mid distance, the seam wall, where every ore sits in plain stone, whole and close up, and the
 * reference wall, where each ore has a column of its own on plain stone.
 */
public class OreLookScenario extends EvidenceScenario {
	/** The one variant: the mod as it ships, with no test pack on. */
	private static final List<Variant> VARIANTS = List.of(new Variant("ore", null));

	@Override
	protected String name() {
		return "ore-look";
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
			world.buildReferenceWall();
			world.eachVariant(VARIANTS, variant -> {
				world.cavernWall(variant);
				world.shaftWall(variant);
				world.seamWall(variant);
				world.referenceWall(variant);
			});
		});
	}
}
