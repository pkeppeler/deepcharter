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
 * runs. A stock Mole's run in layer 1 nets about $115 and a Mole with tier 2 parts in layer 2 about $348, after the braked drive down the shaft ({@code EarlyRunModel});
 * {@code EconomyAffordabilityTest} holds each item to its band.
 */
public enum Consumable {
    // Layer 1 tool: under one stock layer-1 run, the cheap way past a 10 L tank.
    RESERVE_FUEL_TANK("reserve_fuel_tank", 100),
    // Layer 2 tool: under one layer-2 run, and above the station's price for the same 30 HP.
    HULL_NANOBOTS("hull_nanobots", 345),
    // Layer 1 tool: under one stock layer-1 run.
    DYNAMITE("dynamite", 100),
    // Layer 2 tool: under one layer-2 run, and above the dynamite it outdoes.
    PLASTIC_EXPLOSIVES("plastic_explosives", 300),
    // Layer 1 tool (#509): the only thing that clears Company rock. A straight layer 1 bore needs several, so one costs
    // a fraction of a stock layer 1 run.
    BLASTING_CHARGE("blasting_charge", 40),
    // Layer 2 and 3 tool (#526): the same set-up as the blasting charge, but it clears a whole Company rock column.
    // $100 is under three blasting charges ($120), the three it replaces in a thick stretch.
    SHAPED_CHARGE("shaped_charge", 100),
    // SPEC section 11: an expensive escape, about 2 layer-2 runs; the cargo drop is the rest of the cost.
    QUANTUM_TELEPORTER("quantum_teleporter", 690),
    // SPEC section 11: a layer-3 item measured as about 4 layer-2 runs (3.6 from the outpost, #320; $1,650 since #963,
    // which made fuel cheaper and a run's net $455); the cargo drop is
    // the rest of the cost.
    MATTER_TRANSMITTER("matter_transmitter", 1_650),
    // On foot (#986): not used up. A person on foot clips it to a pod's winch, and it is theirs to keep. Priced at
    // about
    // three quarters of a stock layer 1 run, so the first descent pays for it once and no pod is ever without one.
    TETHER("tether", 90, Kind.TOOL),
    // On foot (#987): a tool, kept. The driver costs two fifths of a layer 2 run. EconomyAffordabilityTest holds the
    // bands and BoltDriverPacingTest what a fight and a stretch of tunnel cost.
    BOLT_DRIVER("bolt_driver", 175, Kind.TOOL),
    // The driver's ammunition, bought in packs of 8 for a twentieth of a layer 2 run; the driver spends them.
    BOLT("bolt", 24, 8);

    /** What using the item does to it. */
    public enum Kind {
        /** Used from the pilot's seat through {@code Consumables.use}, and used up. */
        CONSUMED,
        /** Kept: the item handles its own use on foot, and {@code Consumables.use} is never called for it. */
        TOOL
    }

    private final Identifier itemId;
    private final long price;
    private final Kind kind;
    private final int perPurchase;

    Consumable(String path, long price) {
        this(path, price, Kind.CONSUMED, 1);
    }

    Consumable(String path, long price, Kind kind) {
        this(path, price, kind, 1);
    }

    Consumable(String path, long price, int perPurchase) {
        this(path, price, Kind.CONSUMED, perPurchase);
    }

    private Consumable(String path, long price, Kind kind, int perPurchase) {
        this.itemId = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path);
        this.price = price;
        this.kind = kind;
        this.perPurchase = perPurchase;
    }

    public Kind kind() {
        return kind;
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
