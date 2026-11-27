package io.github.pkeppeler.deepcharter.test.evidence;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.transmission.TransmissionOverlay;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodLoad;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RealInput;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Evidence scenario "pod-dump" for #1456: a pilot sits in a Mole whose hold is too heavy to lift. Holding jump, the pod
 * stays on the ground and the action bar says it is overloaded and names the dump key; the first overload also says it
 * once in chat. The pilot holds the dump key: a bar fills for a second, the hold drops under the pod as items with the
 * jettison sound, and the same jump now lifts the pod off, leaving the ore on the ground.
 */
public class PodDumpScenario extends EvidenceScenario {
    private static final int TICKS_PER_FRAME = 2;
    /** Frames of the held jump that does not lift. */
    private static final int STUCK_FRAMES = 25;
    /** Frames the dump key is held: past the second it needs. */
    private static final int DUMP_FRAMES = 14;
    /** Frames of the items falling and settling. */
    private static final int SETTLE_FRAMES = 8;
    /** Frames of the climb once lighter. */
    private static final int CLIMB_FRAMES = 12;
    /** How many goldium ride beside the heavy load, so the dump shows a handful of items. */
    private static final int GOLDIUM = 4;

    @Override
    protected String name() {
        return "pod-dump";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40);
            UUID pod = singleplayer.getServer().computeOnServer(PodDumpScenario::setUp);
            // The founding's welcome transmission is not what this shows, and its panel would cover the pod.
            ClientWait.until(context, "the welcome transmission", client -> TransmissionOverlay.active());
            context.runOnClient(client -> TransmissionOverlay.reset());
            int[] at = singleplayer.getServer().computeOnServer(server -> {
                ServerPlayer player = player(server);
                return new int[] {player.getBlockX(), player.getBlockZ()};
            });
            ClientWait.chunksAround(context, at[0], at[1], 4);
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = player(server);
                if (!(player.level().getEntity(pod) instanceof PodEntity entity)
                        || !player.startRiding(entity, true, false)) {
                    throw new AssertionError("the pilot should board");
                }
            });
            ClientWait.until(
                    context,
                    "the pilot in the pod on the client",
                    client -> ClientSession.player(client).getVehicle() instanceof PodEntity);
            context.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_BACK));
            context.waitTicks(20);
            screenshot(context, "overloaded-on-the-ground");

            double rest = singleplayer
                    .getServer()
                    .computeOnServer(server -> pod(server).getY());
            RealInput.down(context, InputConstants.KEY_SPACE);
            frames(context, STUCK_FRAMES);
            screenshot(context, "jump-does-not-lift");
            RealInput.up(context, InputConstants.KEY_SPACE);
            double stuck = singleplayer
                    .getServer()
                    .computeOnServer(server -> pod(server).getY());
            if (stuck - rest > 0.01) {
                throw new AssertionError("the overloaded pod should not lift, rose " + (stuck - rest));
            }

            RealInput.down(context, InputConstants.KEY_B);
            for (int i = 0; i < DUMP_FRAMES; i++) {
                context.waitTicks(TICKS_PER_FRAME);
                frame(context);
                if (i == DUMP_FRAMES / 3) {
                    screenshot(context, "dump-key-held");
                }
            }
            RealInput.up(context, InputConstants.KEY_B);
            ClientWait.until(
                    context,
                    "the hold dumped",
                    () -> singleplayer
                            .getServer()
                            .computeOnServer(server -> pod(server).cargoUsed() == 0),
                    () -> "hold "
                            + singleplayer
                                    .getServer()
                                    .computeOnServer(server -> pod(server).cargoUsed()));
            frames(context, SETTLE_FRAMES);
            screenshot(context, "hold-dumped");

            RealInput.down(context, InputConstants.KEY_SPACE);
            frames(context, CLIMB_FRAMES);
            screenshot(context, "lifts-off");
            RealInput.up(context, InputConstants.KEY_SPACE);
            double climbed = singleplayer
                    .getServer()
                    .computeOnServer(server -> pod(server).getY());
            if (climbed - rest < 1) {
                throw new AssertionError("the lighter pod should climb, rose " + (climbed - rest));
            }
            singleplayer.getServer().runOnServer(server -> player(server).stopRiding());
            context.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));
        }
    }

    /**
     * The player founds a charter, and a pod of theirs stands beside them with a hold too heavy to lift: one ironium as
     * heavy as the pod can climb with, and a few goldium.
     */
    private static UUID setUp(MinecraftServer server) {
        ServerPlayer player = player(server);
        if (Charters.found(server, player.getUUID(), "Riggs and Sons").isPresent()) {
            throw new AssertionError("founding should succeed");
        }
        CharterId charter = Charters.charterOfOrThrow(server, player.getUUID())
                .orElseThrow()
                .id();
        PodEntity pod = TestEntities.create(PodRegistry.POD, player.level());
        pod.setPos(player.position().add(2, 0, 0));
        player.level().addFreshEntity(pod);
        PodComponents.register(pod, charter);
        pod.cargo()
                .tryAdd(pod, OreRegistry.stack(OreType.IRONIUM), PodLoad.of(pod).climbLimit());
        for (int i = 0; i < GOLDIUM; i++) {
            pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.GOLDIUM));
        }
        return pod.getUUID();
    }

    private void frames(ClientGameTestContext context, int count) {
        for (int i = 0; i < count; i++) {
            context.waitTicks(TICKS_PER_FRAME);
            frame(context);
        }
    }

    private static ServerPlayer player(MinecraftServer server) {
        return server.getPlayerList().getPlayers().getFirst();
    }

    private static PodEntity pod(MinecraftServer server) {
        return TestEntities.vehicle(player(server), PodEntity.class);
    }
}
