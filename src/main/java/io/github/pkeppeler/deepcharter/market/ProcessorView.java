package io.github.pkeppeler.deepcharter.market;

import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.terminal.TerminalFeature;
import io.netty.buffer.ByteBuf;
import java.util.List;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * What the ore processor adds to its view: the charter's work orders, the ore in the cargo of the pods parked at it, which the
 * WITHDRAW ORE screen lists, and where the slag brick is. The server decides every action; this only tells the screen what to
 * offer and what came of the last one.
 *
 * @param orders the work orders
 * @param cargo  the ore the readable pods the player may use hold between them, by ore in the enum's order; an ore none holds is absent
 * @param bricks the spoil and slag brick of those pods and the brick in the player's pack, which MAKE SLAG BRICK changes (#1649)
 */
public record ProcessorView(WorkOrdersView orders, List<Hold> cargo, Bricks bricks) implements TerminalFeature {
    public static final StreamCodec<ByteBuf, ProcessorView> STREAM_CODEC = StreamCodec.composite(
            WorkOrdersView.STREAM_CODEC,
            ProcessorView::orders,
            Hold.STREAM_CODEC.apply(ByteBufCodecs.list()),
            ProcessorView::cargo,
            Bricks.STREAM_CODEC,
            ProcessorView::bricks,
            ProcessorView::new);

    /** How many of one ore the parked pods hold. */
    public record Hold(OreType ore, int count) {
        public static final StreamCodec<ByteBuf, Hold> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT.map(ordinal -> OreType.values()[ordinal], OreType::ordinal),
                Hold::ore,
                ByteBufCodecs.VAR_INT,
                Hold::count,
                Hold::new);
    }

    /**
     * The spoil and the slag brick the parked pods whose lining can be read hold between them, and the brick in the pack.
     *
     * @param spoil    the spoil in their hoppers
     * @param racked   the bricks in their racks
     * @param rackSize what their racks hold when full; 0 when no such pod is parked
     * @param carried  the slag brick in the player's pack
     */
    public record Bricks(int spoil, int racked, int rackSize, int carried) {
        public static final StreamCodec<ByteBuf, Bricks> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT,
                Bricks::spoil,
                ByteBufCodecs.VAR_INT,
                Bricks::racked,
                ByteBufCodecs.VAR_INT,
                Bricks::rackSize,
                ByteBufCodecs.VAR_INT,
                Bricks::carried,
                Bricks::new);
    }

    public ProcessorView {
        cargo = List.copyOf(cargo);
    }
}
