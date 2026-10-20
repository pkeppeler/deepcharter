package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Evidence scenario "tow-line-ground" (#425): a Mole tows a pod over a stone pad, seen from above and to the west. The line is slack
 * while the pod stands behind, pulls it along once the Mole has driven the whole line away, and the pod trails round the corner when
 * the Mole turns east.
 */
public class TowLineGroundScenario extends EvidenceScenario {
    private static final int X = 1700;
    private static final int Z = 1700;
    // fixed-pos: a stone slab the scenario builds in the overworld sky, clear of the terrain
    private static final int FLOOR_Y = 200;
    private static final int SOUTH_TICKS = 110;
    private static final int EAST_TICKS = 60;
    private static final int STOPPED_TICKS = 30;
    private static final int STANDING_FRAMES = 8;
    /** The camera: this far west of the Mole (away from the setting sun) and this high above it, looking down at it. */
    private static final double CAMERA_WEST = 7;

    private static final double CAMERA_ABOVE = 13;

    @Override
    protected String name() {
        return "tow-line-ground";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TwoPlayerServer two = TwoPlayerServer.start(context)) {
            UUID[][] pods = {null};
            two.server().runOnServer(server -> {
                ServerLevel level = server.overworld();
                // The pad reaches past everything the camera, which looks down, can see of it, so the edge of the stage
                // is never in a frame.
                TowStage.fill(level, X - 45, X + 60, FLOOR_Y - 4, FLOOR_Y - 1, Z - 50, Z + 75, Blocks.STONE);
                TowStage.fill(level, X - 12, X + 40, FLOOR_Y, FLOOR_Y + 10, Z - 12, Z + 70, Blocks.AIR);
                TowStage.prepare(level, two, new Vec3(X - 15, FLOOR_Y + 3, Z));
                Vec3 at = new Vec3(X + 0.5, FLOOR_Y, Z + 0.5);
                pods[0] = TowStage.pair(level, two, at, at.add(0, 0, -2.2), false);
            });
            UUID tower = pods[0][0];
            context.waitTicks(TowStage.CHAT_FADE_TICKS);
            for (int i = 0; i < STANDING_FRAMES; i++) {
                look(context, two, tower);
                context.waitTicks(TowStage.TICKS_PER_FRAME);
            }
            screenshot(context, "slack-line-before-the-drive");
            drive(context, two, tower, TowStage.SOUTH, SOUTH_TICKS);
            screenshot(context, "taut-line-on-the-drive");
            drive(context, two, tower, TowStage.EAST, EAST_TICKS);
            screenshot(context, "pod-trailing-round-the-corner");
            stop(context, two, tower);
            screenshot(context, "the-mole-has-stopped");
        }
    }

    private void drive(ClientGameTestContext context, TwoPlayerServer two, UUID tower, float yaw, int ticks) {
        two.server().runOnServer(server -> {
            two.mock().player().setYRot(yaw);
            two.mock().setInput(TowStage.FORWARD);
        });
        for (int done = 0; done < ticks; done += TowStage.TICKS_PER_FRAME) {
            context.waitTicks(TowStage.TICKS_PER_FRAME);
            look(context, two, tower);
        }
    }

    private void stop(ClientGameTestContext context, TwoPlayerServer two, UUID tower) {
        two.server().runOnServer(server -> two.mock().releaseInput());
        for (int done = 0; done < STOPPED_TICKS; done += TowStage.TICKS_PER_FRAME) {
            context.waitTicks(TowStage.TICKS_PER_FRAME);
            look(context, two, tower);
        }
    }

    private void look(ClientGameTestContext context, TwoPlayerServer two, UUID tower) {
        two.server().runOnServer(server -> {
            Vec3 at = TestEntities.require(server.overworld(), tower).position();
            TowStage.film(server.overworld(), two, at.add(-CAMERA_WEST, CAMERA_ABOVE, -3), at.add(0, 0, -3));
        });
        context.runOnClient(client -> client.gui.toastManager().clear());
        frame(context);
    }
}
