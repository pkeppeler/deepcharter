package io.github.pkeppeler.deepcharter.test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.util.Util;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;

import io.github.pkeppeler.deepcharter.layer.LayerChain;
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
			throw helper.assertionException("loaded mock took no damage: %s of %s", loaded.getHealth(), loadedBefore);
		}
		if (unloaded.getHealth() != unloadedBefore) {
			throw helper.assertionException("unloaded mock took damage: %s of %s", unloaded.getHealth(), unloadedBefore);
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
			unloaded.hurtServer(helper.getLevel(), helper.getLevel().damageSources().generic(), 2.0F);
			if (unloaded.getHealth() >= before) {
				throw helper.assertionException("an unloaded mock was still immune after 100 ticks: %s of %s", unloaded.getHealth(), before);
			}
			helper.succeed();
		});
	}

	/** A crossing the mock never confirms leaves a player immune, so a loaded mock confirms it itself. */
	@GameTest
	public void loadedMockStaysDamageableAfterChangingDimension(GameTestHelper helper) {
		ServerPlayer player = MockPlayers.join(helper, "crosser").player();
		player.setGameMode(GameType.SURVIVAL);
		ServerLevel other = helper.getLevel().getServer().getLevel(LayerChain.dimension(1));
		player.teleport(new TeleportTransition(other, new Vec3(0.5, 8, 0.5), Vec3.ZERO, 0, 0,
				TeleportTransition.DO_NOTHING));
		if (!player.isChangingDimension()) {
			throw helper.assertionException("the crossing did not leave the player mid-change, so this test proves nothing");
		}
		helper.runAfterDelay(2, () -> {
			float before = player.getHealth();
			player.hurtServer(other, other.damageSources().generic(), 2.0F);
			if (player.getHealth() >= before) {
				throw helper.assertionException("loaded mock is immune after changing dimension");
			}
			helper.succeed();
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
