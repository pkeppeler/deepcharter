package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.layer.BreachEvents;
import io.github.pkeppeler.deepcharter.layer.BreachService;
import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Server GameTests for breach crossing in place (ADR 0061) for players and vehicles, in the campaign level. Each test uses its own X/Z so
 * the columns it carves do not touch another test's.
 */
public class BreachCrossingTest {
    /** Ticks a fall, once the chunk ticks, needs to cross a breach and be checked. */
    private static final int CROSSING_TICKS = 200;
    /** Ticks the no-bounce test needs: the crossing plus 100 ticks of watching. */
    private static final int NO_BOUNCE_TICKS = 300;
    /** Crossings seen per entity UUID. A UUID survives a crossing, so this follows recreated entities. */
    private static final Map<UUID, Integer> CROSSINGS = new ConcurrentHashMap<>();

    static {
        BreachEvents.CROSSED.register(
                (entity, from, to, fromLayer, toLayer) -> CROSSINGS.merge(entity.getUUID(), 1, Integer::sum));
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + CROSSING_TICKS)
    public void playerInAMinecartCrossesInPlaceStillRiding(GameTestHelper helper) {
        double x = 1200.5;
        double z = 1200.5;
        ServerLevel one = TestLayers.level(helper, 1);
        openShaft(one, x, z);
        MockPlayer mock = MockPlayers.join(helper, "breach-rides");
        mock.teleportTo(one, TestLayers.vec(1, x, 8, z), 0, 0);
        FarChunks.awaitEntityTicking(helper, one, BlockPos.containing(x, TestLayers.worldY(1, 8), z), () -> {
            Minecart cart = TestEntities.create(EntityTypes.MINECART, one);
            cart.setPos(x, TestLayers.worldY(1, 8), z);
            one.addFreshEntity(cart);
            if (!mock.player().startRiding(cart, true, false)) {
                throw failure(helper, "the mock could not board the minecart");
            }
        });
        helper.succeedWhen(() -> {
            ServerPlayer player = mock.player();
            expectIn(helper, player, 2);
            Entity vehicle = player.getVehicle();
            if (!(vehicle instanceof Minecart)) {
                throw failure(helper, "the player is riding %s after crossing, not a minecart", vehicle);
            }
            if (vehicle.level() != player.level()) {
                throw failure(
                        helper,
                        "the minecart is in %s, the player in %s",
                        vehicle.level().dimension(),
                        player.level().dimension());
            }
            if (CROSSINGS.getOrDefault(player.getUUID(), 0) != 1) {
                throw failure(
                        helper, "expected one crossing event for the rider, saw %s", CROSSINGS.get(player.getUUID()));
            }
        });
    }

    /** A crossing up is seen from the layer last seen, so the player is first put in layer 2 and then raised past the crust of layer 1. */
    @GameTest(maxTicks = 200)
    public void risingAboveTheCrustOfLayerOneGoesBackUpInPlace(GameTestHelper helper) {
        double x = 1300.5;
        double z = 1300.5;
        ServerLevel two = TestLayers.level(helper, 2);
        MockPlayer mock = MockPlayers.join(helper, "breach-rises");
        mock.player().setNoGravity(true);
        mock.teleportTo(two, TestLayers.vec(2, x, 100, z), 0, 0);
        int floor = TestLayers.band(TestLayers.level(helper, 1), 1).minY();
        helper.runAfterDelay(
                20, () -> mock.teleportTo(two, new Vec3(x, floor + LayerTuning.DEFAULT.crustThickness() + 1, z), 0, 0));
        helper.succeedWhen(() -> {
            ServerPlayer player = mock.player();
            if (helper.getTick() < 21) {
                throw failure(helper, "waiting for the player to be raised");
            }
            expectIn(helper, player, 1);
            if (player.level() != two) {
                throw failure(
                        helper, "the player left the level: %s", player.level().dimension());
            }
            if (CROSSINGS.getOrDefault(player.getUUID(), 0) != 1) {
                throw failure(helper, "expected one crossing up, saw %s", CROSSINGS.get(player.getUUID()));
            }
        });
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + NO_BOUNCE_TICKS)
    public void noBouncingInPlaceOverOneHundredTicks(GameTestHelper helper) {
        double x = 1400.5;
        double z = 1400.5;
        openShaft(TestLayers.level(helper, 1), x, z);
        MockPlayer mock = MockPlayers.join(helper, "breach-no-bounce");
        mock.teleportTo(TestLayers.level(helper, 1), TestLayers.vec(1, x, 8, z), 0, 0);
        fallWhileInLayerOne(helper, mock);
        long[] arrivedAt = {-1};
        TestTicks.each(helper, () -> {
            boolean inTwo = TestLayers.isIn(mock.player(), 2);
            if (inTwo && arrivedAt[0] < 0) {
                arrivedAt[0] = helper.getTick();
                ServerLevel two = TestLayers.level(helper, 2);
                LayerChain.Band twoBand = TestLayers.band(two, 2);
                double y = mock.player().getY();
                // Under layer 1's floor, which is the line it crossed, and inside layer 2 with margin.
                if (y - twoBand.minY() < 1
                        || y >= TestLayers.band(TestLayers.level(helper, 1), 1).minY()) {
                    throw failure(
                            helper,
                            "arrival y=%s is not at least 1 block inside layer 2 [%s, %s]",
                            y,
                            twoBand.minY(),
                            twoBand.maxY());
                }
            }
            if (arrivedAt[0] >= 0) {
                if (!inTwo) {
                    throw failure(
                            helper, "the player left layer 2 %d ticks after arriving", helper.getTick() - arrivedAt[0]);
                }
                if (helper.getTick() - arrivedAt[0] >= 100) {
                    if (CROSSINGS.getOrDefault(mock.player().getUUID(), 0) != 1) {
                        throw failure(
                                helper,
                                "expected exactly one crossing, saw %s",
                                CROSSINGS.get(mock.player().getUUID()));
                    }
                    helper.succeed();
                }
            }
        });
    }

