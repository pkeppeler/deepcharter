package io.github.pkeppeler.deepcharter.hangar;

import io.github.pkeppeler.deepcharter.terminal.TerminalFeature;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * What the hangar console's screen needs beyond the generic terminal view: how much of the Company's advance the viewing charter
 * has left, and the state of the viewing player's one Mole. It rides on the view as the terminal's {@link TerminalFeature}.
 *
 * @param advanceLeft       the catalysts of the advance the charter has not used yet: 0 once it is used, or when the hangar records
 *                          cannot be read
 * @param mole              the state of the viewing player's Mole, {@link MoleState#NONE} when they have none or the records cannot be read
 * @param writeOffTicksLeft the game ticks until a filed write-off takes effect: 0 unless {@code mole} is {@link MoleState#WRITING_OFF}
 */
public record HangarView(int advanceLeft, MoleState mole, int writeOffTicksLeft) implements TerminalFeature {
    /** Where a player's one Mole stands, for the screen's status line. */
    public enum MoleState {
        NONE,
        WORKING,
        WRECKED,
        WRITING_OFF
    }

    public static final StreamCodec<ByteBuf, HangarView> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT,
            HangarView::advanceLeft,
            ByteBufCodecs.idMapper(ordinal -> MoleState.values()[ordinal], MoleState::ordinal),
            HangarView::mole,
            ByteBufCodecs.VAR_INT,
            HangarView::writeOffTicksLeft,
            HangarView::new);

    public HangarView {
        if (advanceLeft < 0) {
            throw new IllegalArgumentException("a charter cannot have " + advanceLeft + " catalysts of advance left");
        }
        if (writeOffTicksLeft < 0 || (writeOffTicksLeft > 0 && mole != MoleState.WRITING_OFF)) {
            throw new IllegalArgumentException(
                    "a Mole that is " + mole + " cannot have " + writeOffTicksLeft + " ticks of write-off left");
        }
    }
}
