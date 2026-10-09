package io.github.pkeppeler.deepcharter.hangar;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import io.github.pkeppeler.deepcharter.terminal.TerminalFeature;

/**
 * What the hangar console's screen needs beyond the generic terminal view: how much of the Company's advance the viewing charter
 * has left. It rides on the view as the terminal's {@link TerminalFeature}.
 *
 * @param advanceLeft the catalysts of the advance the charter has not used yet: 0 once it is used, or when the hangar records
 *                    cannot be read
 */
public record HangarView(int advanceLeft) implements TerminalFeature {
	public static final StreamCodec<ByteBuf, HangarView> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, HangarView::advanceLeft,
			HangarView::new);

	public HangarView {
		if (advanceLeft < 0) {
			throw new IllegalArgumentException("a charter cannot have " + advanceLeft + " catalysts of advance left");
		}
	}
}
