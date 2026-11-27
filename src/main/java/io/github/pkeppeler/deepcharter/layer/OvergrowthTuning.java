package io.github.pkeppeler.deepcharter.layer;

/**
 * Tunables for overgrowth (#974), read as {@code OvergrowthTuning.DEFAULT.thing()}.
 *
 * @param delayTicks        ticks a chunk's bores go unvisited before the first cell grows over
 * @param visitRadius       blocks from a player to the nearest point of a chunk that count as a visit to it (a chunk
 *     whose rectangle is within this is visited)
 * @param passTicks         ticks between two growth passes
 * @param cellsPerPass      cells one chunk grows over in one pass: the spread rate, however many bores the chunk holds
 * @param holdReach         a bore cell with a lining block this many blocks away in each direction (a cube) never grows
 *     over
 * @param visitStampTicks   a chunk's last visit is written at most this often, so a visited chunk is not saved on every
 *     pass
 * @param maxLooks          bore cells one pass weighs for growth, so a chunk of thousands of cells costs the same as a
 *     small one
 * @param drillFuelMultiplier how many times the drilling fuel rate burns while a slab with growth in it is under the
 *     drill
 * @param hardness          the growth block's hardness (stone is 1.5): the drill's timing and a hand's reads it
 * @param filmReach         blocks round a grown cell that the moss film spills to, and round the film that holds it (a
 *     cube)
 * @param filmCheckTicks    ticks between two checks that a film is still held
 * @param spillNearPercent  chance in percent that an air cell next to a grown cell takes a film
 * @param spillFarPercent   chance in percent that an air cell further out, up to the reach, takes a film
 * @param filmBarePercent   share in percent of the rock faces of a filmed cell that are left bare, so the film is
 *     ragged
 */
public record OvergrowthTuning(
        int delayTicks,
        int visitRadius,
        int passTicks,
        int cellsPerPass,
        int holdReach,
        int visitStampTicks,
        int maxLooks,
        float drillFuelMultiplier,
        float hardness,
        int filmReach,
        int filmCheckTicks,
        int spillNearPercent,
        int spillFarPercent,
        int filmBarePercent) {
    /**
     * (A) Five minutes unvisited before the first cell; then three cells a chunk every 5 s, so a 3 x 3 bore of 8 slabs
     * (72 cells) closes in 2 minutes more, from the walls inward. Visited means a player within 32 blocks: a player
     * inside a bore, or at either end of a stretch that is in view, never sees it close. The drill burns 3 times its
     * fuel through growth (about twice a slab of stone, because the growth is softer than stone); a hand cuts a cell in
     * 1.5 s with nothing in it and less with an axe. The moss film goes 2 blocks round a grown cell: 70% of the cells
     * beside it and 35% of those two out take one, a third of a filmed cell's rock faces stay bare, and a film asks
     * every 2 s whether growth still holds it.
     */
    public static final OvergrowthTuning DEFAULT =
            new OvergrowthTuning(6000, 32, 100, 3, 2, 600, 48, 3f, 1.0f, 2, 40, 70, 35, 33);

    public OvergrowthTuning {
        if (delayTicks < 0 || visitRadius < 0 || holdReach < 0 || visitStampTicks < 1 || maxLooks < 1) {
            throw new IllegalArgumentException(
                    "overgrowth needs a delay, radius, reach and looks that are not negative");
        }
        if (passTicks < 1 || cellsPerPass < 1 || !(drillFuelMultiplier >= 1f) || !(hardness > 0f)) {
            throw new IllegalArgumentException(
                    "overgrowth needs a pass of 1 tick or more, a cell a pass, a fuel multiplier of 1 or more and a hardness above 0");
        }
        if (filmReach < 1
                || filmCheckTicks < 1
                || spillNearPercent < 0
                || spillNearPercent > 100
                || spillFarPercent < 0
                || spillFarPercent > 100
                || filmBarePercent < 0
                || filmBarePercent > 100) {
            throw new IllegalArgumentException(
                    "overgrowth needs a film reach and check of 1 or more and film shares of 0 to 100 percent");
        }
    }
}
