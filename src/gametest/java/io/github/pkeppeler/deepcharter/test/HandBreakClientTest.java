package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.HandBreakChecks;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;
import java.util.Objects;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Client GameTest for #858 (ADR 0069): the real client mines pod-only crust as the attack button does, and the client and
 * the server agree. A pick that bites breaks the block on both sides, with no ghost break and no block that comes back;
 * a tool that cannot bite breaks it on neither, however long it swings.
 */
public class HandBreakClientTest implements FabricClientGameTest {
    private static final int X = 9300;
    private static final int Z = 9300;
    /** The break time is 1200 ticks; the margin covers the tick the client starts on and the one it sends STOP on. */
    private static final int BITING_LIMIT_TICKS = 1500;

    private static final int CLUMSY_TICKS = 160;
    private static final int MIDWAY_TICKS = 600;
    private static final int SETTLE_TICKS = 40;
    /** The pick left with 100 points shatters in about 55 ticks of mining; the rest is the client going on with none. */
    private static final int SHATTER_TICKS = 200;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TwoPlayerServer two = TwoPlayerServer.start(context)) {
            BlockPos target = prepare(two, 1, X);
            context.getInput().lookAt(target);

            // A tool that cannot bite: the swing refuses on the client, and the block stays on both sides.
            hold(two, new ItemStack(Items.IRON_PICKAXE));
            mine(context, target, CLUMSY_TICKS, false, true);
            require(clientHolds(context, target), "an iron pick broke the crust on the client");
            require(serverHolds(two, 1, target), "an iron pick broke the crust on the server");

            // A pick that bites: the client's prediction and the server's check agree.
            hold(two, new ItemStack(Items.DIAMOND_PICKAXE));
            mine(context, target, MIDWAY_TICKS, false, true);
            // Half way, the player must be able to SEE the work: the pick worn on the client, and the crack well along.
            HandBreakChecks.requireWorkShows(
                    context,
                    MIDWAY_TICKS,
                    () -> two.server()
                            .computeOnServer(server ->
                                    realPlayer(server).getMainHandItem().getDamageValue()));
            int ticks = MIDWAY_TICKS + mine(context, target, BITING_LIMIT_TICKS - MIDWAY_TICKS, true, false);
            require(ticks >= 1000, "the crust broke after only " + ticks + " ticks, not about 1200");
            for (int tick = 0; tick < SETTLE_TICKS; tick++) {
                context.waitTicks(1); // tick-wait: the server's correction of a ghost break arrives within a few ticks
                require(
                        !clientHolds(context, target),
                        "the crust came back on the client after it broke (a ghost break)");
            }
            require(!serverHolds(two, 1, target), "the client broke the crust but the server still has it");
            context.takeScreenshot("hand-break-crust-gone");

            // A pick that cannot pay the cost shatters while the client is still mining, so the client never predicts
            // the
            // break: the block holds on both sides, and nothing comes back.
            BlockPos deep = prepare(two, 2, X + 40);
            context.getInput().lookAt(deep);
            hold(two, damaged(Items.NETHERITE_PICKAXE, 100));
            mine(context, deep, SHATTER_TICKS, false, true);
            require(clientHolds(context, deep), "a pick that could not pay broke the crust on the client");
            require(serverHolds(two, 2, deep), "a pick that could not pay broke the crust on the server");
            require(
                    two.server()
                            .computeOnServer(server ->
                                    realPlayer(server).getMainHandItem().isEmpty()),
                    "the pick that could not pay did not shatter");
        }
    }

    /** A room in {@code layer} at column {@code x} with a crust block in front of the real player, who holds nothing yet. Returns the crust's position. */
    private static BlockPos prepare(TwoPlayerServer two, int layer, int x) {
        return two.server().computeOnServer(server -> {
            ServerLevel level = TestLayers.level(server, layer);
            BlockPos floor = TestLayers.pos(layer, x, 80, Z);
            RoomCarver.carve(level, x - 4, x + 4, floor.getY() - 3, floor.getY(), Z - 5, Z + 5, Blocks.STONE);
            RoomCarver.carve(level, x - 4, x + 4, floor.getY() + 1, floor.getY() + 6, Z - 5, Z + 5, Blocks.AIR);
            BlockPos target = floor.offset(0, 2, -2);
            level.setBlock(target, LayerBlocks.BREACH_CRUST.defaultBlockState(), Block.UPDATE_ALL);
            ServerPlayer player = realPlayer(server);
            player.setGameMode(GameType.SURVIVAL);
            player.teleportTo(level, x + 0.5, floor.getY() + 1, Z + 0.5, Set.of(), 0, 0, true);
            return target;
        });
    }

    private static ItemStack damaged(Item item, int pointsLeft) {
        ItemStack stack = new ItemStack(item);
        stack.setDamageValue(stack.getMaxDamage() - pointsLeft);
        return stack;
    }

    private static void hold(TwoPlayerServer two, ItemStack stack) {
        two.server().runOnServer(server -> realPlayer(server).setItemInHand(InteractionHand.MAIN_HAND, stack));
    }

    /**
     * Mines {@code target} as the attack button does: one start, then one continue a tick. Returns the ticks until the client
     * saw the block gone, or {@code limit} when it never did; when {@code expectBreak} the block must go. {@code start} is false to go on with
     * a swing that an earlier call began.
     */
    private static int mine(
            ClientGameTestContext context, BlockPos target, int limit, boolean expectBreak, boolean start) {
        for (int tick = 0; tick < limit; tick++) {
            boolean first = start && tick == 0;
            context.runOnClient(client -> {
                if (first) {
                    ClientSession.gameMode(client).startDestroyBlock(target, Direction.SOUTH);
                } else {
                    ClientSession.gameMode(client).continueDestroyBlock(target, Direction.SOUTH);
                }
                ClientSession.player(client).swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            });
            context.waitTicks(1); // tick-wait: the mining is one call a tick
            if (!clientHolds(context, target)) {
                return tick;
            }
        }
        require(!expectBreak, "the client never broke the crust in " + limit + " ticks");
        return limit;
    }

    private static boolean clientHolds(ClientGameTestContext context, BlockPos target) {
        return context.computeOnClient(client -> {
            ClientLevel level = Objects.requireNonNull(client.level, "the client has no level");
            return level.getBlockState(target).is(LayerBlocks.BREACH_CRUST);
        });
    }

    private static boolean serverHolds(TwoPlayerServer two, int layer, BlockPos target) {
        return two.server()
                .computeOnServer(server ->
                        TestLayers.level(server, layer).getBlockState(target).is(LayerBlocks.BREACH_CRUST));
    }

    /** The real client's player, who joined before the mock ({@link TwoPlayerServer} joins the mock beside the first). */
    private static ServerPlayer realPlayer(MinecraftServer server) {
        return server.getPlayerList().getPlayers().getFirst();
    }
}
