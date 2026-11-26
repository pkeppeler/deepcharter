package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.transmission.TransmissionOverlay;
import io.github.pkeppeler.deepcharter.creature.CreatureRegistry;
import io.github.pkeppeler.deepcharter.creature.LamplessFigure;
import io.github.pkeppeler.deepcharter.creature.Tally;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.EvidencePins;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEventListener;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Evidence scenario "creature-sounds" (#1450): a Tally takes an ore from a pod with its own cue, and a Retained takes the hull of a dark pod
 * and its dread bed rises over the ducked drone, until the figure is struck off and the bed fades.
 *
 * <p>The scene is the Retained's chamber of layer 2's rock (dark; night vision, so the dark can be seen), the camera behind the pilot's pod. A
 * client records no sound, so the scenario logs what the sound engine plays, for the PR's MP4 to be scored from the shipped files: each sound
 * started, as {@code [creature-sounds-start] <frame> <file> <gain>} (its volume, and for a sound at a place, the share of it left at the
 * listener by the engine's linear fall-off), and each loop playing, every frame, as {@code [creature-sounds-loop] <frame> <instance> <file>
 * <gain>}, with the client's game time at each frame, {@code [creature-sounds-frame] <frame> <game time>}: a frame is the
 * {@value #TICKS_PER_FRAME} ticks waited and those the screenshot takes, so the frames' pace for the game's own time is read from the log,
 * not assumed. The segments are logged as {@code [creature-sounds-segment] <name> <first>-<last>}.
 */
public class CreatureSoundsScenario extends EvidenceScenario {
    private static final int LENGTH = 30;
    private static final int HALF_WIDTH = 4;
    private static final int HEIGHT = 8;
    private static final double POD_X = 18.5;
    private static final double POD_Z = 0.5;
    /** The Tally starts up and to the left of the camera's view, as in {@code TallySkimScenario}. */
    private static final double TALLY_X = POD_X + 2.5;

    private static final double TALLY_Z = -2.5;
    private static final double TALLY_HEIGHT = 3;
    /** The figure comes from the north wall, ahead of the pod, in view. */
    private static final double FIGURE_X = POD_X - 4;

    private static final double FIGURE_Z = -3.5;
    private static final int ORES = 3;
    private static final int TICKS_PER_FRAME = 2;
    private static final int TRAIL_FRAMES = 20;
    /** Ticks waited without frames, leaving the last stretch of the trail for the frames. */
    private static final int SKIPPED_TICKS = 500;

    private static final int WAIT_STEP_TICKS = 20;
    private static final int LIMIT_FRAMES = 200;
    private static final int TAIL_FRAMES = 20;
    /** Frames held after the figure takes hold: the bed's rise (2 s) and a stretch at full. */
    private static final int HOLD_FRAMES = 60;
    /** Frames after the figure is struck off: the bed's fall (3 s) and the drone back. */
    private static final int RELEASE_FRAMES = 50;
    /** Yaw 90 faces -x, west down the chamber. */
    private static final float DOWN_THE_CHAMBER = 90f;

    /** The number of the next frame: what a sound started now is heard with. Read on the client thread. */
    private final AtomicInteger nextFrame = new AtomicInteger(1);

    private int podId;
    private int tallyId;
    private int figureId;

    @Override
    protected String name() {
        return "creature-sounds";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        ClientChecks.withHudShown(context, () -> runShown(context));
    }

    /** Logs each sound the engine starts, at the frame it is heard with, and keeps the loops for {@link #logLoops}. */
    private final class Starts implements SoundEventListener {
        private final Set<SoundInstance> loops = new CopyOnWriteArraySet<>();

        @Override
        public void onPlaySound(SoundInstance sound, WeighedSoundEvents events, float range) {
            // A loop is heard again now and then for its subtitle; the loops are logged per frame instead.
            if (sound.isLooping()) {
                loops.add(sound);
                return;
            }
            float gain = sound.getVolume();
            if (!sound.isRelative() && sound.getAttenuation() != SoundInstance.Attenuation.NONE) {
                double distance = Minecraft.getInstance()
                        .getSoundManager()
                        .getListenerTransform()
                        .position()
                        .distanceTo(new Vec3(sound.getX(), sound.getY(), sound.getZ()));
                gain *= (float) Math.max(0, 1 - distance / range);
            }
            System.out.println("[creature-sounds-start] " + nextFrame.get() + " " + file(sound) + " " + gain);
        }

        /** Every loop the engine plays now (the drone, the pod's engine, the bed), with its gain: they play from the cab, so no fall-off. */
        void logLoops(Minecraft client, int frame) {
            for (SoundInstance sound : loops) {
                if (client.getSoundManager().isActive(sound)) {
                    System.out.println("[creature-sounds-loop] " + frame + " " + System.identityHashCode(sound) + " "
                            + file(sound) + " " + sound.getVolume());
                }
            }
        }
    }

    private static String file(SoundInstance sound) {
        Sound played = sound.getSound();
        return played == null ? "none" : played.getLocation().toString();
    }

    private void runShown(ClientGameTestContext context) {
        Starts starts = new Starts();
        context.runOnClient(client -> client.getSoundManager().addListener(starts));
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            Run run = new Run(context, new RetainedChamber(context, singleplayer), starts);
            CameraType cameraType = context.computeOnClient(client -> client.options.getCameraType());
            EvidencePins.lightFlickerOff = true;
            try {
                run.setUp();
                run.take();
                run.hold();
            } finally {
                EvidencePins.reset();
                context.runOnClient(client -> client.options.setCameraType(cameraType));
            }
        } finally {
            context.runOnClient(client -> client.getSoundManager().removeListener(starts));
        }
    }

    private final class Run {
        private final ClientGameTestContext ctx;
        private final RetainedChamber chamber;
        private final Starts starts;

        Run(ClientGameTestContext ctx, RetainedChamber chamber, Starts starts) {
            this.ctx = ctx;
            this.chamber = chamber;
            this.starts = starts;
        }

        private void setUp() {
            chamber.enterLayer(
                    LENGTH,
                    HALF_WIDTH,
                    HEIGHT,
                    RetainedChamber.FLOOR.getX() + POD_X,
                    RetainedChamber.FLOOR.getY() + 3,
                    RetainedChamber.FLOOR.getZ() + POD_Z,
                    DOWN_THE_CHAMBER,
                    12f);
            chamber.serverDo(server -> {
                ServerLevel level = RetainedChamber.layer(server);
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                Charters.found(server, player.getUUID(), "Demo Charter");
                PodEntity pod = TestEntities.create(PodRegistry.POD, level);
                pod.setPos(RetainedChamber.at(POD_X, 1, POD_Z));
                pod.setYRot(DOWN_THE_CHAMBER);
                level.addFreshEntity(pod);
                for (int i = 0; i < ORES; i++) {
                    pod.cargo().tryAdd(pod, OreRegistry.stack(i == 0 ? OreType.GOLDIUM : OreType.IRONIUM));
                }
                podId = pod.getId();
                player.teleportTo(level, pod.getX(), pod.getY(), pod.getZ(), Set.of(), DOWN_THE_CHAMBER, 12f, true);
                if (!player.startRiding(pod)) {
                    throw new AssertionError("the player could not mount the pod");
                }
            });
            chamber.nightVision(true);
            ClientWait.until(
                    ctx,
                    "the pilot seated",
                    client -> client.player != null && client.player.getVehicle() instanceof PodEntity);
            ctx.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_BACK));
            chamber.settle();
            ctx.waitTicks(20); // tick-wait: the transmission and the camera settle
        }

        /** One frame each {@value #TICKS_PER_FRAME} ticks and a screenshot, the game time at it, and the loops the engine plays at it. */
        private void record(int frames) {
            for (int i = 0; i < frames; i++) {
                ctx.waitTicks(
                        TICKS_PER_FRAME); // tick-wait: the clip is cut at fixed frames, its sound at the same times
                int number = nextFrame.getAndIncrement();
                ctx.runOnClient(client -> {
                    TransmissionOverlay.reset();
                    System.out.println("[creature-sounds-frame] " + number + " "
                            + Objects.requireNonNull(client.level, "<in the chamber>")
                                    .getGameTime());
                    starts.logLoops(client, number);
                });
                frame(ctx);
            }
        }

        private <T> T server(Function<ServerLevel, T> query) {
            return chamber.serverGet(server -> query.apply(RetainedChamber.layer(server)));
        }

        private void recordUntil(String what, BooleanSupplier done) {
            int frames = 0;
            while (!done.getAsBoolean()) {
                record(1);
                if (++frames > LIMIT_FRAMES) {
                    throw new AssertionError(what + " had not happened " + LIMIT_FRAMES + " frames on");
                }
            }
        }

        /** A Tally trails the pod, clamps to the hatch, and takes one ore with its cue, and goes. */
        private void take() {
            int first = nextFrame.get();
            chamber.awaitEntityTicking(RetainedChamber.at(TALLY_X, TALLY_HEIGHT, TALLY_Z));
            chamber.serverDo(server -> {
                ServerLevel level = RetainedChamber.layer(server);
                Tally tally = TestEntities.mob(CreatureRegistry.TALLY, level);
                tally.setPos(RetainedChamber.at(TALLY_X, TALLY_HEIGHT, TALLY_Z));
                level.addFreshEntity(tally);
                tallyId = tally.getId();
            });
            record(TRAIL_FRAMES);
            for (int waited = 0; waited < SKIPPED_TICKS; waited += WAIT_STEP_TICKS) {
                ctx.waitTicks(WAIT_STEP_TICKS); // tick-wait: the trail is real time and the frames skip it
            }
            recordUntil(
                    "the clamp",
                    () -> server(level -> level.getEntity(tallyId) instanceof Tally tally && tally.isClamped()));
            screenshot(ctx, "tally-clamped");
            recordUntil("the take", () -> server(level -> level.getEntity(tallyId) == null));
            screenshot(ctx, "tally-took-its-sample");
            record(TAIL_FRAMES);
            System.out.println("[creature-sounds-segment] tally-take " + first + "-" + (nextFrame.get() - 1));
        }

        /** A figure takes the dark pod's hull; the bed rises and the drone ducks; the figure is struck off and the bed fades. */
        private void hold() {
            int first = nextFrame.get();
            chamber.awaitEntityTicking(RetainedChamber.at(FIGURE_X, 1, FIGURE_Z));
            chamber.serverDo(server -> {
                ServerLevel level = RetainedChamber.layer(server);
                LamplessFigure figure = TestEntities.mob(CreatureRegistry.LAMPLESS_FIGURE, level);
                figure.setLamp(true);
                figure.setPos(RetainedChamber.at(FIGURE_X, 1, FIGURE_Z));
                level.addFreshEntity(figure);
                figureId = figure.getId();
            });
            recordUntil(
                    "the grip",
                    () -> server(level ->
                            level.getEntity(figureId) instanceof LamplessFigure figure && figure.isClinging()));
            record(HOLD_FRAMES);
            screenshot(ctx, "retained-holding");
            chamber.serverDo(server -> {
                ServerLevel level = RetainedChamber.layer(server);
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                LamplessFigure figure = TestEntities.require(level, figureId, LamplessFigure.class);
                figure.hurtServer(level, level.damageSources().playerAttack(player), figure.getMaxHealth() * 10);
            });
            record(RELEASE_FRAMES);
            screenshot(ctx, "retained-struck-off");
            System.out.println("[creature-sounds-segment] retained-dread " + first + "-" + (nextFrame.get() - 1));
            System.out.println("[creature-sounds] pod " + podId + ", bay "
                    + server(level -> TestEntities.require(level, podId, PodEntity.class)
                            .cargo()
                            .entries()
                            .size()) + " ore after the take");
        }
    }
}
