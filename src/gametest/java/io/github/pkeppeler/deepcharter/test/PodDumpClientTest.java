package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.pod.PodScreen;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RealInput;
import io.github.pkeppeler.deepcharter.test.support.SlotClicks;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/**
 * Client GameTest for #1456: dumping cargo, driven with the real keyboard and cursor from the seat. A tap of the dump
 * key dumps nothing; holding it a second and a half drops the whole hold under the pod; and Q on a hold stack in the
 * pod screen drops that stack. No ore reaches the seated pilot's pack.
 */
public class PodDumpClientTest implements FabricClientGameTest {
    /** Ticks a tap holds the key: well short of the second the dump needs. */
    private static final int TAP_TICKS = 5;
    /** Ticks a full hold keeps the key down: past the second, with room for a slow tick. */
    private static final int HOLD_TICKS = 30;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            UUID pod = singleplayer.getServer().computeOnServer(PodDumpClientTest::setUp);
            int[] at = singleplayer.getServer().computeOnServer(server -> {
                ServerPlayer player = player(server);
                return new int[] {player.getBlockX(), player.getBlockZ()};
            });
            ClientWait.chunksAround(context, at[0], at[1], 4);
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = player(server);
                PodEntity entity = (PodEntity) player.level().getEntity(pod);
                require(entity != null && player.startRiding(entity, true, false), "the pilot should board");
            });
            ClientWait.until(
                    context,
                    "the pilot in the pod on the client",
                    client -> ClientSession.player(client).getVehicle() instanceof PodEntity);

            RealInput.hold(context, InputConstants.KEY_B, TAP_TICKS);
            context.waitTicks(HOLD_TICKS); // tick-wait: a dump the tap wrongly sent would land within these ticks
            require(
                    singleplayer
                                    .getServer()
                                    .computeOnServer(server -> pod(server).cargoUsed())
                            == 2,
                    "a tap of the dump key dumps nothing");

            RealInput.hold(context, InputConstants.KEY_B, HOLD_TICKS);
            ClientWait.until(
                    context,
                    "the whole hold dumped under the pod",
                    () -> singleplayer
                            .getServer()
                            .computeOnServer(server -> pod(server).cargoUsed() == 0 && oreLying(server) == 2),
                    () -> singleplayer
                            .getServer()
                            .computeOnServer(
                                    server -> "hold " + pod(server).cargoUsed() + ", lying " + oreLying(server)));

            singleplayer
                    .getServer()
                    .runOnServer(
                            server -> pod(server).cargo().tryAdd(pod(server), OreRegistry.stack(OreType.BRONZIUM)));
            RealInput.key(context, InputConstants.KEY_I, 0);
            ClientWait.screen(context, PodScreen.class);
            PodScreen screen = context.computeOnClient(client -> ClientSession.screen(client, PodScreen.class));
            ClientWait.until(
                    context,
                    "the new ore in hold slot 0",
                    client -> !screen.getMenu().holdSlot(0).getItem().isEmpty());
            SlotClicks.hover(context, screen, screen.getMenu().holdSlot(0));
            RealInput.key(context, InputConstants.KEY_Q, 0);
            ClientWait.until(
                    context,
                    "the stack dumped from the screen",
                    () -> singleplayer
                            .getServer()
                            .computeOnServer(server -> pod(server).cargoUsed() == 0 && oreLying(server) == 3),
                    () -> singleplayer
                            .getServer()
                            .computeOnServer(
                                    server -> "hold " + pod(server).cargoUsed() + ", lying " + oreLying(server)));
            require(
                    singleplayer.getServer().computeOnServer(server -> oreHeld(player(server))) == 0,
                    "no dumped ore reaches the seated pilot's pack");

            RealInput.key(context, InputConstants.KEY_ESCAPE, 0);
            ClientWait.until(context, "the screen closed", client -> client.gui.screen() == null);
            singleplayer.getServer().runOnServer(server -> player(server).stopRiding());
        }
    }

    /** The player founds a charter, and a pod of theirs with two ore stands beside them. */
    private static UUID setUp(MinecraftServer server) {
        ServerPlayer player = player(server);
        require(Charters.found(server, player.getUUID(), "Dump Charter").isEmpty(), "founding should succeed");
        CharterId charter = Charters.charterOfOrThrow(server, player.getUUID())
                .orElseThrow()
                .id();
        PodEntity pod = TestEntities.create(PodRegistry.POD, player.level());
        pod.setPos(player.position().add(2, 0, 0));
        player.level().addFreshEntity(pod);
        PodComponents.register(pod, charter);
        pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.GOLDIUM));
        pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.IRONIUM));
        return pod.getUUID();
    }

    private static ServerPlayer player(MinecraftServer server) {
        return server.getPlayerList().getPlayers().getFirst();
    }

    private static PodEntity pod(MinecraftServer server) {
        return TestEntities.vehicle(player(server), PodEntity.class);
    }

    /** The ore lying as items within a few blocks of the pod. */
    private static int oreLying(MinecraftServer server) {
        PodEntity pod = pod(server);
        return pod
                .level()
                .getEntitiesOfClass(ItemEntity.class, pod.getBoundingBox().inflate(3))
                .stream()
                .filter(item -> OreRegistry.typeOf(item.getItem()).isPresent())
                .mapToInt(item -> item.getItem().getCount())
                .sum();
    }

    private static int oreHeld(ServerPlayer player) {
        int count = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (OreRegistry.typeOf(stack).isPresent()) {
                count += stack.getCount();
            }
        }
        return count;
    }
}
