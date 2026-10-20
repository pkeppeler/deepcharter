package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.pod.PodGeoRenderer;
import io.github.pkeppeler.deepcharter.client.theme.PodPaintLook;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.EvidenceWorld;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Evidence scenario "pod-rotor-paint" (#383): two Moles of two charters stand side by side, each in its own paint colour, with their
 * rotors folded. A pilot lifts one off (the rotor folds out and turns), and lets it down (the rotor folds in). Last, a Prospector in
 * the first charter's colour stands behind them.
 *
 * <p>The stage is a stone pad where the world's player starts, under the brightest dusk. The frames are one segment, logged as
 * {@code [pod-rotor-paint-segment] fold <first>-<last>}, which is how the GIF is cut.
 */
public class PodRotorPaintScenario extends EvidenceScenario {
    private static final int PAD_HALF = 14;
    private static final int PAD_CLEARANCE = 12;
    private static final double EYE = 1.62;
    private static final int SETTLE_POLLS = 5;
    private static final int PARKED_FRAMES = 10;
    private static final int TICKS_PER_FRAME = 2;
    /** The pod climbs this far, in blocks, then hovers for {@value #HOVER_FRAMES} frames. */
    private static final double HOVER_HEIGHT = 0.5;

    private static final int HOVER_FRAMES = 14;
    private static final int MAX_CLIMB_FRAMES = 40;
    /** The pilot lets the rotor lift whenever the pod sinks faster than this, in blocks per tick, so it lands soft. */
    private static final double BRAKE_ABOVE_SINK = 0.25;

    private static final int MAX_FRAMES = 120;
    /** Frames after the landing, for the rotor to fold in. */
    private static final int SETTLED_FRAMES = 12;
    /** The pods face the camera, which stands north (-z) of them. */
    private static final float FACING_CAMERA = 180f;

    private static final Input LIFT = new Input(false, false, false, false, true, false, false);

    private int framesTaken;

    @Override
    protected String name() {
        return "pod-rotor-paint";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        ClientChecks.withHudHidden(context, () -> runHidden(context));
    }

    private void runHidden(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            EvidenceWorld.pin(context, singleplayer);
            Stage stage = setUp(context, singleplayer);
            Run run = new Run(context, singleplayer, stage);
            // The pilot's pod, and beside it a pod of a charter whose colour is the next one of the palette.
            PodEntity lifted = run.spawn(PodRegistry.POD, new Vec3(-1.5, 0, 4), stage.pilotCharter());
            CharterId other = new CharterId(new UUID(
                    0L,
                    (PodPaintLook.current().slotOf(stage.pilotCharter()) + 1)
                            % PodPaintLook.current().paints().size()));
            run.spawn(PodRegistry.POD, new Vec3(1.5, 0, 4), other);
            run.board(lifted);
            run.look(new Vec3(0, 4.2, -0.8), new Vec3(0, 3.0, 4));
            run.settle();
            screenshot(context, "two-charters-folded");
            int first = framesTaken + 1;
            for (int i = 0; i < PARKED_FRAMES; i++) {
                context.waitTicks(TICKS_PER_FRAME); // tick-wait: the clip is cut at fixed frames
                run.frame();
            }
            run.fly(lifted);
            System.out.println("[pod-rotor-paint-segment] fold " + first + "-" + framesTaken);
            run.disembark(lifted);
            run.spawn(PodRegistry.PROSPECTOR, new Vec3(0, 0, 9), stage.pilotCharter());
            run.look(new Vec3(0, 4.5, 0), new Vec3(0, 1.8, 7));
            run.settle();
            screenshot(context, "prospector-behind");
        }
    }

    /** What the setup builds: the pad the pods stand on, the pilot, and the pilot's charter. */
    private record Stage(BlockPos pos, MockPlayer pilot, CharterId pilotCharter) {}

    /** Levels a stone pad where the player starts, makes the player a creative flier camera with the HUD hidden, and joins a pilot with a charter. */
    private static Stage setUp(ClientGameTestContext ctx, TestSingleplayerContext world) {
        ClientWait.until(ctx, "the client in the world", client -> client.player != null && client.level != null);
        Stage stage = world.getServer().computeOnServer(server -> {
            ServerPlayer camera = server.getPlayerList().getPlayers().getFirst();
            BlockPos pad = camera.blockPosition();
            ServerLevel level = server.overworld();
            for (int dx = -PAD_HALF; dx <= PAD_HALF; dx++) {
                for (int dz = -PAD_HALF; dz <= PAD_HALF; dz++) {
                    for (int dy = -4; dy < PAD_CLEARANCE; dy++) {
                        level.setBlock(
                                pad.offset(dx, dy, dz),
                                dy < 0 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(),
                                Block.UPDATE_ALL);
                    }
                }
            }
            camera.setGameMode(GameType.CREATIVE);
            camera.getAbilities().mayfly = true;
            camera.getAbilities().flying = true;
            camera.onUpdateAbilities();
            camera.setNoGravity(true);
            camera.setPermanentlyInvulnerable(true);
            MockPlayer pilot = MockPlayers.join(server, "Pilot");
            pilot.teleportTo(level, Vec3.atBottomCenterOf(pad).add(0, 0, 2), FACING_CAMERA, 0f);
            pilot.player().setInvisible(true);
            pilot.player().setPermanentlyInvulnerable(true);
            if (Charters.found(server, pilot.player().getUUID(), "Pod Works").isPresent()) {
                throw new AssertionError("founding the pilot's charter should succeed");
            }
            CharterId pilotCharter = Charters.charterOfOrThrow(
                            server, pilot.player().getUUID())
                    .orElseThrow()
                    .id();
            return new Stage(pad, pilot, pilotCharter);
        });
        ctx.runOnClient(client -> {
            client.options.setCameraType(CameraType.FIRST_PERSON);
        });
        return stage;
    }

    private final class Run {
        private final ClientGameTestContext ctx;
        private final TestSingleplayerContext world;
        private final Stage stage;

        Run(ClientGameTestContext ctx, TestSingleplayerContext world, Stage stage) {
            this.ctx = ctx;
            this.world = world;
            this.stage = stage;
        }

        /** Lifts off to a hover, holds it, then lets down, braking the fall as a pilot does, lands, and waits for the rotor to fold in. */
        private void fly(PodEntity pod) {
            double floor = serverGet(server -> pod.getY());
            int hovered = 0;
            boolean shot = false;
            for (int i = 0; i < MAX_FRAMES; i++) {
                double[] now = serverGet(
                        server -> new double[] {pod.getY(), pod.getDeltaMovement().y, pod.onGround() ? 1 : 0});
                boolean climbing = hovered == 0 && i < MAX_CLIMB_FRAMES && now[0] - floor < HOVER_HEIGHT;
                boolean hovering = !climbing && hovered < HOVER_FRAMES && i < MAX_CLIMB_FRAMES + HOVER_FRAMES;
                if (i > 0 && !climbing && !hovering && now[2] == 1) {
                    break;
                }
                boolean thrust = climbing || (hovering ? now[1] < 0 : now[1] < -BRAKE_ABOVE_SINK);
                if (hovering) {
                    hovered++;
                }
                serverDo(server -> {
                    if (thrust) {
                        stage.pilot().setInput(LIFT);
                    } else {
                        stage.pilot().releaseInput();
                    }
                });
                ctx.waitTicks(TICKS_PER_FRAME); // tick-wait: the clip is cut at fixed frames
                frame();
                if (!shot && hovered > HOVER_FRAMES / 2) {
                    screenshot(ctx, "rotor-out");
                    shot = true;
                }
            }
            serverDo(server -> stage.pilot().releaseInput());
            for (int i = 0; i < SETTLED_FRAMES; i++) {
                ctx.waitTicks(TICKS_PER_FRAME); // tick-wait: the clip is cut at fixed frames
                frame();
            }
            screenshot(ctx, "rotor-folded-after-landing");
        }

        /** A pod of {@code type} {@code offset} from the stage, facing the camera, owned by {@code owner}; waits until the client draws it painted. */
        private PodEntity spawn(EntityType<PodEntity> type, Vec3 offset, CharterId owner) {
            PodEntity[] made = {null};
            serverDo(server -> {
                ServerLevel level = server.overworld();
                PodEntity pod = TestEntities.create(type, level);
                pod.setPos(Vec3.atBottomCenterOf(stage.pos()).add(offset));
                pod.setYRot(FACING_CAMERA);
                level.addFreshEntity(pod);
                PodComponents.register(pod, owner);
                pod.setFuel(100f);
                made[0] = pod;
            });
            PodEntity pod = made[0];
            ClientWait.until(
                    ctx,
                    "the pod drawn in its charter's paint",
                    client -> ClientLight.clientLevel(client).getEntity(pod.getId()) instanceof PodEntity shown
                            && renderer(client, shown)
                                    .appearanceOf(shown)
                                    .paint()
                                    .isPresent(),
                    client -> ClientLight.clientLevel(client).getEntity(pod.getId()) instanceof PodEntity shown
                            ? String.valueOf(renderer(client, shown).appearanceOf(shown))
                            : "no pod");
            return pod;
        }

        private static PodGeoRenderer renderer(Minecraft client, PodEntity pod) {
            Object drawn = client.getEntityRenderDispatcher().getRenderer(pod);
            if (!(drawn instanceof PodGeoRenderer geo)) {
                throw new AssertionError("the pod is not drawn with the GeckoLib renderer but with " + drawn);
            }
            return geo;
        }

        private void board(PodEntity pod) {
            serverDo(server -> {
                if (!stage.pilot().player().startRiding(pod)) {
                    throw new AssertionError("the pilot could not board " + pod);
                }
            });
            ClientWait.until(
                    ctx,
                    "the pilot aboard on the client",
                    client -> ClientLight.clientLevel(client).getEntity(pod.getId()) instanceof PodEntity shown
                            && shown.getControllingPassenger() != null);
            // An invisible player still draws what it holds: empty the hands of the client's copy only.
            ctx.runOnClient(client -> {
                if (ClientLight.clientLevel(client)
                                .getEntity(stage.pilot().player().getId())
                        instanceof Player shown) {
                    shown.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                    shown.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
                }
            });
        }

        private void disembark(PodEntity pod) {
            serverDo(server -> stage.pilot().player().stopRiding());
            ClientWait.until(
                    ctx,
                    "the pilot off the pod on the client",
                    client -> ClientLight.clientLevel(client).getEntity(pod.getId()) instanceof PodEntity shown
                            && shown.getControllingPassenger() == null);
        }

        /** Puts the camera's eye {@code eye} blocks from the stage, looking at {@code target} (also from the stage). */
        private void look(Vec3 eye, Vec3 target) {
            Vec3 from = Vec3.atBottomCenterOf(stage.pos()).add(eye);
            Vec3 d = target.subtract(eye);
            float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
            float pitch = (float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z)));
            serverDo(server -> server.getPlayerList()
                    .getPlayers()
                    .getFirst()
                    .teleportTo(server.overworld(), from.x, from.y - EYE, from.z, Set.of(), yaw, pitch, true));
        }

        /** Waits until the light has settled and every section in view has rendered, for {@value #SETTLE_POLLS} polls in a row. */
        private void settle() {
            int[] stable = {0};
            ClientWait.until(
                    ctx,
                    "the view settled",
                    () -> {
                        boolean ready = !serverGet(server ->
                                        server.overworld().getLightEngine().hasLightWork())
                                && ctx.computeOnClient(client -> client.levelRenderer.hasRenderedAllSections());
                        stable[0] = ready ? stable[0] + 1 : 0;
                        return stable[0] >= SETTLE_POLLS;
                    },
                    () -> ctx.computeOnClient(ClientWait::describe));
        }

        private void frame() {
            PodRotorPaintScenario.this.frame(ctx);
            framesTaken++;
        }

        private void serverDo(Consumer<MinecraftServer> action) {
            world.getServer().runOnServer(action::accept);
        }

        private <T> T serverGet(Function<MinecraftServer, T> query) {
            return world.getServer().computeOnServer(query::apply);
        }
    }
}
