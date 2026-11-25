package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.server.level.ServerPlayer;

/**
 * Footage for the death sequence mock-ups (#1337): one frame per tick (20 fps, real time), and a log of the frame of each
 * thing heard, which the mock-up's rebuilt audio track is placed by (the cloud client records no sound). The log goes to
 * {@code events.txt} beside the frames and to standard output, because a CI recording keeps only the media.
 */
final class DeathFootage {
    private final EvidenceScenario scenario;
    private final List<String> events = new ArrayList<>();
    private int tick;

    DeathFootage(EvidenceScenario scenario) {
        this.scenario = scenario;
    }

    /** One tick, then one frame. */
    void step(ClientGameTestContext context) {
        context.waitTicks(1);
        scenario.frame(context);
        tick++;
    }

    void steps(ClientGameTestContext context, int count) {
        for (int i = 0; i < count; i++) {
            step(context);
        }
    }

    void event(String what) {
        events.add(tick + " " + what);
        System.out.println("death-mockup event: " + scenario.name() + " frame " + tick + " " + what);
    }

    /** True once the client shows the vanilla death screen or its player is dying: the frame before is the last. */
    static boolean dead(ClientGameTestContext context) {
        return context.computeOnClient(client ->
                client.gui.screen() instanceof DeathScreen || ClientSession.player(client).isDeadOrDying());
    }

    static ServerPlayer pilot(TestSingleplayerContext singleplayer) {
        return singleplayer
                .getServer()
                .computeOnServer(server -> server.getPlayerList().getPlayers().getFirst());
    }

    void writeEvents() {
        String root = System.getenv("DEEPCHARTER_EVIDENCE_DIR");
        if (root == null) {
            return;
        }
        try {
            Files.write(Path.of(root).toAbsolutePath().resolve(scenario.name()).resolve("events.txt"), events);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
