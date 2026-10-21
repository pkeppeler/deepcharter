package io.github.pkeppeler.deepcharter.pod;

import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import java.util.List;

/**
 * Tunables for the heat shield part (#482, #712), read as {@code PodHeatShieldTuning.DEFAULT.thing()}. The price is {@code UpgradeTuning}'s.
 *
 * @param tiers one entry for each part tier from 1, as many as {@link ComponentTrack#HEAT_SHIELD} has
 */
public record PodHeatShieldTuning(List<Tier> tiers) {
    /**
     * What one tier of the shield does.
     *
     * @param hullPerBrick lava hull one slag brick absorbs before it is spent, above 0 (after the radiator has cut the lava's rate)
     * @param packFirst    true when the brick comes from the seated pilot's pack before the rack, so the rack stays for lining
     */
    public record Tier(float hullPerBrick, boolean packFirst) {
        public Tier {
            if (!(hullPerBrick > 0f)) {
                throw new IllegalArgumentException("a brick absorbs hull above 0, got " + hullPerBrick);
            }
        }
    }

    /**
     * (A) Tier 1 soaks 4 hull a brick: a second of lava is 10 hull, so a brick buys 0.4 s and a full rack of 24 doubles a stock Mole's 10 s in lava. Tier 2, the
     * best a Mole takes, soaks 6 (a rack buys 14 s). Tier 3, a Prospector's, soaks 8 (a rack buys 19 s) and burns the pilot's pack before the rack, so the rack stays for lining.
     */
    public static final PodHeatShieldTuning DEFAULT =
            new PodHeatShieldTuning(List.of(new Tier(4f, false), new Tier(6f, false), new Tier(8f, true)));

    public PodHeatShieldTuning {
        tiers = List.copyOf(tiers);
        if (tiers.size() != ComponentTrack.HEAT_SHIELD.maxTier()) {
            throw new IllegalArgumentException("the heat shield has " + ComponentTrack.HEAT_SHIELD.maxTier()
                    + " part tiers, the tuning has " + tiers.size());
        }
    }

    /** What the shield does at {@code tier}, 1 or more. */
    public Tier tier(int tier) {
        ComponentTrack.HEAT_SHIELD.requirePartTier(tier);
        return tiers.get(tier - 1);
    }
}
