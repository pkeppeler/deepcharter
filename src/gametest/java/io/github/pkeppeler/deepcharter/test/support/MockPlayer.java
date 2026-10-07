package io.github.pkeppeler.deepcharter.test.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;

/**
 * A real {@link ServerPlayer} whose connection is a Netty {@link EmbeddedChannel}. Create one
 * with {@link MockPlayers#join}. Everything here runs on the server thread.
 */
public final class MockPlayer {
	private final MinecraftServer server;
	private final ServerPlayer player;
	private final Connection connection;
	private final EmbeddedChannel channel;
	private final BooleanSupplier ownerDone;
	private final List<Component> actionBar = new ArrayList<>();

	MockPlayer(MinecraftServer server, ServerPlayer player, Connection connection, EmbeddedChannel channel,
			BooleanSupplier ownerDone) {
		this.server = server;
		this.player = player;
		this.connection = connection;
		this.channel = channel;
		this.ownerDone = ownerDone;
	}

	/** Every action-bar message the server has sent this player so far, oldest first. */
	public List<Component> actionBarMessages() {
		return List.copyOf(actionBar);
	}

	public ServerPlayer player() {
		return player;
	}

	/** True while the player is on the server's player list. */
	public boolean isOnline() {
		return server.getPlayerList().getPlayer(player.getUUID()) == player;
	}

	/** Set the input the server reads from the player, as a serverbound input packet would. */
	public void setInput(Input input) {
		player.setLastClientInput(input);
		player.setShiftKeyDown(input.shift());
	}

	public void releaseInput() {
		setInput(Input.EMPTY);
	}

	public void teleportTo(ServerLevel level, Vec3 pos, float yRot, float xRot) {
		if (!player.teleportTo(level, pos.x, pos.y, pos.z, Set.of(), yRot, xRot, true)) {
			throw new IllegalStateException("Mock player " + player.getGameProfile().name() + " could not teleport to " + pos);
		}
	}

	/** Disconnect and remove the player. Safe to call when it has already left. */
	public void leave() {
		if (isOnline()) {
			connection.disconnect(Component.literal("mock player left"));
			connection.handleDisconnection();
		}
		MockPlayers.forget(this);
	}

	MinecraftServer server() {
		return server;
	}

	/**
	 * One server tick of the fake network: tick the connection (which runs the packet listener
	 * and sends keep-alives), then drain everything the server wrote to the channel, answering
	 * each keep-alive as a real client would.
	 */
	void tick() {
		if (ownerDone.getAsBoolean()) {
			leave();
			return;
		}
		connection.tick();
		Object outbound;
		while ((outbound = channel.readOutbound()) != null) {
			try {
				if (outbound instanceof ClientboundSystemChatPacket chat && chat.overlay()) {
					actionBar.add(chat.content());
				}
				if (outbound instanceof ClientboundKeepAlivePacket keepAlive && player.connection != null) {
					player.connection.handleKeepAlive(new ServerboundKeepAlivePacket(keepAlive.getId()));
				}
			} finally {
				ReferenceCountUtil.release(outbound);
			}
		}
	}
}
