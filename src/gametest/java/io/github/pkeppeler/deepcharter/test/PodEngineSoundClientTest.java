package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.sound.SoundTuning;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.PacketJitter;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEventListener;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client GameTest for #961: the engine of the pod the player pilots sounds as one continuous loop while it drives. The loops that
 * start and stop do so silently, so a change of engine state is a crossfade, never a cut; no loop's volume or pitch steps by more than
 * a fade tick's share; and the loops of the cab are not placed in the world, because a sound at the pod's last tick position, heard
 * from a camera that moves every frame, pans and swells twenty times a second. The network is made irregular
 * ({@link PacketJitter}), as it is in play.
 */
public class PodEngineSoundClientTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(PodEngineSoundClientTest.class);

    private static final int DRIVE_TICKS = 70;
    /** After the keys are let go: the pod's moving hold, and a fade out, and a fade in of the idle loop, with margin. */
    private static final int COAST_TICKS = 60;
    /** Ticks a drive has run before the engine is judged to be settled on its drive loop: the hold, the fade and margin. */
    private static final int SETTLE_TICKS = 30;
    /** Ticks for the idle loop to rise to full before the drive starts. */
    private static final int FADE_SETTLE_TICKS = 15;

    /** The pilot's pod's loops sound within this distance of the pilot; the mock's pod is eight blocks away. */
    private static final double OWN_RADIUS_SQUARED = 9;

    /** A step of the pod's client position above this, in one tick, is a move; the log lists them so a loop that starts can be put beside one. */
    private static final double PODS_STILL = 0.001;

    private static final String ENGINE_DRIVE = "pod.engine_drive";
    private static final String ENGINE_IDLE = "pod.engine_idle";

    /** The most a loop's volume or pitch may step in one client tick: one fade tick's share and 5% for rounding, so a shorter fade fails. */
    private static final float STEP_BOUND = 1f / SoundTuning.DEFAULT.engineFadeTicks() * 1.05f;
    /** A loop that starts or stops is silent to within one step. */
    private static final float SILENT = STEP_BOUND;

    /** What the test saw of one loop: when it was first and last active, and its volume and pitch every tick it was. */
    private static final class Trace {
        private final SoundInstance sound;
        /** Whether the loop is the pilot's own pod's: heard from the cab, or placed within a few blocks of the pilot. */
        private final boolean own;

        private final List<Float> volumes = new ArrayList<>();
        private final List<Float> pitches = new ArrayList<>();
        private int firstTick = -1;
        private int lastTick = -1;

        private Trace(SoundInstance sound, boolean own) {
            this.sound = sound;
            this.own = own;
        }

        private String path() {
            return sound.getIdentifier().getPath();
        }

        private String describe() {
            return path() + " ticks " + firstTick + ".." + lastTick + " volume " + volumes + " pitch " + pitches
                    + (sound.isRelative() ? " in the cab" : " in the world");
        }
    }

    /** Every pod loop the client starts, once each, in the order they start. */
    private static final class Heard implements SoundEventListener {
        private final IdentityHashMap<SoundInstance, Trace> traces = new IdentityHashMap<>();
        private final List<Trace> order = new ArrayList<>();

        @Override
        public void onPlaySound(SoundInstance sound, WeighedSoundEvents events, float range) {
            if (sound.getIdentifier().getNamespace().equals(DeepCharter.MOD_ID)
                    && sound.getIdentifier().getPath().startsWith("pod.engine")
                    && !traces.containsKey(sound)) {
                LocalPlayer player = Minecraft.getInstance().player;
                boolean own = sound.isRelative()
                        || (player != null
                                && player.distanceToSqr(sound.getX(), sound.getY(), sound.getZ()) < OWN_RADIUS_SQUARED);
                Trace trace = new Trace(sound, own);
                traces.put(sound, trace);
                order.add(trace);
            }
        }

        /** Reads every loop's state at client tick {@code tick}. */
        void sample(SoundManager sounds, int tick) {
            for (Trace trace : order) {
                if (sounds.isActive(trace.sound)) {
                    if (trace.firstTick < 0) {
                        trace.firstTick = tick;
                    }
                    trace.lastTick = tick;
                    trace.volumes.add(trace.sound.getVolume());
                    trace.pitches.add(trace.sound.getPitch());
                }
            }
        }

        /** The loops of the pilot's own pod: the other pod, eight blocks off, is another's. */
        List<Trace> cab() {
            return order.stream().filter(trace -> trace.own).toList();
        }
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        Heard heard = new Heard();
        context.runOnClient(client -> client.getSoundManager().addListener(heard));
        try (TwoPlayerServer two = TwoPlayerServer.start(context)) {
            PodMovementClientTest.moveToOpenGround(context, two);
            int mockPodId = PodMovementClientTest.mountBoth(two);
            ClientWait.until(
                    context,
                    "the client riding a pod with the mock's pod in view",
                    client -> ClientSession.player(client).getVehicle() instanceof PodEntity
                            && ClientLight.clientLevel(client).getEntity(mockPodId) instanceof PodEntity);
            PacketJitter.start();
            try {
                drive(context, heard, mockPodId);
            } finally {
                PacketJitter.stop();
                context.getInput().releaseKey(options -> options.keyUp);
            }
        } finally {
            context.runOnClient(client -> client.getSoundManager().removeListener(heard));
        }
    }

    private static void drive(ClientGameTestContext context, Heard heard, int mockPodId) {
        int[] tick = {0};
        Vec3[] last = {null};
        List<String> moves = new ArrayList<>();
        Runnable sample = () -> {
            context.waitTick();
            tick[0]++;
            context.runOnClient(client -> {
                heard.sample(client.getSoundManager(), tick[0]);
                Vec3 at = TestEntities.vehicle(ClientSession.player(client)).position();
                if (last[0] != null && at.distanceTo(last[0]) > PODS_STILL) {
                    moves.add(tick[0] + ": " + at.distanceTo(last[0]));
                }
                last[0] = at;
            });
        };
        ClientWait.until(
                context,
                "the cab's idle engine loop playing",
                client -> heard.cab().stream()
                        .anyMatch(trace -> trace.path().equals(ENGINE_IDLE)
                                && client.getSoundManager().isActive(trace.sound)));
        // Let the idle loop rise to full, so the drive starts from an engine heard at its steadiest.
        for (int i = 0; i < FADE_SETTLE_TICKS; i++) {
            sample.run();
        }
        context.getInput().holdKey(options -> options.keyUp);
        for (int i = 0; i < DRIVE_TICKS; i++) {
            sample.run();
        }
        // The loops the drive settled on, read while the key is still down.
        List<Trace> driving = heard.cab().stream()
                .filter(trace -> trace.lastTick == tick[0] && trace.volumes.getLast() > SILENT)
                .toList();
        context.getInput().releaseKey(options -> options.keyUp);
        for (int i = 0; i < COAST_TICKS; i++) {
            sample.run();
        }

        List<Trace> cab = heard.cab();
        LOGGER.info(
                "PodEngineSoundClientTest: cab loops: {}",
                cab.stream().map(Trace::describe).toList());
        LOGGER.info(
                "PodEngineSoundClientTest: client ticks the pilot's pod moved by more than {}: {}", PODS_STILL, moves);
        require(!cab.isEmpty(), "The pilot heard no engine loop in the cab");
        require(
                cab.stream()
                        .allMatch(trace -> trace.sound.isRelative()
                                && trace.sound.getAttenuation() == SoundInstance.Attenuation.NONE),
                "A cab loop is placed in the world, so it pans as the camera moves past the pod's tick position: "
                        + cab.stream().map(Trace::describe).toList());
        for (Trace trace : cab) {
            require(
                    trace.volumes.getFirst() <= SILENT,
                    "The loop " + trace.path() + " started at volume " + trace.volumes.getFirst() + ", over " + SILENT
                            + ": an engine cut in. " + trace.describe());
            boolean stillPlaying =
                    context.computeOnClient(c -> c.getSoundManager().isActive(trace.sound));
            require(
                    stillPlaying || trace.volumes.getLast() <= SILENT,
                    "The loop " + trace.path() + " stopped at volume " + trace.volumes.getLast() + ", over " + SILENT
                            + ": an engine was cut off. " + trace.describe());
            for (int i = 1; i < trace.volumes.size(); i++) {
                require(
                        Math.abs(trace.volumes.get(i) - trace.volumes.get(i - 1)) <= STEP_BOUND,
                        "The loop " + trace.path() + " stepped in volume at its sample " + i + ", over " + STEP_BOUND
                                + ". " + trace.describe());
                require(
                        Math.abs(trace.pitches.get(i) - trace.pitches.get(i - 1)) <= STEP_BOUND,
                        "The loop " + trace.path() + " stepped in pitch at its sample " + i + ", over " + STEP_BOUND
                                + ". " + trace.describe());
            }
        }
        // Nothing leaks: a loop is stopped, or still audible. The one still playing after the coast is the idle loop.
        List<Trace> playing = new ArrayList<>();
        for (Trace trace : cab) {
            if (context.computeOnClient(c -> c.getSoundManager().isActive(trace.sound))) {
                playing.add(trace);
            }
        }
        require(
                playing.size() == 1 && playing.getFirst().path().equals(ENGINE_IDLE),
                "After the coast only the idle loop should still play, a faded loop is stopped and removed: "
                        + playing.stream().map(Trace::describe).toList());
        // The other pod's loops are placed at that pod, not heard from the cab.
        List<Trace> others = heard.order.stream().filter(trace -> !trace.own).toList();
        Vec3 mockPod = context.computeOnClient(
                c -> TestEntities.require(ClientLight.clientLevel(c), mockPodId).position());
        require(!others.isEmpty(), "The pilot's client started no loop for the other pod");
        for (Trace trace : others) {
            require(
                    !trace.sound.isRelative()
                            && trace.sound.getAttenuation() != SoundInstance.Attenuation.NONE
                            && mockPod.distanceToSqr(trace.sound.getX(), trace.sound.getY(), trace.sound.getZ()) < 4,
                    "The other pod's loop is not placed at that pod " + mockPod + ": " + trace.describe());
        }
        require(
                driving.size() == 1 && driving.getFirst().path().equals(ENGINE_DRIVE),
                "A settled drive should sound as one drive loop at full volume, not "
                        + driving.stream().map(Trace::describe).toList());
        Trace engine = driving.getFirst();
        long driveLoops =
                cab.stream().filter(trace -> trace.path().equals(ENGINE_DRIVE)).count();
        require(
                driveLoops == 1,
                "The drive restarted its loop: " + driveLoops + " drive loops started, "
                        + cab.stream().map(Trace::describe).toList());
        require(
                engine.lastTick - engine.firstTick + 1 >= DRIVE_TICKS - SETTLE_TICKS,
                "The drive loop did not play through the drive: " + engine.describe());
    }
}
