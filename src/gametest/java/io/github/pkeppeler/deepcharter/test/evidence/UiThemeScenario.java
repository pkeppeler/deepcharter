package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.test.support.ClientPacks;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.CrtDemoScreen;
import io.github.pkeppeler.deepcharter.test.support.TestPacks;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * Evidence scenario "ui-theme" for #226: the CRT demo screen with today's green phosphor, then, after a one-file resource pack is turned
 * on and the resources reload (what F3+T does), with amber phosphor. Stills "crt-default" and "crt-amber".
 */
public class UiThemeScenario extends EvidenceScenario {
    @Override
    protected String name() {
        return "ui-theme";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        context.setScreen(CrtDemoScreen::new);
        ClientWait.screen(context, CrtDemoScreen.class);
        CrtDemoScreen screen = context.computeOnClient(client -> (CrtDemoScreen) client.gui.screen());
        ClientWait.until(
                context, "the typewriter done", client -> screen.typewriter().done());
        context.waitTicks(5);
        screenshot(context, "crt-default");
        frame(context);

        ClientPacks.enable(context, TestPacks.AMBER_CRT);
        try {
            context.waitTicks(5);
            screenshot(context, "crt-amber");
            frame(context);
        } finally {
            ClientPacks.disable(context, TestPacks.AMBER_CRT);
            context.setScreen(() -> null);
        }
    }
}
