package io.github.pkeppeler.deepcharter.creature;

import java.util.List;
import net.minecraft.util.RandomSource;

/**
 * The three forms a Drifter takes (#1203). A spawn picks one ({@link #pick}), and it is synced so every client draws
 * the same. Each is one model, texture, glowmask and set of animations; {@link #id()} names its files.
 */
public enum DrifterVariant {
    /** A translucent bell around a glowing core, with trailing tendrils. */
    JELLYFISH("jellyfish"),
    /** A veined puffball with gills underneath. */
    SAC("sac"),
    /** A string of small glowing bulbs. */
    CHAIN("chain");

    private final String id;

    DrifterVariant(String id) {
        this.id = id;
    }

    /** The name its files carry: {@code drifter_<id>}. */
    public String id() {
        return id;
    }

    /** The variant with this {@link #ordinal()}; an id out of range (a newer server's) is a jellyfish. */
    public static DrifterVariant byOrdinal(int ordinal) {
        DrifterVariant[] all = values();
        return ordinal >= 0 && ordinal < all.length ? all[ordinal] : JELLYFISH;
    }

    /**
     * Draws one by weight, one {@link RandomSource#nextDouble()} call, so a seeded source gives the same variant each
     * time.
     */
    public static DrifterVariant pick(RandomSource random, List<Double> weights) {
        double total = weights.stream().mapToDouble(Double::doubleValue).sum();
        double roll = random.nextDouble() * total;
        DrifterVariant[] all = values();
        for (int i = 0; i < all.length - 1; i++) {
            roll -= weights.get(i);
            if (roll < 0) {
                return all[i];
            }
        }
        return all[all.length - 1];
    }
}
