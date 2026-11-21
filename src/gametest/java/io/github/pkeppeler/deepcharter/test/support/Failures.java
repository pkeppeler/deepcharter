package io.github.pkeppeler.deepcharter.test.support;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The failure every server GameTest throws. {@code helper.assertionException(String, Object...)} leaves the placeholders unfilled in
 * the report, so the message is formatted here and handed over as a literal.
 */
public final class Failures {
    private Failures() {}

    @FormatMethod
    public static RuntimeException failure(
            GameTestHelper helper, @FormatString String format, @Nullable Object... args) {
        return helper.assertionException(Component.literal(String.format(format, args)));
    }

    @FormatMethod
    public static void expect(
            GameTestHelper helper, boolean condition, @FormatString String format, @Nullable Object... args) {
        if (!condition) {
            throw failure(helper, format, args);
        }
    }
}
