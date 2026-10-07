package io.github.pkeppeler.deepcharter.test.support;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;

/**
 * Joins real {@link ServerPlayer}s to a server with no network client behind them. This mirrors
 * vanilla's {@code GameTestHelper.makeMockServerPlayerInLevel}: a {@code Connection} on an
 * {@link EmbeddedChannel}, added through {@code PlayerList.placeNewPlayer}. Fabric's FakePlayer
 * is not an option, because its {@code startRiding} always returns false.
 *
 * <p>Vanilla's helper is built for short tests. These players live as long as the server does,
 * so each server tick this class also:
 * <ul>
 *   <li>ticks the mock's {@code Connection}, which ticks its packet listener. Vanilla only does
 *       that for connections it accepted itself;</li>
 *   <li>empties the channel's outbound queue, so packets do not pile up in memory;</li>
 *   <li>answers every {@code ClientboundKeepAlivePacket}, because vanilla kicks a player that
 *       has not answered one for 15 seconds.</li>
 * </ul>
 * Drive a mock with {@link MockPlayer#setInput}. Call everything on the server thread.
 */
public final class MockPlayers {
	private static final List<MockPlayer> ACTIVE = new ArrayList<>();

	static {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			for (MockPlayer mock : List.copyOf(ACTIVE)) {
				if (mock.server() == server) {
					mock.tick();
				}
			}
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> ACTIVE.removeIf(mock -> mock.server() == server));
	}

	private MockPlayers() {
	}

	/** Join a new mock player in the server's default spawn. Move it with {@link MockPlayer#teleportTo}. */
	public static MockPlayer join(MinecraftServer server, String name) {
		if (!server.isSameThread()) {
			throw new IllegalStateException("MockPlayers.join must run on the server thread");
		}
		GameProfile profile = new GameProfile(UUID.randomUUID(), name);
		CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
		ServerPlayer player = new ServerPlayer(server, server.overworld(), profile, cookie.clientInformation());
		Connection connection = new Connection(PacketFlow.SERVERBOUND);
		EmbeddedChannel channel = new EmbeddedChannel(connection);
		server.getPlayerList().placeNewPlayer(connection, player, cookie);
		MockPlayer mock = new MockPlayer(server, player, connection, channel);
		ACTIVE.add(mock);
		return mock;
	}

	static void forget(MockPlayer mock) {
		ACTIVE.remove(mock);
	}
}
