package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.client.pod.LiningKeys;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.layer.StructureKind;
import io.github.pkeppeler.deepcharter.layer.StructureSite;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodLining;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.ScannerPods;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

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
    private static final int SETTLE_TICKS = 80;
    private static final int MAX_TICKS = 6000;
    private static final int BRICKS = 24;
    private static final float LOOK_DOWN = 40f;
    /** The pilot lets the pod sink this fast, in blocks a tick, the same as the measuring test's pilot. */
    private static final double SINK = 0.3;

    private static final int RESTING_TICKS = 6;
    /** Frames shown after the lining is done, with the pod boring the bulkhead. */
    private static final int AFTER_FRAMES = 40;

    private static final int CRUST_BAND = LayerTuning.DEFAULT.crustThickness() + 1;

    @Override
    protected String name() {
        return "kells-shaft";
    }

    /** What the scenario reads of the pod on a tick. */
    private record PodState(double y, double vy, boolean onGround, boolean lining, float hull) {}

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            awaitWorld(context);
            StructureSite site = prepare(singleplayer);
            int floor = site.origin().getY() + site.height() - 3;
            float yaw = site.alongZ() ? 0f : -90f;
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel two = TestLayers.level(server, LAYER);
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SURVIVAL);
                player.addEffect(new MobEffectInstance(
                        MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION, 0, false, false));
                BlockPos start = pos(site, -3.2, floor, -0.5);
                player.teleportTo(two, start.getX() + 0.5, floor, start.getZ() + 0.5, Set.of(), yaw, LOOK_DOWN, true);
                PodEntity pod = TestEntities.create(PodRegistry.POD, two);
                pod.setPos(worldX(site, -3.2, -0.5), floor, worldZ(site, -3.2, -0.5));
                pod.setYRot(yaw);
                two.addFreshEntity(pod);
                ScannerPods.fit(server, player, pod, 2);
                ScannerPods.fit(server, player, pod, ComponentTrack.SPOIL_HOPPER, 1);
                pod.setFuel(100f);
                PodLining.modify(pod, state -> new PodLining.State(0, BRICKS, 0, false, false));
                if (!player.startRiding(pod)) {
                    throw new AssertionError("the player could not mount the pod");
                }
            });
            ClientWait.until(
                    context,
                    "the pilot riding a pod in layer 2",
                    client -> client.player != null
                            && client.player.getVehicle() instanceof PodEntity
                            && TestLayers.isIn(ClientLight.clientLevel(client), LAYER));
            context.runOnClient(client -> {
                client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                ClientSession.player(client).setXRot(LOOK_DOWN);
            });
            context.waitTicks(SETTLE_TICKS);
            screenshot(context, "kells-shaft-the-lip");
            for (int i = 0; i < 4; i++) {
                frame(context);
                context.waitTicks(TICKS_PER_FRAME);
            }

            // Roll to the lip and drop in.
            context.getInput().holdKey(options -> options.keyUp);
            boolean rotor = false;
            boolean recording = true;
            boolean shotGap = false;
            boolean lined = false;
            int resting = 0;
            double restedAt = Double.NaN;
            int afterFrames = 0;
            boolean released = false;
            for (int tick = 0; tick < MAX_TICKS; tick++) {
                PodState state = read(singleplayer);
                if (!released && state.y() < floor - 0.5) {
                    context.getInput().releaseKey(options -> options.keyUp);
                    released = true;
                }
                boolean wantRotor = !state.onGround() && state.vy() < -SINK && released;
                if (wantRotor != rotor) {
                    rotor = wantRotor;
                    if (rotor) {
                        context.getInput().holdKey(options -> options.keyJump);
                    } else {
                        context.getInput().releaseKey(options -> options.keyJump);
                    }
                }
                resting = released && state.onGround() && !state.lining() ? resting + 1 : 0;
                if (state.y() <= site.origin().getY() + CRUST_BAND - 0.5) {
                    break;
                }
                if (resting == RESTING_TICKS && !lined && recording) {
                    // Landed on the first bulkhead: the gap is in the wall beside the pod.
                    if (!shotGap) {
                        shotGap = true;
                        restedAt = state.y();
                        for (int i = 0; i < 6; i++) {
                            context.waitTicks(TICKS_PER_FRAME);
                            frame(context);
                        }
                        screenshot(context, "kells-shaft-the-gap");
                    }
                    context.getInput().pressKey(LiningKeys.LINE);
                    context.getInput().holdKey(options -> options.keySprint);
                    resting = 0;
                } else if (resting == RESTING_TICKS && (lined || !recording)) {
                    context.getInput().pressKey(LiningKeys.LINE);
                }
                if (recording && shotGap && !lined && !state.lining() && state.onGround() && liningDone(singleplayer)) {
                    lined = true;
                    screenshot(context, "kells-shaft-lined");
                }
                if (recording && tick % TICKS_PER_FRAME == 0) {
                    frame(context);
                    if (lined && ++afterFrames > AFTER_FRAMES) {
                        recording = false;
                        screenshot(context, "kells-shaft-bored-through");
                    }
                }
                context.waitTicks(1); // tick-wait: the pilot is polled and steered on every tick of the descent
            }
            context.getInput().releaseKey(options -> options.keyJump);
            context.getInput().releaseKey(options -> options.keySprint);
            if (!shotGap || !lined) {
                throw new AssertionError("The pod should land on a bulkhead and line it, landed " + shotGap + ", lined "
                        + lined + " (rested at " + restedAt + ")");
            }
            PodState end = read(singleplayer);
            if (end.y() > site.origin().getY() + CRUST_BAND || end.hull() < 1f) {
                throw new AssertionError("The pod should reach the crust of the shaft alive: " + end);
            }
            screenshot(context, "kells-shaft-the-crust");
        }
    }

    /** The lining of the pod's slab is over: the pod holds no work and has placed bricks. */
    private static boolean liningDone(TestSingleplayerContext singleplayer) {
        return singleplayer.getServer().computeOnServer(server -> {
            PodEntity pod =
                    TestEntities.vehicle(server.getPlayerList().getPlayers().getFirst(), PodEntity.class);
            return !PodLining.working(pod) && PodLining.of(pod).used() > 0;
        });
    }

    private static PodState read(TestSingleplayerContext singleplayer) {
        return singleplayer.getServer().computeOnServer(server -> {
            PodEntity pod =
                    TestEntities.vehicle(server.getPlayerList().getPlayers().getFirst(), PodEntity.class);
            return new PodState(
                    pod.getY(), pod.getDeltaMovement().y, pod.onGround(), PodLining.working(pod), pod.hull());
        });
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

    private static BlockPos pos(StructureSite site, double u, int y, double v) {
        return BlockPos.containing(worldX(site, u, v), y, worldZ(site, u, v));
    }
}
