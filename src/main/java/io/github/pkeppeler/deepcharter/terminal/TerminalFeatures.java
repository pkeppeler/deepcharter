package io.github.pkeppeler.deepcharter.terminal;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import io.netty.buffer.ByteBuf;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import io.github.pkeppeler.deepcharter.charter.Charter;

/**
 * Which {@link TerminalFeature} each terminal type adds to its view. Register while mods initialise, on both sides: the server
 * needs the supplier and the client needs the codec. Every type writes a presence flag first, so a type with no feature sends one false byte and both sides read the same layout.
 */
public final class TerminalFeatures {
	private static final Map<Identifier, Registration<?>> REGISTRATIONS = new HashMap<>();

	private TerminalFeatures() {
	}

	/**
	 * Makes the feature of a terminal's view for one player, on the server thread, whenever the server sends the view of a
	 * repaired terminal: on open and after each action. It must not change anything, and must not throw on unreadable saved data.
	 */
	@FunctionalInterface
	public interface Supplier<T extends TerminalFeature> {
		T supply(MinecraftServer server, ServerPlayer player, Optional<Charter> charter, BlockPos pos);
	}

	private record Registration<T extends TerminalFeature>(StreamCodec<ByteBuf, T> codec, Supplier<T> supplier) {
		Optional<TerminalFeature> supply(MinecraftServer server, ServerPlayer player, Optional<Charter> charter, BlockPos pos) {
			return Optional.of(supplier.supply(server, player, charter, pos));
		}

		StreamCodec<ByteBuf, Optional<TerminalFeature>> optionalCodec() {
			return ByteBufCodecs.optional(codec.map(feature -> feature, this::cast));
		}

		@SuppressWarnings("unchecked")
		private T cast(TerminalFeature feature) {
			return (T) feature;
		}
	}

	/** Sets the feature of {@code type}'s view. Once per type. */
	public static <T extends TerminalFeature> void register(TerminalType type, StreamCodec<ByteBuf, T> codec, Supplier<T> supplier) {
		if (REGISTRATIONS.putIfAbsent(type.id(), new Registration<>(codec, supplier)) != null) {
			throw new IllegalArgumentException("terminal " + type.id() + " already has a view feature");
		}
	}

	/** True when {@code type} adds a feature to its view, so a test can require a fixture for each. */
	public static boolean registered(TerminalType type) {
		return REGISTRATIONS.containsKey(type.id());
	}

	/** The feature of a view of {@code type}, or empty for a type with none or a terminal that is offline. */
	static Optional<TerminalFeature> supply(TerminalType type, boolean repaired, MinecraftServer server, ServerPlayer player,
			Optional<Charter> charter, BlockPos pos) {
		Registration<?> registration = REGISTRATIONS.get(type.id());
		return registration == null || !repaired ? Optional.empty() : registration.supply(server, player, charter, pos);
	}

	/** How a view of the terminal type {@code id} carries its feature on the wire. */
	static StreamCodec<ByteBuf, Optional<TerminalFeature>> codec(Identifier id) {
		Registration<?> registration = REGISTRATIONS.get(id);
		return registration == null ? NONE : registration.optionalCodec();
	}

	private static final StreamCodec<ByteBuf, Optional<TerminalFeature>> NONE = new StreamCodec<>() {
		@Override
		public Optional<TerminalFeature> decode(ByteBuf buffer) {
			if (ByteBufCodecs.BOOL.decode(buffer)) {
				throw new IllegalStateException("the server sent a view feature for a terminal type that registered none here: the client and the server register different features");
			}
			return Optional.empty();
		}

		@Override
		public void encode(ByteBuf buffer, Optional<TerminalFeature> feature) {
			if (feature.isPresent()) {
				throw new IllegalStateException("a terminal type with no registered view feature cannot send one: " + feature.get());
			}
			ByteBufCodecs.BOOL.encode(buffer, false);
		}
	};
}
