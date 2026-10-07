package io.github.pkeppeler.deepcharter.charter;

import java.util.Objects;
import java.util.UUID;

import com.mojang.serialization.Codec;

import io.netty.buffer.ByteBuf;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;

/**
 * The identity of a charter: a random UUID made once when the charter is founded, never reused and
 * never a player's UUID. #52 defines the Charter around it; this record and its codecs are frozen.
 */
public record CharterId(UUID value) {
	public static final Codec<CharterId> CODEC = UUIDUtil.CODEC.xmap(CharterId::new, CharterId::value);
	public static final StreamCodec<ByteBuf, CharterId> STREAM_CODEC = UUIDUtil.STREAM_CODEC.map(CharterId::new, CharterId::value);

	public CharterId {
		Objects.requireNonNull(value, "value");
	}

	/** A new identity, for a charter being founded. */
	public static CharterId random() {
		return new CharterId(UUID.randomUUID());
	}
}
