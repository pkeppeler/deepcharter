package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.List;
import java.util.Map;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import io.github.pkeppeler.deepcharter.test.support.LayerConceptScenes;
import io.github.pkeppeler.deepcharter.test.support.TestPacks;

/**
 * Evidence scenario "layer-concepts" for #447 (docs/design/layer-concepts-2.md): each of the three texture options in the scene of each
 * of layers 1, 2 and 3, shot from the same ten places in the rock: a gallery (lamp-lit, at the fog edge, at a Company Rock, at a lava flow, at
 * the breach crust, close up), a hall (a wide flat wall, and the layer's rock beside vanilla stone and deepslate) and a natural cave. The
 * options are test packs that only this scenario turns on, so nothing a player sees has changed. The scenes and the cameras are data
 * ({@code tools/layer_concepts/make.py} writes them), and every look is in a pack.
 */
public class LayerConceptsScenario extends EvidenceScenario {
	/** The texture options, with the test pack each turns on. */
	private static final Map<String, String> PACKS = Map.of("grain", TestPacks.LAYER_CONCEPT_GRAIN, "bedded", TestPacks.LAYER_CONCEPT_BEDDED,
			"mottled", TestPacks.LAYER_CONCEPT_MOTTLED);
	/** The form pack of each layer: its Company Rock and breach crust. */
	private static final Map<Integer, String> FORMS = Map.of(1, TestPacks.LAYER_CONCEPT_FORM_1, 2, TestPacks.LAYER_CONCEPT_FORM_2,
			3, TestPacks.LAYER_CONCEPT_FORM_3);

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
			// DEEPCHARTER_LAYER_OPTIONS=mottled shoots only that option, for a recording that a loaded machine cut short.
			String only = System.getenv("DEEPCHARTER_LAYER_OPTIONS");
			List<String> options = only == null ? LayerConceptScenes.STYLES : List.of(only.split(","));
			for (String option : options) {
				if (!LayerConceptScenes.STYLES.contains(option)) {
					throw new IllegalStateException("DEEPCHARTER_LAYER_OPTIONS has \"" + option + "\", which is not one of " + LayerConceptScenes.STYLES);
				}
			}
			for (String option : options) {
				world.shootStyle(option, PACKS.get(option), FORMS);
			}
			if (world.stills() == 0) {
				throw new IllegalStateException("the layer-concepts run shot no still");
			}
		});
	}
}
