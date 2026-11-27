package io.github.pkeppeler.deepcharter.hangar;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.Chassis;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.Identifier;

/**
 * Tunables for the hangar feature, read as {@code HangarTuning.DEFAULT.thing()}. Money is in dollars, and every price is
 * invented; the SPEC (section 7) says only "a modest fee". Why each is what it is (EconomyAffordabilityTest has the income):
 * <ul>
 * <li>A crafted Mole pays no fee: its price is the four parts ({@link HangarParts}), and each member has one (ADR 0117).
 * <li>A Mole wreck's restore, $100: the fee for the tow it takes.
 * <li>The Prospector's restore, $1,390: about four layer 2 runs of a Mole with tier 2 parts (a run pays for the braked drive down the shaft first, #319), which is what is left to earn after
 * the onboarding's other buys, so it ends the onboarding as the SPEC intends. The three Cicatrium are the longer wait
 * in the ore (about 50 runs in the deepest zone), so the Company advances them, once per charter, against its contract.
 * <li>A write-off, $2,000: more than a Prospector's restore money ($1,390) and with no catalyst, so a member who cannot reach a
 * wreck pays more than a tow and a restore would have cost, and no member writes a wreck off to skip the way home. It takes one
 * in-game day, so a free place never comes sooner than a rescue could.
 * </ul>
 *
 * @param restoreCosts      what the hangar charges to restore a wreck, by chassis id
 * @param catalyst          the ore that restoring a wreck uses up
 * @param wreckRadius       the hangar restores the nearest wreck this many blocks from the console, at most
 * @param bayRadius         a new Mole stands in a free place in the bay, this many blocks from the hangar anchor at most
 * @param slotGap           the places of the bay are a pod's bore width plus this many blocks apart, so two parked pods of any chassis leave a block between them
 * @param writeOffCost     what filing a write-off at the console costs, in dollars
 * @param writeOffTicks    the game ticks from filing a write-off to its taking effect: one in-game day
 */
public record HangarTuning(
        Map<String, RestoreCost> restoreCosts,
        OreType catalyst,
        double wreckRadius,
        int bayRadius,
        int slotGap,
        long writeOffCost,
        long writeOffTicks) {
    public static final HangarTuning DEFAULT = new HangarTuning(
            Map.of(
                    "mole",
                    new RestoreCost(100, 1, 0, Optional.empty()),
                    "prospector",
                    new RestoreCost(
                            1_390, 3, 3, Optional.of(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "t17")))),
            OreType.CICATRIUM,
            24,
            6,
            1,
            2_000,
            24_000);

    /**
     * The dollars and the catalysts that restoring a wreck takes, the catalysts that the Company advances to each charter against
     * its contract for this restore (once per charter, spent only by a restore, never held in a pack, so it cannot be sold), and the
     * transmission it fires for the charter, if it fires one. An advance below the catalysts would gate the restore on ore again.
     */
    public record RestoreCost(long money, int catalysts, int advance, Optional<Identifier> transmission) {}

    public HangarTuning {
        restoreCosts = Map.copyOf(restoreCosts);
        if (writeOffTicks <= 0) {
            throw new IllegalArgumentException("a write-off needs a positive wait, got " + writeOffTicks);
        }
    }

    /** What restoring a wreck of {@code chassis} costs. A chassis with no price is a bug, so it throws. */
    public RestoreCost restoreCost(Chassis chassis) {
        RestoreCost cost = restoreCosts.get(chassis.id());
        if (cost == null) {
            throw new IllegalArgumentException("no restore price for chassis " + chassis.id());
        }
        return cost;
    }
}
