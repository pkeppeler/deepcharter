package io.github.pkeppeler.deepcharter.test;

import java.lang.reflect.Field;

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
		MockPlayer mock = MockPlayers.join(helper.getLevel().getServer(), "stays-online");
		if (!mock.isOnline()) {
			throw helper.assertionException("mock player is not on the player list after joining");
		}
		helper.runAfterDelay(TICKS, () -> {
			try {
				if (!mock.isOnline()) {
					throw helper.assertionException("mock player left the player list within %d ticks", TICKS);
				}
			} finally {
				mock.leave();
			}
			helper.succeed();
		});
	}

	/**
	 * Server GameTests finish in seconds, so the 15 s keep-alive never comes due on its own.
	 * Backdate the listener's last keep-alive instead: the server must send one, and the mock
	 * must answer it, or the player would be kicked 15 s later.
	 */
	@GameTest
	public void mockPlayerAnswersKeepAlives(GameTestHelper helper) {
		MockPlayer mock = MockPlayers.join(helper.getLevel().getServer(), "answers-keep-alives");
		try {
			setField(mock.player().connection, "keepAliveTime", Util.getMillis() - 16_000L);
		} catch (ReflectiveOperationException e) {
			mock.leave();
			throw new IllegalStateException(e);
		}
		helper.runAfterDelay(5, () -> {
			try {
				long sentAt = (long) getField(mock.player().connection, "keepAliveTime");
				boolean pending = (boolean) getField(mock.player().connection, "keepAlivePending");
				if (Util.getMillis() - sentAt > 5_000L) {
					throw helper.assertionException("the server never sent a keep-alive to the mock player");
				}
				if (pending) {
					throw helper.assertionException("the mock player left a keep-alive unanswered");
				}
				if (!mock.isOnline()) {
					throw helper.assertionException("mock player left the player list");
				}
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException(e);
			} finally {
				mock.leave();
			}
			helper.succeed();
		});
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
