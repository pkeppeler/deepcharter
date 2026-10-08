package io.github.pkeppeler.deepcharter.charter;

import java.util.UUID;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * What one player's client knows of their charter. It has no UUIDs: a client learns names and totals, not who else is online.
 *
 * @param director true when the viewing player is the Director
 * @param people   the Director and crew, counted
 */
public record CharterView(String name, long balance, int deepestPoint, boolean director, int people) {
	public static final StreamCodec<ByteBuf, CharterView> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.STRING_UTF8, CharterView::name,
			ByteBufCodecs.VAR_LONG, CharterView::balance,
			ByteBufCodecs.VAR_INT, CharterView::deepestPoint,
			ByteBufCodecs.BOOL, CharterView::director,
			ByteBufCodecs.VAR_INT, CharterView::people,
			CharterView::new);

	public static CharterView of(Charter charter, UUID viewer) {
		return new CharterView(charter.name(), charter.account(), charter.deepestPoint(), charter.isDirector(viewer), charter.roster().size());
	}
}
