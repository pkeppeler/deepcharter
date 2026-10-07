package io.github.pkeppeler.deepcharter.wreck;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * The wreck attachment of a pod: whether its hull ran out and it has not been restored since. The body of a
 * {@code Versioned<WreckState>}, so it has no version field.
 *
 * @param wrecked true from the moment the hull reaches 0 until {@link Wrecks#restore}
 */
public record WreckState(boolean wrecked) {
	/** The saved version; 1 is the first. */
	public static final int VERSION = 1;
	public static final WreckState INTACT = new WreckState(false);
	public static final WreckState WRECKED = new WreckState(true);
	public static final MapCodec<WreckState> BODY = RecordCodecBuilder.mapCodec(instance -> instance.group(
			Codec.BOOL.fieldOf("wrecked").forGetter(WreckState::wrecked)).apply(instance, WreckState::new));
	public static final StreamCodec<ByteBuf, WreckState> STREAM = ByteBufCodecs.BOOL.map(WreckState::new, WreckState::wrecked);
}
