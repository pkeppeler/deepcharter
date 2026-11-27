package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.pod.PodScreen;
import io.github.pkeppeler.deepcharter.client.pod.PodStatusHud;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodLooseOre;
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
 * Client GameTest for #1456 and #1540: the pod screen driven with the real keyboard and cursor from the seat. The
 * retired dump key dumps nothing; shift-click moves ore from the hold to the pack and back, and the pack's ore reads
 * LOOSE on the cockpit; Q on a hold stack in the pod screen drops that stack under the pod, and none of it reaches the
 * seated pilot's pack.
 */
public class PodDumpClientTest implements FabricClientGameTest {
    /** Ticks the retired key is held: past the second it once needed, with room for a slow tick. */
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

            // The dump key is gone: B does nothing, held for as long as it took.
            RealInput.hold(context, InputConstants.KEY_B, HOLD_TICKS);
            context.waitTicks(HOLD_TICKS); // tick-wait: a dump the retired key sent would land by now
            require(
                    singleplayer
                                    .getServer()
                                    .computeOnServer(server -> pod(server).cargoUsed())
                            == 2,
                    "the retired dump key dumps nothing");

            context.getInput().pressKey(options -> options.keyInventory);
            ClientWait.screen(context, PodScreen.class);
            PodScreen screen = context.computeOnClient(client -> ClientSession.screen(client, PodScreen.class));
            ClientWait.until(
                    context,
                    "the ore in hold slot 0",
                    client -> !screen.getMenu().holdSlot(0).getItem().isEmpty());
            // Ore moves to the pack like any item, and the pod says it is loose.
            SlotClicks.shiftClick(context, screen, screen.getMenu().holdSlot(0));
            ClientWait.until(
                    context,
                    "the ore in the pack and the pod reading it loose",
                    () -> singleplayer
                            .getServer()
                            .computeOnServer(server -> pod(server).cargoUsed() == 1
                                    && oreHeld(player(server)) == 1
                                    && PodLooseOre.of(pod(server)).ore() == 1),
                    () -> singleplayer
                            .getServer()
                            .computeOnServer(server -> "hold " + pod(server).cargoUsed() + ", pack "
                                    + oreHeld(player(server)) + ", loose "
                                    + PodLooseOre.of(pod(server)).ore()));
            ClientWait.until(
                    context,
                    "the cockpit readout saying LOOSE",
                    client -> ClientSession.player(client).getVehicle() instanceof PodEntity pod
                            && PodStatusHud.looseLine(pod).isPresent());
            // And back: the same click on the pack's ore puts it in the hold.
            SlotClicks.shiftClick(
                    context, screen, SlotClicks.inventorySlot(context, screen, OreRegistry.item(OreType.GOLDIUM)));
            ClientWait.until(
                    context,
                    "the ore back in the hold",
                    () -> singleplayer
                            .getServer()
                            .computeOnServer(server -> pod(server).cargoUsed() == 2 && oreHeld(player(server)) == 0),
                    () -> singleplayer
                            .getServer()
                            .computeOnServer(
                                    server -> "hold " + pod(server).cargoUsed() + ", pack " + oreHeld(player(server))));

            // Q on a hold stack dumps it under the pod, to the ground and not to a pack.
            SlotClicks.hover(context, screen, screen.getMenu().holdSlot(0));
            RealInput.key(context, InputConstants.KEY_Q, 0);
            ClientWait.until(
                    context,
                    "the stack dumped from the screen",
                    () -> singleplayer
                            .getServer()
                            .computeOnServer(server -> pod(server).cargoUsed() == 1 && oreLying(server) == 1),
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
