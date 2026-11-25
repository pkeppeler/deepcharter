package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Founds charters and joins members through the real {@link Charters} calls, failing the test with the refusal when one is refused. */
public final class TestCharters {
    private TestCharters() {}

    /** Founds a charter under a unique name with {@code director} as Director, and returns its id. */
    public static CharterId found(GameTestHelper helper, MinecraftServer server, ServerPlayer director) {
        Charters.found(
                        server,
                        director.getUUID(),
                        "Test " + UUID.randomUUID().toString().substring(0, 8))
                .ifPresent(refusal -> {
                    throw Failures.failure(helper, "founding should succeed, was refused: %s", refusal);
                });
        return Charters.charterOfOrThrow(server, director.getUUID())
                .orElseThrow()
                .id();
    }

    /** Applies as {@code member} to the charter {@code id} and has {@code director} approve it. */
    public static void join(
            GameTestHelper helper, MinecraftServer server, CharterId id, ServerPlayer director, ServerPlayer member) {
        Charters.apply(server, member.getUUID(), id).ifPresent(refusal -> {
            throw Failures.failure(helper, "applying should succeed, was refused: %s", refusal);
        });
        Charters.approve(server, director.getUUID(), member.getUUID()).ifPresent(refusal -> {
            throw Failures.failure(helper, "approving should succeed, was refused: %s", refusal);
        });
    }
}
