package io.github.pkeppeler.deepcharter.test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.util.Util;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;

import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;

/** Proves the {@link MockPlayers} harness: a mock player is a real player that stays connected. */
public class MockPlayersTest {
	private static final int TICKS = 1200;

	@GameTest(maxTicks = TICKS + 100)
	public void mockPlayerSurvivesOneMinuteOfTicks(GameTestHelper helper) {
		MockPlayer mock = MockPlayers.join(helper, "stays-online");
		if (!mock.isOnline()) {
			throw helper.assertionException("mock player is not on the player list after joining");
		}
		helper.runAfterDelay(TICKS, () -> {
			if (!mock.isOnline()) {
				throw helper.assertionException("mock player left the player list within %d ticks", TICKS);
			}
			helper.succeed();
		});
	}

	/** A mock owned by a test is removed once that test is done: the sweep, not the test, cleans up. */
	@GameTest
	public void mockPlayerIsRemovedWhenItsOwnerIsDone(GameTestHelper helper) {
		AtomicBoolean ownerDone = new AtomicBoolean();
		MockPlayer mock = MockPlayers.join(helper.getLevel().getServer(), "owned", ownerDone::get);
		helper.runAfterDelay(5, () -> {
			if (!mock.isOnline()) {
				throw helper.assertionException("mock player left before its owner was done");
			}
			ownerDone.set(true);
			helper.runAfterDelay(5, () -> {
				if (mock.isOnline()) {
					throw helper.assertionException("mock player outlived its owner");
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
					throw helper.assertionException("the server never sent a keep-alive to the mock player");
				}
				if (pending) {
					throw helper.assertionException("the mock player left a keep-alive unanswered");
				}
				if (!mock.isOnline()) {
					throw helper.assertionException("mock player left the player list");
				}
			} catch (ReflectiveOperationException e) {
				throw reflectionFailure("keepAliveTime or keepAlivePending", e);
			}
		});
	}

	private static IllegalStateException reflectionFailure(String field, ReflectiveOperationException e) {
		return new IllegalStateException("Cannot access ServerCommonPacketListenerImpl." + field
				+ ", likely a mapping change in Minecraft", e);
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
}
