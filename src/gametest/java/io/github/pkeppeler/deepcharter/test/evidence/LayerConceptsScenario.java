package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.test.support.TestPacks;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * Evidence scenario "layer-concepts" for #447 and #241 (docs/design/layer-concepts-2.md): the scene of each of layers 1, 2 and 3, shot from the same
 * ten places in the rock: a gallery (lamp-lit, at the fog edge, at a Company Rock, at a lava flow, at the breach crust, close up), a hall (a wide flat wall,
 * and the layer's rock beside vanilla stone and deepslate) and a natural cave. The rock of layers 1 and 2 and the lava are the mod's own (the user's
 * pick, Grain); layer 3's rock is the Grain test pack, which only this scenario turns on. The scenes and the cameras are data
 * ({@code tools/layer_concepts/make.py} writes them).
 */
public class LayerConceptsScenario extends EvidenceScenario {
    /** The look of the stills' name: the user's pick. */
    private static final String OPTION = "grain";
    /** The form pack of each layer: its Company Rock and breach crust. */
    private static final Map<Integer, String> FORMS = Map.of(
            1, TestPacks.LAYER_CONCEPT_FORM_1, 2, TestPacks.LAYER_CONCEPT_FORM_2, 3, TestPacks.LAYER_CONCEPT_FORM_3);

    @Override
    protected String name() {
        return "layer-concepts";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        LayerConceptsWorld.open(
                context,
                still -> {
                    screenshot(context, still);
                    frame(context);
                },
                world -> {
                    world.shootStyle(OPTION, TestPacks.LAYER_CONCEPT_GRAIN, FORMS);
                    if (world.stills() == 0) {
                        throw new IllegalStateException("the layer-concepts run shot no still");
                    }
                });
    }
}
