package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.test.support.ClientPacks;
import io.github.pkeppeler.deepcharter.test.support.TestPacks;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * Client GameTest for the layer concepts (#447, docs/design/layer-concepts-2.md): the Grain pack and the three form packs are off by default, each turns on
 * and reloads without the game dropping it (a pack with a bad blockstate, model or texture is unselected by a failed reload), and each
 * turns off again, so a run of the scenario leaves the game as it found it.
 */
public class LayerConceptsClientTest implements FabricClientGameTest {
    private static final String[] PACKS = {
        TestPacks.LAYER_CONCEPT_GRAIN,
        TestPacks.LAYER_CONCEPT_FORM_1,
        TestPacks.LAYER_CONCEPT_FORM_2,
        TestPacks.LAYER_CONCEPT_FORM_3
    };

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        for (String pack : PACKS) {
            require(
                    !selected(context, pack),
                    "the pack " + pack + " is on by default: a concept must show only behind its own switch");
        }
        for (String pack : PACKS) {
            ClientPacks.enable(context, pack);
            require(
                    selected(context, pack),
                    "the pack " + pack
                            + " did not stay selected after its reload: a blockstate, model or texture of it is bad");
            ClientPacks.disable(context, pack);
            require(!selected(context, pack), "the pack " + pack + " is still selected after it was turned off");
        }
    }

    private static boolean selected(ClientGameTestContext context, String pack) {
        return context.computeOnClient(client ->
                client.getResourcePackRepository().getSelectedIds().stream().anyMatch(id -> id.endsWith(pack)));
    }
}
