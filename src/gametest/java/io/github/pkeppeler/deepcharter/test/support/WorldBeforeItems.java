package io.github.pkeppeler.deepcharter.test.support;

import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * Fails a client GameTest class that reads an item's components before the class has joined a world. Item components are bound only once
 * a world has loaded ("Components not bound yet", #1124), and the client JVM keeps them bound afterwards, so a class with no world of its
 * own passes behind a class that joined one and fails when sharding puts it first (#1574). {@code ClientTestLog.start} calls
 * {@link #begin}; {@code WorldBeforeItemsMixin} calls {@link #check} on every {@code Holder.Reference.components()} on the client thread, when test code is on the stack. Once the
 * client has a level the class is clear for good, so a class that joins a world first is never slowed or stopped.
 */
public final class WorldBeforeItems {
    private static volatile @Nullable String current;
    private static volatile boolean joined;
    private static final String TEST_PACKAGE = "io.github.pkeppeler.deepcharter.test.";

    private WorldBeforeItems() {}

    /** A test class starts: until it has a world, reading an item's components fails. */
    public static void begin(String testClass) {
        current = testClass;
        joined = false;
    }

    /** The mixin's check. Throws, naming the class, when the running test class has joined no world yet. */
    public static void check() {
        String test = current;
        if (test == null || joined) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.level != null) {
            joined = true;
            return;
        }
        if (client.isSameThread() && calledFromTestCode()) {
            throw new IllegalStateException(
                    test + " reads an item's components before it has joined a world; the client JVM"
                            + " keeps them bound after an earlier class's world, so this passes only behind one. Join a world first"
                            + " (context.worldBuilder().create()) (#1574).");
        }
    }

    /**
     * Whether a test class's code is on the stack. Loading a world binds the components, and reads them on this thread while the client
     * has no level yet, with no test frame in between (the test only waits for it); a test's own draw or stack has one.
     */
    private static boolean calledFromTestCode() {
        String guard = WorldBeforeItems.class.getName();
        return StackWalker.getInstance()
                .walk(frames -> frames.map(StackWalker.StackFrame::getClassName)
                        .anyMatch(name -> name.startsWith(TEST_PACKAGE) && !name.equals(guard)));
    }
}
