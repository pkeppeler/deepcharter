package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.test.support.CavernSky;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import org.joml.Vector3fc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client GameTest for #1161: in a cavern of layer 1 whose lines of sight run past the render distance, the sky the shared overworld
 * type draws shows the zone's fog and nothing else, at dusk (the sun and its glow in the west), at moonrise (the moon low in the east,
 * the stars coming out) and at night (every star out). Without the zone biomes' sky values the dusk sun, the moon and the stars stand
 * in the sampled box. {@code SkyClientTest} keeps the surface's sky.
 */
public class CavernSkyClientTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(CavernSkyClientTest.class);

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            ClientWait.until(
                    context, "the player in the world", client -> client.player != null && client.level != null);
            TestServerContext server = singleplayer.getServer();
            server.runCommand("gamerule advance_time false");
            server.runCommand("weather clear");
            BlockPos floor = CavernSky.carve(server);
            List<String> leaks = new ArrayList<>();
            CavernSky.withShortRenderDistance(context, () -> {
                for (CavernSky.View view : CavernSky.VIEWS) {
                    CavernSky.stand(context, server, floor, view);
                    Path shot = ClientChecks.skyShot(context, "cavern-sky-" + view.name());
                    Vector3fc fog = CavernSky.fogAtCamera(context);
                    CavernSky.Reading reading = CavernSky.read(shot, fog);
                    LOGGER.info("[cavern-sky] {}: {}", view.name(), reading.describe());
                    if (!reading.fogOnly()) {
                        leaks.add(view.name() + ": " + reading.describe());
                    }
                }
            });
            ClientChecks.require(
                    leaks.isEmpty(),
                    "the sky shows through a layer 1 cavern past the render distance (a sun, moon, star or glow where the zone's fog"
                            + " should be): " + String.join("; ", leaks));
        }
    }
}
