package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.layer.StructureKind;
import io.github.pkeppeler.deepcharter.layer.StructureSite;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodLining;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.ScannerPods;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import java.util.Objects;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import org.jspecify.annotations.Nullable;

/**
 * Evidence scenario "kells-shaft" (#716): a Mole with the spoil hopper and a rack of slag brick rolls along the rail at the head of the Kells' shaft and
 * drops into it, easing down on the rotor. It lands on the first bulkhead with lava weeping from a gap in the brick wall beside it, and the pilot
 * presses the lining key: the pod stands still while it bricks the gap and the lava under the hole. It bores the bulkhead and goes on, and the
 * rest of the way down is not recorded: the pod reaches the crust. The player has night vision, because the layers are dark by design.
 */
public class KellsShaftScenario extends EvidenceScenario {
    /** The spacing cell of the shaft that is shown. */
    private static final int CELL = 3;

    private static final int LAYER = StructureKind.KELLS_SHAFT.layer();

    private static final int TICKS_PER_FRAME = 4;
    private static final int MAX_TICKS = 6000;
    private static final int BRICKS = 24;
    /** The pilot lets the pod sink this fast, in blocks a tick, the same as the measuring test's pilot. */
    private static final double SINK = 0.3;

    private static final int RESTING_TICKS = 6;
    private static final double CAMERA_UP = 3.2;
    private static final int GAP_LOOK = 5;
    private static final float MIN_FUEL = 10f;
    /** Frames shown after the lining is done, with the pod boring the bulkhead. */
    private static final int AFTER_FRAMES = 40;

    private static final int CRUST_BAND = LayerTuning.DEFAULT.crustThickness() + 1;

    @Override
    protected String name() {
        return "kells-shaft";
    }

    /** What the scenario reads of the pod on a tick. */
    private record PodState(double y, boolean onGround, boolean lining, int used, float hull) {}

