package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.List;
import java.util.Map;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import io.github.pkeppeler.deepcharter.test.support.LayerConceptScenes;
import io.github.pkeppeler.deepcharter.test.support.TestPacks;

/**
 * Evidence scenario "layer-concepts" for #241 (docs/design/layer-concepts.md): each of the three options in layer 1 and in layer 2,
 * shot from the same five places in a gallery cut in the rock: lamp-lit, at the fog edge, at a Company Rock, at a lava flow and at the
 * breach crust. The options are test packs that only this scenario turns on, so nothing a player sees has changed. The scene and the
 * cameras are data ({@code tools/layer_concepts/make.py} writes them), and every look is in a pack.
 */
public class LayerConceptsScenario extends EvidenceScenario {
	/** The options, in order, with the test pack each turns on. */
	private static final Map<String, String> PACKS = Map.of("a", TestPacks.LAYER_CONCEPT_A, "b", TestPacks.LAYER_CONCEPT_B, "c", TestPacks.LAYER_CONCEPT_C);

	@Override
	protected String name() {
		return "layer-concepts";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		LayerConceptsWorld.open(context, still -> {
			screenshot(context, still);
			frame(context);
		}, world -> {
			// DEEPCHARTER_LAYER_OPTIONS=c shoots only option C, for a recording that a loaded machine cut short.
			String only = System.getenv("DEEPCHARTER_LAYER_OPTIONS");
			List<String> options = only == null ? LayerConceptScenes.OPTIONS : List.of(only.split(","));
			for (String option : options) {
				if (!LayerConceptScenes.OPTIONS.contains(option)) {
					throw new IllegalStateException("DEEPCHARTER_LAYER_OPTIONS has \"" + option + "\", which is not one of " + LayerConceptScenes.OPTIONS);
				}
			}
			for (String option : options) {
				world.shootOption(option, PACKS.get(option));
			}
			if (world.stills() == 0) {
				throw new IllegalStateException("the layer-concepts run shot no still");
			}
		});
	}
}
