package io.github.pkeppeler.deepcharter.repair;

/**
 * Tunables for the Company recovery of a stranded pod (#963, ADR 0073),
 * read as {@code RecoveryTuning.DEFAULT.thing()}.
 *
 * @param delayTicks    ticks a pilot waits in the dark pod before the winch crew hauls it home (a minute: time for a friend with a fuel item to arrive, and long enough to hurt)
 * @param feePerLayer   dollars the haul costs for each layer the pod is in (layer 1 is 1), so a deep stranding costs more; taken from the owner charter's account up to what it holds
 * @param minHull       hull points the rescue leaves at least: the hull pays the unpaid fee at the repair station's price a point, but never below this, so the rescue never wrecks a pod
 * @param splashLitres  litres the crew leaves in the pod, so the pilot can drive from the pad to the pump
 * @param scatter       blocks, at most, from the colony's pad that the crew sets the pod down, so a blocked column is not a refusal; a blocked colony falls back to the world spawn
 * @param spill         how long the ore that stays behind lies before it despawns
 */
public record RecoveryTuning(
        int delayTicks, long feePerLayer, float minHull, float splashLitres, double scatter, SpillLife spill) {
    /**
     * (A) A minute of waiting, and $30 a layer: a full tank is $10 at the pump, so a refuel is always cheaper, and layer 1's $30 is a quarter of what a stock layer 1 run
     * nets ($124, {@code EarlyRunModel}), layer 2's $60 an eighth of a layer 2 run ($455). A pilot who cannot pay loses hull at the repair station's $1 a point, so money and
     * hull cost the same, down to {@code minHull}: a broke pilot loses 30 hull in layer 1, 60 in layer 2 and 90 from layer 3. The ore lies {@link SpillLife#EXTENDED about 32 minutes}.
     */
    public static final RecoveryTuning DEFAULT = new RecoveryTuning(1200, 30L, 10f, 1f, 6.0, SpillLife.EXTENDED);

    public RecoveryTuning {
        if (delayTicks < 1 || feePerLayer < 1 || !(minHull >= 1f) || !(splashLitres > 0f) || !(scatter >= 0.0)) {
            throw new IllegalArgumentException(
                    "recovery needs a delay and a fee of 1 or more, a floor of 1 hull or more, some litres and a scatter of 0 or more, got "
                            + delayTicks + ", " + feePerLayer + ", " + minHull + ", " + splashLitres + ", " + scatter);
        }
    }

    /** The fee in layer {@code layer}; the surface counts as layer 1. */
    public long feeAt(int layer) {
        return feePerLayer * Math.max(1, layer);
    }
}
