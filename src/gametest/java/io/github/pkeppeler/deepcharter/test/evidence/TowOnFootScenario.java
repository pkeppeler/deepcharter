package io.github.pkeppeler.deepcharter.test.evidence;

import static io.github.pkeppeler.deepcharter.test.support.Inputs.FORWARD;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodTowing;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Evidence scenario "tow-on-foot" (#1605): the real player, out of every pod, holds a tow cable and right-clicks one pod, then the
 * other. The first click picks the pod: a chain is drawn from it to the player, and the bar says what to click next. The second
 * hooks the pods: the bar says so, the cable hangs between them, and the crewmate in the first pod drives off with the second in tow.
 */
public class TowOnFootScenario extends EvidenceScenario {
    private static final int X = 800;
    private static final int Z = 800;
    private static final int FLOOR_Y = SKY_SLAB_Y;
    private static final double APART = 6;
    /** Chat lines (the join messages) stay on screen for 10 seconds. */
    private static final int CHAT_FADE_TICKS = 220;

    private static final int TICKS_PER_FRAME = 4;
    private static final int HOLD_FRAMES = 8;
    private static final int DRIVE_FRAMES = 10;

    private static final Vec3 FIRST_AT = new Vec3(X + 0.5, FLOOR_Y, Z + 0.5);
    private static final Vec3 SECOND_AT = FIRST_AT.add(APART, 0, 0);
    private static final Vec3 STAND_AT = FIRST_AT.add(APART / 2, 0, 2);

    private record Rig(UUID first, UUID second) {}

    @Override
    protected String name() {
        return "tow-on-foot";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TwoPlayerServer two = TwoPlayerServer.start(context)) {
            Rig rig = two.server().computeOnServer(server -> setUp(server.overworld(), two));
            context.waitTicks(CHAT_FADE_TICKS);
            context.runOnClient(client -> client.gui.toastManager().clear());

            aimAt(context, two, rig.first());
            holdFrames(context, 2);
            context.getInput().pressKey(options -> options.keyUse);
            ClientWait.until(
                    context,
                    "the first pod picked",
                    () -> two.server()
                            .computeOnServer(server -> PodTowing.pickedPodId(watcher(server.overworld(), two))
                                    .isPresent()),
                    () -> "no pick on the server");
            holdFrames(context, HOLD_FRAMES);
            screenshot(context, "tow-first-pod-picked");

            aimAt(context, two, rig.second());
            context.getInput().pressKey(options -> options.keyUse);
            ClientWait.until(
                    context,
                    "the second pod hooked",
                    () -> two.server()
                            .computeOnServer(server -> Optional.of(rig.first())
                                    .equals(PodTowing.towerId(
                                            TestEntities.require(server.overworld(), rig.second(), PodEntity.class)))),
                    () -> "no cable on the second pod");
            holdFrames(context, HOLD_FRAMES);
            screenshot(context, "tow-pods-hooked");

            two.server().runOnServer(server -> two.mock().setInput(FORWARD));
            holdFrames(context, DRIVE_FRAMES);
            two.server().runOnServer(server -> two.mock().releaseInput());
            holdFrames(context, 2);
            screenshot(context, "tow-second-pod-following");
        }
    }

    private void holdFrames(ClientGameTestContext context, int frames) {
        for (int i = 0; i < frames; i++) {
            context.waitTicks(TICKS_PER_FRAME);
            frame(context);
        }
    }

    /** Turns the player to look at the middle of the pod and waits for the crosshair to rest on it. */
    private static void aimAt(ClientGameTestContext context, TwoPlayerServer two, UUID pod) {
        two.server().runOnServer(server -> {
            ServerLevel level = server.overworld();
            ServerPlayer real = watcher(level, two);
            Vec3 toward = TestEntities.require(level, pod)
                    .getBoundingBox()
                    .getCenter()
                    .subtract(real.getEyePosition());
            float yaw = (float) Math.toDegrees(Math.atan2(-toward.x, toward.z));
            float pitch = (float) -Math.toDegrees(Math.atan2(toward.y, Math.hypot(toward.x, toward.z)));
            real.teleportTo(level, STAND_AT.x, STAND_AT.y, STAND_AT.z, Set.of(), yaw, pitch, true);
        });
        ClientWait.until(
                context,
                "the crosshair on the pod",
                client -> onPod(client, pod),
                client -> "the crosshair is on " + client.hitResult);
    }

    private static boolean onPod(Minecraft client, UUID pod) {
        return client.hitResult instanceof EntityHitResult hit
                && hit.getEntity().getUUID().equals(pod);
    }

    private static ServerPlayer watcher(ServerLevel level, TwoPlayerServer two) {
        return level.getServer().getPlayerList().getPlayers().stream()
                .filter(player -> player != two.mock().player())
                .findFirst()
                .orElseThrow();
    }

    /** A stone slab high above the terrain, the real player on it with a cable, the mock crewmate in the first pod. */
    private static Rig setUp(ServerLevel level, TwoPlayerServer two) {
        fill(level, FLOOR_Y - 8, FLOOR_Y - 1, Blocks.STONE);
        fill(level, FLOOR_Y, FLOOR_Y + 10, Blocks.AIR);
        ServerPlayer real = watcher(level, two);
        real.teleportTo(level, STAND_AT.x, STAND_AT.y, STAND_AT.z, Set.of(), 0, 0, true);
        real.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(PodRegistry.TOW_CABLE));
        PodEntity first = spawn(level, FIRST_AT, "POD A");
        PodEntity second = spawn(level, SECOND_AT, "POD B");
        first.setFuel(100f);
        two.mock().teleportTo(level, FIRST_AT, 0, 0);
        if (!two.mock().player().startRiding(first)) {
            throw new AssertionError("the crewmate could not mount the first pod");
        }
        return new Rig(first.getUUID(), second.getUUID());
    }

    private static PodEntity spawn(ServerLevel level, Vec3 at, String name) {
        PodEntity pod = TestEntities.create(PodRegistry.POD, level);
        pod.setPos(at);
        pod.setCustomName(Component.literal(name));
        pod.setCustomNameVisible(true);
        level.addFreshEntity(pod);
        return pod;
    }

    private static void fill(ServerLevel level, int yFrom, int yTo, Block block) {
        for (int x = X - 4; x <= X + 12; x++) {
            for (int y = yFrom; y <= yTo; y++) {
                for (int z = Z - 6; z <= Z + 14; z++) {
                    level.setBlock(new BlockPos(x, y, z), block.defaultBlockState(), 3);
                }
            }
        }
    }
}
