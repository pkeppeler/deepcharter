package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.outpost.OutpostBuilder;
import io.github.pkeppeler.deepcharter.outpost.OutpostData;
import io.github.pkeppeler.deepcharter.outpost.OutpostVisits;
import io.github.pkeppeler.deepcharter.outpost.OutpostVisits.Ledger;
import io.github.pkeppeler.deepcharter.outpost.OutpostVisits.Pending;
import io.github.pkeppeler.deepcharter.outpost.OutpostVisits.Report;
import io.github.pkeppeler.deepcharter.outpost.SettlingTuning;
import io.github.pkeppeler.deepcharter.test.support.CampaignEnvironments;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.SettlingRooms;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * #1004: the real tick wiring. An outpost the kit built, its lamps put out and its ledger long overdue, is swept by the server's own tick
 * (no test calls the sweep), and its stored fuel goes. The outpost is recorded in the world's data, not a swapped copy: a swap lasts a
 * tick and the sweep comes later.
 */
public class OutpostSettlingSweepTest {
    /** The wait for the chunk, then several sweeps (one every 100 ticks) and the light going out. */
    private static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + 800;
    /** The hall's lamps, as offsets from the floor: the builder's six. */
    private static final List<BlockPos> LAMPS = List.of(
            new BlockPos(-2, 3, -4),
            new BlockPos(2, 3, -4),
            new BlockPos(0, 4, -4),
            new BlockPos(0, 4, 4),
            new BlockPos(-4, 4, 0),
            new BlockPos(4, 4, 0));

    @GameTest(environment = CampaignEnvironments.B, maxTicks = MAX_TICKS)
    public void theServerTickSettlesAnOverdueDarkOutpostWithoutAnyoneCalling(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel one = TestLayers.level(helper, 1);
        BlockPos floor = SettlingRooms.kitFloor(one, 9700);
        MockPlayer builder = MockPlayers.join(helper, "sweep-builder");
        builder.player().setGameMode(GameType.SURVIVAL);
        Charters.found(
                        server,
                        builder.player().getUUID(),
                        "Sweep " + UUID.randomUUID().toString().substring(0, 8))
                .ifPresent(refusal -> {
                    throw failure(helper, "could not found a charter: %s", refusal);
                });
        CharterId charter = Charters.charterOfOrThrow(server, builder.player().getUUID())
                .orElseThrow()
                .id();
        builder.teleportTo(one, Vec3.atBottomCenterOf(floor.above()), 0f, 0f);
        boolean[] ticking = {false};
        // The hall's light is made on a background thread, so its going dark is awaited under a wall-clock Deadline,
        // not counted in ticks.
        FarChunks.Deadline[] darkBy = {null};
        boolean[] dark = {false};
        TestTicks.each(helper, () -> {
            if (darkBy[0] == null || dark[0]) {
                return;
            }
            int light = one.getBrightness(LightLayer.BLOCK, floor.above());
            darkBy[0].await(helper, one, light < 3, () -> "the hall did not go dark (block light " + light + ")");
            dark[0] = light < 3;
        });
        BlockPos barrel = floor.offset(3, 1, 3);
        FarChunks.awaitEntityTicking(helper, one, floor, () -> ticking[0] = true);
        helper.startSequence()
                .thenWaitUntil(() -> expect(helper, ticking[0], "waiting for the hall's chunk to tick entities"))
                .thenExecute(() -> {
                    Optional<net.minecraft.network.chat.Component> refused =
                            OutpostBuilder.build(builder.player(), one, floor, Direction.UP);
                    expect(helper, refused.isEmpty(), "the kit should build, it said %s", refused);
                    // Nobody stays near, and the lamps go out.
                    builder.teleportTo(
                            helper.getLevel(),
                            Vec3.atBottomCenterOf(helper.absolutePos(BlockPos.ZERO.above())),
                            0f,
                            0f);
                    for (BlockPos lamp : LAMPS) {
                        RoomCarver.carve(one, floor.offset(lamp), floor.offset(lamp), Blocks.AIR.defaultBlockState());
                    }
                    darkBy[0] = FarChunks.deadline();
                    one.setBlock(barrel, Blocks.BARREL.defaultBlockState(), 3);
                    Container storage = SettlingRooms.containerAt(one, barrel);
                    for (int slot = 0; slot < storage.getContainerSize(); slot++) {
                        storage.setItem(slot, new net.minecraft.world.item.ItemStack(Items.COAL, 64));
                    }
                    long window = one.getGameTime() / SettlingTuning.DEFAULT.windowTicks();
                    OutpostVisits.get(server)
                            .put(new Ledger(
                                    charter,
                                    1,
                                    window - SettlingTuning.DEFAULT.maxWindowsPerSweep(),
                                    Optional.empty(),
                                    Pending.NONE,
                                    Report.NONE));
                })
                .thenWaitUntil(() -> expect(helper, dark[0], "waiting for the hall to go dark"))
                .thenWaitUntil(() -> {
                    int coal = SettlingRooms.count(SettlingRooms.containerAt(one, barrel), Items.COAL);
                    expect(helper, coal < 27 * 64, "waiting for a sweep to take fuel, the barrel still holds %s", coal);
                })
                .thenExecute(() -> {
                    Ledger ledger = OutpostVisits.get(server).find(charter, 1).orElseThrow();
                    expect(helper, ledger.unread().visits() > 0, "the sweep should have kept a report: %s", ledger);
                    expect(
                            helper,
                            OutpostData.get(server).of(charter, 1).isPresent(),
                            "the outpost is the world's own, not a swapped copy");
                })
                .thenSucceed();
    }
}
