package io.github.pkeppeler.deepcharter.suit;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * The suit's tracks (#1064, ADR 0091), bought one tier at a time at the repair station: heat and pressure each raise the suit's rating by
 * a level a tier, and air raises the flooding rating and the capacity ({@link SuitTuning#capacities}), so SPEC section 9's oxygen and
 * toxins need no fifth track: "toxins" is a name until an axis for it exists. Prices are banded against the layer 2 runs that fund them
 * ({@code EconomyAffordabilityTest}).
 */
public enum SuitTrack {
    HEAT(450, 1_100, 2_500),
    PRESSURE(450, 1_100, 2_500),
    AIR(550, 1_400, 3_000);

    private final long[] prices;

    SuitTrack(long... prices) {
        this.prices = prices;
    }

    /** The lower-case id: the {@code track} argument of the buy action and the end of the track's lang key. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The top tier, which nothing sells past. */
    public int maxTier() {
        return prices.length;
    }

    /** What {@code tier}, 1 to {@link #maxTier}, costs. */
    public long price(int tier) {
        if (tier < 1 || tier > maxTier()) {
            throw new IllegalArgumentException(this + " has no tier " + tier);
        }
        return prices[tier - 1];
    }

    public static Optional<SuitTrack> byId(String id) {
        return Arrays.stream(values()).filter(track -> track.id().equals(id)).findFirst();
    }
}
