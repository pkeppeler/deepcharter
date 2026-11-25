package io.github.pkeppeler.deepcharter.test.support;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Makes the client's network irregular on purpose: on every third client tick the client handles no entity move or teleport, and
 * on the next tick it handles those it held and that tick's own, so one tick shows nothing and the one after a double step. A real
 * connection does this whenever the client's tick and the server's packets drift against each other; the GameTest harness ticks the
 * server and the client in lockstep, so it never does by itself. Inert until {@link #start} and ended by {@link #stop}, which a test
 * calls in a {@code finally}. Read by {@code ClientPacketListenerMixin}. Client thread only.
 *
 * <p>It also counts the client ticks in a row that handled no entity move ({@link #resetMoveless}, {@link #movelessTicks}): the test
 * server is a real one, and on a loaded machine it delivers late on its own, on top of the stall a test injects.
 */
public final class PacketJitter {
    /** Every third client tick holds the moves. */
    private static final int PERIOD = 3;

    private static int period;
    /** The client game time until which every move is held; -1 when no stall is on. */
    private static long stallUntil = -1;

    /** Client ticks since the jitter was first registered: the clock the moveless count runs on. */
    private static long clientTick;

    /** The client tick the latest entity move was handled on. */
    private static long lastMoveTick;

    /** The most client ticks in a row without a handled move since {@link #resetMoveless}. */
    private static int movelessTicks;

    /** Client ticks every move is held for, from {@link #lag}; 0 when the connection is as fast as before. */
    private static int lagTicks;

    /** The client game time each move held by the lag is handed over at, in the order the moves came. */
    private static final List<Long> LAGGED_DUE = new ArrayList<>();

    private static boolean replaying;
    private static boolean registered;
    private static final List<Runnable> HELD = new ArrayList<>();

    private PacketJitter() {}

    /** From now on holds the moves of every third client tick until the tick after. */
    public static void start() {
        HELD.clear();
        period = PERIOD;
        registerOnce();
    }

    private static void registerOnce() {
        if (!registered) {
            registered = true;
            // An event cannot be unregistered; with no period and no stall it does nothing.
            ClientTickEvents.START_CLIENT_TICK.register(PacketJitter::replayIfDue);
        }
    }

    /**
     * Holds every entity move for the next {@code ticks} client ticks, then hands them all over in one tick: what a client sees when
     * the server falls behind and catches up, or the network stalls and delivers. Call it with the jitter off or on.
     */
    public static void stall(int ticks) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            throw new IllegalStateException("no client level to stall");
        }
        registerOnce();
        stallUntil = level.getGameTime() + ticks;
    }

    /**
     * From now on holds every entity move for {@code ticks} client ticks, one by one and in order, so the connection gets slower by
     * that much and stays so: the pod sees no move for {@code ticks} ticks, then a move a tick, each as stale as the stall was long
     * (#1112). A stall hands what it held over in one burst; this is a delay that does not catch up. {@link #endLag} ends it.
     */
    public static void lag(int ticks) {
        registerOnce();
        // A move the jitter holds now is one the server sent: hand it over, never drop it, or the client's position is
        // a move short for the rest of the drive (a lag that starts right after the skipped tick).
        replayHeld();
        LAGGED_DUE.clear();
        lagTicks = ticks;
    }

    /** Ends {@link #lag} and hands over every move it still holds. */
    public static void endLag() {
        lagTicks = 0;
        LAGGED_DUE.clear();
        replayHeld();
    }

    /** Starts counting the longest stretch of client ticks with no move of any entity handled from now. */
    public static void resetMoveless() {
        movelessTicks = 0;
        lastMoveTick = clientTick;
    }

    /**
     * The most client ticks in a row, since {@link #resetMoveless}, in which no entity move was handled; the stretch still open now
     * counts. The jitter's skipped tick is one of them, a stall adds its length, and a late server adds its own.
     */
    public static int movelessTicks() {
        return Math.max(movelessTicks, (int) (clientTick - lastMoveTick) - 1);
    }

    /** Ends the jitter and drops any move still held: a held move is one the client never sees, which is what a test that ended wants. */
    public static void stop() {
        period = 0;
        stallUntil = -1;
        lagTicks = 0;
        LAGGED_DUE.clear();
        HELD.clear();
    }

    /**
     * Replays the held packets at the start of a tick that is not a skipped one, so a move held on the last skipped tick of a stretch
     * with no later packet is delivered one tick late, as a real connection would, and not whenever the next packet comes.
     */
    private static void replayIfDue(Minecraft client) {
        clientTick++;
        ClientLevel level = client.level;
        if (level == null) {
            return;
        }
        if (lagTicks > 0) {
            replayLagged(level.getGameTime());
        } else if (stallUntil >= 0) {
            if (level.getGameTime() < stallUntil) {
                return;
            }
            stallUntil = -1;
            replayHeld();
        } else if (period != 0 && level.getGameTime() % period != 1) {
            replayHeld();
        }
    }

    /** Hands over the moves the lag holds that are due by {@code now}, in order. */
    private static void replayLagged(long now) {
        int due = 0;
        while (due < LAGGED_DUE.size() && LAGGED_DUE.get(due) <= now) {
            due++;
        }
        if (due == 0) {
            return;
        }
        List<Runnable> replay = List.copyOf(HELD.subList(0, due));
        HELD.subList(0, due).clear();
        LAGGED_DUE.subList(0, due).clear();
        replaying = true;
        try {
            replay.forEach(Runnable::run);
        } finally {
            replaying = false;
        }
    }

    /** Holds {@code handle} for the lag; false when there is none to hold it for. */
    private static boolean lags(Runnable handle) {
        Minecraft client = Minecraft.getInstance();
        if (lagTicks == 0 || replaying || client.level == null || !client.isSameThread()) {
            return false;
        }
        HELD.add(handle);
        LAGGED_DUE.add(client.level.getGameTime() + lagTicks);
        return true;
    }

    /** Notes that the client handles an entity move now. */
    private static void noteMove() {
        movelessTicks = movelessTicks();
        lastMoveTick = clientTick;
    }

    private static void replayHeld() {
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
    }

    /** Whether a stall holds a packet now, to be handled by {@code handle} when the stall ends. Only a stall holds it, never the jitter. */
    public static boolean stalls(Runnable handle) {
        Minecraft client = Minecraft.getInstance();
        if (lags(handle)) {
            return true;
        }
        if (stallUntil < 0 || replaying || client.level == null || !client.isSameThread()) {
            return false;
        }
        HELD.add(handle);
        return true;
    }

    /**
     * Whether the client is to skip a position packet now, and handle it later by running {@code handle}. A tick that is not skipped
     * replays the held packets first, in order, so entities see their moves and teleports in the order the server sent them: a
     * relative move replayed after a teleport that came later would land the entity off its mark for good.
     */
    public static boolean skips(Runnable handle) {
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        // A packet arrives on the network thread first, and vanilla hands it to the client thread by calling the
        // handler again.
        if (lags(handle)) {
            return true;
        }
        if ((period == 0 && stallUntil < 0) || replaying || level == null || !client.isSameThread()) {
            // A move handled on the client thread, whether it came straight in or was replayed; the network thread's
            // first call is not one.
            if (level != null && client.isSameThread()) {
                noteMove();
            }
            return false;
        }
        if (stallUntil >= 0) {
            HELD.add(handle);
            return true;
        }
        if (level.getGameTime() % period == 1) {
            HELD.add(handle);
            return true;
        }
        replayHeld();
        noteMove();
        return false;
    }
}
