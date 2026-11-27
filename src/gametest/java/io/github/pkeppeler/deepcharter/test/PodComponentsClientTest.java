package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.pod.PodScreen;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodMenu;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.PodReadout;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import java.util.List;
import java.util.stream.IntStream;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Client GameTest for #65: the parts of a pod reach the client, so the HUD shows the hull as points out of the pod's real
 * maximum, and the pod screen's hold is as big as the bay.
 */
public class PodComponentsClientTest implements FabricClientGameTest {
    private static final float HULL_NOW = 150f;
    /** A tier 2 hull: 30 points of the original's 10 for the stock one, scaled to the mod's 100. */
    private static final int HULL_MAX = 300;
    /** A tier 1 bay. */
    private static final int BAY_SLOTS = 15;

    private static final OreType[] LOAD = {
        OreType.IRONIUM,
        OreType.BRONZIUM,
        OreType.SILVERIUM,
        OreType.GOLDIUM,
        OreType.PLATINIUM,
        OreType.EINSTEINIUM,
        OreType.CICATRIUM,
        OreType.IRONIUM,
        OreType.BRONZIUM,
        OreType.GOLDIUM
    };

    /** How many slots of the hold's first page hold something. */
    private static long holdShown(PodMenu menu) {
        return IntStream.range(0, PodMenu.HOLD_WINDOW)
                .filter(i -> !menu.holdSlot(i).getItem().isEmpty())
                .count();
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                require(
                        Charters.found(server, player.getUUID(), "Client components")
                                .isEmpty(),
                        "the player could not found a charter");
                CharterId charter = Charters.charterOfOrThrow(server, player.getUUID())
                        .orElseThrow()
                        .id();
                PodEntity pod = TestEntities.create(PodRegistry.POD, player.level());
                pod.setPos(player.position());
                player.level().addFreshEntity(pod);
                PodComponents.register(pod, charter);
                PodComponents.install(pod, ComponentItems.mint(server, ComponentTrack.HULL, 2, charter));
                PodComponents.install(pod, ComponentItems.mint(server, ComponentTrack.CARGO_BAY, 1, charter));
                pod.setHull(HULL_NOW);
                for (OreType type : LOAD) {
                    pod.cargo().tryAdd(pod, OreRegistry.stack(type));
                }
                require(player.startRiding(pod), "the charter's founder could not mount the pod");
            });
            // The registration, the parts and the hull reach the client in separate packets: wait for each value read
            // below.
            ClientWait.until(
                    context,
                    "the pod with its registration, parts and hull of " + HULL_NOW + "/" + HULL_MAX,
                    client -> client.player != null
                            && client.player.getVehicle() instanceof PodEntity pod
                            && PodComponents.registration(pod).isPresent()
                            && Math.round(pod.maxHull()) == HULL_MAX
                            && pod.hull() == HULL_NOW,
                    client -> client.player != null && client.player.getVehicle() instanceof PodEntity pod
                            ? "registration=" + PodComponents.registration(pod).isPresent() + ", maxHull="
                                    + pod.maxHull() + ", hull=" + pod.hull()
                            : "no pod under the player");

            int maxHull = context.computeOnClient(
                    client -> Math.round(TestEntities.vehicle(ClientSession.player(client), PodEntity.class)
                            .maxHull()));
            require(
                    maxHull == HULL_MAX,
                    "The client should work out the pod's maximum hull " + HULL_MAX + " from the synced parts, got "
                            + maxHull);
            List<Component> lines = context.computeOnClient(client ->
                    PodReadout.texts(client, TestEntities.vehicle(ClientSession.player(client), PodEntity.class)));
            require(
                    lines.getFirst().getString().equals("Hull 150/300"),
                    "The HUD should show the hull as 150/300, showed "
                            + lines.getFirst().getString());
            context.waitTicks(10);
            context.takeScreenshot("m2-pod-components-hud");

            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                PodMenu.open(player, TestEntities.vehicle(player, PodEntity.class));
            });
            ClientWait.screen(context, PodScreen.class);
            ClientWait.until(
                    context,
                    "the pod screen with its " + LOAD.length + " ore in a hold of " + BAY_SLOTS,
                    client -> ClientSession.player(client).containerMenu instanceof PodMenu menu
                            && menu.capacity() == BAY_SLOTS
                            && holdShown(menu) == LOAD.length,
                    client -> ClientSession.player(client).containerMenu instanceof PodMenu menu
                            ? holdShown(menu) + " ore in a hold of " + menu.capacity()
                            : "no pod screen");
            context.waitTicks(10);
            context.takeScreenshot("m2-pod-components-cargo");
        }
    }
}
