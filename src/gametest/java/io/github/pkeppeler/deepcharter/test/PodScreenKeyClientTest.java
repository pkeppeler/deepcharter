package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.client.pod.PodScreen;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.server.level.ServerPlayer;

/**
 * Client GameTest for the pod screen on the inventory key (#1537): a seated pilot presses the inventory key and sees the
 * pod screen, as a rider sees a horse's; the same key closes it; and on foot the key still opens the player's own
 * inventory.
 */
public class PodScreenKeyClientTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            int[] at = singleplayer.getServer().computeOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                return new int[] {player.getBlockX(), player.getBlockZ()};
            });
            ClientWait.chunksAround(context, at[0], at[1], 4);
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                PodEntity pod = TestEntities.create(PodRegistry.POD, player.level());
                pod.setPos(player.position());
                player.level().addFreshEntity(pod);
                if (!player.startRiding(pod)) {
                    throw new AssertionError("the player could not mount the new pod");
                }
            });
            ClientWait.until(
                    context,
                    "the pilot seated in the pod",
                    client -> client.player != null && client.player.getVehicle() instanceof PodEntity);

            context.getInput().pressKey(options -> options.keyInventory);
            ClientWait.screen(context, PodScreen.class);

            // The inventory key closes a container screen, so the pod screen needs no key of its own to close either.
            context.getInput().pressKey(options -> options.keyInventory);
            ClientWait.until(context, "the pod screen closed", client -> client.gui.screen() == null);

            singleplayer
                    .getServer()
                    .runOnServer(server ->
                            server.getPlayerList().getPlayers().getFirst().stopRiding());
            ClientWait.until(
                    context,
                    "the player on foot",
                    client -> client.player != null && client.player.getVehicle() == null);
            context.getInput().pressKey(options -> options.keyInventory);
            ClientWait.screen(context, InventoryScreen.class);
        }
    }
}
