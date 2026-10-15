package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyKit;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.BlockSurvey;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.EvidenceWorld;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.phys.Vec3;

/**
 * Evidence scenario "glow-beams" (#248): the colony by night, where the floodlights throw their beams and the lamps, gauges and
 * screens carry a soft glow of the three light colours; then a sealed dark room with two pods side by side, one with a lights part and
 * one without, so the lit pod shows its headlamp beam (cut short by the far wall) and the dark one shows none.
 */
public class GlowBeamsScenario extends EvidenceScenario {
    private static final int TICKS_PER_FRAME = 3;
    private static final int SETTLE_TICKS = 40;
    private static final int ORBIT_FRAMES = 36;
    private static final int ROOM_HALF_WIDTH = 12;
    private static final int ROOM_LENGTH = 22;
    private static final int ROOM_HEIGHT = 6;

    @Override
    protected String name() {
        return "glow-beams";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            awaitWorld(context);
            EvidenceWorld.pin(context, singleplayer);
            ClientChecks.withHudHidden(context, () -> {
                night(context, singleplayer);
                ColonySite.Placed colony = singleplayer.getServer().computeOnServer(server -> {
                    ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                    player.setGameMode(GameType.SPECTATOR);
                    player.getInventory().clearContent();
                    return Colony.placed(server).orElseThrow();
                });
                floodlights(context, singleplayer);
                glowAccents(context, singleplayer);
                darkRoom(context, singleplayer, colony);
            });
        }
    }

    private void night(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        singleplayer
                .getServer()
                .runOnServer(server -> server.getCommands()
                        .performPrefixedCommand(server.createCommandSourceStack(), "time set 18000"));
        EvidenceWorld.skyPhase(context, singleplayer, EvidenceWorld.SKY_DARKEST);
    }

    private void floodlights(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        List<BlockPos> lights = singleplayer
                .getServer()
                .computeOnServer(server -> BlockSurvey.find(server.overworld(), 4, ColonyKit.FLOODLIGHT));
        if (lights.isEmpty()) {
            throw new AssertionError("the colony has no floodlight within four chunks of the spawn");
        }
        BlockPos at = lights.getFirst();
        Direction facing = singleplayer
                .getServer()
                .computeOnServer(
                        server -> server.overworld().getBlockState(at).getValue(HorizontalDirectionalBlock.FACING));
        Vec3 lamp = Vec3.atCenterOf(at);
        Vec3 side = new Vec3(facing.getStepZ(), 0, -facing.getStepX());
        Vec3 front = new Vec3(facing.getStepX(), 0, facing.getStepZ());
        Vec3 eye = lamp.add(front.scale(-7)).add(side.scale(9)).add(0, 3, 0);
        view(singleplayer, eye, lamp.add(front.scale(7)).add(0, -1, 0));
        context.waitTicks(SETTLE_TICKS);
        screenshot(context, "floodlight-beam-night");
        for (int i = 0; i < ORBIT_FRAMES / 2; i++) {
            double f = (double) i / (ORBIT_FRAMES / 2);
            Vec3 eyeNow = lamp.add(front.scale(-7 + 14 * f)).add(side.scale(9)).add(0, 3, 0);
            view(singleplayer, eyeNow, lamp.add(front.scale(7 * (1 - f))).add(0, -1, 0));
            context.waitTicks(TICKS_PER_FRAME);
            frame(context);
        }
    }

    private void glowAccents(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        glowStill(context, singleplayer, ColonyKit.WALL_LAMP, "wall-lamp-glow-night", 3);
        glowStill(context, singleplayer, ColonyKit.GAUGE_PANEL, "gauge-panel-glow-night", 3);
    }

    private void glowStill(
            ClientGameTestContext context,
            TestSingleplayerContext singleplayer,
            Block block,
            String still,
            int distance) {
        List<BlockPos> found =
                singleplayer.getServer().computeOnServer(server -> BlockSurvey.find(server.overworld(), 4, block));
        if (found.isEmpty()) {
            throw new AssertionError("the colony has no " + still + " subject within four chunks of the spawn");
        }
        BlockPos at = found.getFirst();
        Direction facing = singleplayer
                .getServer()
                .computeOnServer(
                        server -> server.overworld().getBlockState(at).getValue(HorizontalDirectionalBlock.FACING));
        Vec3 target = Vec3.atCenterOf(at);
        view(singleplayer, target.add(facing.getStepX() * distance, 0.8, facing.getStepZ() * distance), target);
        context.waitTicks(SETTLE_TICKS);
        screenshot(context, still);
    }

    private void darkRoom(
            ClientGameTestContext context, TestSingleplayerContext singleplayer, ColonySite.Placed colony) {
        BlockPos floor = colony.center().offset(0, 60, 150);
        PodEntity[] pods = new PodEntity[2];
        singleplayer.getServer().runOnServer(server -> {
            ServerLevel level = server.overworld();
            level.getChunkAt(floor);
            buildSealedRoom(level, floor);
            Vec3 spot = Vec3.atBottomCenterOf(floor.above());
            Charters.found(
                    server, server.getPlayerList().getPlayers().getFirst().getUUID(), "Glow Charter");
            CharterId charter = Charters.charterOfOrThrow(
                            server,
                            server.getPlayerList().getPlayers().getFirst().getUUID())
                    .orElseThrow()
                    .id();
            for (int i = 0; i < 2; i++) {
                pods[i] = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
                pods[i].setPos(spot.x + (i == 0 ? -3.5 : 3.5), spot.y, spot.z + 2);
                level.addFreshEntity(pods[i]);
                PodComponents.register(pods[i], charter);
            }
            PodComponents.install(pods[0], ComponentItems.mint(server, ComponentTrack.LIGHTS, 3, charter));
        });
        Vec3 pod = Vec3.atBottomCenterOf(floor.above()).add(0, 0, 2);
        view(singleplayer, pod.add(-10, 2.6, -7), pod.add(0, 1.2, 3));
        context.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));
        context.waitTicks(SETTLE_TICKS);
        screenshot(context, "pod-beams-lit-and-unlit");
        for (int i = 0; i < ORBIT_FRAMES; i++) {
            double angle = Math.toRadians(-100 + 200.0 * i / ORBIT_FRAMES);
            Vec3 eye = pod.add(Math.sin(angle) * 10, 2.6, -Math.cos(angle) * 10);
            view(singleplayer, eye, pod.add(0, 1.2, 3));
            context.waitTicks(TICKS_PER_FRAME);
            frame(context);
        }
        view(singleplayer, pod.add(-8, 4.6, -4), pod.add(0, 0.5, 6));
        context.waitTicks(SETTLE_TICKS);
        screenshot(context, "pod-beams-from-above");
        singleplayer.getServer().runOnServer(server -> pods[0].setStranded(true));
        context.waitTicks(SETTLE_TICKS);
        screenshot(context, "pod-beams-stranded-dark");
    }

    private static void view(TestSingleplayerContext singleplayer, Vec3 eye, Vec3 target) {
        Vec3 d = target.subtract(eye);
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z)));
        singleplayer
                .getServer()
                .runOnServer(server -> server.getPlayerList()
                        .getPlayers()
                        .getFirst()
                        .teleportTo(server.overworld(), eye.x, eye.y - 1.62, eye.z, Set.of(), yaw, pitch, true));
    }

    /** A stone box whose floor is at {@code floor}, the sky cannot reach inside it, with its long axis along z. */
    private static void buildSealedRoom(ServerLevel level, BlockPos floor) {
        for (int dx = -ROOM_HALF_WIDTH - 1; dx <= ROOM_HALF_WIDTH + 1; dx++) {
            for (int dz = -ROOM_LENGTH - 1; dz <= 1 + 14; dz++) {
                for (int dy = 0; dy <= ROOM_HEIGHT + 1; dy++) {
                    boolean shell = dy == 0
                            || dy == ROOM_HEIGHT + 1
                            || Math.abs(dx) == ROOM_HALF_WIDTH + 1
                            || dz == -ROOM_LENGTH - 1
                            || dz == 14 + 1;
                    level.setBlock(
                            floor.offset(dx, dy, dz), (shell ? Blocks.STONE : Blocks.AIR).defaultBlockState(), 3);
                }
            }
        }
    }
}
