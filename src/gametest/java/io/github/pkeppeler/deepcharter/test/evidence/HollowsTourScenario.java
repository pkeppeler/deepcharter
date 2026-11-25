package io.github.pkeppeler.deepcharter.test.evidence;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * Evidence scenario "hollows-tour" (#972): the design tour's layer 3 stops alone, a cavern of each zone of the Fungal Hollows, in the
 * tour's world and with its settle and pins. The whole tour takes longer than the record job allows on CI's renderer.
 */
public class HollowsTourScenario extends EvidenceScenario {
    @Override
    protected String name() {
        return "hollows-tour";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        new DesignTourScenario().tour(context, this, DesignTourScenario.Stops.LAYER_THREE);
    }
}
