package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.UnderwaterFog;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client GameTest for #1187: under the water of a flooded hall in each layer, looking past the render distance once the
 * eyes have adjusted, the frame stays dark. Vanilla's water fog, which no zone biome replaced, is lifted there to a
 * bright-blue wall ({@code #1919ff}).
 */
public class UnderwaterFogClientTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(UnderwaterFogClientTest.class);

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            ClientWait.until(
                    context, "the player in the world", client -> client.player != null && client.level != null);
            TestServerContext server = singleplayer.getServer();
            server.runCommand("gamerule advance_time false");
            List<String> bright = new ArrayList<>();
            UnderwaterFog.tour(context, server, layer -> {
                Path shot = ClientChecks.skyShot(context, "underwater-fog-layer-" + layer);
                UnderwaterFog.Reading reading = UnderwaterFog.read(shot);
                LOGGER.info("[underwater-fog] layer {}: {}", layer, reading.describe());
                if (!reading.dark()) {
                    bright.add("layer " + layer + ": " + reading.describe());
                }
            });
            ClientChecks.require(
                    bright.isEmpty(),
                    "the water of a layer shows a bright fog once the eyes adjust, where the world should stay black: "
                            + String.join("; ", bright));
        }
    }
}
