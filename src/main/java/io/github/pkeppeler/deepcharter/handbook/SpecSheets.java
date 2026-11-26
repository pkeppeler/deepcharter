package io.github.pkeppeler.deepcharter.handbook;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.fuel.FuelTuning;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodFuelItems;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.repair.RepairTuning;
import io.github.pkeppeler.deepcharter.suit.SuitTuning;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItem;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.github.pkeppeler.deepcharter.upgrade.PartUnits;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeStat;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeTuning;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Builds the spec sheet of an item from the numbers the game itself runs on: the part tiers of {@link UpgradeTuning} and the tunings
 * of the parts, the shop's {@link Consumable} prices and {@link RepairTuning}, the ores' {@link OreType} values, and the item's own stack
 * size and durability. There is no table of ratings to drift: a changed tuning changes the sheet.
 *
 * <p>Every Company item has a sheet with its own blurb ({@code deepcharter.spec.<path>}); any other item has a sheet that says
 * it is stock, with what it shares with all items. All text is lang keys under {@code deepcharter.spec}. The same code runs on the
 * client, which reads the tunings, the registries and the synced item tags the same way, so nothing but the lang is sent.
 */
public final class SpecSheets {
    private static final String KEY = "deepcharter.spec.";
    /** The part tiers' price column: a tier at this price is crafted, not sold. */
    private static final long CRAFTED = 0L;

    private SpecSheets() {}

    /** Whether {@code item} is Company issue: registered by this mod. */
    public static boolean isCompany(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getNamespace().equals(DeepCharter.MOD_ID);
    }

    /** The sheet of {@code item}. */
    public static SpecSheet of(Item item) {
        Identifier id = BuiltInRegistries.ITEM.getKey(item);
        boolean company = isCompany(item);
        List<SpecSheet.Rating> ratings = new ArrayList<>();
        if (company) {
            company(item, id, ratings);
        }
        shared(item, ratings);
        return new SpecSheet(
                item,
                Component.translatable(KEY + (company ? "kind.company" : "kind.stock")),
                Component.translatable(KEY + (company ? "item." + id.getPath() : "stock")),
                ratings);
    }

    private static void company(Item item, Identifier id, List<SpecSheet.Rating> ratings) {
        if (item instanceof ComponentItem part) {
            partTiers(part.track(), 0, ratings);
            return;
        }
        Optional<ComponentTrack> unit = PartUnits.trackOf(new ItemStack(item));
        if (unit.isPresent()) {
            partTiers(unit.get(), 1, ratings);
            return;
        }
        Optional<Consumable> consumable = Consumable.byItemId(id);
        if (consumable.isPresent()) {
            consumable(consumable.get(), ratings);
            return;
        }
        for (OreType ore : OreType.values()) {
            if (ore.itemId().equals(id) || ore.blockId().equals(id)) {
                ratings.add(rating("worth", "dollars", money(ore.value())));
            }
        }
        if (id.getPath().equals("reserve_tank")) {
            ratings.add(rating("tank", "litres", number(FuelTuning.DEFAULT.reserveLitres())));
        }
    }

    /** What every item has: its stack size, its durability when it wears, and whether a pod burns it. */
    private static void shared(Item item, List<SpecSheet.Rating> ratings) {
        ItemStack stack = new ItemStack(item);
        if (stack.is(PodFuelItems.TAG)) {
            ratings.add(rating("pod_fuel", "yes"));
        }
        ratings.add(rating("stack", "count", stack.getMaxStackSize()));
        if (stack.isDamageableItem()) {
            ratings.add(rating("durability", "count", stack.getMaxDamage()));
        }
    }

