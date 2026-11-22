package io.github.pkeppeler.deepcharter.repair;

/** How long the ore that a transit spills lies before it despawns. */
public enum SpillLife {
    /** Vanilla's five minutes. */
    VANILLA,
    /** {@code ItemEntity#setExtendedLifetime}: about 32 minutes of game time. */
    EXTENDED
}
