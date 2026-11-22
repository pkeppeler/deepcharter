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
    // Thrown by hand on foot (#985, CREATURES section 8): the operator's rounds without the launcher. Each costs a
    // fraction of a layer 1 run, so a fight costs money without ending the run. ThrownKitPacingTest holds the three to
    // their bands.
    THROWN_FLARE("thrown_flare", 12, true),
    // Not sold yet: nothing on main is drawn to the drill, so no creature feels its pull. On sale with the first
    // drill-drawn creature (Drifters, #918, PR #975): whichever lands second flips this and adds the steer call.
    THROWN_DECOY("thrown_decoy", 25, false),
    THROWN_CHARGE("thrown_charge", 55, true);

    private final Identifier itemId;
    private final long price;
    private final boolean thrown;
    private final boolean sold;

    Consumable(String path, long price) {
        this(path, price, false, true);
    }

    /** A thrown item: {@code sold} is whether the repair station offers it. */
    Consumable(String path, long price, boolean sold) {
        this(path, price, true, sold);
    }

    private Consumable(String path, long price, boolean thrown, boolean sold) {
        this.itemId = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path);
        this.price = price;
        this.thrown = thrown;
        this.sold = sold;
    }

    /** Whether the item is thrown by hand on foot ({@link ThrownKit}), not used from the pilot's seat. */
    public boolean thrown() {
        return thrown;
    }

    /** Whether the repair station lists the item and sells it. An item that is not sold is still registered, priced and works when held. */
    public boolean sold() {
        return sold;
    }

    public Identifier itemId() {
        return itemId;
    }

    /** Dollars, taken from the charter's account. */
    public long price() {
        return price;
    }

    public static Optional<Consumable> byItemId(Identifier id) {
        return Arrays.stream(values())
                .filter(consumable -> consumable.itemId.equals(id))
                .findFirst();
    }
}
