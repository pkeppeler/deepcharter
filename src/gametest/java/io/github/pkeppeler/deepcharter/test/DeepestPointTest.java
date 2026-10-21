package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.layer.Depth;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.market.WorkOrder;
import io.github.pkeppeler.deepcharter.market.WorkOrders;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Server GameTests for #476: a charter's deepest point rises through play and nothing else, and the layer-gated work orders open from it.
 * No test here writes the record by hand. Columns 9100 to 9300 are this class's.
 */
public class DeepestPointTest {
    private static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + 1000;
    private static final int X = 9100;
    private static final int Z = 9100;
    private static final int FLOOR = 60;
    private static final Input SPRINT = new Input(false, false, false, false, false, false, true);

    private static MockPlayer charterMember(GameTestHelper helper, String name) {
        MockPlayer mock = MockPlayers.join(helper, name);
        mock.player().setGameMode(GameType.SURVIVAL);
        MinecraftServer server = helper.getLevel().getServer();
        if (Charters.found(
                        server,
                        mock.player().getUUID(),
                        name + " " + UUID.randomUUID().toString().substring(0, 8))
                .isPresent()) {
            throw helper.assertionException("founding a charter for %s should succeed", name);
        }
        return mock;
    }

    private static int deepest(MinecraftServer server, MockPlayer mock) {
        return WorkOrdersTest.charter(server, mock.player()).deepestPoint();
    }

    private static boolean offersMorale(MinecraftServer server, MockPlayer mock) {
        return WorkOrders.view(
                        server,
                        mock.player(),
                        Charters.charterOfOrThrow(server, mock.player().getUUID()),
                        BlockPos.ZERO)
                .orders()
                .stream()
                .anyMatch(entry -> entry.order() == WorkOrder.MORALE_INITIATIVE);
    }

    /** A pilot in a pod on a stone floor in layer 1, sprinting: the pod bores straight down. The charter's record follows the pod down. */
    @GameTest(maxTicks = MAX_TICKS)
    public void aPodThatBoresToANewDepthRaisesItsCharterDeepestPoint(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel level = TestLayers.level(server, 1);
        RoomCarver.carve(level, X - 4, X + 3, FLOOR - 8, FLOOR - 1, Z - 4, Z + 3, Blocks.STONE);
        RoomCarver.carve(level, X - 4, X + 3, FLOOR, FLOOR + 10, Z - 4, Z + 3, Blocks.AIR);
        MockPlayer pilot = charterMember(helper, "dp-bore");
        pilot.player().setPermanentlyInvulnerable(true);
        Vec3 at = new Vec3(X, FLOOR, Z);
        pilot.teleportTo(level, at, 0f, 0f);
        boolean[] seated = {false};
        int startDepth = Depth.of(level, FLOOR);
        FarChunks.awaitEntityTicking(helper, level, BlockPos.containing(at), () -> {
            PodEntity pod = TestEntities.create(PodRegistry.POD, level);
            pod.setPos(at);
            level.addFreshEntity(pod);
            if (!pilot.player().startRiding(pod)) {
                throw helper.assertionException("the pilot could not mount the pod");
            }
            pilot.setInput(SPRINT);
            seated[0] = true;
        });
        helper.succeedWhen(() -> {
            if (!seated[0]) {
                throw helper.assertionException("waiting for the far chunk to tick");
            }
            int reached = Depth.of(level, pilot.player().getBlockY());
            if (reached < startDepth + 3) {
                throw helper.assertionException("the pod is at depth %s, waiting for %s", reached, startDepth + 3);
            }
            pilot.releaseInput();
            int recorded = deepest(server, pilot);
            if (recorded < reached) {
                throw helper.assertionException(
                        "a pod at depth %s should have raised the deepest point to it, got %s", reached, recorded);
            }
        });
    }

    /** The Morale Initiative opens at the top of layer 3, which the chain puts at the floor of layer 2: a member above the floor sees no such order. */
    @GameTest(maxTicks = MAX_TICKS)
    public void reachingTheTopOfLayerThreeOpensTheMoraleInitiative(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel two = TestLayers.level(server, 2);
        MockPlayer crew = charterMember(helper, "dp-order");
        crew.player().setPermanentlyInvulnerable(true);
        crew.player().setNoGravity(true);
        int above = two.getMinY() + 4;
        crew.teleportTo(two, new Vec3(X + 100.5, above, Z + 100.5), 0, 0);
        int layerThreeTop = LayerChain.topDepth(server.registryAccess(), 3);
        boolean[] moved = {false};
        helper.succeedWhen(() -> {
            if (!moved[0]) {
                if (deepest(server, crew) < Depth.of(two, above)) {
                    throw helper.assertionException(
                            "waiting for the poll to record the depth above the floor of layer 2");
                }
                if (deepest(server, crew) >= layerThreeTop || offersMorale(server, crew)) {
                    throw helper.assertionException(
                            "a depth above the floor of layer 2 should not open the Morale Initiative, deepest point %s",
                            deepest(server, crew));
                }
                moved[0] = true;
                crew.teleportTo(two, new Vec3(X + 100.5, two.getMinY(), Z + 100.5), 0, 0);
            }
            if (!offersMorale(server, crew)) {
                throw helper.assertionException(
                        "a member at the top of layer 3 should open the Morale Initiative, deepest point %s of %s",
                        deepest(server, crew), layerThreeTop);
            }
        });
    }

    /** A spectator or a creative player moves through rock for free, so being deep proves no dive. */
    @GameTest(maxTicks = MAX_TICKS)
    public void aSpectatorOrACreativePlayerSetsNoRecord(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel two = TestLayers.level(server, 2);
        MockPlayer ghost = charterMember(helper, "dp-ghost");
        ghost.player().setGameMode(GameType.SPECTATOR);
        ghost.teleportTo(two, new Vec3(X + 200.5, two.getMinY() + 4, Z + 200.5), 0, 0);
        MockPlayer builder = charterMember(helper, "dp-creative");
        builder.player().setGameMode(GameType.CREATIVE);
        builder.teleportTo(two, new Vec3(X + 250.5, two.getMinY() + 4, Z + 250.5), 0, 0);
        expectNoRecordAfterPolls(helper, server, ghost, builder);
    }

    /** The Nether has a depth of its own, which means nothing in the chain. */
    @GameTest(maxTicks = MAX_TICKS)
    public void aMemberInTheNetherSetsNoRecord(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel nether = ClientLight.serverLevel(server, Level.NETHER);
        MockPlayer crew = charterMember(helper, "dp-nether");
        crew.player().setPermanentlyInvulnerable(true);
        crew.player().setNoGravity(true);
        crew.teleportTo(nether, new Vec3(X + 300.5, nether.getMinY() + 4, Z + 300.5), 0, 0);
        if (Depth.of(nether, nether.getMinY() + 4) <= 0) {
            throw helper.assertionException(
                    "the Nether test position should read as a positive depth, or it proves nothing");
        }
        expectNoRecordAfterPolls(helper, server, crew);
    }

    private static void expectNoRecordAfterPolls(GameTestHelper helper, MinecraftServer server, MockPlayer... members) {
        int[] ticks = {0};
        helper.succeedWhen(() -> {
            if (++ticks[0] < 60) {
                throw helper.assertionException("waiting for a few polls");
            }
            for (MockPlayer member : members) {
                if (deepest(server, member) != 0) {
                    throw helper.assertionException(
                            "%s should set no record, got %s",
                            member.player().getGameProfile().name(), deepest(server, member));
                }
            }
        });
    }
}