    @GameTest(maxTicks = 40)
    public void theLastLayersFloorHasNoCrossing(GameTestHelper helper) {
        ServerLevel two = TestLayers.level(helper, CampaignBands.lastLayer());
        LayerChain.Band twoBand = TestLayers.band(two, CampaignBands.lastLayer());
        MockPlayer mock = MockPlayers.join(helper, "breach-last-floor");
        mock.player().setNoGravity(true);
        mock.teleportTo(two, new Vec3(1500.5, twoBand.minY() + 10, 1500.5), 0, 0);
        helper.runAfterDelay(10, () -> mock.teleportTo(two, new Vec3(1500.5, twoBand.minY() - 2, 1500.5), 0, 0));
        helper.runAfterDelay(30, () -> {
            if (CROSSINGS.containsKey(mock.player().getUUID())) {
                throw failure(helper, "the last layer's floor crossed");
            }
            // Below the last layer's band is no layer: the player stays in the level, under its floor.
            if (mock.player().level() != two || mock.player().getY() >= twoBand.minY()) {
                throw failure(
                        helper,
                        "the player is at y=%s in %s, expected under the floor %s of the last layer",
                        mock.player().getY(),
                        mock.player().level().dimension(),
                        twoBand.minY());
            }
            helper.succeed();
        });
    }

    @GameTest
    public void bareHandsCannotBreakTheCrustButADrillCan(GameTestHelper helper) {
        ServerLevel one = TestLayers.level(helper, 1);
        BlockPos pos = new BlockPos(1600, TestLayers.band(one, 1).minY() + 1, 1600);
        MockPlayer mock = MockPlayers.join(helper, "breach-hands");
        mock.teleportTo(one, Vec3.atBottomCenterOf(pos.above(4)), 0, 0);
        mock.player().setGameMode(GameType.SURVIVAL);
        one.setBlock(pos, LayerBlocks.BREACH_CRUST.defaultBlockState(), 3);
        if (mock.player().gameMode.destroyBlock(pos)) {
            throw failure(helper, "a bare-handed player broke the crust");
        }
        if (!one.getBlockState(pos).is(LayerBlocks.BREACH_CRUST)) {
            throw failure(helper, "the crust is gone after a refused bare-hand break");
        }
        // Other blocks are unaffected.
        BlockPos stone = pos.east();
        one.setBlock(stone, Blocks.DIRT.defaultBlockState(), 3);
        if (!mock.player().gameMode.destroyBlock(stone)) {
            throw failure(helper, "the hand-break guard stopped an ordinary block");
        }
        mock.player().setGameMode(GameType.ADVENTURE);
        if (mock.player().gameMode.destroyBlock(pos) || !one.getBlockState(pos).is(LayerBlocks.BREACH_CRUST)) {
            throw failure(helper, "a bare-handed adventure player broke the crust");
        }
        mock.player().setGameMode(GameType.CREATIVE);
        if (!mock.player().gameMode.destroyBlock(pos) || !one.getBlockState(pos).isAir()) {
            throw failure(helper, "a creative player could not break the crust");
        }
        one.setBlock(pos, LayerBlocks.BREACH_CRUST.defaultBlockState(), 3);
        if (!BreachService.breakCrust(one, pos)) {
            throw failure(helper, "the drill hook refused to break the crust");
        }
        if (!one.getBlockState(pos).isAir()) {
            throw failure(helper, "the crust is still there after the drill hook");
        }
        helper.succeed();
    }

    /** A mock has no client gravity, so drop it a block a tick while in layer 1, once its chunk ticks. */
    private static void fallWhileInLayerOne(GameTestHelper helper, MockPlayer mock) {
        boolean[] ticking = {false};
        FarChunks.awaitEntityTicking(
                helper, TestLayers.level(helper, 1), mock.player().blockPosition(), () -> ticking[0] = true);
        TestTicks.each(helper, () -> {
            ServerPlayer player = mock.player();
            if (ticking[0] && TestLayers.isIn(player, 1)) {
                player.setPos(player.getX(), player.getY() - 1, player.getZ());
            }
        });
    }

    /** Clear the floor of layer 1 at one column, over a pocket in the top of layer 2, so an entity above it falls straight out of the layer. */
    private static void openShaft(ServerLevel level, double x, double z) {
        BlockPos column = BlockPos.containing(x, 0, z);
        RoomCarver.carvePocketBelow(level, 1, column.getX(), column.getX(), column.getZ(), column.getZ());
        RoomCarver.carve(
                level,
                column.atY(TestLayers.band(level, 1).minY()),
                column.atY(TestLayers.band(level, 1).minY() + 10),
                Blocks.AIR.defaultBlockState(),
                Block.UPDATE_ALL);
    }

    private static void expectIn(GameTestHelper helper, Entity entity, int layer) {
        if (!TestLayers.isIn(entity, layer)) {
            throw failure(
                    helper,
                    "%s is in %s, expected layer_%d",
                    entity.getName().getString(),
                    entity.level().dimension(),
                    layer);
        }
    }
}
