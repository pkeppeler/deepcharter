package io.github.pkeppeler.deepcharter.outpost;

/**
 * Tunables of the shell's exposure rule, read as {@code ShellTuning.DEFAULT.thing()} (ADR 0092). A pocket of open air is a room only
 * while it is small enough to be one: past either limit it is the outside a creature walks in from. How often the rule is surveyed is
 * {@link SettlingTuning#sweepIntervalTicks}, not repeated here.
 *
 * @param openSpan  the most blocks a closed pocket spans along one axis: a pocket of this span is a room, one block more is outside
 * @param pocketCap the most cells a closed pocket holds: a pocket of this many cells is a room, one cell more is outside
 */
public record ShellTuning(int openSpan, int pocketCap) {
    /** (A) 32 blocks is a hall of any size the kit builds (9 across, 5 high) and a shaft twice the Company's tallest room; 4,096 is a 16 cube. */
    public static final ShellTuning DEFAULT = new ShellTuning(32, 4096);

    public ShellTuning {
        if (openSpan < 1 || pocketCap < 1) {
            throw new IllegalArgumentException("a span and a cell cap each need at least 1: " + this);
        }
    }
}
