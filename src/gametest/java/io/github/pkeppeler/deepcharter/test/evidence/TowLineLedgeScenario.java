package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.test.support.Inputs;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Evidence scenario "tow-line-ledge" (#1122): a Mole stands on a stone ledge with a sheer drop on its west side and no floor under the
 * drop for many blocks. Its towed pod starts out over the drop, held still, then is let go: it falls past the lip and hangs from it,
 * the line draped over the edge. The Mole then drives east, away from the ledge, and hauls the pod up after it. The camera looks
 * south, side-on to the lip and the line.
 */
public class TowLineLedgeScenario extends EvidenceScenario {
    private static final int X = 2700;
    private static final int Z = 1900;
    private static final int FLOOR_Y = SKY_SLAB_Y;
    /** The ledge's top: the tower stands on it, this far above the stage's floor, so the drop is far longer than the line. */
    private static final int LEDGE_HEIGHT = 24;

    private static final int LEDGE_LENGTH = 16;
    private static final int STANDING_FRAMES = 8;
    private static final int HANGING_TICKS = 160;
    private static final int MAX_DRIVE_TICKS = 400;
    private static final int HAUL_FRAMES = 10;
    /** The tower stops this far from the lip, near the ledge's east end. */
    private static final double STOP_AT = 13;
    /** The tower stands this far in from the lip; the pod starts this far west of it, its hull clear of the ledge. */
    private static final double TOWER_AT = 2.5;

    private static final double POD_BESIDE = 4.2;
    /** The camera: this far north of the pods, looking south. */
    private static final double CAMERA_NORTH = 11;

    private static final double AIM_BELOW = 1.5;

    @Override
    protected String name() {
        return "tow-line-ledge";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TwoPlayerServer two = TwoPlayerServer.start(context)) {
            UUID[][] pods = {null};
            two.server().runOnServer(server -> {
                ServerLevel level = server.overworld();
                int top = FLOOR_Y + LEDGE_HEIGHT;
                // A floor far below and a wall behind the ledge reach past the camera's view, so it never sees the
                // edge of the stage.
                TowStage.fill(level, X - 40, X + 40, FLOOR_Y - 4, FLOOR_Y - 1, Z - 40, Z + 40, Blocks.STONE);
                TowStage.fill(level, X - 40, X + 40, FLOOR_Y, FLOOR_Y + 60, Z + 14, Z + 15, Blocks.STONE);
                TowStage.fill(level, X - 30, X + 30, FLOOR_Y, FLOOR_Y + 50, Z - 20, Z + 13, Blocks.AIR);
                TowStage.fill(level, X, X + LEDGE_LENGTH, top - 3, top - 1, Z - 1, Z + 4, Blocks.STONE);
                TowStage.prepare(level, two, new Vec3(X + 3, top + 3, Z - CAMERA_NORTH));
                Vec3 at = new Vec3(X + TOWER_AT, top, Z + 1.5);
                pods[0] = TowStage.pair(level, two, at, at.add(-POD_BESIDE, 0, 0), false);
                // The pod is held out over the drop until the first still is taken.
                TestEntities.require(level, pods[0][1]).setNoGravity(true);
            });
            UUID tower = pods[0][0];
            UUID towed = pods[0][1];
            context.waitTicks(TowStage.CHAT_FADE_TICKS);
            for (int i = 0; i < STANDING_FRAMES; i++) {
                look(context, two, tower, towed);
                context.waitTicks(TowStage.TICKS_PER_FRAME);
            }
            screenshot(context, "pod-out-over-the-drop");

            two.server()
                    .runOnServer(server ->
                            TestEntities.require(server.overworld(), towed).setNoGravity(false));
            for (int ticks = 0; ticks < HANGING_TICKS; ticks += TowStage.TICKS_PER_FRAME) {
                context.waitTicks(TowStage.TICKS_PER_FRAME);
                look(context, two, tower, towed);
            }
            screenshot(context, "pod-hanging-from-the-lip");

            // East along the ledge, away from the drop, until the pod is back up on the ledge.
            two.server().runOnServer(server -> {
                TowStage.turn(two, TowStage.EAST);
                two.mock().setInput(Inputs.FORWARD);
            });
            int top = FLOOR_Y + LEDGE_HEIGHT;
            boolean hauled = false;
            for (int ticks = 0; ticks < MAX_DRIVE_TICKS && !hauled; ticks += TowStage.TICKS_PER_FRAME) {
                context.waitTicks(TowStage.TICKS_PER_FRAME);
                look(context, two, tower, towed);
                hauled = TowStage.where(two, towed).y > top - 1.0;
            }
            if (!hauled) {
                throw new AssertionError(
                        "The pod was not hauled up onto the ledge within " + MAX_DRIVE_TICKS + " ticks");
            }
            // On along the ledge, so the pod trails behind the tower on the ledge, then stop.
            for (int ticks = 0;
                    TowStage.where(two, tower).x < X + STOP_AT && ticks < MAX_DRIVE_TICKS;
                    ticks += TowStage.TICKS_PER_FRAME) {
                context.waitTicks(TowStage.TICKS_PER_FRAME);
                look(context, two, tower, towed);
            }
            two.server().runOnServer(server -> two.mock().releaseInput());
            for (int i = 0; i < HAUL_FRAMES; i++) {
                context.waitTicks(TowStage.TICKS_PER_FRAME);
                look(context, two, tower, towed);
            }
            screenshot(context, "pod-hauled-up-as-the-tower-leaves");
        }
    }

    /** Films the middle of the two pods, side-on. */
    private void look(ClientGameTestContext context, TwoPlayerServer two, UUID tower, UUID towed) {
        two.server().runOnServer(server -> {
            Vec3 a = TestEntities.require(server.overworld(), tower).position();
            Vec3 b = TestEntities.require(server.overworld(), towed).position();
            Vec3 focus = a.add(b).scale(0.5).add(0, -AIM_BELOW, 0);
            TowStage.film(server.overworld(), two, focus.add(0, 0, -CAMERA_NORTH), focus);
        });
        context.runOnClient(client -> client.gui.toastManager().clear());
        frame(context);
    }
}
