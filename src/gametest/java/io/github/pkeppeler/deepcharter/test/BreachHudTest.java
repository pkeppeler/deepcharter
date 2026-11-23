package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.client.layer.Altimeter;
import io.github.pkeppeler.deepcharter.client.layer.BreachEffects;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.Locale;
import java.util.Set;
import java.util.function.ToIntFunction;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Client GameTest: the altimeter string matches the depth maths, and a crossing in place shakes the HUD. */
public class BreachHudTest implements FabricClientGameTest {
    private static final double X = 2000.5;
    private static final double Z = 2000.5;
    /** Real ticks to wait for something that should take a handful. */
    private static final int PATIENCE = 200;
    /** Ticks in which a shake has settled, as long as the theme lets one last. */
    private static final int SETTLED_TICKS = 100;

    /** Written out here, not read from Depth, so the test checks the maths rather than repeating it. */
    private static final int SEA_LEVEL = 63;

    private static final double FEET_PER_BLOCK = 3.28;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            ClientWait.until(
                    context, "the client in the world", client -> client.player != null && client.level != null);

            assertRumble(context);

            // On the surface the altimeter reads sea level minus Y.
            assertAltimeter(
                    context,
                    "the surface",
                    client -> SEA_LEVEL - ClientSession.player(client).getBlockY());

            // In layer 1 low enough that the reading is over a thousand feet, so it has a thousands separator. The
            // depth is the
            // surface's formula (ADR 0059): sea level minus Y, in the one level.
            teleport(singleplayer, 1, 5);
            ClientWait.until(context, "the client in layer 1", client -> TestLayers.clientIsIn(client, 1));
            assertAltimeter(
                    context,
                    "layer 1",
                    client -> SEA_LEVEL - ClientSession.player(client).getBlockY());
            String deep = context.computeOnClient(client ->
                    Altimeter.reading(ClientSession.player(client).getBlockY()).getString());
            if (!deep.contains(",")) {
                throw new AssertionError("A reading over 1,000 ft should group its digits, read: " + deep);
            }

            // A crossing: one block under the floor of layer 1, so it crosses on the next server tick. It is in place,
            // so the HUD shakes and the screen is not hidden.
            teleport(singleplayer, 1, -1);
            boolean shook = false;
            int inLayerTwoFor = 0;
            for (int tick = 0; tick < PATIENCE && inLayerTwoFor <= SETTLED_TICKS; tick++) {
                shook |= !context.computeOnClient(
                        client -> BreachEffects.jitter().equals(new BreachEffects.Offset(0, 0)));
                if (context.computeOnClient(client -> TestLayers.clientIsIn(client, 2))) {
                    inLayerTwoFor++;
                }
                context.waitTick();
            }
            if (inLayerTwoFor <= SETTLED_TICKS) {
                throw new AssertionError("The crossing should have put the client in layer 2");
            }
            if (!shook) {
                throw new AssertionError("A crossing in place should shake the HUD");
            }
        }
    }

    /** A crossing shakes the HUD and sounds, and nothing else: nobody moves. */
    private static void assertRumble(ClientGameTestContext context) {
        context.runOnClient(client -> BreachEffects.begin());
        boolean shook = false;
        for (int tick = 0; tick <= SETTLED_TICKS; tick++) {
            shook |= !context.computeOnClient(client -> BreachEffects.jitter().equals(new BreachEffects.Offset(0, 0)));
            context.waitTick();
        }
        context.runOnClient(client -> BreachEffects.reset());
        if (!shook) {
            throw new AssertionError("A crossing should shake the HUD");
        }
    }

    /** Teleports the real player into layer {@code layer} at height {@code y}. */
    private static void teleport(TestSingleplayerContext singleplayer, int layer, int y) {
        singleplayer.getServer().runOnServer(server -> {
            ServerLevel level = TestLayers.level(server, layer);
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            player.teleportTo(level, X, TestLayers.worldY(layer, y), Z, Set.of(), 0, 0, true);
        });
    }

    /**
     * The reading and the block Y it was made from are taken in one client call, so the player cannot
     * move between them.
     */
    private static void assertAltimeter(
            ClientGameTestContext context, String where, ToIntFunction<Minecraft> blocksDeep) {
        record Sample(String text, int blocks) {}
        Sample sample = context.computeOnClient(client -> new Sample(
                Altimeter.reading(ClientSession.player(client).getBlockY()).getString(),
                blocksDeep.applyAsInt(client)));
        int feet = (int) Math.round(sample.blocks() * FEET_PER_BLOCK);
        String expected = String.format(Locale.US, "%,d", -feet) + " ft.";
        if (!sample.text().equals(expected)) {
            throw new AssertionError("Altimeter in " + where + " should read '" + expected + "' for " + sample.blocks()
                    + " blocks of depth, read '" + sample.text() + "'");
        }
    }
}
