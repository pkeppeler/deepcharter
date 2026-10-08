package io.github.pkeppeler.deepcharter.upgrade;

import java.util.List;
import java.util.Optional;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import io.github.pkeppeler.deepcharter.terminal.TerminalFeature;

/**
 * What an upgrade terminal's screen needs beyond the generic terminal view: the pod parked at it, as the server sees it now.
 * It rides on the view as the terminal's {@link TerminalFeature}.
 * Prices come from {@link UpgradeTuning}, which both sides have.
 *
 * @param pod        the player's charter's pod parked at the terminal, or empty
 * @param foreignPod true when no pod of the charter is parked but one of another charter (or of nobody) is
 */
public record UpgradeView(Optional<Pod> pod, boolean foreignPod) implements TerminalFeature {
	public static final StreamCodec<ByteBuf, UpgradeView> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.optional(Pod.STREAM_CODEC), UpgradeView::pod,
			ByteBufCodecs.BOOL, UpgradeView::foreignPod,
			UpgradeView::new);

	/**
	 * @param serial the pod's serial, such as {@code MOLE-0001}
	 * @param cap    the best part tier its chassis works at
	 * @param slots  one for each track, in track order
	 */
	public record Pod(String serial, int cap, List<Slot> slots) {
		public static final StreamCodec<ByteBuf, Pod> STREAM_CODEC = StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, Pod::serial,
				ByteBufCodecs.VAR_INT, Pod::cap,
				Slot.STREAM_CODEC.apply(ByteBufCodecs.list()), Pod::slots,
				Pod::new);

		public Pod {
			slots = List.copyOf(slots);
		}
	}

	/**
	 * @param installed the tier of the part in the pod, void or not, or 0 for none
	 * @param effective the tier the pod works at from it: 0 for none or a void part, and never above the cap
	 */
	public record Slot(ComponentTrack track, int installed, int effective) {
		public static final StreamCodec<ByteBuf, Slot> STREAM_CODEC = StreamCodec.composite(
				ComponentTrack.STREAM_CODEC, Slot::track,
				ByteBufCodecs.VAR_INT, Slot::installed,
				ByteBufCodecs.VAR_INT, Slot::effective,
				Slot::new);
	}
}
