package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.client.transmission.TransmissionOverlay;
import io.github.pkeppeler.deepcharter.creature.CreatureRegistry;
import io.github.pkeppeler.deepcharter.creature.LamplessFigure;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.EvidencePins;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

/**
 * Evidence scenario "retained-on-foot" (#916): a Retained comes to a person on foot in the dark, clings, slows them as ore does and drains
 * their hunger; two blows of a sword kill it and free them. A second figure comes to the same person holding a torch, and does not take hold.
 *
 * <p>The scene is the Retained's sealed chamber of layer 2's rock, dark, with night vision for the camera. The player is in survival and in third
 * person with the HUD on, so the hunger bar and the Overburdened status read. The segments are logged as
 * {@code [retained-foot-segment] <name> <first>-<last>}, which is how the GIFs are cut.
 */
public class RetainedFootScenario extends EvidenceScenario {
    private static final int LENGTH = 30;
    private static final int HALF_WIDTH = 4;
    private static final int HEIGHT = 8;
    private static final double PERSON_X = 18.5;
    private static final double PERSON_Z = 0.5;
    private static final double FIGURE_X = 18.5;
    private static final double FIGURE_Z = -3.5;
    private static final int TICKS_PER_FRAME = 2;
    private static final int APPROACH_FRAMES_LIMIT = 150;
    /** Frames of the grip before the still of the drained hunger: over a point at 6 seconds a point. */
    private static final int DRAIN_FRAMES = 70;
    /** Food at the grab, so that the grip's first point shows in a short clip: the floor is 6. */
    private static final int FOOD_AT_GRAB = 8;

    private static final int FREED_FRAMES_LIMIT = 150;
    private static final int DISSOLVE_TAIL_FRAMES = 20;
    private static final int LIT_WATCH_FRAMES = 60;
    private static final float DOWN_THE_CHAMBER = 90f;
    /** Ticks between two blows by hand: a hurt mob ignores a blow that is no stronger than the last for 10 ticks. */
    private static final int BLOW_SPACING_FRAMES = 6;

    private static final float SWORD_DAMAGE = 6f;
    /** Far over the two blows of a sword that kill a figure of 12. */
    private static final int MAX_BLOWS = 10;

    private int framesTaken;
    private int figureId;

    @Override
    protected String name() {
        return "retained-on-foot";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        ClientChecks.withHudShown(context, () -> runShown(context));
    }

