package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.EvidencePins;
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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Client GameTests for #858 (ADR 0069), in two classes, {@link HandBreakClientTest} and {@link HandBreakIronClientTest}, so that neither outruns a CI shard (#1574): the real client mines pod-only crust as the attack button does, and the client
 * and the server agree. A diamond pick breaks the block on both sides, with no ghost break and no block that comes
 * back, and so does an iron pick, which the block destroys as it goes; a bare hand breaks it on neither, however long
 * it swings.
 */
abstract class HandBreakCases implements FabricClientGameTest {
    static final int X = 9300;
    static final int Z = 9300;
    /** The break time is 1200 ticks; the margin covers the tick the client starts on and the one it sends STOP on. */
    static final int BITING_LIMIT_TICKS = 1500;

    /** The ticks after which a tool must have moved the client's progress: the pin is the only miner. */
    static final int PROGRESS_TICKS = 40;

    static final int BARE_HAND_TICKS = 160;
    static final int MIDWAY_TICKS = 600;
    static final int SETTLE_TICKS = 40;

    @Override
    public final void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TwoPlayerServer two = TwoPlayerServer.start(context)) {
            try {
                cases(context, two);
            } finally {
                EvidencePins.attackHeld = false;
            }
        }
    }

    /** What this class mines, with the two players in. */
    abstract void cases(ClientGameTestContext context, TwoPlayerServer two);

    /**
     * A room in {@code layer} at column {@code x} with a crust block in front of the real player, who holds nothing
     * yet. Returns the crust's position.
     */
    static BlockPos prepare(TwoPlayerServer two, int layer, int x) {
        return two.server().computeOnServer(server -> {
            ServerLevel level = TestLayers.level(server, layer);
            BlockPos floor = TestLayers.pos(layer, x, 80, Z);
            RoomCarver.carve(level, x - 4, x + 4, floor.getY() - 3, floor.getY(), Z - 5, Z + 5, Blocks.STONE);
            RoomCarver.carve(level, x - 4, x + 4, floor.getY() + 1, floor.getY() + 6, Z - 5, Z + 5, Blocks.AIR);
            BlockPos target = floor.offset(0, 2, -2);
            level.setBlock(target, LayerBlocks.BREACH_CRUST.defaultBlockState(), Block.UPDATE_ALL);
            ServerPlayer player = realPlayer(server);
            player.setGameMode(GameType.SURVIVAL);
            // Facing north, at the crust: a teleport that reaches the client after the test aims must not turn the
            // player away.
            player.teleportTo(level, x + 0.5, floor.getY() + 1, Z + 0.5, Set.of(), 180, 0, true);
            return target;
        });
    }

    static void hold(TwoPlayerServer two, ItemStack stack) {
        two.server().runOnServer(server -> realPlayer(server).setItemInHand(InteractionHand.MAIN_HAND, stack));
    }

    /**
     * Mines for up to {@code limit} ticks with the attack button held (the pin is on, see {@link
     * EvidencePins#attackHeld}). With {@code bites}, the client's progress must have moved by then (the pin is the
     * only miner, nothing steps the game mode here). Returns the ticks until the client saw the block gone, or {@code
     * limit} when it never did; when {@code expectBreak} the block must go.
     */
    static int mine(
            ClientGameTestContext context, BlockPos target, int limit, boolean bites, boolean expectBreak) {
        float start = HandBreakChecks.clientProgress(context);
        for (int tick = 0; tick < limit; tick++) {
            if (bites && tick == PROGRESS_TICKS) {
                HandBreakChecks.requireProgressedFrom(context, start, tick);
            }
            context.waitTicks(1); // tick-wait: the mining is one step a tick
            if (!clientHolds(context, target)) {
                return tick;
            }
        }
        require(!expectBreak, "the client never broke the crust in " + limit + " ticks");
        return limit;
    }

    /**
     * Waits for the client to hold the crust the server placed: a block set on the server reaches the client a few
     * ticks later, and a new layer is a new level.
     */
    static void awaitCrust(ClientGameTestContext context, BlockPos target) {
        ClientWait.until(
                context,
                "the crust at " + target.toShortString() + " in the client's level",
                client -> client.level != null
                        && client.level.getBlockState(target).is(LayerBlocks.BREACH_CRUST)
                        && client.player != null
                        && client.player.blockPosition().distSqr(target) < 36);
        context.getInput().lookAt(target);
        // The crosshair, which the game's own mining follows, must be on the crust before anything is mined.
        ClientWait.until(
                context,
                "the crosshair on the crust at " + target.toShortString(),
                client -> client.hitResult instanceof BlockHitResult hit
                        && hit.getBlockPos().equals(target),
                client -> "the crosshair is " + client.hitResult
                        + (client.hitResult instanceof BlockHitResult hit
                                ? " at " + hit.getBlockPos().toShortString() + " on "
                                        + Objects.requireNonNull(client.level).getBlockState(hit.getBlockPos())
                                : "")
                        + ", the player at "
                        + (client.player == null
                                ? "(none)"
                                : client.player.position() + " facing " + client.player.getYRot() + "/"
                                        + client.player.getXRot()));
    }

    /**
     * What the client holds of the block, for a failure message: its block, the player's game mode and the held item.
     */
    static String clientFacts(ClientGameTestContext context, BlockPos target) {
        return context.computeOnClient(
                client -> client.level == null || client.player == null || client.gameMode == null
                        ? "no level or player yet"
                        : "block " + client.level.getBlockState(target) + " at " + target.toShortString()
                                + ", game mode "
                                + client.gameMode.getPlayerMode() + ", held " + client.player.getMainHandItem()
                                + ", player at " + client.player.blockPosition().toShortString());
    }

    static boolean clientHolds(ClientGameTestContext context, BlockPos target) {
        return context.computeOnClient(client -> {
            ClientLevel level = Objects.requireNonNull(client.level, "the client has no level");
            return level.getBlockState(target).is(LayerBlocks.BREACH_CRUST);
        });
    }

    static boolean serverHolds(TwoPlayerServer two, int layer, BlockPos target) {
        return two.server()
                .computeOnServer(server ->
                        TestLayers.level(server, layer).getBlockState(target).is(LayerBlocks.BREACH_CRUST));
    }

    /**
     * The real client's player, who joined before the mock ({@link TwoPlayerServer} joins the mock beside the first).
     */
    static ServerPlayer realPlayer(MinecraftServer server) {
        return server.getPlayerList().getPlayers().getFirst();
    }
}
