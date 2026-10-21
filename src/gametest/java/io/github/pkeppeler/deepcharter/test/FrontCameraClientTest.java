package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.client.pod.FrontCameraFeed;
import io.github.pkeppeler.deepcharter.client.pod.FrontCameraFeed.Inputs;
import io.github.pkeppeler.deepcharter.client.pod.FrontCameraFeed.State;
import io.github.pkeppeler.deepcharter.client.theme.CockpitLook;
import io.github.pkeppeler.deepcharter.client.theme.CockpitLook.CameraLook;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientPacks;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.EvidencePins;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.ScannerPods;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestPacks;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Set;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * Client GameTest for the front camera's feed (#448, docs/design/cockpit-concepts-2.md): the feed's state follows the hazards (a hit leaves static that fades, lava heats the
 * lens until the feed fails and cools it back, a hull that is nearly gone drops the feed out in bursts, no power means no feed), and in the game a pod with the camera has its cab
 * left out of the picture from the seat while the feed is live, has it back while the feed is down, and shows static over the picture after a hit.
 */
public class FrontCameraClientTest implements FabricClientGameTest {
    private static final int FEET_Y = TestLayers.worldY(1, 150);
    private static final int ROOM_RADIUS = 8;
    private static final int ROOM_HEIGHT = 30;
    private static final int LAVA_RADIUS = 2;
    private static final int GUI_SCALE = 2;
    private static final float HIT = 20f;
    /** The middle of the view at GUI scale 2 (427 x 240), clear of the plain HUD's text, scanner and hotbar: where the cab's pillars and cutter are. */
    private static final int[] VIEW_BOX = {100, 70, 330, 200};
    /** How much of the view must change when the cab leaves it, or when static covers it. */
    private static final double CHANGED = 0.10;

