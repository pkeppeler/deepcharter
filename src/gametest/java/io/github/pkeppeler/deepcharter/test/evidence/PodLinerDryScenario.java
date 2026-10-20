package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.layer.LavaHazard;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.ore.SlagBrick;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodLiner;
import io.github.pkeppeler.deepcharter.pod.PodLining;
import io.github.pkeppeler.deepcharter.pod.PodLiningTuning;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.ScannerPods;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Evidence scenario "pod-liner-dry" (#432): a pilot in a pod with a tier 2 liner holds the drill down a shaft. An open gallery runs down
 * the south side of the bore, with a lens of lava on its floor. The liner lines only the cells that lava can reach. The first ring has
 * no lava within the liner's view, so it lays no brick and the rack on the HUD does not move. The second ring sees the lava, and lines the gallery wall.
 * The camera is the third person view from behind and above the pod, which looks down into the gallery.
 */
public class PodLinerDryScenario extends EvidenceScenario {
    private static final int X = 4700;
    private static final int Z = 4600;
    // fixed-pos: a layer 1 room the scenario carves at this height, above the signal-decay onset of layer 1
    // (SignalDecayTuning band 1), so the gauges stay live
    private static final int FLOOR_Y = 100;
    private static final int RADIUS = 6;
    private static final int DEPTH = 16;
    /** The gallery south of the bore: air from the room down to {@link #LENS_Y} + 1, 7 wide, 5 deep. The lava lens is its floor. */
    private static final int LENS_Y = FLOOR_Y - 9;
    /** The tier 2 liner rings when the pod's feet are at 97 and 94: the ring at 97 sees down to 94, and the ring at 94 sees the lens at 91. */
    private static final int FIRST_RING_Y = FLOOR_Y - 3;

    private static final int SECOND_RING_Y = FLOOR_Y - 6;
    private static final int BOTTOM_Y = FLOOR_Y - 10;
    private static final int TICKS_PER_FRAME = 6;
    private static final int MAX_TICKS = 6000;
    /** A full rack. */
    private static final int BRICKS = PodLiningTuning.DEFAULT.brickCapacity();

    private static final float LOOK_DOWN = 75f;
    /** The pod and the pilot face north, so that the third person camera stands south of them, in the gallery. */
    private static final float FACING_NORTH = 180f;

    private static final int LINER_TIER = 2;

    @Override
    protected String name() {
        return "pod-liner-dry";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40);
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = ClientLight.serverLevel(server, LayerChain.dimension(1));
                build(one);
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SURVIVAL);
                player.teleportTo(one, X, FLOOR_Y, Z, Set.of(), FACING_NORTH, LOOK_DOWN, true);
                PodEntity pod = TestEntities.create(PodRegistry.POD, one);
                pod.setPos(X, FLOOR_Y, Z);
                pod.setYRot(FACING_NORTH);
                one.addFreshEntity(pod);
                ScannerPods.fit(server, player, pod, ComponentTrack.SPOIL_HOPPER, 1);
                ScannerPods.fit(server, player, pod, ComponentTrack.LINER, LINER_TIER);
                ScannerPods.fit(server, player, pod, ComponentTrack.FUEL_TANK, 2);
                pod.setFuel(100f);
                PodLining.modify(pod, state -> new PodLining.State(0, BRICKS, 0, false, false));
                if (!player.startRiding(pod)) {
                    throw new AssertionError("the player could not mount the pod");
                }
            });
            ClientWait.until(
                    context,
                    "the pilot seated in the pod in layer 1",
                    client -> client.player != null
                            && client.player.getVehicle() instanceof PodEntity
                            && ClientLight.clientLevel(client).dimension().equals(LayerChain.dimension(1)));
            context.runOnClient(client -> {
                client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                ClientSession.player(client).setXRot(LOOK_DOWN);
            });
            context.waitTicks(60);
            for (int i = 0; i < 4; i++) {
                frame(context);
                context.waitTicks(TICKS_PER_FRAME);
            }

            // The drill is held for the whole dive. The liner does the lining.
            context.getInput().holdKey(options -> options.keySprint);
            boolean dryShot = false;
            boolean linedShot = false;
            int ticks = 0;
            while (feetY(singleplayer) > BOTTOM_Y + 0.01 && ticks < MAX_TICKS) {
                assertNotTouching(singleplayer);
                context.waitTicks(TICKS_PER_FRAME);
                ticks += TICKS_PER_FRAME;
                frame(context);
                if (!dryShot && ringedAtOrBelow(singleplayer, FIRST_RING_Y)) {
                    dryShot = true;
                    expectLining(singleplayer, "after the first ring, with no lava within the liner's view", true);
                    screenshot(context, "pod-liner-dry-first-ring");
                }
                if (!linedShot && ringedAtOrBelow(singleplayer, SECOND_RING_Y)) {
                    linedShot = true;
                    expectLining(singleplayer, "after the second ring, with the lava within the liner's view", false);
                    screenshot(context, "pod-liner-dry-second-ring");
                }
            }
            context.getInput().releaseKey(options -> options.keySprint);
            if (feetY(singleplayer) > BOTTOM_Y + 0.01) {
                throw new AssertionError("The pod did not drill down to " + BOTTOM_Y + " within " + MAX_TICKS
                        + " ticks, it is at " + feetY(singleplayer));
            }
            if (!dryShot || !linedShot) {
                throw new AssertionError(
                        "The pod never reached both rings: first " + dryShot + ", second " + linedShot);
            }
            for (int i = 0; i < 8; i++) {
                context.waitTicks(TICKS_PER_FRAME);
                frame(context);
            }
        }
    }

    /** Before the lava is within the liner's view the gallery wall is bare and the rack is full; after it, the wall from the lens up holds slag brick and the rack has paid for them. */
    private static void expectLining(TestSingleplayerContext singleplayer, String when, boolean dry) {
        int[] seen = singleplayer.getServer().computeOnServer(server -> {
            PodEntity pod =
                    TestEntities.vehicle(server.getPlayerList().getPlayers().getFirst(), PodEntity.class);
            ServerLevel one = ClientLight.serverLevel(server, LayerChain.dimension(1));
            int bricks = 0;
            for (int y = LENS_Y; y < FLOOR_Y; y++) {
                for (int x = X - 1; x <= X; x++) {
                    if (one.getBlockState(new BlockPos(x, y, Z + 1)).is(SlagBrick.BLOCK)) {
                        bricks++;
                    }
                }
            }
            return new int[] {bricks, PodLining.of(pod).bricks()};
        });
        boolean ok = dry ? seen[0] == 0 && seen[1] == BRICKS : seen[0] > 0 && seen[1] < BRICKS;
        if (!ok) {
            throw new AssertionError(
                    "The gallery wall should be " + (dry ? "bare and the rack full" : "lined and the rack spent") + " "
                            + when + ", but it holds " + seen[0] + " bricks and the rack " + seen[1] + " of " + BRICKS);
        }
    }

    private static double feetY(TestSingleplayerContext singleplayer) {
        return singleplayer
                .getServer()
                .computeOnServer(server -> TestEntities.vehicle(
                                server.getPlayerList().getPlayers().getFirst())
                        .getY());
    }

    /** The liner has rung at {@code ringY} or lower: the anchor it counts from has moved down to there. */
    private static boolean ringedAtOrBelow(TestSingleplayerContext singleplayer, int ringY) {
        return singleplayer.getServer().computeOnServer(server -> {
            PodEntity pod =
                    TestEntities.vehicle(server.getPlayerList().getPlayers().getFirst(), PodEntity.class);
            return Versioned.readable(pod, PodLiner.STATE)
                            .orElseThrow()
                            .anchor()
                            .map(PodLiner.Anchor::feetY)
                            .orElse(Integer.MAX_VALUE)
                    <= ringY;
        });
    }

    private static void assertNotTouching(TestSingleplayerContext singleplayer) {
        boolean touching = singleplayer
                .getServer()
                .computeOnServer(server -> LavaHazard.touchesLava(
                        TestEntities.vehicle(server.getPlayerList().getPlayers().getFirst(), PodEntity.class)));
        if (touching) {
            throw new AssertionError("Lava touched the pod, so the liner did not hold it back");
        }
    }

    /**
     * A stone bed under an open room, and an open gallery down the south side of the bore (z + 1 to z + 5) with a lens of lava on its floor.
     * The pod stands at x - 1 and x, z - 1 and z, so its ring on the south side is the gallery wall at z + 1. Lamps light the gallery.
     */
    private static void build(ServerLevel level) {
        RoomCarver.carve(
                level, X - RADIUS, X + RADIUS, FLOOR_Y - DEPTH, FLOOR_Y - 1, Z - RADIUS, Z + RADIUS, Blocks.STONE);
        RoomCarver.carve(
                level,
                new BlockPos(X - RADIUS, FLOOR_Y, Z - RADIUS),
                new BlockPos(X + RADIUS, FLOOR_Y + 9, Z + RADIUS),
                Blocks.AIR.defaultBlockState(),
                Block.UPDATE_ALL);
        RoomCarver.carve(
                level,
                new BlockPos(X - 3, LENS_Y + 1, Z + 1),
                new BlockPos(X + 3, FLOOR_Y - 1, Z + 5),
                Blocks.AIR.defaultBlockState(),
                Block.UPDATE_CLIENTS);
        RoomCarver.carve(
                level,
                new BlockPos(X - 3, LENS_Y, Z + 1),
                new BlockPos(X + 3, LENS_Y, Z + 5),
                Blocks.LAVA.defaultBlockState(),
                Block.UPDATE_CLIENTS);
        for (BlockPos lamp : new BlockPos[] {
            new BlockPos(X + RADIUS, FLOOR_Y + 3, Z),
            new BlockPos(X - RADIUS, FLOOR_Y + 3, Z),
            new BlockPos(X, FLOOR_Y + 3, Z + RADIUS),
            new BlockPos(X, FLOOR_Y + 3, Z - RADIUS),
            new BlockPos(X + 4, FLOOR_Y - 2, Z + 4),
            new BlockPos(X - 4, FLOOR_Y - 2, Z + 4),
            new BlockPos(X, FLOOR_Y - 3, Z + 6),
            new BlockPos(X + 4, FLOOR_Y - 6, Z + 4),
            new BlockPos(X - 4, FLOOR_Y - 6, Z + 4),
            new BlockPos(X + 2, FLOOR_Y - 7, Z + 6)
        }) {
            level.setBlock(lamp, Blocks.GLOWSTONE.defaultBlockState(), 3);
        }
    }
}
