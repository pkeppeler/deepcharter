package io.github.pkeppeler.deepcharter.test.support;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;

/**
 * Makes the client's network irregular on purpose: on every {@code period}th client tick the client handles no relative entity move, and
 * on the next tick it handles those it held and that tick's own, so one tick shows nothing and the one after a double step. A real
 * connection does this whenever the client's tick and the server's packets drift against each other; the GameTest harness ticks the
 * server and the client in lockstep, so it never does by itself. Inert until {@link #start} and ended by {@link #stop}, which a test
 * calls in a {@code finally}. Read by {@code ClientPacketListenerMixin}. Client thread only.
 */
public final class PacketJitter {
    private static int period;
    private static boolean replaying;
    private static final List<Runnable> HELD = new ArrayList<>();

    private PacketJitter() {}

    /** From now on holds the moves of every {@code everyTicks}th client tick (2 or more) until the tick after. */
    public static void start(int everyTicks) {
        if (everyTicks < 2) {
            throw new IllegalArgumentException("packet jitter needs a period of at least 2 ticks, got " + everyTicks);
        }
        period = everyTicks;
    }

    /** Ends the jitter and drops any move still held: a held move is one the client never sees, which is what a test that ended wants. */
    public static void stop() {
        period = 0;
        HELD.clear();
    }

    /**
     * Whether {@code listener} is to skip {@code packet} now. A tick that is not skipped replays the held moves first, in order, so
     * entities see their moves in the order the server sent them.
     */
    public static boolean skips(ClientPacketListener listener, ClientboundMoveEntityPacket packet) {
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        // A packet arrives on the network thread first, and vanilla hands it to the client thread by calling the
        // handler again.
        if (period == 0 || replaying || level == null || !client.isSameThread()) {
            return false;
        }
        if (level.getGameTime() % period == 1) {
            HELD.add(() -> listener.handleMoveEntity(packet));
            return true;
        }
        if (!HELD.isEmpty()) {
            List<Runnable> replay = List.copyOf(HELD);
            HELD.clear();
            replaying = true;
            try {
                replay.forEach(Runnable::run);
            } finally {
                replaying = false;
            }
        }
        return false;
    }
}
