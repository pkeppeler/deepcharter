package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.layer.LightSync;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

/**
 * Waits in a client GameTest for a value the server syncs, or for any other state the test awaits. The wait is on the wall clock, not on ticks: the client and
 * server tick unthrottled, so a tick budget runs out faster the more the Mac is loaded. Pod parts, hull and cargo reach
 * the client in separate packets, so seeing one of them does not mean the others have arrived.
 */
public final class ClientWait {
    private ClientWait() {}

    /**
     * Waits until {@code synced} holds on the client, for up to {@link FarChunks#WAIT_SECONDS}.
     *
     * @param what names the awaited state in the failure message
     * @param actual describes what the client has, read on the client; it goes in the failure message
     * @throws AssertionError if the deadline passes first
     */
    public static void until(
            ClientGameTestContext context,
            String what,
            Predicate<Minecraft> synced,
            Function<Minecraft, String> actual) {
        until(context, what, () -> context.computeOnClient(synced::test), () -> context.computeOnClient(actual::apply));
    }

    /**
     * Like {@link #until(ClientGameTestContext, String, Predicate, Function)}, with the client's level, player, vehicle and screen
     * as the failure's description. For a wait whose condition says it all.
     */
    public static void until(ClientGameTestContext context, String what, Predicate<Minecraft> synced) {
        until(context, what, synced, ClientWait::describe);
    }

    /** Waits until the client shows a {@code screen}, for up to {@link FarChunks#WAIT_SECONDS}. The wall-clock {@code waitForScreen}. */
    public static void screen(ClientGameTestContext context, Class<? extends Screen> screen) {
        until(context, "the " + screen.getSimpleName() + " screen", client -> screen.isInstance(client.gui.screen()));
    }

    /**
     * Waits until {@code condition} holds, for up to {@link FarChunks#WAIT_SECONDS}. The condition runs on the test thread, so it may
     * read server state through {@code computeOnServer}, which a {@link Predicate} on the client thread may not.
     */
    public static void until(
            ClientGameTestContext context, String what, BooleanSupplier condition, Supplier<String> actual) {
        FarChunks.Deadline deadline = FarChunks.deadline();
        while (!condition.getAsBoolean()) {
            if (deadline.expired()) {
                throw new AssertionError("Timed out after " + FarChunks.WAIT_SECONDS + " s waiting for " + what
                        + "; it had " + actual.get() + "; LightSync holds: " + LightSync.heldChunks());
            }
            try {
                Thread.sleep(FarChunks.POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for " + what, e);
            }
            context.waitTicks(1);
        }
    }

    /**
     * Waits until the client holds every chunk the square of {@code radius} blocks round {@code (x, z)} touches. Call it before seating players at a
     * spot just built: the server tracks an entity for a player only after sending that entity's chunk, so a chunk still queued makes the other
     * rider vanish from the client for a tick.
     */
    public static void chunksAround(ClientGameTestContext context, int x, int z, int radius) {
        int minX = SectionPos.blockToSectionCoord(x - radius);
        int maxX = SectionPos.blockToSectionCoord(x + radius);
        int minZ = SectionPos.blockToSectionCoord(z - radius);
        int maxZ = SectionPos.blockToSectionCoord(z + radius);
        until(
                context,
                "the chunks " + minX + "," + minZ + " to " + maxX + "," + maxZ + " on the client",
                client -> missingChunks(client, minX, maxX, minZ, maxZ).isEmpty(),
                client -> "chunks missing: " + missingChunks(client, minX, maxX, minZ, maxZ) + ", " + describe(client));
    }

    /**
     * Waits until the server has settled the light of the chunks round the square of {@code radius} at ({@code x},
     * {@code z}) in {@code layer}. Call it after a carve, before a pod with a pilot moves in: a rider sends no move
     * packets, so a pod dropped while a chunk send is queued stays dropped (#1249).
     */
    public static void lightSettled(
            ClientGameTestContext context, TestServerContext server, int layer, int x, int z, int radius) {
        int minX = SectionPos.blockToSectionCoord(x - radius) - 1;
        int maxX = SectionPos.blockToSectionCoord(x + radius) + 1;
        int minZ = SectionPos.blockToSectionCoord(z - radius) - 1;
        int maxZ = SectionPos.blockToSectionCoord(z + radius) + 1;
        until(
                context,
                "the light of the chunks " + minX + "," + minZ + " to " + maxX + "," + maxZ
                        + " to settle on the server",
                () -> server.computeOnServer(
                        srv -> unsettledChunks(TestLayers.level(srv, layer), minX, maxX, minZ, maxZ)
                                .isEmpty()),
                () -> "unsettled chunks "
                        + server.computeOnServer(
                                srv -> unsettledChunks(TestLayers.level(srv, layer), minX, maxX, minZ, maxZ)));
    }

    private static List<ChunkPos> unsettledChunks(ServerLevel level, int minX, int maxX, int minZ, int maxZ) {
        List<ChunkPos> unsettled = new ArrayList<>();
        for (int chunkX = minX; chunkX <= maxX; chunkX++) {
            for (int chunkZ = minZ; chunkZ <= maxZ; chunkZ++) {
                ChunkPos pos = new ChunkPos(chunkX, chunkZ);
                boolean queued = false;
                for (ServerPlayer player : level.players()) {
                    queued |= player.connection.chunkSender.isPending(pos.pack());
                }
                if (queued || LightSync.watching(level, pos)) {
                    unsettled.add(pos);
                }
            }
        }
        return unsettled;
    }

    private static List<ChunkPos> missingChunks(Minecraft client, int minX, int maxX, int minZ, int maxZ) {
        List<ChunkPos> missing = new ArrayList<>();
        for (int chunkX = minX; chunkX <= maxX; chunkX++) {
            for (int chunkZ = minZ; chunkZ <= maxZ; chunkZ++) {
                if (client.level == null || !client.level.getChunkSource().hasChunk(chunkX, chunkZ)) {
                    missing.add(new ChunkPos(chunkX, chunkZ));
                }
            }
        }
        return missing;
    }

    /** What the client is showing, for a failure message: the level, whether it has a player, what the player rides, and the open screen. */
    public static String describe(Minecraft client) {
        String level = client.level == null
                ? "no level"
                : client.level.dimension().identifier().toString();
        String player = client.player == null
                ? "no player"
                : "a player at " + client.player.blockPosition().toShortString() + " in "
                        + (client.gameMode == null ? "no game mode" : client.gameMode.getPlayerMode());
        String vehicle = client.player == null || client.player.getVehicle() == null
                ? "no vehicle"
                : client.player.getVehicle().getType().toString();
        String screen = client.gui.screen() == null
                ? "no screen"
                : client.gui.screen().getClass().getSimpleName();
        return level + ", " + player + ", " + vehicle + ", " + screen;
    }
}
