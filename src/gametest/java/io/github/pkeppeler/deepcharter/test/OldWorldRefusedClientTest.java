package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.OldWorlds;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * Client GameTest for #800: a singleplayer world with a colony and an old world-format stamp is refused with a screen, not a crash report,
 * and nothing in it changes (ADR 0059). The world is made, given an old stamp through {@code WorldData}, saved and closed; opening
 * it from the world list shows the refusal. A crash would end this test with the client, so reaching the checks proves there was none.
 */
public class OldWorldRefusedClientTest implements FabricClientGameTest {
    private static final String TITLE = "deepcharter.world_format.refused.title";

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        TestWorldSave save = OldWorlds.make(context);
        Map<String, String> before = OldWorlds.regionHashes(save.getSaveDirectory());
        if (before.isEmpty()) {
            throw new AssertionError("the closed world should have region files to compare");
        }

        OldWorlds.open(context, save);

        boolean refused = context.computeOnClient(client ->
                ClientSession.screen(client, AlertScreen.class).getTitle().getContents()
                                instanceof TranslatableContents title
                        && title.getKey().equals(TITLE));
        if (!refused) {
            throw new AssertionError("the screen should be the refusal, titled " + TITLE);
        }
        if (context.computeOnClient(client -> client.getSingleplayerServer() != null)) {
            throw new AssertionError("a refused world must not start a server");
        }
        Map<String, String> after = OldWorlds.regionHashes(save.getSaveDirectory());
        if (!after.equals(before)) {
            throw new AssertionError(
                    "opening a refused world changed its region files: before " + before + ", after " + after);
        }

        OldWorlds.backToTheTitleScreen(context);
    }
}
