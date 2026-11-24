package io.github.pkeppeler.deepcharter.test.support;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.RetryOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;

/**
 * A {@link GameTestInfo} a test makes and ends itself, to stand in for a test that finished: what the runner does at the end of a test
 * (the {@code GameTestInfoMixin} hooks, the ticker's drop of pending work) can then be checked by one test, with no other test and no
 * batch layout (#1163). Never started: the ticker must not tick it before it is {@linkplain #finish finished}.
 */
public final class ProbeInfos {
    private ProbeInfos() {}

    /** A probe of the test {@code owner}, on its level; it has no structure, unless the caller gives it the owner's test block. */
    public static GameTestInfo of(GameTestInfo owner, ServerLevel level) {
        return new GameTestInfo(owner.getTestHolder(), Rotation.NONE, level, RetryOptions.noRetries());
    }

    // succeed() and fail() read the test block, which a bare probe has not got; finish() is what marks a test done.
    public static void finish(GameTestInfo info) {
        try {
            Method finish = GameTestInfo.class.getDeclaredMethod("finish");
            finish.setAccessible(true);
            finish.invoke(info);
        } catch (InvocationTargetException e) {
            // finish() itself threw: report its own failure, not a mapping change.
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(String.valueOf(cause), cause);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot call GameTestInfo.finish, likely a mapping change in Minecraft", e);
        }
    }
}
