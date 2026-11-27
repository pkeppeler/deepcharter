package io.github.pkeppeler.deepcharter.repair;

import io.github.pkeppeler.deepcharter.DeepCharter;
import java.util.Arrays;
import java.util.Optional;
import net.minecraft.resources.Identifier;

/**
 * The items the repair station sells. The original's debug Core Teleporter is not sold, so it is not here.
 *
 * <p>Prices follow issue 201's scale (PR 206), not the original's $2,000 to $10,000. The tools cost a quarter of a run to one run's net
 * of the layer where they start to matter; SPEC section 11 calls the two teleport items expensive, so they cost about 2 and 4 layer 2
 * runs. What a run nets in each layer is {@code EarlyRunModel}'s, after the braked drive down the shaft;
 * {@code EconomyAffordabilityTest} holds each item to its band.
 */
public enum Consumable {
    // Layer 1 tool: under one stock layer-1 run, the cheap way past a 10 L tank.
    RESERVE_FUEL_TANK("reserve_fuel_tank", 100, Kind.CONSUMED, Use.PILOT_SEAT),
    // Layer 2 tool: under one layer-2 run, and above the station's price for the same 30 HP.
    HULL_NANOBOTS("hull_nanobots", 345, Kind.CONSUMED, Use.PILOT_SEAT),
    // The explosives rise in steps, blast and price together (#1458, mechanics.md "Explosive tiers"): each is a
    // deliberate spend, not a routine one.
    // Layer 1 tool: just under one stock layer-1 run, so the first run still pays for a first stick.
    DYNAMITE("dynamite", 120, Kind.CONSUMED, Use.PILOT_SEAT),
    // Layer 2 tool: under one layer-2 run, and above the dynamite it outdoes.
    PLASTIC_EXPLOSIVES("plastic_explosives", 400, Kind.CONSUMED, Use.PILOT_SEAT),
    // Layer 1 tool (#509): the only thing that clears Company rock. A straight layer 1 bore needs several, so one costs
    // a fraction of a stock layer 1 run, and the shaft at most three runs. Twice as deep since #1458, so a row costs
    // less than a slab the drill bites through (#526).
    BLASTING_CHARGE("blasting_charge", 60, Kind.CONSUMED, Use.PILOT_SEAT),
    // Layer 2 and 3 tool (#526): the same set-up as the blasting charge, but it clears a whole Company rock column.
    // $160 is under three blasting charges ($180), the three it replaces in a thick stretch.
    SHAPED_CHARGE("shaped_charge", 160, Kind.CONSUMED, Use.PILOT_SEAT),
    // SPEC section 11: an expensive escape, about 2 layer-2 runs; the cargo drop is the rest of the cost.
    QUANTUM_TELEPORTER("quantum_teleporter", 690, Kind.CONSUMED, Use.PILOT_SEAT),
    // SPEC section 11: a layer-3 item measured as about 4 layer-2 runs (3.6 from the outpost, #320; $1,650 since #963,
    // which made fuel cheaper and a run's net $455); the cargo drop is
    // the rest of the cost.
    MATTER_TRANSMITTER("matter_transmitter", 1_650, Kind.CONSUMED, Use.PILOT_SEAT),
    // Thrown by hand on foot (#985, CREATURES section 8): the operator's rounds without the launcher. Each costs a
    // fraction of a layer 1 run, so a fight costs money without ending the run. ThrownKitPacingTest holds the three to
    // their bands.
    THROWN_FLARE("thrown_flare", 12, Kind.CONSUMED, Use.THROWN),
    THROWN_DECOY("thrown_decoy", 25, Kind.CONSUMED, Use.THROWN),
    THROWN_CHARGE("thrown_charge", 65, Kind.CONSUMED, Use.THROWN),
    // On foot (#986): not used up. A person on foot clips it to a pod's winch, and it is theirs to keep. Priced at
    // about three quarters of a stock layer 1 run, so the first descent pays for it once and no pod is ever
    // without one.
    TETHER("tether", 90, Kind.TOOL, Use.OWN),
    // On foot (#987): a tool, kept. The driver costs two fifths of a layer 2 run. EconomyAffordabilityTest holds the
    // bands and BoltDriverPacingTest what a fight and a stretch of tunnel cost.
    BOLT_DRIVER("bolt_driver", 175, Kind.TOOL, Use.OWN),
    // The driver's ammunition, bought in packs of 8 for a twentieth of a layer 2 run; the driver spends them.
    BOLT("bolt", 24, Kind.CONSUMED, Use.OWN, 8),
    // On foot (#1194): a layer 3 item, about a third of a Mole layer 2 run ($455). It tops up the suit's reserve
    // anywhere, so it is sold at the repair station and has no recipe.
    AIR_CANISTER("air_canister", 150, Kind.CONSUMED, Use.SUIT);

    /** What using the item does to it. */
    public enum Kind {
        /** Used up when it works. */
        CONSUMED,
        /** Kept: it is not used up. */
        TOOL
    }

    /** Where and how the item is used; {@code Consumables.use} dispatches on it. */
    public enum Use {
        /** From the pilot's seat, on the pod: the repair station's own items. */
        PILOT_SEAT,
        /** Thrown by hand on foot ({@link ThrownKit}). */
        THROWN,
        /** On foot, on the user's suit ({@code SuitReserve}), wherever they are. */
        SUIT,
        /** By the item itself, never through {@code Consumables.use}: the tether, the bolt driver and its bolts. */
        OWN
    }

    private final Identifier itemId;
    private final long price;
    private final Kind kind;
    private final Use use;
    private final int perPurchase;

    /** A single item. */
    Consumable(String path, long price, Kind kind, Use use) {
        this(path, price, kind, use, 1);
    }

    Consumable(String path, long price, Kind kind, Use use, int perPurchase) {
        this.itemId = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path);
        this.price = price;
        this.kind = kind;
        this.use = use;
        this.perPurchase = perPurchase;
    }

    public Kind kind() {
        return kind;
    }

    public Use use() {
        return use;
    }

    public Identifier itemId() {
        return itemId;
    }

    /** Dollars, taken from the charter's account. */
    public long price() {
        return price;
    }

    /** Items the station hands over for one purchase: a pack of bolts, otherwise one. */
    public int perPurchase() {
        return perPurchase;
    }

    public static Optional<Consumable> byItemId(Identifier id) {
        return Arrays.stream(values())
                .filter(consumable -> consumable.itemId.equals(id))
                .findFirst();
    }
}
