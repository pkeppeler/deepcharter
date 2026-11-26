package io.github.pkeppeler.deepcharter.ore;

import io.github.pkeppeler.deepcharter.DeepCharter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToLongFunction;
import net.minecraft.resources.Identifier;

/**
 * Every ore the mod adds. Value and reference mass are the original game's (dollars, and ore mass in its own unit);
 * {@link #mass()} is the mass in pod units. Cicatrium is the catalyst, and its numbers are invented: it is the dearest ore
 * (#1445), so selling it and keeping it for a restore is a choice (docs/design/mechanics.md, Cicatrium pacing).
 */
public enum OreType {
    IRONIUM("ironium", 30, 1),
    BRONZIUM("bronzium", 60, 1),
    SILVERIUM("silverium", 100, 1),
    GOLDIUM("goldium", 250, 2),
    PLATINIUM("platinium", 750, 3),
    EINSTEINIUM("einsteinium", 2000, 4),
    CICATRIUM("cicatrium", 2500, 2);

    private final String name;
    private final int value;
    private final int referenceMass;

    OreType(String name, int value, int referenceMass) {
        this.name = name;
        this.value = value;
        this.referenceMass = referenceMass;
    }

    /**
     * Every ore, dearest first by {@code price}; ores at one price keep the enum's order. A price list sorts here, never by the
     * enum's order, which stays fixed because the network carries ores by ordinal.
     */
    public static List<OreType> dearestFirst(ToLongFunction<OreType> price) {
        return Arrays.stream(values())
                .sorted(Comparator.comparingLong(price).reversed())
                .toList();
    }

    /** What a terminal pays for one, in dollars. */
    public int value() {
        return value;
    }

    /** Mass of one in pod units, the unit of engine power: what it adds to a pod's cargo mass and to a carrier's load. */
    public float mass() {
        return referenceMass * OreTuning.DEFAULT.massScale();
    }

    /** The id of the ore item. */
    public Identifier itemId() {
        return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, name);
    }

    /** The id of the ore block, which the drill turns into the ore item. */
    public Identifier blockId() {
        return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, name + "_ore");
    }
}
