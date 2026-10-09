package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.List;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.test.evidence.TextureTestWorld.Variant;
import io.github.pkeppeler.deepcharter.test.support.TestPacks;

/**
 * Evidence scenario "texture-density" for #336 (docs/design/texture-density.md): the same views with each texture density variant.
 * A is the mod as it ships; B, C and D are test packs. The views ({@link TextureTestWorld}): the lamp-lit cavern wall with every ore,
 * the wall close up, the wall with the lamp out, the shaft wall at mid distance, the regolith of an open plain at noon, and the
 * terminal room (C redraws the terminals).
 */
public class TextureDensityScenario extends EvidenceScenario {
	/** The variants, in order, with the test pack each turns on (none for A). */
	private static final List<Variant> VARIANTS = List.of(new Variant("a", null), new Variant("b", TestPacks.TEXTURE_DENSITY_B),
			new Variant("c", TestPacks.TEXTURE_DENSITY_C), new Variant("d", TestPacks.TEXTURE_DENSITY_D));

	@Override
	protected String name() {
		return "texture-density";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		TextureTestWorld.open(context, still -> {
			screenshot(context, still);
			frame(context);
		}, world -> {
			Vec3 plain = world.regolithPlain();
			world.buildCavern();
			world.buildShaft();
			world.buildTerminalRoom();
			world.eachVariant(VARIANTS, variant -> {
				world.cavernWall(variant);
				world.shaftWall(variant);
				world.surface(variant, plain);
				world.terminals(variant);
			});
		});
	}
}
