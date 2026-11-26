package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.sound.phonograph.PhonographRegistry;
import io.github.pkeppeler.deepcharter.sound.phonograph.Recording;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The drops of every loot table the mod ships, from the real {@code LootTable} with fixed seeds (#469). The tables are in the 26.3 format;
 * the pre-26.3 {@code conditions} and {@code functions} keys load without error and are ignored, so only a roll shows that a condition or a
 * count applies. No shipped table reads the tool, so silk touch and fortune change nothing, and a test pins that too.
 */
public class LootTableDropsTest {
    /** The one item that each single-item block drops: independent literals, not read from the registry. */
    private static final Map<String, String> SINGLE_DROP = Map.ofEntries(
            Map.entry("basalt_outcrop", "basalt_outcrop"),
            Map.entry("bronzium_ore", "bronzium"),
            Map.entry("cicatrium_ore", "cicatrium"),
            Map.entry("einsteinium_ore", "einsteinium"),
            Map.entry("fungal_stalk", "fungal_stalk"),
            Map.entry("goldium_ore", "goldium"),
            Map.entry("great_stalk", "fungal_stalk"),
            Map.entry("great_stalk_cap", "fungal_stalk"),
            Map.entry("ironium_ore", "ironium"),
            Map.entry("ochre_regolith", "ochre_regolith"),
            Map.entry("phonograph", "phonograph"),
            Map.entry("platinium_ore", "platinium"),
            Map.entry("regolith", "regolith"),
            Map.entry("regolith_packed", "regolith_packed"),
            Map.entry("regolith_rock", "regolith_rock"),
            Map.entry("silverium_ore", "silverium"),
            Map.entry("slag_brick", "slag_brick"),
            Map.entry("stalk_planks", "stalk_planks"),
            Map.entry("packed_shale", "packed_shale"),
            Map.entry("packed_shale_rubble", "packed_shale_rubble"),
            Map.entry("hardpan", "hardpan"),
            Map.entry("black_shale", "black_shale"),
            Map.entry("lunch_pail", "lunch_pail"),
            Map.entry("prayer_card", "prayer_card"),
            Map.entry("green_shale", "green_shale"),
            Map.entry("green_shale_rubble", "green_shale_rubble"),
            Map.entry("basalt_core", "basalt_core"),
            Map.entry("columnar_basalt", "columnar_basalt"),
            Map.entry("glowcap", "glowcap"));

    /** The salvage crate's pools: item to its fewest and most per roll. */
    private record Range(String item, int min, int max) {}

    private static final List<Range> CRATE = List.of(
            new Range("minecraft:oak_planks", 1, 3),
            new Range("minecraft:stick", 0, 2),
            new Range("deepcharter:salvage_cloth", 0, 1),
            new Range("deepcharter:scrap_iron", 0, 2),
            new Range("deepcharter:scrap_copper", 0, 2),
            new Range("minecraft:redstone", 0, 1),
            new Range("deepcharter:company_ration", 0, 1));

    /** Seeds 1 to this: seed 0 means "no seed" to a loot context, which then rolls from the world's random sequence. */
    private static final int SEEDS = 200;
    /** A blast so far from the block that its survival chance (1 over the radius) is about one in a million. */
    private static final float FAR_BLAST = 1_000_000f;

    @GameTest
    public void everyShippedLootTableIsCoveredHere(GameTestHelper helper) {
        Set<String> shipped = new TreeSet<>();
        helper.getLevel()
                .getServer()
                .reloadableRegistries()
                .lookup()
                .lookupOrThrow(Registries.LOOT_TABLE)
                .listElementIds()
                .map(ResourceKey::identifier)
                .filter(id -> id.getNamespace().equals(DeepCharter.MOD_ID))
                .forEach(id -> shipped.add(id.getPath()));
        Set<String> covered = new TreeSet<>();
        SINGLE_DROP.keySet().forEach(path -> covered.add("blocks/" + path));
        covered.add("blocks/salvage_crate");
        covered.add("chests/wreck_locker");
        if (!shipped.equals(covered)) {
            throw helper.assertionException("the mod ships loot tables %s and this test covers %s", shipped, covered);
        }
        helper.succeed();
    }

    @GameTest
    public void aSingleDropBlockDropsItsItemOnceOnEverySeed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        new TreeMap<>(SINGLE_DROP).forEach((path, item) -> {
            for (long seed = 1; seed <= SEEDS; seed++) {
                List<ItemStack> drops = roll(level, path, seed, null);
                if (!names(drops).equals(List.of("deepcharter:" + item))
                        || drops.getFirst().getCount() != 1) {
                    throw helper.assertionException(
                            "%s on seed %s should drop 1 of deepcharter:%s, it drops %s", path, seed, item, drops);
                }
            }
        });
        helper.succeed();
    }

    /** {@code survives_explosion} keeps a drop with the chance of 1 over the blast radius: a radius of 1 keeps it always, a far blast loses it. */
    @GameTest
    public void aBlastAtTheBlockKeepsTheDropAndAFarBlastLosesIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        new TreeMap<>(SINGLE_DROP).forEach((path, item) -> {
            for (long seed = 1; seed <= SEEDS; seed++) {
                List<String> near = names(roll(level, path, seed, 1f));
                if (!near.equals(List.of("deepcharter:" + item))) {
                    throw helper.assertionException(
                            "%s on seed %s with blast radius 1 should drop [deepcharter:%s], it drops %s",
                            path, seed, item, near);
                }
                List<String> far = names(roll(level, path, seed, FAR_BLAST));
                if (!far.isEmpty()) {
                    throw helper.assertionException(
                            "%s on seed %s with blast radius %s should drop nothing, it drops %s",
                            path, seed, FAR_BLAST, far);
                }
            }
        });
        helper.succeed();
    }

    @GameTest
    public void theSalvageCrateDropsEachItemWithinItsCountAndReachesBothEnds(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Map<String, Set<Integer>> seen = new TreeMap<>();
        Set<String> known = new TreeSet<>();
        CRATE.forEach(range -> known.add(range.item()));
        // The rare cylinder is not in CRATE: its own tests below count it.
        known.add("deepcharter:wax_cylinder");
        for (long seed = 1; seed <= SEEDS; seed++) {
            List<ItemStack> drops = roll(level, "salvage_crate", seed, null);
            for (Range range : CRATE) {
                int count = drops.stream()
                        .filter(stack -> id(stack).equals(range.item()))
                        .mapToInt(ItemStack::getCount)
                        .sum();
                if (count < range.min() || count > range.max()) {
                    throw helper.assertionException(
                            "salvage_crate seed %s drops %s of %s, expected %s to %s",
                            seed, count, range.item(), range.min(), range.max());
                }
                seen.computeIfAbsent(range.item(), key -> new TreeSet<>()).add(count);
            }
            for (ItemStack stack : drops) {
                if (!known.contains(id(stack))) {
                    throw helper.assertionException(
                            "salvage_crate seed %s drops %s, which is not in its table", seed, id(stack));
                }
            }
        }
        for (Range range : CRATE) {
            Set<Integer> counts = new TreeSet<>();
            for (int count = range.min(); count <= range.max(); count++) {
                counts.add(count);
            }
            if (!Objects.requireNonNull(seen.get(range.item()), "seen.get(range.item()) is absent")
                    .equals(counts)) {
                throw helper.assertionException(
                        "salvage_crate drops %s in counts %s over %s seeds, expected every count of %s",
                        range.item(), seen.get(range.item()), SEEDS, counts);
            }
        }
        helper.succeed();
    }

    /** The ration pool: one at most per crate, and over the seeds some crate holds one. */
    @GameTest
    public void theSalvageCrateSometimesHoldsOneCompanyRation(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int holding = 0;
        for (long seed = 1; seed <= SEEDS; seed++) {
            int rations = roll(level, "salvage_crate", seed, null).stream()
                    .filter(stack -> id(stack).equals("deepcharter:company_ration"))
                    .mapToInt(ItemStack::getCount)
                    .sum();
            if (rations > 1) {
                throw helper.assertionException("salvage_crate seed %s holds %s rations, at most 1", seed, rations);
            }
            holding += rations;
        }
        if (holding == 0) {
            throw helper.assertionException("no salvage crate of %s seeds holds a Company ration", SEEDS);
        }
        helper.succeed();
    }

    @GameTest
    public void aFarBlastLosesTheWholeSalvageCrate(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (long seed = 1; seed <= SEEDS; seed++) {
            List<String> far = names(roll(level, "salvage_crate", seed, FAR_BLAST));
            if (!far.isEmpty()) {
                throw helper.assertionException(
                        "salvage_crate seed %s with blast radius %s should drop nothing, it drops %s",
                        seed, FAR_BLAST, far);
            }
        }
        helper.succeed();
    }

    /** The two recordings that are the colony's alone (LORE guardrail 6: a child's rhyme is not for the deep): in no loot table. */
    private static final Set<Recording> COLONY_ONLY = Set.of(Recording.EDISON_DOLL, Recording.VOLTA_NURSERY_RHYME);

    /** The recordings a wreck locker or a salvage crate can hold: every one but the colony's own. */
    private static final Set<Recording> FOUND = Arrays.stream(Recording.values())
            .filter(recording -> !COLONY_ONLY.contains(recording))
            .collect(Collectors.toUnmodifiableSet());

    /**
     * A rare find is measured over many more seeds than the common drops: enough that each of the recordings (eight since #946) comes up
     * about 60 times in the crates, whose cylinder is the rarer.
     */
    private static final int RARE_SEEDS = 12000;

    /** The salvage crate's cylinder: a few in a hundred crates, a single one, and any of the recordings. */
    @GameTest(maxTicks = 400)
    public void aSalvageCrateRarelyDropsOneWaxCylinder(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Map<Recording, Integer> found = new TreeMap<>();
        int crates = 0;
        for (long seed = 1; seed <= RARE_SEEDS; seed++) {
            List<ItemStack> cylinders = cylinders(roll(level, "salvage_crate", seed, null));
            if (cylinders.size() > 1) {
                throw helper.assertionException("salvage_crate seed %s drops %s cylinders", seed, cylinders.size());
            }
            for (ItemStack cylinder : cylinders) {
                crates++;
                found.merge(recording(helper, cylinder), 1, Integer::sum);
            }
        }
        expectRare(helper, "salvage_crate", crates, RARE_SEEDS, 0.03, 0.05, found);
        helper.succeed();
    }

    /** The wreck's locker: scrap, and a cylinder in about three bays in ten. */
    @GameTest(maxTicks = 400)
    public void aWreckLockerHoldsScrapAndNowAndThenOneWaxCylinder(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Set<String> allowed = Set.of(
                "deepcharter:scrap_iron",
                "deepcharter:scrap_copper",
                "deepcharter:salvage_cloth",
                "minecraft:redstone",
                "deepcharter:wax_cylinder");
        Map<Recording, Integer> found = new TreeMap<>();
        int lockers = 0;
        for (long seed = 1; seed <= RARE_SEEDS; seed++) {
            List<ItemStack> loot = lockerLoot(level, seed);
            if (loot.isEmpty()) {
                throw helper.assertionException("the wreck locker on seed %s is empty", seed);
            }
            for (ItemStack stack : loot) {
                if (!allowed.contains(id(stack))) {
                    throw helper.assertionException("the wreck locker on seed %s holds %s", seed, id(stack));
                }
            }
            List<ItemStack> cylinders = cylinders(loot);
            if (cylinders.size() > 1) {
                throw helper.assertionException(
                        "the wreck locker on seed %s holds %s cylinders", seed, cylinders.size());
            }
            for (ItemStack cylinder : cylinders) {
                lockers++;
                found.merge(recording(helper, cylinder), 1, Integer::sum);
            }
        }
        expectRare(helper, "wreck_locker", lockers, RARE_SEEDS, 0.26, 0.34, found);
        helper.succeed();
    }

    private static void expectRare(
            GameTestHelper helper,
            String table,
            int cylinders,
            int rolls,
            double least,
            double most,
            Map<Recording, Integer> found) {
        double share = (double) cylinders / rolls;
        if (share < least || share > most) {
            throw helper.assertionException(
                    "%s drops a cylinder in %s of %s rolls (%s), expected between %s and %s",
                    table, cylinders, rolls, share, least, most);
        }
        if (!found.keySet().equals(FOUND)) {
            throw helper.assertionException(
                    "%s drops the recordings %s, expected every one but the colony's own: %s", table, found, FOUND);
        }
        // Each recording is as likely as the next: one in however many there are, give or take 40 per cent. A doubled
        // weight is 78 per
        // cent over and a halved one 47 per cent under, so either fails, and so does a table that drops only the first
        // few.
        double each = 1.0 / FOUND.size();
        for (Map.Entry<Recording, Integer> entry : found.entrySet()) {
            double of = (double) entry.getValue() / cylinders;
            if (of < each * 0.6 || of > each * 1.4) {
                throw helper.assertionException(
                        "%s drops %s in %s of its cylinders, expected about %s", table, entry.getKey(), of, each);
            }
        }
    }

    /** The cylinders among drops; any of them carries one recording of the cylinders' own set and nothing else on it. */
    private static List<ItemStack> cylinders(List<ItemStack> drops) {
        return drops.stream()
                .filter(stack -> stack.is(PhonographRegistry.WAX_CYLINDER))
                .toList();
    }

    private static Recording recording(GameTestHelper helper, ItemStack cylinder) {
        Recording recording = cylinder.get(PhonographRegistry.RECORDING);
        if (recording == null
                || cylinder.getCount() != 1
                || cylinder.getComponentsPatch().size() > 1) {
            throw helper.assertionException("a dropped cylinder is not a lone recording: %s", cylinder);
        }
        return recording;
    }

    private static List<ItemStack> lockerLoot(ServerLevel level, long seed) {
        ResourceKey<LootTable> key = ResourceKey.create(
                Registries.LOOT_TABLE, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "chests/wreck_locker"));
        LootTable table = level.getServer().reloadableRegistries().getLootTable(key);
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.ZERO)
                .create(LootContextParamSets.CHEST);
        return table.getRandomItems(params, seed).stream()
                .filter(stack -> !stack.isEmpty())
                .toList();
    }

    /** No shipped table reads the tool: a silk touch pick and a fortune pick drop what an empty hand does. */
    @GameTest
    public void theToolDoesNotChangeAnyDrop(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<String> paths = new ArrayList<>(SINGLE_DROP.keySet());
        paths.add("salvage_crate");
        for (String path : paths) {
            for (long seed = 1; seed <= 20; seed++) {
                List<String> bare = names(roll(level, path, seed, null));
                List<String> picked = names(roll(level, path, seed, null, new ItemStack(Items.DIAMOND_PICKAXE)));
                if (!bare.equals(picked)) {
                    throw helper.assertionException(
                            "%s on seed %s drops %s by hand and %s with a pickaxe", path, seed, bare, picked);
                }
            }
        }
        helper.succeed();
    }

    private static List<ItemStack> roll(ServerLevel level, String path, long seed, @Nullable Float blastRadius) {
        return roll(level, path, seed, blastRadius, ItemStack.EMPTY);
    }

    private static List<ItemStack> roll(
            ServerLevel level, String path, long seed, @Nullable Float blastRadius, ItemStack tool) {
        var block = BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path));
        ResourceKey<LootTable> key = block.getLootTable().orElseThrow();
        LootTable table = level.getServer().reloadableRegistries().getLootTable(key);
        LootParams.Builder builder = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.ZERO)
                .withParameter(LootContextParams.BLOCK_STATE, block.defaultBlockState())
                .withParameter(LootContextParams.TOOL, tool);
        if (blastRadius != null) {
            builder.withParameter(LootContextParams.EXPLOSION_RADIUS, blastRadius);
        }
        return table.getRandomItems(builder.create(LootContextParamSets.BLOCK), seed).stream()
                .filter(stack -> !stack.isEmpty())
                .toList();
    }

    private static List<String> names(List<ItemStack> stacks) {
        return stacks.stream().map(LootTableDropsTest::id).sorted().toList();
    }

    private static String id(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
