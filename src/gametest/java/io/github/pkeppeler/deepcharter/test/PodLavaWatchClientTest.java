package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.client.pod.PodStatusHud;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodLavaWatch;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.PodReadout;
import java.util.Optional;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.Blocks;

/**
 * Client GameTest for the lava watch HUD (#454): a pod with a pilot shows no lava line with no lava about, and a lava line (an ALERT, which lights the cockpit's warning
 * lamp) once lava flows toward it. The number on the line is the number the server's watch reads, and lava from above gets its own line.
 */
public class PodLavaWatchClientTest implements FabricClientGameTest {
    private static final int LAVA_AT = 3;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                ServerLevel level = player.level();
                BlockPos feet = player.blockPosition();
                // A stone bed under the pod and the lava, and clear air round and over them.
                for (int x = -3; x <= LAVA_AT + 2; x++) {
                    for (int z = -3; z <= 3; z++) {
                        level.setBlock(feet.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3);
                        for (int y = 0; y <= 3; y++) {
                            level.setBlock(feet.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                        }
                    }
                }
                PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
                pod.setPos(Mth.floor(player.getX()), feet.getY(), Mth.floor(player.getZ()));
                level.addFreshEntity(pod);
                if (!player.startRiding(pod)) {
                    throw new AssertionError("the player could not mount the new pod");
                }
            });
            ClientWait.until(
                    context,
                    "the pod status HUD",
                    client -> client.player != null
                            && client.player.getVehicle() instanceof PodEntity pod
                            && !PodReadout.texts(client, pod).isEmpty());
            context.waitTicks(
                    30); // tick-wait: long enough for a few looks of the watch (every 10 ticks) to have found nothing
            if (context.computeOnClient(client -> PodStatusHud.lavaNearLine((PodEntity) client.player.getVehicle())
                    .isPresent())) {
                throw new AssertionError("A pod with no lava about shows no lava line");
            }

            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                BlockPos feet = player.getVehicle().blockPosition();
                // The source flows over the stone bed at once; the flowing lava stays while the source does.
                player.level().setBlock(feet.offset(LAVA_AT, 0, 0), Blocks.LAVA.defaultBlockState(), 3);
            });
            // The client's number must be the server's: wait for the two to agree, and say where the pod and the lava
            // were if they never do.
            ClientWait.until(
                    context,
                    "the client's lava line to show the server's reading",
                    () -> serverReading(singleplayer).isPresent()
                            && serverReading(singleplayer).equals(clientReading(context)),
                    () -> "the server reads " + serverReading(singleplayer) + ", the client " + clientReading(context)
                            + "; " + surroundings(singleplayer));
            PodStatusHud.Line line = context.computeOnClient(
                    client -> PodStatusHud.readout(client.player, (PodEntity) client.player.getVehicle()).stream()
                            .filter(shown -> key(shown.text()).equals("hud.deepcharter.pod.lava_near"))
                            .findFirst()
                            .orElseThrow(() -> new AssertionError("The readout should hold the lava line")));
            if (line.kind() != PodStatusHud.Kind.ALERT) {
                throw new AssertionError("The lava line is a warning, it is " + line.kind());
            }

            // Lava over the pod gets its own line, which names the cap. It is read from a pod of the client's own, so
            // that the server's next look cannot overwrite it.
            String above = context.computeOnClient(client -> {
                PodEntity pod = PodRegistry.POD.create(client.level, EntitySpawnReason.COMMAND);
                pod.setAttached(PodLavaWatch.STATE, Versioned.of(new PodLavaWatch.State(5, true, 5, 0)));
                return PodStatusHud.lavaNearLine(pod)
                        .map(PodLavaWatchClientTest::key)
                        .orElse("none");
            });
            if (!above.equals("hud.deepcharter.pod.lava_above")) {
                throw new AssertionError("Lava from above has its own line, it shows " + above);
            }
        }
    }

    private static String key(Component line) {
        return line.getContents() instanceof TranslatableContents contents ? contents.getKey() : line.getString();
    }

    private static Optional<Integer> serverReading(TestSingleplayerContext singleplayer) {
        return singleplayer
                .getServer()
                .computeOnServer(server -> PodLavaWatch.warning((PodEntity)
                                server.getPlayerList().getPlayers().getFirst().getVehicle())
                        .map(PodLavaWatch.State::blocksOff));
    }

    private static Optional<Integer> clientReading(ClientGameTestContext context) {
        return context.computeOnClient(client -> client.player.getVehicle() instanceof PodEntity pod
                ? PodLavaWatch.warning(pod).map(PodLavaWatch.State::blocksOff)
                : Optional.empty());
    }

    /** The pod's position and the lava cells within 5 blocks of it, as the server has them, for a failure message. */
    private static String surroundings(TestSingleplayerContext singleplayer) {
        return singleplayer.getServer().computeOnServer(server -> {
            PodEntity pod =
                    (PodEntity) server.getPlayerList().getPlayers().getFirst().getVehicle();
            StringBuilder lava = new StringBuilder();
            for (BlockPos pos : BlockPos.betweenClosed(
                    pod.blockPosition().offset(-5, -2, -5), pod.blockPosition().offset(8, 3, 5))) {
                if (pod.level().getFluidState(pos).is(FluidTags.LAVA)) {
                    lava.append(pos.toShortString())
                            .append(pod.level().getFluidState(pos).isSource() ? "S " : "F ");
                }
            }
            return "pod at " + pod.position() + " (cell " + pod.blockPosition().toShortString() + "), state "
                    + Versioned.readable(pod, PodLavaWatch.STATE) + ", lava " + lava;
        });
    }
}