    private void runShown(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            Run run = new Run(context, new RetainedChamber(context, singleplayer));
            CameraType cameraType = context.computeOnClient(client -> client.options.getCameraType());
            EvidencePins.lightFlickerOff = true;
            try {
                run.setUp();
                run.approachAndDrain();
                run.strikeItOff();
                run.aTorchKeepsOneOff();
            } finally {
                EvidencePins.reset();
                context.runOnClient(client -> client.options.setCameraType(cameraType));
            }
        }
    }

    private final class Run {
        private final ClientGameTestContext ctx;
        private final RetainedChamber chamber;

        Run(ClientGameTestContext ctx, RetainedChamber chamber) {
            this.ctx = ctx;
            this.chamber = chamber;
        }

        private void setUp() {
            chamber.enterLayer(
                    LENGTH,
                    HALF_WIDTH,
                    HEIGHT,
                    RetainedChamber.FLOOR.getX() + PERSON_X,
                    RetainedChamber.FLOOR.getY() + 1,
                    RetainedChamber.FLOOR.getZ() + PERSON_Z,
                    DOWN_THE_CHAMBER,
                    12f);
            chamber.serverDo(server -> {
                ServerLevel level = RetainedChamber.layer(server);
                ServerPlayer player = person(server);
                player.setGameMode(GameType.SURVIVAL);
                player.teleportTo(
                        level,
                        RetainedChamber.at(PERSON_X, 1, PERSON_Z).x,
                        RetainedChamber.at(PERSON_X, 1, PERSON_Z).y,
                        RetainedChamber.at(PERSON_X, 1, PERSON_Z).z,
                        Set.of(),
                        DOWN_THE_CHAMBER,
                        12f,
                        true);
                player.getFoodData().setFoodLevel(FOOD_AT_GRAB);
                player.getFoodData().setSaturation(0f);
            });
            chamber.nightVision(true);
            ctx.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_BACK));
            chamber.settle();
            ctx.waitTicks(20); // tick-wait: the camera settles
        }

        private ServerPlayer person(MinecraftServer server) {
            return server.getPlayerList().getPlayers().getFirst();
        }

        private boolean clinging() {
            return chamber.serverGet(
                    server -> TestEntities.require(RetainedChamber.layer(server), figureId, LamplessFigure.class)
                            .isClinging());
        }

        private boolean dying() {
            return chamber.serverGet(
                    server -> RetainedChamber.layer(server).getEntity(figureId) instanceof LamplessFigure figure
                            && !figure.isAlive());
        }

        private boolean deadOrGone() {
            return gone() || dying();
        }

        private boolean gone() {
            return chamber.serverGet(server -> RetainedChamber.layer(server).getEntity(figureId) == null);
        }

        private int food() {
            return chamber.serverGet(server -> person(server).getFoodData().getFoodLevel());
        }

        private boolean burdened() {
            return chamber.serverGet(server -> person(server).getEffect(OreRegistry.OVERBURDENED) != null);
        }

        private void sendFigure() {
            chamber.awaitEntityTicking(RetainedChamber.at(FIGURE_X, 1, FIGURE_Z));
            chamber.serverDo(server -> {
                ServerLevel level = RetainedChamber.layer(server);
                LamplessFigure figure = TestEntities.create(CreatureRegistry.LAMPLESS_FIGURE, level);
                figure.setLamp(true);
                figure.setPos(RetainedChamber.at(FIGURE_X, 1, FIGURE_Z));
                figure.setHeading(Direction.SOUTH);
                level.addFreshEntity(figure);
                figureId = figure.getId();
            });
        }

        private void record(int frames) {
            for (int i = 0; i < frames; i++) {
                ctx.waitTicks(TICKS_PER_FRAME); // tick-wait: the clip is cut at fixed frames
                ctx.runOnClient(client -> TransmissionOverlay.reset());
                frame(ctx);
                framesTaken++;
            }
        }

        /** The figure walks to the person in the dark and takes hold; the weight shows, and the hunger bar falls. */
        private void approachAndDrain() {
            sendFigure();
            int first = framesTaken + 1;
            int walked = 0;
            while (!clinging()) {
                record(1);
                if (++walked > APPROACH_FRAMES_LIMIT) {
                    throw new AssertionError("the figure had not taken hold of the person in the dark "
                            + APPROACH_FRAMES_LIMIT + " frames after it set off");
                }
            }
            int foodAtGrab = food();
            record(8);
            if (!burdened()) {
                throw new AssertionError("a held person shows no Overburdened status");
            }
            screenshot(ctx, "retained-foot-clinging");
            record(DRAIN_FRAMES - 8);
            screenshot(ctx, "retained-foot-drained");
            System.out.println("[retained-foot] food " + foodAtGrab + " at the grab, " + food() + " after "
                    + DRAIN_FRAMES + " frames of the grip");
            System.out.println("[retained-foot-segment] retained-foot-grab " + first + "-" + framesTaken);
        }

        /** The person strikes with a sword: two blows kill it, and the weight goes. */
        private void strikeItOff() {
            int first = framesTaken + 1;
            chamber.serverDo(
                    server -> person(server).setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD)));
            record(4);
            int blows = 0;
            // One frame at a time between blows, and a death that is already gone ends the loop too: a figure
            // dissolves in
            // 20 ticks, and a slow frame in CI can span that, after which it is no longer there to be found dying.
            while (!deadOrGone()) {
                strike();
                blows++;
                for (int waited = 0; waited < BLOW_SPACING_FRAMES && !deadOrGone(); waited++) {
                    record(1);
                }
                if (blows > MAX_BLOWS) {
                    throw new AssertionError("the figure still lived after " + blows + " blows");
                }
            }
            if (!gone()) {
                screenshot(ctx, "retained-foot-dissolving");
            }
            int dissolving = 0;
            while (!gone()) {
                record(1);
                if (++dissolving > FREED_FRAMES_LIMIT) {
                    throw new AssertionError(
                            "the figure had not dissolved " + FREED_FRAMES_LIMIT + " frames after it died");
                }
            }
            record(DISSOLVE_TAIL_FRAMES);
            if (burdened()) {
                throw new AssertionError("the weight stayed on the person after the figure died");
            }
            screenshot(ctx, "retained-foot-freed");
            System.out.println("[retained-foot-segment] retained-foot-kill " + first + "-" + framesTaken);
        }

        private void strike() {
            chamber.serverDo(server -> {
                ServerPlayer player = person(server);
                if (RetainedChamber.layer(server).getEntity(figureId) instanceof LamplessFigure figure) {
                    figure.hurtServer(
                            RetainedChamber.layer(server),
                            RetainedChamber.layer(server).damageSources().playerAttack(player),
                            SWORD_DAMAGE);
                }
            });
        }

        /** A torch in the hand, and a second figure walks the chamber and does not take hold. */
        private void aTorchKeepsOneOff() {
            chamber.serverDo(
                    server -> person(server).setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.TORCH)));
            int first = framesTaken + 1;
            sendFigure();
            record(LIT_WATCH_FRAMES);
            if (clinging()) {
                throw new AssertionError("a figure took hold of a person who holds a torch");
            }
            screenshot(ctx, "retained-foot-torch-left-alone");
            System.out.println("[retained-foot-segment] retained-foot-light " + first + "-" + framesTaken);
        }
    }
}