    /** One row for each tier of {@code track} from {@code first}: the tier and its price, then the track's figure at that tier. */
    private static void partTiers(ComponentTrack track, int first, List<SpecSheet.Rating> ratings) {
        UpgradeTuning tuning = UpgradeTuning.DEFAULT;
        int top = first == 1 ? 1 : track.maxTier();
        for (int tier = first; tier <= top; tier++) {
            if (tier == 0 && !tuning.hasStock(track)) {
                continue;
            }
            Component label = tier == 0
                    ? Component.translatable(KEY + "tier.stock")
                    : tuning.price(track, tier) == CRAFTED
                            ? Component.translatable(KEY + "tier.crafted", tier)
                            : Component.translatable(KEY + "tier.bought", tier, money(tuning.price(track, tier)));
            ratings.add(new SpecSheet.Rating(label, tierFigure(track, tier)));
        }
    }

    /**
     * The figure a track is rated for at {@code tier}: its first {@link UpgradeStat}, the one model of per-tier figures
     * the upgrade terminal reads too, so the sheet and the terminal cannot disagree. The spoil hopper has no figure.
     */
    private static Component tierFigure(ComponentTrack track, int tier) {
        String key = KEY + "value." + track.id();
        List<UpgradeStat> stats = UpgradeStat.of(track);
        return stats.isEmpty()
                ? Component.translatable(key)
                : Component.translatable(key, stats.getLast().figure(tier));
    }

    private static void consumable(Consumable consumable, List<SpecSheet.Rating> ratings) {
        RepairTuning tuning = RepairTuning.DEFAULT;
        ratings.add(rating("price", "dollars", money(consumable.price())));
        switch (consumable) {
            case RESERVE_FUEL_TANK -> ratings.add(rating("adds", "litres", number(tuning.reserveLitres())));
            case HULL_NANOBOTS -> ratings.add(rating("repairs", "hull", number(tuning.nanobotHp())));
            case DYNAMITE -> ratings.add(rating("clears", "cube", cube(tuning.dynamiteRadius())));
            case PLASTIC_EXPLOSIVES -> ratings.add(rating("clears", "cube", cube(tuning.plasticRadius())));
            case BLASTING_CHARGE -> {
                ratings.add(rating("clears", "slabs", tuning.chargeDepth()));
                ratings.add(rating("fuse", "seconds", number(tuning.chargeFuseTicks() / 20f)));
            }
            case SHAPED_CHARGE -> {
                ratings.add(rating("clears", "slabs", tuning.shapedDepth()));
                ratings.add(rating("fuse", "seconds", number(tuning.chargeFuseTicks() / 20f)));
            }
            case QUANTUM_TELEPORTER ->
                ratings.add(rating("scatter", "blocks", number((float) tuning.quantumScatter())));
            case AIR_CANISTER -> ratings.add(rating("adds", "seconds", number(SuitTuning.DEFAULT.canisterSeconds())));
            case MATTER_TRANSMITTER, TETHER, THROWN_FLARE, THROWN_DECOY, THROWN_CHARGE, BOLT_DRIVER, BOLT -> {}
        }
    }

    private static SpecSheet.Rating rating(String label, String value, Object... args) {
        return new SpecSheet.Rating(
                Component.translatable(KEY + "rating." + label), Component.translatable(KEY + "unit." + value, args));
    }

    /** The edge of the cube of blocks a blast with {@code radius} clears on each axis, as "3 x 3 x 3". */
    private static String cube(int radius) {
        int side = 2 * radius + 1;
        return side + " x " + side + " x " + side;
    }

    /** Whole dollars with a thousands separator, the same on every machine. */
    private static String money(long dollars) {
        return String.format(Locale.ROOT, "%,d", dollars);
    }

    /** A figure without a trailing {@code .0}: 4 for 4.0, 2.8 for 2.8. */
    private static String number(float value) {
        // Rounded to two places first, so a product such as 100 * 1.7 never prints as 169.99998.
        float rounded = Math.round(value * 100f) / 100f;
        return rounded == Math.rint(rounded) ? Long.toString((long) rounded) : Float.toString(rounded);
    }
}