    private static final double COVERED = 0.50;
    private static final int DROP_SAMPLE = 2000;
    private static final int POD = 1;
    private static final int OTHER_POD = 2;
    private static final double OTHER_POD_EAST = 4.0;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        context.runOnClient(client -> expectTheFeedFollowsItsHazards());
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40); // tick-wait: the world settles before the room is carved
            context.runOnClient(client -> {
                client.options.guiScale().set(GUI_SCALE);
                client.options.setCameraType(CameraType.FIRST_PERSON);
            });
            EvidencePins.lightFlickerOff = true;
            EvidencePins.handsHidden = true;
            try {
                PodEntity pod = seatedPod(context, singleplayer);
                expectNoCameraWithoutThePart(context);
                BufferedImage withoutCamera = shot(context, "front-camera-off");
                ClientPacks.enable(context, TestPacks.COCKPIT_CAMERA);
                try {
                    BufferedImage clear = expectTheCabLeavesTheView(context, withoutCamera);
                    expectTheFeedAnswersOnlyToItsPilotInFirstPerson(context, singleplayer, pod);
                    expectLavaCooksTheLensAndTheCabComesBack(context, singleplayer, pod, clear);
                } finally {
                    ClientPacks.disable(context, TestPacks.COCKPIT_CAMERA);
                }
                ClientPacks.enable(context, TestPacks.COCKPIT_CAMERA_HOLD);
                try {
                    expectAHitLeavesStatic(context, singleplayer, pod);
                } finally {
                    ClientPacks.disable(context, TestPacks.COCKPIT_CAMERA_HOLD);
                }
            } finally {
                EvidencePins.reset();
            }
        }
    }

    /** The feed's rules on their own, one tick at a time. */
    private static void expectTheFeedFollowsItsHazards() {
        CockpitLook cockpit = CockpitLook.current();
        CameraLook look = cockpit.camera();
        double low = cockpit.hullLowFraction();
        RandomSource random = RandomSource.create(7);
        float max = 100f;

        State state = FrontCameraFeed.step(State.START, new Inputs(POD, max, max, false, true), look, low, random);
        State swapped =
                FrontCameraFeed.step(state, new Inputs(OTHER_POD, max / 2, max, false, true), look, low, random);
        require(
                swapped.staticLevel() == 0.0 && swapped.podId() == OTHER_POD,
                "A pilot who changes pods straight to another pod has a new feed and no hit from the other pod's hull: "
                        + swapped);
        require(
                state.staticLevel() == 0.0 && state.live(),
                "A pod that has not been hit has a clear, live feed: " + state);
        float hit = 5f;
        State hurt = FrontCameraFeed.step(state, new Inputs(POD, max - hit, max, false, true), look, low, random);
        double expected = hit / max * look.staticPerHull();
        require(
                Math.abs(hurt.staticLevel() - expected) < 1e-9,
                "A hit of " + hit + " of " + max + " leaves static of " + expected + ": " + hurt);
        require(hurt.live(), "Static does not end the feed, only covers it: " + hurt);
        State fading = hurt;
        int ticks = 3;
        for (int i = 0; i < ticks; i++) {
            fading = FrontCameraFeed.step(fading, new Inputs(POD, max - hit, max, false, true), look, low, random);
        }
        require(
                Math.abs(fading.staticLevel() - (expected - ticks * look.staticFallPerTick())) < 1e-9,
                "Static falls by the fade each tick: " + fading);
        State blast = FrontCameraFeed.step(state, new Inputs(POD, max / 2, max, false, true), look, low, random);
        require(blast.staticLevel() == 1.0, "A hit of half the hull is full static, not more: " + blast);

        State lava = FrontCameraFeed.step(state, new Inputs(POD, max - hit, max, true, true), look, low, random);
        require(
                lava.staticLevel() == 0.0,
                "Lava's burn is heat, not an impact: no static from the hull it takes: " + lava);

        State burning = state;
        int toFail = (int) Math.ceil(look.lensDead() / look.lensHeatPerTick());
        for (int i = 0; i < toFail - 2; i++) {
            burning = FrontCameraFeed.step(burning, new Inputs(POD, max, max, true, true), look, low, random);
        }
        require(
                !burning.lensFailed() && burning.live(),
                "The lens holds a little under " + toFail + " ticks of lava: " + burning);
        for (int i = 0; i < 4; i++) {
            burning = FrontCameraFeed.step(burning, new Inputs(POD, max, max, true, true), look, low, random);
        }
        require(
                burning.lensFailed() && !burning.live(),
                "The lens fails after " + toFail + " ticks of lava, and a failed feed is not live: " + burning);
        State cooling = burning;
        int toRecover = (int) Math.ceil((look.lensDead() - look.lensRecover()) / look.lensCoolPerTick());
        for (int i = 0; i < toRecover - 3; i++) {
            cooling = FrontCameraFeed.step(cooling, new Inputs(POD, max, max, false, true), look, low, random);
        }
        require(
                cooling.lensFailed(),
                "A failed lens stays failed until it has cooled to " + look.lensRecover() + ": " + cooling);
        for (int i = 0; i < 6; i++) {
            cooling = FrontCameraFeed.step(cooling, new Inputs(POD, max, max, false, true), look, low, random);
        }
        require(!cooling.lensFailed() && cooling.live(), "A cooled lens brings the feed back: " + cooling);

        State unpowered = FrontCameraFeed.step(state, new Inputs(POD, max, max, false, false), look, low, random);
        require(!unpowered.live(), "A pod with no power has no feed: " + unpowered);

        int healthy = droppedTicks(look, low, max * (float) (low + 0.2), max);
        int hurting = droppedTicks(look, low, max * (float) low / 2f, max);
        int wreck = droppedTicks(look, low, 0f, max);
        require(
                healthy == 0,
                "A hull over the low mark never drops the feed out, got " + healthy + " ticks of drop-out");
        require(
                hurting > 0 && wreck > hurting,
                "The less hull is left, the more of the feed drops out: " + hurting + " ticks at half the low mark, "
                        + wreck + " at none");
        require(
                wreck < DROP_SAMPLE,
                "Even a hull of none leaves some of the feed: " + wreck + " of " + DROP_SAMPLE + " ticks");
    }

    /** How many of {@link #DROP_SAMPLE} ticks the feed is out for, with this much hull left. */
    private static int droppedTicks(CameraLook look, double low, float hull, float max) {
        RandomSource random = RandomSource.create(11);
        State state = State.START;
        int dropped = 0;
        for (int i = 0; i < DROP_SAMPLE; i++) {
            state = FrontCameraFeed.step(state, new Inputs(POD, hull, max, false, true), look, low, random);
            if (!state.live()) {
                dropped++;
            }
        }
        return dropped;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private PodEntity seatedPod(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        PodEntity[] made = {null};
        singleplayer.getServer().runOnServer(server -> {
            ServerLevel one = TestLayers.level(server, 1);
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            player.setGameMode(GameType.SURVIVAL);
            RoomCarver.carve(
                    one,
                    -ROOM_RADIUS,
                    ROOM_RADIUS,
                    FEET_Y - 1,
                    FEET_Y + ROOM_HEIGHT,
                    -ROOM_RADIUS,
                    ROOM_RADIUS,
                    Blocks.AIR);
            player.teleportTo(one, 0.5, FEET_Y, 0.5, Set.of(), 0f, 0f, true);
            PodEntity pod = TestEntities.create(PodRegistry.POD, one);
            pod.setPos(0.5, FEET_Y, 0.5);
            pod.setNoGravity(true);
            one.addFreshEntity(pod);
            ScannerPods.fit(server, player, pod, 1);
            if (!player.startRiding(pod)) {
                throw new AssertionError("the player could not mount the pod");
            }
            made[0] = pod;
        });
        ClientWait.until(
                context,
                "the pilot seated in the pod in layer 1",
                client -> client.player != null
                        && client.player.getVehicle() instanceof PodEntity
                        && TestLayers.isIn(ClientLight.clientLevel(client), 1));
        return made[0];
    }

    private void expectNoCameraWithoutThePart(ClientGameTestContext context) {
        boolean hidden = context.computeOnClient(client ->
                FrontCameraFeed.hidesCab(TestEntities.vehicle(ClientSession.player(client), PodEntity.class)));
        if (hidden || !context.computeOnClient(client -> FrontCameraFeed.state().equals(State.START))) {
            throw new AssertionError(
                    "The mod ships with no pod carrying the camera: the cab is in the view and the feed has no state");
        }
    }

    /** With the camera the pod is left out of the picture; returns the picture. */
    private BufferedImage expectTheCabLeavesTheView(ClientGameTestContext context, BufferedImage withoutCamera) {
        ClientWait.until(
                context,
                "a live front camera feed",
                client -> ClientSession.player(client).getVehicle() instanceof PodEntity shown
                        && FrontCameraFeed.hidesCab(shown));
        BufferedImage clear = shot(context, "front-camera-clear");
        double changed = differing(withoutCamera, clear, VIEW_BOX);
        if (changed < CHANGED) {
            throw new AssertionError("With the front camera the pod's cab is not in the view from the seat: only "
                    + changed + " of the middle of the view differs from the view without it");
        }
        return clear;
    }

    /**
     * The cab is hidden only for the pilot looking out of their own pod in first person: from outside, in third person and from another pod it is drawn. A pilot who
     * gets out starts the feed again, and one who boards starts it clear.
     */
    private void expectTheFeedAnswersOnlyToItsPilotInFirstPerson(
            ClientGameTestContext context, TestSingleplayerContext singleplayer, PodEntity pod) {
        context.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_BACK));
        boolean hiddenInThirdPerson = context.computeOnClient(client ->
                FrontCameraFeed.hidesCab(TestEntities.vehicle(ClientSession.player(client), PodEntity.class)));
        context.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));
        if (hiddenInThirdPerson) {
            throw new AssertionError(
                    "In third person the pod is outside the view and is drawn: the camera hides the cab only from the seat in first person");
        }
        int[] otherId = {0};
        singleplayer.getServer().runOnServer(server -> {
            ServerLevel one = TestLayers.level(server, 1);
            PodEntity other = TestEntities.create(PodRegistry.POD, one);
            other.setPos(0.5 + OTHER_POD_EAST, FEET_Y, 0.5);
            other.setNoGravity(true);
            one.addFreshEntity(other);
            otherId[0] = other.getId();
        });
        ClientWait.until(
                context,
                "the other pod on the client",
                client -> ClientLight.clientLevel(client).getEntity(otherId[0]) instanceof PodEntity);
        boolean hidesOther = context.computeOnClient(client -> FrontCameraFeed.hidesCab(
                TestEntities.require(ClientLight.clientLevel(client), otherId[0], PodEntity.class)));
        boolean hidesOwn = context.computeOnClient(client ->
                FrontCameraFeed.hidesCab(TestEntities.vehicle(ClientSession.player(client), PodEntity.class)));
        if (hidesOther || !hidesOwn) {
            throw new AssertionError("The camera hides the pilot's own pod and not a pod beside it: own " + hidesOwn
                    + ", other " + hidesOther);
        }
        singleplayer.getServer().runOnServer(server -> {
            server.getPlayerList().getPlayers().getFirst().stopRiding();
            TestEntities.require(TestLayers.level(server, 1), otherId[0]).discard();
        });
        ClientWait.until(
                context,
                "the feed reset after the pilot got out",
                client -> ClientSession.player(client).getVehicle() == null
                        && FrontCameraFeed.state().equals(State.START),
                client -> FrontCameraFeed.state().toString());
        singleplayer.getServer().runOnServer(server -> {
            if (!server.getPlayerList().getPlayers().getFirst().startRiding(pod)) {
                throw new AssertionError("the pilot could not board the pod again");
            }
        });
        ClientWait.until(
                context,
                "a live feed once the pilot is back",
                client -> ClientSession.player(client).getVehicle() instanceof PodEntity shown
                        && FrontCameraFeed.hidesCab(shown));
    }

    private void expectLavaCooksTheLensAndTheCabComesBack(
            ClientGameTestContext context, TestSingleplayerContext singleplayer, PodEntity pod, BufferedImage clear) {
        singleplayer.getServer().runOnServer(server -> {
            pod.setHull(pod.maxHull());
            lava(pod, true);
        });
        ClientWait.until(
                context,
                "the lens failed in the lava",
                client -> FrontCameraFeed.state().lensFailed(),
                client -> FrontCameraFeed.state().toString());
        boolean hidden = context.computeOnClient(client ->
                FrontCameraFeed.hidesCab(TestEntities.vehicle(ClientSession.player(client), PodEntity.class)));
        if (hidden) {
            throw new AssertionError("A failed feed shows the cab again: the pod must not be hidden from its pilot");
        }
        BufferedImage dead = shot(context, "front-camera-dead");
        double changed = differing(clear, dead, VIEW_BOX);
        if (changed < CHANGED) {
            throw new AssertionError("With the feed down the cab is back in the view: only " + changed
                    + " of the middle of the view differs from the clear feed");
        }
        singleplayer.getServer().runOnServer(server -> lava(pod, false));
        ClientWait.until(
                context,
                "the lens cooled and the feed back",
                client -> ClientSession.player(client).getVehicle() instanceof PodEntity shown
                        && FrontCameraFeed.hidesCab(shown),
                client -> FrontCameraFeed.state().toString());
    }

    private void expectAHitLeavesStatic(
            ClientGameTestContext context, TestSingleplayerContext singleplayer, PodEntity pod) {
        singleplayer.getServer().runOnServer(server -> pod.setHull(pod.maxHull()));
        ClientWait.until(
                context,
                "a live feed with no static",
                client -> ClientSession.player(client).getVehicle() instanceof PodEntity shown
                        && FrontCameraFeed.hidesCab(shown)
                        && FrontCameraFeed.state().staticLevel() == 0.0,
                client -> FrontCameraFeed.state().toString());
        BufferedImage clear = shot(context, "front-camera-hold-clear");
        singleplayer.getServer().runOnServer(server -> pod.setHull(pod.hull() - HIT));
        ClientWait.until(
                context,
                "static after the hit",
                client -> FrontCameraFeed.state().staticLevel() > COVERED,
                client -> FrontCameraFeed.state().toString());
        BufferedImage snowy = shot(context, "front-camera-static");
        double covered = differing(clear, snowy, VIEW_BOX);
        if (covered < COVERED) {
            throw new AssertionError("Static after a hit covers the picture: only " + covered
                    + " of the middle of the view differs from the clear feed");
        }
        if (!context.computeOnClient(client ->
                FrontCameraFeed.hidesCab(TestEntities.vehicle(ClientSession.player(client), PodEntity.class)))) {
            throw new AssertionError("Static does not bring the cab back: the feed is live under it");
        }
    }

    /** Lava, or air again, in a square at the pod's feet, so the hull burns while it sits there. */
    private static void lava(PodEntity pod, boolean on) {
        ServerLevel level = (ServerLevel) pod.level();
        BlockPos feet = pod.blockPosition();
        RoomCarver.carve(
                level,
                feet.offset(-LAVA_RADIUS, 0, -LAVA_RADIUS),
                feet.offset(LAVA_RADIUS, 0, LAVA_RADIUS),
                (on ? Blocks.LAVA : Blocks.AIR).defaultBlockState());
    }

    private BufferedImage shot(ClientGameTestContext context, String name) {
        context.runOnClient(client -> {
            ClientSession.player(client).setYRot(0f);
            ClientSession.player(client).setXRot(4f);
            client.gui.toastManager().clear();
        });
        ClientWait.until(
                context, "every section in view rendered", client -> client.levelRenderer.hasRenderedAllSections());
        context.waitTicks(5); // tick-wait: the pod and the view settle for a few frames
        Path file = context.takeScreenshot(name);
        try {
            return ImageIO.read(file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The share of the pixels of the box that differ by more than a few levels between two shots. */
    private static double differing(BufferedImage a, BufferedImage b, int[] box) {
        int differing = 0;
        int total = 0;
        for (int y = box[1] * GUI_SCALE; y < box[3] * GUI_SCALE; y++) {
            for (int x = box[0] * GUI_SCALE; x < box[2] * GUI_SCALE; x++) {
                total++;
                int pa = a.getRGB(x, y);
                int pb = b.getRGB(x, y);
                int delta = Math.abs((pa >> 16 & 0xFF) - (pb >> 16 & 0xFF))
                        + Math.abs((pa >> 8 & 0xFF) - (pb >> 8 & 0xFF))
                        + Math.abs((pa & 0xFF) - (pb & 0xFF));
                if (delta > 12) {
                    differing++;
                }
            }
        }
        return (double) differing / total;
    }
}
