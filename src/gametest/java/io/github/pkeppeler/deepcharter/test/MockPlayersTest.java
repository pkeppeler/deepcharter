package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.ProbeInfos;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import io.github.pkeppeler.deepcharter.test.support.TickLists;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestTicker;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.util.Util;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Proves the {@link MockPlayers} harness: a mock player is a real player that stays connected. */
public class MockPlayersTest {
    private static final AtomicReference<@Nullable Runnable> AFTER_TICK = new AtomicReference<>();

    static {
        // Fabric events cannot be unregistered, so one listener runs whatever is pending and holds nothing after.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            Runnable pending = AFTER_TICK.getAndSet(null);
            if (pending != null) {
                pending.run();
            }
        });
    }

    private static final int TICKS = 1200;
    private static final int SURVIVAL_SECONDS = 3;
    /** The old bare join died at tick 242; a slow server covers fewer ticks in the wall-clock floor, so the test also needs this many. */
    private static final int MIN_TICKS = 400;

    @GameTest(maxTicks = TICKS + 100)
    public void mockPlayerSurvivesOneMinuteOfTicks(GameTestHelper helper) {
        MockPlayer mock = MockPlayers.join(helper, "stays-online");
        if (!mock.isOnline()) {
            throw failure(helper, "mock player is not on the player list after joining");
        }
        helper.runAfterDelay(TICKS, () -> {
            if (!mock.isOnline()) {
                throw failure(helper, "mock player left the player list within %d ticks", TICKS);
            }
            helper.succeed();
        });
    }

    /**
     * A mock joined through the server path stands where it was told to and is still alive a few
     * seconds later. Joined inside the surface rock it suffocates, and a dead mock cannot be teleported.
     * Waits by wall clock, because the GameTest server ticks unthrottled.
     */
    @GameTest(maxTicks = 20_000)
    public void serverJoinedMockSurvivesAtItsSpawn(GameTestHelper helper) {
        Vec3 spawn = helper.absoluteVec(MockPlayers.STRUCTURE_SPAWN);
        MockPlayer mock = MockPlayers.join(helper.getLevel().getServer(), "server-joined", helper.getLevel(), spawn);
        mock.player().setGameMode(GameType.SURVIVAL);
        long deadline = System.nanoTime() + SURVIVAL_SECONDS * 1_000_000_000L;
        TestTicks.each(helper, () -> {
            if (!mock.player().isAlive()) {
                throw failure(
                        helper,
                        "the server-joined mock died at %s",
                        mock.player().position());
            }
            if (System.nanoTime() - deadline <= 0 || helper.getTick() < MIN_TICKS) {
                sleepOneTick();
            } else {
                if (mock.player().position().distanceTo(spawn) > 0.5) {
                    throw failure(
                            helper,
                            "the mock is at %s, not at its spawn %s",
                            mock.player().position(),
                            spawn);
                }
                mock.leave();
                helper.succeed();
            }
        });
    }

    @GameTest(maxTicks = 20_000)
    public void helperJoinedMockSurvivesInsideTheStructure(GameTestHelper helper) {
        MockPlayer mock = MockPlayers.join(helper, "helper-joined");
        mock.player().setGameMode(GameType.SURVIVAL);
        long deadline = System.nanoTime() + SURVIVAL_SECONDS * 1_000_000_000L;
        TestTicks.each(helper, () -> {
            if (!mock.player().isAlive()) {
                throw failure(
                        helper,
                        "the helper-joined mock died at %s",
                        mock.player().position());
            }
            if (System.nanoTime() - deadline <= 0 || helper.getTick() < MIN_TICKS) {
                sleepOneTick();
            } else {
                Vec3 at = mock.player().position();
                if (at.distanceTo(helper.absoluteVec(MockPlayers.STRUCTURE_SPAWN)) > 5) {
                    throw failure(helper, "the helper-joined mock is at %s, outside the structure", at);
                }
                helper.succeed();
            }
        });
    }

    // The GameTest server ticks unthrottled, so a few seconds of ticks pass in milliseconds unless each tick sleeps.
    private static void sleepOneTick() {
        try {
            Thread.sleep(FarChunks.POLL_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A mock owned by a test is removed once that test is done: the sweep, not the test, cleans up. */
    @GameTest
    public void mockPlayerIsRemovedWhenItsOwnerIsDone(GameTestHelper helper) {
        AtomicBoolean ownerDone = new AtomicBoolean();
        MockPlayer mock = MockPlayers.join(
                helper.getLevel().getServer(),
                "owned",
                helper.getLevel(),
                helper.absoluteVec(MockPlayers.STRUCTURE_SPAWN),
                ownerDone::get);
        helper.runAfterDelay(5, () -> {
            if (!mock.isOnline()) {
                throw failure(helper, "mock player left before its owner was done");
            }
            ownerDone.set(true);
            helper.runAfterDelay(5, () -> {
                if (mock.isOnline()) {
                    throw failure(helper, "mock player outlived its owner");
                }
                helper.succeed();
            });
        });
    }

    /**
     * Server GameTests finish in seconds, so the 15 s keep-alive never comes due on its own.
     * Backdate the listener's last keep-alive instead, then poll every tick: the server must
     * send one, and the mock must answer it, or the player would be kicked 15 s later.
     */
    @GameTest(maxTicks = 100)
    public void mockPlayerAnswersKeepAlives(GameTestHelper helper) {
        MockPlayer mock = MockPlayers.join(helper, "answers-keep-alives");
        long backdated = Util.getMillis() - 16_000L;
        try {
            setField(mock.player().connection, "keepAliveTime", backdated);
        } catch (ReflectiveOperationException e) {
            throw reflectionFailure("keepAliveTime", e);
        }
        helper.succeedWhen(() -> {
            try {
                long sentAt = (long) getField(mock.player().connection, "keepAliveTime");
                boolean pending = (boolean) getField(mock.player().connection, "keepAlivePending");
                if (sentAt <= backdated) {
                    throw failure(helper, "the server never sent a keep-alive to the mock player");
                }
                if (pending) {
                    throw failure(helper, "the mock player left a keep-alive unanswered");
                }
                if (!mock.isOnline()) {
                    throw failure(helper, "mock player left the player list");
                }
            } catch (ReflectiveOperationException e) {
                throw reflectionFailure("keepAliveTime or keepAlivePending", e);
            }
        });
    }

    /** The helper's contract: a default mock can be hurt, an unloaded one is immune to everything. */
    @GameTest
    public void loadedMockTakesDamageAndUnloadedDoesNot(GameTestHelper helper) {
        ServerPlayer loaded = MockPlayers.join(helper, "loaded").player();
        ServerPlayer unloaded = MockPlayers.joinUnloaded(helper, "unloaded").player();
        // The server's default game mode is not survival, and a creative player takes no damage either.
        loaded.setGameMode(GameType.SURVIVAL);
        unloaded.setGameMode(GameType.SURVIVAL);
        float loadedBefore = loaded.getHealth();
        float unloadedBefore = unloaded.getHealth();
        loaded.hurtServer(helper.getLevel(), helper.getLevel().damageSources().generic(), 2.0F);
        unloaded.hurtServer(helper.getLevel(), helper.getLevel().damageSources().generic(), 2.0F);
        if (loaded.getHealth() >= loadedBefore) {
            throw failure(helper, "loaded mock took no damage: %s of %s", loaded.getHealth(), loadedBefore);
        }
        if (unloaded.getHealth() != unloadedBefore) {
            throw failure(helper, "unloaded mock took damage: %s of %s", unloaded.getHealth(), unloadedBefore);
        }
        helper.succeed();
    }

    /** Vanilla only grants a joining player 60 ticks of load immunity, so an unloaded mock is not a safe place to wait. */
    @GameTest(maxTicks = 200)
    public void unloadedMockBecomesDamageableAfterVanillasLoadTimeout(GameTestHelper helper) {
        ServerPlayer unloaded = MockPlayers.joinUnloaded(helper, "timing-out").player();
        unloaded.setGameMode(GameType.SURVIVAL);
        helper.runAfterDelay(100, () -> {
            float before = unloaded.getHealth();
            unloaded.hurtServer(
                    helper.getLevel(), helper.getLevel().damageSources().generic(), 2.0F);
            if (unloaded.getHealth() >= before) {
                throw failure(
                        helper,
                        "an unloaded mock was still immune after 100 ticks: %s of %s",
                        unloaded.getHealth(),
                        before);
            }
            helper.succeed();
        });
    }

    /** A crossing the mock never confirms leaves a player immune, so a loaded mock confirms it itself. */
    @GameTest
    public void loadedMockStaysDamageableAfterChangingDimension(GameTestHelper helper) {
        ServerPlayer player = MockPlayers.join(helper, "crosser").player();
        player.setGameMode(GameType.SURVIVAL);
        ServerLevel other = TestLayers.level(helper.getLevel().getServer(), 1);
        player.teleport(
                new TeleportTransition(other, new Vec3(0.5, 8, 0.5), Vec3.ZERO, 0, 0, TeleportTransition.DO_NOTHING));
        if (!player.isChangingDimension()) {
            throw failure(helper, "the crossing did not leave the player mid-change, so this test proves nothing");
        }
        helper.runAfterDelay(2, () -> {
            float before = player.getHealth();
            player.hurtServer(other, other.damageSources().generic(), 2.0F);
            if (player.getHealth() >= before) {
                throw failure(helper, "loaded mock is immune after changing dimension");
            }
            helper.succeed();
        });
    }

    private static IllegalStateException reflectionFailure(String field, ReflectiveOperationException e) {
        return new IllegalStateException(
                "Cannot access ServerCommonPacketListenerImpl." + field + ", likely a mapping change in Minecraft", e);
    }

    private static Field field(String name) throws NoSuchFieldException {
        Field field = ServerCommonPacketListenerImpl.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void setField(Object target, String name, long value) throws ReflectiveOperationException {
        field(name).setLong(target, value);
    }

    private static Object getField(Object target, String name) throws ReflectiveOperationException {
        return field(name).get(target);
    }

    /** A mock that left is dropped from the tick list's stale snapshot (#723). */
    @GameTest
    public void aLeftMockIsNotHeldByTheTickList(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MockPlayer mock = MockPlayers.join(helper, "retention-ticklist");
        ServerPlayer player = mock.player();
        TickLists.snapshotAsIfChangedWhileTicking(level);
        if (!TickLists.retains(level, player)) {
            throw failure(helper, "the snapshot should hold the live mock");
        }
        mock.leave();
        helper.succeedWhen(() -> {
            for (ServerLevel each : level.getServer().getAllLevels()) {
                if (TickLists.retains(each, player)) {
                    throw failure(
                            helper,
                            "the tick list of %s still holds the mock that left",
                            each.dimension().identifier());
                }
            }
        });
    }

    /**
     * A finished test lets go of its mock player (#656, #1163). It stands up its own probes, so it needs no other test and no batch
     * layout: three {@link GameTestInfo}s that never run, each owning a mock and ending with a callback still pending that captures it.
     * They end and join the runner's ticker outside its tick, as a test the server's task queue finishes does, and the ticker's drop of a
     * done test's pending work, with the mock sweep, must leave nothing holding the mocks, which it reads through weak references.
     */
    @GameTest(maxTicks = 200)
    public void finishedTestDoesNotRetainItsMock(GameTestHelper helper) {
        // The runner keeps every finished test to the end of the run, so these stay reachable: only dropping what they
        // have pending frees the mocks.
        List<GameTestInfo> finished = new ArrayList<>();
        List<WeakReference<ServerPlayer>> probes = startProbes(helper, finished);
        helper.succeedWhen(() -> {
            if (finished.stream().anyMatch(info -> !info.isDone())) {
                throw failure(helper, "waiting for the probes to end");
            }
            for (WeakReference<ServerPlayer> probe : probes) {
                System.gc();
                if (probe.get() != null) {
                    throw failure(helper, "a finished test still holds its mock ServerPlayer");
                }
            }
        });
    }

    /** Starts the probes, adding them to {@code infos}, and returns weak references to their mocks, so that the caller holds no mock. */
    private static List<WeakReference<ServerPlayer>> startProbes(GameTestHelper helper, List<GameTestInfo> infos) {
        GameTestInfo owner = MockPlayers.testInfo(helper);
        ServerLevel level = helper.getLevel();
        Vec3 spawn = helper.absoluteVec(MockPlayers.STRUCTURE_SPAWN);
        List<WeakReference<ServerPlayer>> weak = new ArrayList<>();

        // Set before any probe exists, so the probes made before a failing step are still finished: a mock left joined
        // would stay on the player list for the rest of the run. The ticker iterates its infos while it ticks, so they
        // join it from an end-of-tick event, already done.
        AFTER_TICK.set(() -> {
            RuntimeException failure = null;
            for (GameTestInfo info : infos) {
                try {
                    ProbeInfos.finish(info);
                    GameTestTicker.SINGLETON.add(info);
                } catch (RuntimeException e) {
                    if (failure == null) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        });

        // A callback scheduled for the tick after the test ends, as TestTicks.each leaves.
        GameTestInfo each = ProbeInfos.of(owner, level);
        GameTestHelper eachHelper = new GameTestHelper(each);
        infos.add(each);
        MockPlayer eachMock = MockPlayers.join(level.getServer(), "retention-each", level, spawn, each::isDone);
        TestTicks.each(eachHelper, eachMock.player()::getName);
        weak.add(new WeakReference<>(eachMock.player()));

        // A callback far in the future, never reached.
        GameTestInfo delayed = ProbeInfos.of(owner, level);
        infos.add(delayed);
        MockPlayer delayedMock =
                MockPlayers.join(level.getServer(), "retention-delayed", level, spawn, delayed::isDone);
        new GameTestHelper(delayed)
                .runAfterDelay(100_000, () -> delayedMock.player().getName());
        weak.add(new WeakReference<>(delayedMock.player()));

        // A mock respawned as the Respawn button does, so the sweep must disconnect the listener (#698).
        GameTestInfo respawn = ProbeInfos.of(owner, level);
        infos.add(respawn);
        MockPlayer respawnMock =
                MockPlayers.join(level.getServer(), "retention-respawn", level, spawn, respawn::isDone);
        ServerPlayer dead = respawnMock.player();
        dead.setGameMode(GameType.SURVIVAL);
        dead.kill(level);
        dead.connection.handleClientCommand(
                new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
        ServerPlayer reborn = dead.connection.getPlayer();
        if (reborn == dead) {
            throw failure(helper, "the respawn should replace the dead ServerPlayer");
        }
        weak.add(new WeakReference<>(dead));
        weak.add(new WeakReference<>(reborn));

        return weak;
    }
}
