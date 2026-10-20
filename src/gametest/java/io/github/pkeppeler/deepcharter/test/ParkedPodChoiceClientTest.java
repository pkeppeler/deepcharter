package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.fuel.FuelPumpScreen;
import io.github.pkeppeler.deepcharter.client.repair.RepairStationScreen;
import io.github.pkeppeler.deepcharter.fuel.FuelPump;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.DrawnText;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * A pod terminal's screen shows the pod that the server will serve: the player's own pod, even when an unowned pod is parked
 * nearer. The fuel pump and the repair station each have the unowned pod nearer than the own one.
 */
public class ParkedPodChoiceClientTest implements FabricClientGameTest {
    private static final float OWN_FUEL = 30f;
    private static final float UNOWNED_FUEL = 60f;
    private static final float OWN_DAMAGE = 30f;
    private static final float UNOWNED_DAMAGE = 10f;

    /** The line the pump draws for a pod, and the line the station draws. */
    private record Lines(String tank, String hull) {}

    /**
     * What the scene gives the test: the two terminals, and the lines they draw for the own pod and for the unowned one. The pods differ in
     * fuel and in hull, so the drawn line says which pod a screen shows.
     */
    private record Scene(BlockPos pump, BlockPos station, Lines own, Lines unowned) {}

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            Scene scene = singleplayer.getServer().computeOnServer(ParkedPodChoiceClientTest::setUp);

            context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(scene.pump())));
            ClientWait.screen(context, FuelPumpScreen.class);
            requireOwnPodShown(context, scene.own().tank(), scene.unowned().tank(), "pump");
            context.setScreen(() -> null);

            context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(scene.station())));
            ClientWait.screen(context, RepairStationScreen.class);
            requireOwnPodShown(context, scene.own().hull(), scene.unowned().hull(), "station");
            context.setScreen(() -> null);
        }
    }

    /** Waits for the open screen to draw the own pod's line, then holds it to not drawing the nearer unowned pod's. */
    private static void requireOwnPodShown(ClientGameTestContext context, String own, String unowned, String terminal) {
        ClientWait.until(
                context,
                "the own pod drawn at the " + terminal,
                client -> drawn(client).contains(own),
                client -> "drawn " + drawn(client));
        List<String> drawn = context.computeOnClient(ParkedPodChoiceClientTest::drawn);
        require(
                !drawn.contains(unowned),
                "the " + terminal + " screen should show the own pod (" + own + ") and not the nearer unowned pod ("
                        + unowned + "), draws " + drawn);
    }

    private static List<String> drawn(Minecraft client) {
        return DrawnText.of(ClientSession.screen(client, Screen.class)).stream()
                .map(DrawnText.Drawn::text)
                .toList();
    }

    /** A repaired pump east of the player and a repaired station north; the own pod three blocks west, an unowned one a block east. */
    private static Scene setUp(MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
        require(
                Charters.found(server, player.getUUID(), "Pod Choice Charter").isEmpty(),
                "founding the charter should succeed");
        CharterId charter = Charters.charterOfOrThrow(server, player.getUUID())
                .orElseThrow()
                .id();
        RepairState repairs = RepairState.get(server);
        for (TerminalType type : new TerminalType[] {
            TerminalTypes.FUEL_PUMP,
            TerminalTypes.ORE_PROCESSOR,
            TerminalTypes.UPGRADE_TERMINAL,
            TerminalTypes.REPAIR_STATION
        }) {
            type.parts().forEach(part -> repairs.insert(type, part));
        }
        BlockPos pump = player.blockPosition().relative(Direction.EAST, 2);
        BlockPos station = player.blockPosition().relative(Direction.NORTH, 2);
        server.overworld().setBlock(pump, TerminalTypes.FUEL_PUMP.block().defaultBlockState(), 3);
        server.overworld()
                .setBlock(station, TerminalTypes.REPAIR_STATION.block().defaultBlockState(), 3);
        PodEntity own = spawn(player, player.position().relative(Direction.WEST, 3));
        PodComponents.register(own, charter);
        own.setFuel(OWN_FUEL);
        own.setHull(own.maxHull() - OWN_DAMAGE);
        PodEntity unowned = spawn(player, player.position().relative(Direction.EAST, 1));
        unowned.setFuel(UNOWNED_FUEL);
        unowned.setHull(unowned.maxHull() - UNOWNED_DAMAGE);
        return new Scene(pump, station, linesOf(own), linesOf(unowned));
    }

    /** The tank line the pump draws for {@code pod}, and the hull line the station draws, as their screens build them. */
    private static Lines linesOf(PodEntity pod) {
        String tank = Component.translatable(
                        "screen.deepcharter.fuel_pump.tank",
                        String.format(Locale.ROOT, "%.1f", FuelPump.litres(pod)),
                        String.format(Locale.ROOT, "%.0f", PodStats.of(pod).tankLitres()))
                .getString();
        String hull = Component.translatable(
                        "screen.deepcharter.repair.hull", Math.round(pod.hull()), Math.round(pod.maxHull()))
                .getString()
                .toUpperCase(Locale.ROOT);
        return new Lines(tank, hull);
    }

    private static PodEntity spawn(ServerPlayer player, Vec3 at) {
        PodEntity pod = TestEntities.create(PodRegistry.POD, player.level());
        pod.setPos(at);
        player.level().addFreshEntity(pod);
        return pod;
    }
}
