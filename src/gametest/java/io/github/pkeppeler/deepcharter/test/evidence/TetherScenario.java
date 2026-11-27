package io.github.pkeppeler.deepcharter.test.evidence;

import static io.github.pkeppeler.deepcharter.test.support.Inputs.FORWARD;

import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodTether;
import io.github.pkeppeler.deepcharter.pod.PodTowing;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.repair.RepairRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.UseEntity;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Evidence scenario "m3-tether" (#986): a person on foot clips a tether to their pod's winch on a shelf over a deep pit, steps off the
 * lip and is caught by the line, hauls themselves up the face and over it with the winch, hooks a wreck on the shelf to the pod with
 * the same tether, and then drives off with the wreck trailing.
 */
public class TetherScenario extends EvidenceScenario {
    private static final int X = 5600;
    private static final int Z = 5600;
    private static final int FLOOR_Y = TestLayers.worldY(1, LayerTuning.DEFAULT.crustThickness() + 1);
    /** Blocks the shelf stands above the pit's floor: more than the line's catch, so a fall hangs well clear of it. */
    private static final int SHELF = 22;

    private static final int SHELF_TOP = FLOOR_Y + SHELF;
    private static final int PIT_HEIGHT = SHELF + 12;
    /** South, so the pod, to the west, is on the right of the picture and the whole line is in it. */
    private static final float FACING_SOUTH = 0f;

    private static final float LOOK_UP = -20f;
    private static final int TICKS_PER_FRAME = 3;
    private static final int FALL_TICKS = 75;
    private static final int MAX_HAUL_TICKS = 400;
    private static final int DRIVE_TICKS = 70;
    private static final double MIN_TOWED_BLOCKS = 6;

    @Override
    protected String name() {
        return "m3-tether";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40);
            UUID[] pods = new UUID[2];
            singleplayer.getServer().runOnServer(TetherScenario::build);
            ClientWait.lightSettled(context, singleplayer.getServer(), 1, X + 3, Z, 38);
            singleplayer.getServer().runOnServer(server -> place(server, pods));
            ClientWait.until(
                    context,
                    "the player in layer 1",
                    client -> client.player != null && TestLayers.clientIsIn(client, 1));
            context.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_BACK));
            context.waitTicks(40);

            // Clip on: the person uses the tether on their pod.
            singleplayer.getServer().runOnServer(server -> {
                if (!UseEntity.on(player(server), level(server), InteractionHand.MAIN_HAND, pod(server, pods[0]))
                        .consumesAction()) {
                    throw new AssertionError("the tether did not clip to the pod");
                }
            });
            for (int i = 0; i < 4; i++) {
                context.waitTicks(TICKS_PER_FRAME);
                frame(context);
            }
            screenshot(context, "tether-clipped");

            // Over the lip: the fall is caught by the line, and nobody is hurt.
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = player(server);
                player.teleportTo(level(server), X + 4.5, SHELF_TOP, Z + 0.5, Set.of(), FACING_SOUTH, LOOK_UP, true);
            });
            for (int ticks = 0; ticks < FALL_TICKS; ticks += TICKS_PER_FRAME) {
                context.waitTicks(TICKS_PER_FRAME);
                frame(context);
            }
            screenshot(context, "tether-caught-fall");
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = player(server);
                if (!PodTether.isClipped(player) || player.getHealth() < player.getMaxHealth()) {
                    throw new AssertionError("the line should have caught the fall and left the person unhurt, health "
                            + player.getHealth() + ", clipped " + PodTether.isClipped(player));
                }
                if (player.getY() > SHELF_TOP - 6 || player.getY() < FLOOR_Y + 2) {
                    throw new AssertionError("the person should hang well below the lip and above the pit's floor, at "
                            + player.position());
                }
            });

            // The winch: the person uses the tether in the air, and is hauled up the face and over the lip.
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = player(server);
                player.getMainHandItem().use(level(server), player, InteractionHand.MAIN_HAND);
                if (!PodTether.isWinching(player)) {
                    throw new AssertionError("the winch did not start");
                }
            });
            int ticks = 0;
            while (singleplayer.getServer().computeOnServer(server -> PodTether.isWinching(player(server)))
                    && ticks < MAX_HAUL_TICKS) {
                context.waitTicks(2);
                ticks += 2;
                frame(context);
            }
            if (ticks >= MAX_HAUL_TICKS) {
                throw new AssertionError("the winch did not haul the person home within " + MAX_HAUL_TICKS + " ticks");
            }
            context.waitTicks(10);
            screenshot(context, "tether-hauled-home");

            // A tow: the same tether hooks the wreck to the pod, and the pod drives off with it.
            singleplayer
                    .getServer()
                    .runOnServer(server -> player(server)
                            .teleportTo(level(server), X - 7.5, SHELF_TOP, Z - 5.5, Set.of(), FACING_SOUTH, 5, true));
            context.waitTicks(10);
            singleplayer.getServer().runOnServer(server -> {
                if (!UseEntity.on(player(server), level(server), InteractionHand.MAIN_HAND, pod(server, pods[1]))
                                .consumesAction()
                        || !PodTowing.isTowed(pod(server, pods[1]))) {
                    throw new AssertionError("the tether did not hook the wreck");
                }
            });
            for (int i = 0; i < 4; i++) {
                context.waitTicks(TICKS_PER_FRAME);
                frame(context);
            }
            screenshot(context, "tether-tow-hooked");
            Vec3 start = singleplayer
                    .getServer()
                    .computeOnServer(server -> pod(server, pods[1]).position());
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = player(server);
                PodEntity pod = pod(server, pods[0]);
                pod.setFuel(100f);
                // A pod heads where its pilot looks: south, down the length of the shelf.
                player.teleportTo(level(server), player.getX(), player.getY(), player.getZ(), Set.of(), 0f, 10f, true);
                if (!player.startRiding(pod)) {
                    throw new AssertionError("the person could not climb into the pod");
                }
            });
            context.waitTicks(10);
            for (int driven = 0; driven < DRIVE_TICKS; driven++) {
                // The client sends its own input every tick, so the drive is set again on every one.
                singleplayer.getServer().runOnServer(server -> player(server).setLastClientInput(FORWARD));
                context.waitTicks(1);
                if (driven % TICKS_PER_FRAME == 0) {
                    frame(context);
                }
            }
            singleplayer.getServer().runOnServer(server -> player(server).setLastClientInput(Input.EMPTY));
            screenshot(context, "tether-towing");
            double towed = singleplayer
                    .getServer()
                    .computeOnServer(server -> pod(server, pods[1]).position())
                    .subtract(start)
                    .horizontalDistance();
            if (towed < MIN_TOWED_BLOCKS) {
                throw new AssertionError("the wreck should trail the pod, it moved " + towed + " blocks; "
                        + singleplayer
                                .getServer()
                                .computeOnServer(server -> "pod at "
                                        + pod(server, pods[0]).position()
                                        + ", fuel " + pod(server, pods[0]).fuel()
                                        + ", wreck at " + pod(server, pods[1]).position()
                                        + ", towed by " + PodTowing.towerId(pod(server, pods[1]))));
            }
        }
    }

    private static ServerPlayer player(net.minecraft.server.MinecraftServer server) {
        return server.getPlayerList().getPlayers().getFirst();
    }

    private static ServerLevel level(net.minecraft.server.MinecraftServer server) {
        return TestLayers.level(server, 1);
    }

    private static PodEntity pod(net.minecraft.server.MinecraftServer server, UUID id) {
        return TestEntities.require(level(server), id, PodEntity.class);
    }

    /** A hall in layer 1, a shelf along its west side, a pit east of it, and the person on the shelf with a tether. */
    private static void build(net.minecraft.server.MinecraftServer server) {
        ServerLevel one = level(server);
        RoomCarver.carve(
                one,
                new BlockPos(X - 16, FLOOR_Y - 3, Z - 38),
                new BlockPos(X + 22, FLOOR_Y + PIT_HEIGHT + 3, Z + 38),
                Blocks.STONE.defaultBlockState());
        RoomCarver.carve(
                one,
                new BlockPos(X - 15, FLOOR_Y, Z - 37),
                new BlockPos(X + 21, FLOOR_Y + PIT_HEIGHT, Z + 37),
                Blocks.AIR.defaultBlockState());
        // The shelf: solid rock under the west side, up to its top at SHELF_TOP.
        RoomCarver.carve(
                one,
                new BlockPos(X - 15, FLOOR_Y, Z - 37),
                new BlockPos(X + 2, SHELF_TOP - 1, Z + 37),
                Blocks.STONE.defaultBlockState());
        // Lamps on the far wall and along the shelf, so the pit and the line read on screen.
        for (int y = FLOOR_Y + 4; y < FLOOR_Y + PIT_HEIGHT; y += 6) {
            for (int z = Z - 34; z <= Z + 34; z += 12) {
                one.setBlock(new BlockPos(X + 21, y, z), Blocks.GLOWSTONE.defaultBlockState(), 3);
            }
        }
        // Lamps set into the face of the shelf, where a person hangs, and on the shelf by the pod.
        for (int y = FLOOR_Y + 3; y < SHELF_TOP; y += 4) {
            for (int z = Z - 3; z <= Z + 3; z += 3) {
                one.setBlock(new BlockPos(X + 2, y, z), Blocks.GLOWSTONE.defaultBlockState(), 3);
            }
        }
        one.setBlock(new BlockPos(X - 7, SHELF_TOP, Z - 3), Blocks.GLOWSTONE.defaultBlockState(), 3);
        one.setBlock(new BlockPos(X - 1, SHELF_TOP, Z + 4), Blocks.GLOWSTONE.defaultBlockState(), 3);
        for (int z = Z - 36; z <= Z + 36; z += 8) {
            one.setBlock(new BlockPos(X - 15, SHELF_TOP + 3, z), Blocks.GLOWSTONE.defaultBlockState(), 3);
        }
        ServerPlayer player = player(server);
        player.setGameMode(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(RepairRegistry.item(Consumable.TETHER)));
        player.teleportTo(one, X - 0.5, SHELF_TOP, Z + 0.5, Set.of(), FACING_SOUTH, 0, true);
    }

    /** A pod and a wreck on the shelf. */
    private static void place(net.minecraft.server.MinecraftServer server, UUID[] pods) {
        ServerLevel one = level(server);
        PodEntity pod = TestEntities.create(PodRegistry.POD, one);
        pod.setPos(X - 4.5, SHELF_TOP, Z + 0.5);
        one.addFreshEntity(pod);
        PodEntity wreck = TestEntities.create(PodRegistry.POD, one);
        wreck.setPos(X - 9.5, SHELF_TOP, Z + 4.5);
        one.addFreshEntity(wreck);
        wreck.setHull(0f);
        pods[0] = pod.getUUID();
        pods[1] = wreck.getUUID();
    }
}
