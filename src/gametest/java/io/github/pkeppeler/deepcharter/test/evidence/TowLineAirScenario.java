package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Evidence scenario "tow-line-air" (#425): a Mole carries a wreck in flight. It lifts off with the wreck on its cable, flies south,
 * stops, turns and flies back. The wreck hangs below and behind it on the line like a sling load and swings when the Mole stops and
 * turns.
 */
public class TowLineAirScenario extends EvidenceScenario {
    private static final int X = 1500;
    private static final int Z = 1500;
    private static final int FLOOR_Y = SKY_SLAB_Y;
    private static final double CRUISE_HEIGHT = 9;
    /** Ticks of each leg of the flight, and of the hover after it. */
    private static final int LEG_TICKS = 50;

    private static final int HOVER_TICKS = 30;
    private static final int GROUND_FRAMES = 8;
    /** The camera: east of the pods, level with the tower, looking west at the tower and the line below it (not into the setting sun). */
    private static final double CAMERA_EAST = 13;

    private static final double CAMERA_ABOVE = 0;
    /** The camera aims this far below the tower, at the middle of the tower and the wreck on its line. */
    private static final double AIM_BELOW = 3.5;

    @Override
    protected String name() {
        return "tow-line-air";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TwoPlayerServer two = TwoPlayerServer.start(context)) {
            UUID[][] pods = {null};
            two.server().runOnServer(server -> {
                ServerLevel level = server.overworld();
                // A floor and a wall behind the flight reach past the camera's view, so it never sees the edge of the
                // stage.
                TowStage.fill(level, X - 45, X + 45, FLOOR_Y - 4, FLOOR_Y - 1, Z - 80, Z + 120, Blocks.STONE);
                TowStage.fill(level, X - 26, X - 25, FLOOR_Y, FLOOR_Y + 50, Z - 80, Z + 120, Blocks.STONE);
                TowStage.fill(level, X - 10, X + 10, FLOOR_Y, FLOOR_Y + 40, Z - 14, Z + 60, Blocks.AIR);
                TowStage.prepare(level, two, new Vec3(X - 15, FLOOR_Y + 3, Z));
                Vec3 at = new Vec3(X + 0.5, FLOOR_Y, Z + 0.5);
                pods[0] = TowStage.pair(level, two, at, at.add(0, 0, -2.2), true);
            });
            UUID tower = pods[0][0];
            context.waitTicks(TowStage.CHAT_FADE_TICKS);
            for (int i = 0; i < GROUND_FRAMES; i++) {
                look(context, two, tower);
                context.waitTicks(TowStage.TICKS_PER_FRAME);
            }
            screenshot(context, "wreck-on-the-line-on-the-ground");

            // Up to cruising height with the wreck hanging from the line.
            while (TowStage.where(two, tower).y < FLOOR_Y + CRUISE_HEIGHT) {
                fly(context, two, tower, false);
            }
            // South, a stop, north: the wreck trails, swings forward, and trails the other way.
            leg(context, two, tower, TowStage.SOUTH);
            hover(context, two, tower);
            screenshot(context, "wreck-swinging-forward-after-the-stop");
            leg(context, two, tower, TowStage.NORTH);
            hover(context, two, tower);
            screenshot(context, "wreck-hanging-below-the-mole");
        }
    }

    /** Flies {@link #LEG_TICKS} ticks along {@code yaw}, holding height. */
    private void leg(ClientGameTestContext context, TwoPlayerServer two, UUID tower, float yaw) {
        two.server().runOnServer(server -> two.mock().player().setYRot(yaw));
        for (int done = 0; done < LEG_TICKS; done += TowStage.TICKS_PER_FRAME) {
            fly(context, two, tower, true);
        }
    }

    private void hover(ClientGameTestContext context, TwoPlayerServer two, UUID tower) {
        for (int done = 0; done < HOVER_TICKS; done += TowStage.TICKS_PER_FRAME) {
            fly(context, two, tower, false);
        }
    }

    /** One frame of flight: the rotor is on while the pod is below cruising height, and the forward key is down if {@code forward}. */
    private void fly(ClientGameTestContext context, TwoPlayerServer two, UUID tower, boolean forward) {
        boolean climb = TowStage.where(two, tower).y < FLOOR_Y + CRUISE_HEIGHT;
        Input input = new Input(forward, false, false, false, climb, false, false);
        two.server().runOnServer(server -> two.mock().setInput(input));
        context.waitTicks(TowStage.TICKS_PER_FRAME);
        look(context, two, tower);
    }

    private void look(ClientGameTestContext context, TwoPlayerServer two, UUID tower) {
        two.server().runOnServer(server -> {
            Vec3 at = TestEntities.require(server.overworld(), tower).position();
            TowStage.film(
                    server.overworld(),
                    two,
                    new Vec3(X + CAMERA_EAST, at.y + CAMERA_ABOVE, at.z),
                    at.add(0, -AIM_BELOW, 0));
        });
        context.runOnClient(client -> client.gui.toastManager().clear());
        frame(context);
    }
}
