package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.test.mixin.MinecraftInstanceAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jspecify.annotations.Nullable;

/**
 * Fails a client GameTest class that reads an item's components before the class has joined a world. Item components
 * are bound only once a world has loaded ("Components not bound yet", #1124), and the client JVM keeps them bound
 * afterwards, so a class with no world of its own passes behind a class that joined one and fails when sharding puts
 * it first (#1574).
 *
 * <p>{@code ClientTestLog.start} calls {@link #begin}, which notes the level still up from an earlier class, if any.
 * {@code WorldBeforeItemsMixin} calls {@link #check} on every {@code Holder.Reference.components()}. A class is
 * clear once the client has a level other than the one it started with, or once its own
 * {@code worldBuilder().create()} or {@code createServer} has returned ({@code WorldBuilderMixin}; a dedicated server
 * binds the components too). The check does not fire while that call runs, nor while a connection is being made
 * (a server join reads components before the level exists). Any other read on the client thread with no world of
 * the class's own fails, whatever is on the stack, so a screen opened with {@code setScreen} and drawn by the
 * render loop is caught too.
 */
public final class WorldBeforeItems {
    private static volatile @Nullable String current;
    private static volatile @Nullable ClientLevel levelAtStart;
    private static volatile boolean joined;
    private static volatile boolean creating;

    private WorldBeforeItems() {}

    /** A test class starts: until it has a world of its own, reading an item's components fails. */
    public static void begin(String testClass) {
        current = testClass;
        levelAtStart = MinecraftInstanceAccess.deepcharter$instance().level;
        joined = false;
        creating = false;
    }

    /** {@code worldBuilder().create()} starts: the world loads, and reads components on the way. */
    public static void creating() {
        creating = true;
    }

    /** {@code worldBuilder().create()} returned: the class has its own world. */
    public static void created() {
        creating = false;
        joined = true;
    }

    /** The mixin's check. Throws, naming the class, when the running test class has joined no world of its own. */
    public static void check() {
        String test = current;
        if (test == null || joined || creating) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        if (level != null && level != levelAtStart) {
            joined = true;
            return;
        }
        boolean joining = level == null && client.getConnection() != null;
        if (client.isSameThread() && !joining) {
            throw new IllegalStateException(
                    test + " reads an item's components before it has joined a world; the client"
                            + " JVM keeps them bound after an earlier class's world, so this passes only behind one. Join a"
                            + " world first (context.worldBuilder().create()) (#1574).");
        }
    }
}