    private static final Input FORWARD = new Input(true, false, false, false, false, false, false);
    private static final Input ROTOR = new Input(false, false, false, false, true, false, false);
    private static final Input SPRINT = new Input(false, false, false, false, false, false, true);
    private static final Input IDLE = new Input(false, false, false, false, false, false, false);

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            awaitWorld(context);
            StructureSite site = prepare(singleplayer);
            int floor = site.origin().getY() + site.height() - 3;
            float yaw = site.alongZ() ? 0f : -90f;
            Descent descent = new Descent(site, floor);
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel two = TestLayers.level(server, LAYER);
                ServerPlayer camera = server.getPlayerList().getPlayers().getFirst();
                camera.setGameMode(GameType.SPECTATOR);
                camera.addEffect(new MobEffectInstance(
                        MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION, 0, false, false));
                Vec3 start = new Vec3(worldX(site, -3.2, -0.5), floor, worldZ(site, -3.2, -0.5));
                MockPlayer pilot = MockPlayers.join(server, "Kells pilot", two, start, yaw);
                pilot.player().setGameMode(GameType.SURVIVAL);
                // The pilot's name tag would hang over the pod in every frame.
                Scoreboard scoreboard = server.getScoreboard();
                PlayerTeam hidden = scoreboard.addPlayerTeam("kells-shaft-pilot");
                hidden.setNameTagVisibility(Team.Visibility.NEVER);
                scoreboard.addPlayerToTeam(pilot.player().getScoreboardName(), hidden);
                PodEntity pod = TestEntities.create(PodRegistry.POD, two);
                pod.setPos(start);
                pod.setYRot(yaw);
                two.addFreshEntity(pod);
                ScannerPods.fit(server, pilot.player(), pod, 2);
                ScannerPods.fit(server, pilot.player(), pod, ComponentTrack.SPOIL_HOPPER, 1);
                pod.setFuel(100f);
                PodLining.modify(pod, state -> new PodLining.State(0, BRICKS, 0, false, false));
                if (!pilot.player().startRiding(pod)) {
                    throw new AssertionError("the pilot could not mount the pod");
                }
                descent.start(pilot, pod, camera);
                descent.look();
            });
            ClientWait.until(
                    context,
                    "the camera in layer 2",
                    client -> client.player != null && TestLayers.isIn(ClientLight.clientLevel(client), LAYER));
            context.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));
            // The pilot's "joined the game" line would sit over the bottom of every frame.
            context.runOnClient(client -> client.gui.hud.getChat().clearMessages(false));
            for (int i = 0; i < 20; i++) {
                singleplayer.getServer().runOnServer(server -> descent.look());
                context.waitTicks(TICKS_PER_FRAME);
            }
            screenshot(context, "kells-shaft-the-lip");

            boolean recording = true;
            boolean shotGap = false;
            boolean lined = false;
            int afterFrames = 0;
            int resting = 0;
            PodState state = null;
            for (int tick = 0; tick < MAX_TICKS; tick++) {
                state = singleplayer.getServer().computeOnServer(server -> descent.step());
                resting = descent.released() && state.onGround() && !state.lining() ? resting + 1 : 0;
                if (state.y() <= site.origin().getY() + CRUST_BAND - 0.5) {
                    break;
                }
                if (recording && !shotGap && resting == RESTING_TICKS) {
                    // The pilot has not pressed the lining key yet: the camera frames the open gap and its lava.
                    shotGap = true;
                    for (int i = 0; i < 6; i++) {
                        context.waitTicks(TICKS_PER_FRAME);
                        frame(context);
                    }
                    screenshot(context, "kells-shaft-the-gap");
                    singleplayer.getServer().runOnServer(server -> descent.press());
                }
                if (recording && shotGap && !lined && state.used() > 0 && !state.lining()) {
                    lined = true;
                    for (int i = 0; i < 3; i++) {
                        context.waitTicks(TICKS_PER_FRAME);
                        frame(context);
                    }
                    screenshot(context, "kells-shaft-lined");
                }
                if (recording && tick % TICKS_PER_FRAME == 0) {
                    frame(context);
                    if (lined && ++afterFrames > AFTER_FRAMES) {
                        recording = false;
                        screenshot(context, "kells-shaft-bored-through");
                    }
                }
                context.waitTicks(1); // tick-wait: the pilot is steered on every tick of the descent
            }
            if (!shotGap || !lined || state == null) {
                throw new AssertionError(
                        "The pod should land on a bulkhead and line it, landed " + shotGap + ", lined " + lined);
            }
            if (state.y() > site.origin().getY() + CRUST_BAND || state.hull() < 1f) {
                throw new AssertionError("The pod should reach the crust of the shaft alive: " + state);
            }
            screenshot(context, "kells-shaft-the-crust");
        }
    }

    /** The pilot's bot and the camera, which run on the server a tick at a time. */
    private static final class Descent {
        private final StructureSite site;
        private final int floor;
        private @Nullable MockPlayer pilot;
        private @Nullable PodEntity pod;
        private @Nullable ServerPlayer camera;
        private boolean released;
        /** True until the gap still is taken: the pilot rests on the bulkhead and does not press the lining key. */
        private boolean hold = true;

        Descent(StructureSite site, int floor) {
            this.site = site;
            this.floor = floor;
        }

        void start(MockPlayer pilot, PodEntity pod, ServerPlayer camera) {
            this.pilot = Objects.requireNonNull(pilot, "pilot");
            this.pod = pod;
            this.camera = camera;
        }

        boolean released() {
            return released;
        }

        /** The pilot may press the lining key. */
        void press() {
            hold = false;
        }

        /** One tick of the pilot: roll to the lip, sink on the rotor, land on a bulkhead, line a gap in reach, bore on. */
        PodState step() {
            MockPlayer pilot = Objects.requireNonNull(this.pilot, "the pilot is mounted before the first step");
            PodEntity pod = Objects.requireNonNull(this.pod, "the pod is placed before the first step");
            if (pod.fuel() < MIN_FUEL) {
                pod.setFuel(100f);
                pod.setStranded(false);
            }
            boolean lining = PodLining.working(pod);
            if (!released) {
                pilot.setInput(FORWARD);
                released = pod.getY() < floor - 0.5;
            } else {
                if (!hold && !lining && pod.onGround() && lavaToLine(pod)) {
                    PodLining.toggle(pilot.player());
                    lining = PodLining.working(pod);
                }
                boolean rotor = !pod.onGround() && pod.getDeltaMovement().y < -SINK;
                pilot.setInput(rotor ? ROTOR : !lining && !hold && pod.onGround() ? SPRINT : IDLE);
            }
            look();
            return new PodState(
                    pod.getY(), pod.onGround(), lining, PodLining.of(pod).used(), pod.hull());
        }

        /** The camera: down the rail to the lip while the pod rolls, then above the pod, looking toward the nearest gap in the wall. */
        void look() {
            PodEntity pod = Objects.requireNonNull(this.pod, "the pod is placed before the camera moves");
            ServerPlayer camera = Objects.requireNonNull(this.camera, "the camera is set before it moves");
            Vec3 at;
            Vec3 target;
            if (!released) {
                at = new Vec3(worldX(site, -8, -0.5), floor + 1.6, worldZ(site, -8, -0.5));
                target = pod.position().add(0, 1, 0);
            } else {
                int[] gap = nearestGap(pod);
                double du = gap == null ? 1 : gap[0] + 0.5 - 0.0;
                double dv = gap == null ? 0 : gap[1] + 0.5;
                // The camera is over the pod's cell, toward the side the gap is not on, and looks at the gap's cell.
                at = new Vec3(
                        worldX(site, -0.3 - 0.3 * Math.signum(du), -0.5 - 0.3 * Math.signum(dv)),
                        pod.getY() + CAMERA_UP,
                        worldZ(site, -0.3 - 0.3 * Math.signum(du), -0.5 - 0.3 * Math.signum(dv)));
                int gapY = gap == null ? (int) pod.getY() - 1 : gap[2];
                target = new Vec3(worldX(site, du - 0.5, dv - 0.5), gapY + 0.5, worldZ(site, du - 0.5, dv - 0.5));
            }
            Vec3 d = target.subtract(at);
            float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
            float pitch = (float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z)));
            camera.teleportTo((ServerLevel) pod.level(), at.x, at.y, at.z, Set.of(), yaw, pitch, true);
        }

        /** The ring cell holding lava nearest below the pod: its {@code u} and {@code v} in the shaft's axes, and its Y; null when none is within reach. */
        private int @Nullable [] nearestGap(PodEntity pod) {
            for (int y = (int) Math.floor(pod.getY()) + 1; y > (int) Math.floor(pod.getY()) - GAP_LOOK; y--) {
                for (StructureKind.RingSide side : StructureKind.kellsRingSides()) {
                    BlockPos cell = BlockPos.containing(
                            worldX(site, side.u() + 0.5, side.v() + 0.5),
                            y,
                            worldZ(site, side.u() + 0.5, side.v() + 0.5));
                    if (pod.level().getFluidState(cell).is(FluidTags.LAVA)) {
                        return new int[] {side.u(), side.v(), y};
                    }
                }
            }
            return null;
        }

        private static boolean lavaToLine(PodEntity pod) {
            return PodLining.cellsToLine(pod).stream()
                    .anyMatch(cell -> pod.level().getFluidState(cell).is(FluidTags.LAVA));
        }
    }

    /** The shaft of the cell, with the chunks of it and of the rail line made full. */
    private static StructureSite prepare(TestSingleplayerContext singleplayer) {
        return singleplayer.getServer().computeOnServer(server -> {
            ServerLevel level = TestLayers.level(server, LAYER);
            StructureSite site = StructureSite.in(
                    level.getSeed(),
                    StructureKind.KELLS_SHAFT,
                    level.getMinY(),
                    level.getHeight(),
                    CELL,
                    CELL,
                    Colony.anchor(server, ColonyAnchor.CONDUIT)
                            .orElseThrow(() -> new AssertionError("the colony was not built when the world started")));
            BoundingBox box = site.bounds();
            for (int chunkX = (box.minX() - 8) >> 4; chunkX <= (box.maxX() + 8) >> 4; chunkX++) {
                for (int chunkZ = (box.minZ() - 8) >> 4; chunkZ <= (box.maxZ() + 8) >> 4; chunkZ++) {
                    level.getChunk(chunkX, chunkZ, ChunkStatus.FULL);
                }
            }
            return site;
        });
    }

    /** The world x of a point in the structure's own axes. */
    private static double worldX(StructureSite site, double u, double v) {
        return site.origin().getX() + (site.alongZ() ? v : u);
    }

    private static double worldZ(StructureSite site, double u, double v) {
        return site.origin().getZ() + (site.alongZ() ? u : v);
    }
}
