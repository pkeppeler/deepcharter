package io.github.pkeppeler.deepcharter.test.support;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.layer.HandBreak;
import java.lang.reflect.Field;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * What a player must see while a pick brute-forces a block (#858): the pick worn on the client and the crack well
 * along.
 */
public final class HandBreakChecks {
    private HandBreakChecks() {}

    /**
     * Fails unless, after {@code ticks} of unbroken mining with a tool, the client's held pick shows wear and the
     * client's own destroy progress, which draws the crack, is about {@code ticks / 1200}. A swing the client
     * restarted would show neither. {@code serverWear} reads the damage of the server's held pick, to tell a pick
     * never worn from wear never shown.
     */
    public static void requireWorkShows(
            ClientGameTestContext context, int ticks, IntSupplier serverWear, Supplier<String> serverFacts) {
        int serverWorn = serverWear.getAsInt();
        require(
                serverWorn > 0,
                "the server has not worn the pick after " + ticks + " ticks of mining; the server says: "
                        + serverFacts.get() + "; the client says: " + clientFacts(context));
        int worn = context.computeOnClient(
                client -> ClientSession.player(client).getMainHandItem().getDamageValue());
        require(
                worn > 0,
                "the server wore the pick by " + serverWorn + " but the client's pick shows no wear after " + ticks
                        + " ticks of mining");
        float progress = context.computeOnClient(HandBreakChecks::destroyProgress);
        float expected = ticks / 1200.0F;
        require(
                Math.abs(progress - expected) < 0.1F,
                "the client's destroy progress is " + progress + " after " + ticks + " ticks of mining, expected about "
                        + expected);
    }

    /** The client's destroy progress now, for {@link #requireProgressedFrom}. */
    public static float clientProgress(ClientGameTestContext context) {
        return context.computeOnClient(HandBreakChecks::destroyProgress);
    }

    /**
     * Fails unless the client's destroy progress has moved on from {@code start} after {@code ticks} ticks of mining
     * with a tool: the pin is the only miner, so no progress means it is not mining the block.
     */
    public static void requireProgressedFrom(ClientGameTestContext context, float start, int ticks) {
        require(
                clientProgress(context) > start,
                "the client's destroy progress has not moved from " + start + " after " + ticks
                        + " ticks of mining with a tool; the client says: " + clientFacts(context));
    }

    /**
     * What the server holds of the mining of {@code target} by its first player, for a failure message: the game mode,
     * the held pick, the game mode's own mining state, and what the rule makes of the block and the pick. Run on the
     * server.
     */
    public static String serverFacts(MinecraftServer server, BlockPos target) {
        ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
        ServerLevel level = player.level();
        BlockState state = level.getBlockState(target);
        return "game mode " + player.gameMode.getGameModeForPlayer()
                + ", held " + player.getMainHandItem() + " with damage "
                + player.getMainHandItem().getDamageValue()
                + ", isDestroyingBlock " + gameModeField(player, "isDestroyingBlock")
                + ", destroyPos " + gameModeField(player, "destroyPos")
                + ", target " + target.toShortString()
                + ", block " + state.getBlock()
                + ", dimension " + level.dimension().identifier()
                + ", layerOf " + HandBreak.layerOf(level, target)
                + ", governs " + HandBreak.governs(player, level, target, state)
                + ", tool " + HandBreak.isTool(player.getMainHandItem());
    }

    /**
     * What the client holds of its own mining, for a failure message: the crosshair, the held pick and the game mode's
     * mining state.
     */
    public static String clientFacts(ClientGameTestContext context) {
        return context.computeOnClient(client -> "crosshair " + client.hitResult + ", held "
                + (client.player == null ? "(no player)" : client.player.getMainHandItem()) + ", game mode "
                + (client.gameMode == null ? "(none)" : client.gameMode.getPlayerMode())
                + ", isDestroying " + clientField(client, "isDestroying")
                + ", destroyBlockPos " + clientField(client, "destroyBlockPos")
                + ", destroyDelay " + clientField(client, "destroyDelay")
                + ", destroyProgress " + clientField(client, "destroyProgress")
                + ", attackHeld " + EvidencePins.attackHeld
                + ", missTime " + minecraftField(client, "missTime")
                + ", " + clientRule(client));
    }

    /**
     * What the rule makes of the block under the client's crosshair, as the client sees it: the swing's answer, and the
     * pieces of it.
     */
    private static String clientRule(Minecraft client) {
        if (!(client.hitResult instanceof BlockHitResult hit) || client.level == null || client.player == null) {
            return "no block under the crosshair";
        }
        BlockPos pos = hit.getBlockPos();
        BlockState state = client.level.getBlockState(pos);
        int layer = HandBreak.layerOf(client.level, pos);
        return "crosshair block " + state.getBlock() + " at " + pos.toShortString()
                + ", governs " + HandBreak.governs(client.player, client.level, pos, state)
                + ", layerOf " + layer
                + ", tool " + HandBreak.isTool(client.player.getMainHandItem())
                + ", swing answer "
                + AttackBlockCallback.EVENT
                        .invoker()
                        .interact(client.player, client.level, InteractionHand.MAIN_HAND, pos, hit.getDirection());
    }

    private static Object minecraftField(Minecraft client, String name) {
        try {
            Field field = Minecraft.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(client);
        } catch (ReflectiveOperationException e) {
            throw new LinkageError("the client's " + name + " could not be read", e);
        }
    }

    private static Object clientField(Minecraft client, String name) {
        try {
            Field field = MultiPlayerGameMode.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(ClientSession.gameMode(client));
        } catch (ReflectiveOperationException e) {
            throw new LinkageError("the client's " + name + " could not be read", e);
        }
    }

    private static Object gameModeField(ServerPlayer player, String name) {
        try {
            Field field = ServerPlayerGameMode.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(player.gameMode);
        } catch (ReflectiveOperationException e) {
            throw new LinkageError("the game mode's " + name + " could not be read", e);
        }
    }

    /** The client's own progress on the block it is mining, whose tenths are the stage of the crack. */
    private static float destroyProgress(Minecraft client) {
        try {
            Field field = MultiPlayerGameMode.class.getDeclaredField("destroyProgress");
            field.setAccessible(true);
            return field.getFloat(ClientSession.gameMode(client));
        } catch (ReflectiveOperationException e) {
            throw new LinkageError("the client's destroy progress could not be read", e);
        }
    }
}
