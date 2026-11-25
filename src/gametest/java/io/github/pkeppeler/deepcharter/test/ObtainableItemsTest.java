package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.colony.ColonyBuilder;
import io.github.pkeppeler.deepcharter.handbook.ObtainableItems;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerStructures;
import io.github.pkeppeler.deepcharter.layer.StructureKind;
import io.github.pkeppeler.deepcharter.layer.StructureSite;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.BlockTransformer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.carver.WorldCarver;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server GameTests for #1263 (ADR 0093): a recipe that needs or makes an item the campaign world has none of is not
 * loaded, so it cannot be crafted, while a tag ingredient with one listed member keeps its recipe; and the list in the
 * {@code deepcharter:obtainable} item tag is what the world yields, derived here from the world itself.
 *
 * <p>The derivation starts from every item of ours, the blocks the world is made of (the campaign noise settings and
 * the features of our biomes, the colony's structure files, the layer structures as they generate, the colony's fill),
 * the creatures our biomes and our code spawn, and the loot tables we ship, and closes over: breaking a block (its loot
 * table, with a tool that can harvest it), crafting (a recipe whose every ingredient has an obtainable item and whose
 * station is obtainable makes its results obtainable), placing a block item and breaking it again, a tool's block
 * transform (stripping a log), copper weathering, a bucket of a fluid the world holds, and the composter. It fails,
 * naming the source to teach it, when the world holds something it does not walk: a vanilla structure in one of our
 * biomes, or a loot table of ours it cannot roll.
 */
public class ObtainableItemsTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(ObtainableItemsTest.class);
    /** The spacing cell of the layer structures to read; {@code LayerStructuresTest} generates the same one. */
    private static final int CELL = LayerStructuresTest.CELL;
    /** Seeds 1 to this: seed 0 means "no seed" to a loot context. A drop of under about 5 % a roll may go unseen. */
    private static final int SEEDS = 64;
    /**
     * Seeds for a tool after the first that harvests a block: another tool changes a drop only where the table matches
     * it (shears take a cobweb whole), which is no chance roll.
     */
    private static final int OTHER_TOOL_SEEDS = 8;
    /**
     * A block with at most this many states is broken in each of them (a bed drops from its head half only); one with
     * more, in its default.
     */
    private static final int ALL_STATES_UP_TO = 32;

    private static final Identifier LIST_FILE =
            Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "tags/item/obtainable.json");

    private static boolean isVanilla(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getNamespace().equals(Identifier.DEFAULT_NAMESPACE);
    }

    private static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    private static boolean loaded(MinecraftServer server, String path) {
        return loaded(server, Identifier.withDefaultNamespace(path));
    }

    private static boolean loaded(MinecraftServer server, Identifier id) {
        return server.getRecipeManager()
                .byKey(ResourceKey.create(Registries.RECIPE, id))
                .isPresent();
    }

    /**
     * Puts {@code stacks} in the 2 x 2 grid of the player's inventory, row by row, and reads what the result slot
     * offers.
     */
    private static ItemStack craftInHand(ServerPlayer player, List<ItemStack> stacks) {
        InventoryMenu menu = player.inventoryMenu;
        for (int index = 0; index < stacks.size(); index++) {
            menu.getSlot(InventoryMenu.CRAFT_SLOT_START + index)
                    .set(stacks.get(index).copy());
        }
        return menu.getSlot(InventoryMenu.RESULT_SLOT).getItem();
    }

    @GameTest
    public void aRecipeThatMakesAnUnlistedItemIsNotLoadedAndCannotBeCrafted(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        expect(
                helper,
                !loaded(server, "jungle_planks"),
                "the jungle planks recipe is loaded, but the world has no jungle");
        expect(
                helper,
                !loaded(server, "diamond_pickaxe"),
                "the diamond pickaxe recipe is loaded, but the world has no diamond");
        ServerPlayer player = MockPlayers.join(helper, "Crafter").player();

        ItemStack result = craftInHand(player, List.of(new ItemStack(Items.JUNGLE_LOG)));

        expect(helper, result.isEmpty(), "a jungle log in the grid offers %s, should offer nothing", result);
        helper.succeed();
    }

    @GameTest
    public void aRecipeThatNeedsAnUnlistedItemIsNotLoadedThoughItMakesAListedOne(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        expect(
                helper,
                ObtainableItems.isObtainable(new ItemStack(Items.STICK)),
                "sticks are listed (salvage crates drop them)");
        expect(
                helper,
                !loaded(server, "stick_from_bamboo_item"),
                "sticks from bamboo are loaded, but the world has no bamboo");
        helper.succeed();
    }

    @GameTest
    public void aTagIngredientWithOneListedMemberKeepsItsRecipe(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        expect(
                helper,
                loaded(server, "chest"),
                "the chest takes any planks and oak planks are listed, so it is loaded");
        ServerPlayer player = MockPlayers.join(helper, "Carpenter").player();

        ItemStack result = craftInHand(player, List.of(new ItemStack(Items.OAK_LOG)));

        expect(
                helper,
                result.is(Items.OAK_PLANKS),
                "an oak log in the grid offers %s, should offer oak planks",
                result);
        helper.succeed();
    }

    @GameTest
    public void everyRecipeOfOursIsLoaded(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        List<String> missing = server.reloadableRegistries()
                .lookup()
                .lookupOrThrow(Registries.RECIPE)
                .listElementIds()
                .filter(key -> key.identifier().getNamespace().equals(DeepCharter.MOD_ID))
                .filter(key -> server.getRecipeManager().byKey(key).isEmpty())
                .map(key -> key.identifier().toString())
                .sorted()
                .toList();
        expect(
                helper,
                missing.isEmpty(),
                "recipes of ours are not loaded, so an item they need is not listed: %s",
                missing);
        helper.succeed();
    }

    // ---------------------------------------------------------------- the drift gate

    /**
     * A layer structure site and the band of the level it stands in. The Prospector's wreck site holds its Note (N10),
     * which an ordinary wreck site does not, so a Note there is no sign it is unfinished.
     */
    private record Site(ServerLevel level, LayerChain.Band band, StructureSite site, boolean prospector) {}

    /**
     * The gate: the list is what the world yields, both ways. The layer structures are read as they generate, so the
     * chunks of one site of each kind (and the Prospector's) are forced first and polled under a wall-clock deadline
     * until they are loaded and each stands.
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 600)
    public void theListIsWhatTheCampaignWorldYields(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        BlockPos conduit = Colony.anchor(server, ColonyAnchor.CONDUIT)
                .orElseThrow(() -> failure(helper, "the colony was not built"));
        ServerLevel wrecks = TestLayers.level(helper, StructureKind.WRECK.layer());
        List<Site> sites = sites(helper, conduit, wrecks);
        // Loaded on the worker threads; a synchronous getChunk here would hold the server thread (#1016).
        for (Site site : sites) {
            for (var chunk : LayerStructuresTest.chunksOf(site.site())) {
                FarChunks.force(helper, site.level(), chunk.x(), chunk.z());
            }
        }
        FarChunks.Deadline deadline = FarChunks.deadline();
        TestTicks.each(helper, () -> {
            List<String> standing = notStanding(sites);
            if (!standing.isEmpty()) {
                deadline.await(
                        helper, wrecks, false, () -> String.format("the layer structures did not stand: %s", standing));
                return;
            }
            Set<Block> world = new LinkedHashSet<>();
            Set<EntityType<?>> creatures = new LinkedHashSet<>();
            sites.forEach(site -> world.addAll(blocksIn(site)));
            world.addAll(worldgenBlocks(helper, server, creatures));
            world.addAll(colonyBlocks(server));
            world.add(ColonyBuilder.FILL);
            BuiltInRegistries.BLOCK
                    .listElements()
                    .filter(holder -> holder.key().identifier().getNamespace().equals(DeepCharter.MOD_ID))
                    .forEach(holder -> world.add(holder.value()));
            // Creatures our code spawns (drifters, the Tally) are not in a biome's spawns.
            BuiltInRegistries.ENTITY_TYPE
                    .listElements()
                    .filter(holder -> holder.key().identifier().getNamespace().equals(DeepCharter.MOD_ID))
                    .forEach(holder -> creatures.add(holder.value()));
            Set<Item> derived = new Derivation(helper, world, creatures).run();
            compare(helper, derived);
            helper.succeed();
        });
    }

    /** One site of each layer structure kind, and the Prospector's. */
    private static List<Site> sites(GameTestHelper helper, BlockPos conduit, ServerLevel wrecks) {
        List<Site> sites = new ArrayList<>();
        for (int layer = 1; layer <= 2; layer++) {
            ServerLevel level = TestLayers.level(helper, layer);
            LayerChain.Band band = TestLayers.band(level, layer);
            for (StructureKind kind : StructureKind.inLayer(layer)) {
                sites.add(new Site(
                        level,
                        band,
                        StructureSite.in(level.getSeed(), kind, band.minY(), band.height(), CELL, CELL, conduit),
                        false));
            }
        }
        Optional<StructureSite> prospector = LayerStructures.prospector(wrecks);
        expect(helper, prospector.isPresent(), "the layer of wrecks has no Prospector site");
        sites.add(
                new Site(wrecks, TestLayers.band(wrecks, StructureKind.WRECK.layer()), prospector.orElseThrow(), true));
        return sites;
    }

    /** What keeps each site from standing yet; empty once every one stands. */
    private static List<String> notStanding(List<Site> sites) {
        List<String> standing = new ArrayList<>();
        for (Site site : sites) {
            if (!LayerStructuresTest.loaded(site.level(), site.site())) {
                standing.add(site.site().kind() + " not loaded yet");
                continue;
            }
            List<String> unmet = LayerStructuresTest.unmet(
                    site.level(),
                    site.band().minY(),
                    site.band().height(),
                    site.site(),
                    LayerStructuresTest.census(site.level(), site.site()));
            if (site.prospector()) {
                unmet = unmet.stream().filter(line -> !line.startsWith("Notes")).toList();
            }
            if (!unmet.isEmpty()) {
                standing.add(site.site().kind() + " " + unmet);
            }
        }
        return standing;
    }

    private static void compare(GameTestHelper helper, Set<Item> derived) {
        Set<String> yielded = derived.stream()
                .filter(ObtainableItemsTest::isVanilla)
                .filter(item -> item != Items.AIR)
                .map(ObtainableItemsTest::id)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> listed = new TreeSet<>();
        BuiltInRegistries.ITEM.getTagOrEmpty(ObtainableItems.LISTED).forEach(holder -> listed.add(id(holder.value())));
        Set<String> unlisted = new TreeSet<>(yielded);
        unlisted.removeAll(listed);
        Set<String> unyielded = new TreeSet<>(listed);
        unyielded.removeAll(yielded);
        if (!unlisted.isEmpty() || !unyielded.isEmpty()) {
            LOGGER.info("[obtainable] the world yields {} vanilla items: {}", yielded.size(), yielded);
            throw failure(
                    helper,
                    "data/%s/%s drifted from what the campaign world yields. Add %s; remove %s",
                    LIST_FILE.getNamespace(),
                    LIST_FILE.getPath(),
                    unlisted,
                    unyielded);
        }
    }

    /** Every block in the site's bounds now. */
    private static Set<Block> blocksIn(Site site) {
        Set<Block> found = new LinkedHashSet<>();
        BoundingBox box = site.site().bounds();
        for (BlockPos pos :
                BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
            found.add(site.level().getBlockState(pos).getBlock());
        }
        return found;
    }

    /**
     * The blocks the campaign world's data names: its noise settings and the features and carvers of every biome of
     * ours, encoded as data and read for every string that names a block. The creatures our biomes spawn go into
     * {@code creatures}. Fails when a vanilla structure may generate in one of our biomes, since its pieces are not
     * walked here.
     */
    private static Set<Block> worldgenBlocks(
            GameTestHelper helper, MinecraftServer server, Set<EntityType<?>> creatures) {
        HolderLookup.Provider registries = server.registryAccess();
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        List<JsonElement> encoded = new ArrayList<>();
        NoiseGeneratorSettings campaign = registries
                .lookupOrThrow(Registries.NOISE_SETTINGS)
                .getOrThrow(ResourceKey.create(
                        Registries.NOISE_SETTINGS, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "campaign")))
                .value();
        encoded.add(
                NoiseGeneratorSettings.DIRECT_CODEC.encodeStart(ops, campaign).getOrThrow());
        List<Holder.Reference<Biome>> ours = registries
                .lookupOrThrow(Registries.BIOME)
                .listElements()
                .filter(holder -> holder.key().identifier().getNamespace().equals(DeepCharter.MOD_ID))
                .toList();
        for (Holder.Reference<Biome> biome : ours) {
            for (HolderSet<PlacedFeature> step :
                    biome.value().getGenerationSettings().features()) {
                for (Holder<PlacedFeature> placed : step) {
                    Feature feature = placed.value().feature().value();
                    encoded.add(Feature.DIRECT_CODEC.encodeStart(ops, feature).getOrThrow());
                }
            }
            biome.value()
                    .getGenerationSettings()
                    .getCarvers()
                    .forEach(carver -> encoded.add(WorldCarver.DIRECT_CODEC
                            .encodeStart(ops, carver.value())
                            .getOrThrow()));
            MobSpawnSettings spawns = Objects.requireNonNullElse(
                    biome.value()
                            .getAttributes()
                            .applyModifier(
                                    EnvironmentAttributes.NATURAL_MOB_SPAWNS,
                                    EnvironmentAttributes.NATURAL_MOB_SPAWNS.defaultValue()),
                    MobSpawnSettings.EMPTY);
            for (MobCategory category : MobCategory.values()) {
                spawns.getMobsToSpawn(category)
                        .unwrap()
                        .forEach(spawn -> creatures.add(spawn.value().type()));
            }
        }
        registries.lookupOrThrow(Registries.STRUCTURE).listElements().forEach(structure -> {
            Structure value = structure.value();
            for (Holder.Reference<Biome> biome : ours) {
                expect(
                        helper,
                        !value.biomes().contains(biome),
                        "the structure %s may generate in %s, and this derivation does not walk its pieces",
                        structure.key().identifier(),
                        biome.key().identifier());
            }
        });
        Set<Block> blocks = new LinkedHashSet<>();
        encoded.forEach(json -> collectBlocks(json, blocks));
        return blocks;
    }

    private static void collectBlocks(JsonElement json, Set<Block> blocks) {
        if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()) {
            Identifier id = Identifier.tryParse(json.getAsString());
            if (id != null) {
                BuiltInRegistries.BLOCK.getOptional(id).ifPresent(blocks::add);
            }
        } else if (json.isJsonArray()) {
            json.getAsJsonArray().forEach(element -> collectBlocks(element, blocks));
        } else if (json.isJsonObject()) {
            json.getAsJsonObject().entrySet().forEach(entry -> collectBlocks(entry.getValue(), blocks));
        }
    }

    /** The palette of every structure file of the colony. */
    private static Set<Block> colonyBlocks(MinecraftServer server) {
        Set<Block> blocks = new LinkedHashSet<>();
        Map<Identifier, Resource> files = new TreeMap<>(server.getResourceManager()
                .listResources("structure/colony", path -> path.getPath().endsWith(".nbt")));
        HolderLookup<Block> lookup = server.registryAccess().lookupOrThrow(Registries.BLOCK);
        files.forEach((path, resource) -> {
            try (InputStream in = resource.open()) {
                CompoundTag structure = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
                structure
                        .getListOrEmpty("palette")
                        .forEach(entry -> blocks.add(NbtUtils.readBlockState(lookup, (CompoundTag) entry)
                                .getBlock()));
            } catch (IOException e) {
                throw new UncheckedIOException("reading " + path, e);
            }
        });
        if (blocks.isEmpty()) {
            throw new IllegalStateException("no colony structure file was read");
        }
        return blocks;
    }

    /** The closure over the ways to get an item from what the world holds; see the class comment. */
    private static final class Derivation {
        /** Sets a block with no neighbour update, no {@code onPlace} and no drop from the block it replaces. */
        private static final int QUIET = Block.UPDATE_CLIENTS
                | Block.UPDATE_KNOWN_SHAPE
                | Block.UPDATE_SKIP_ON_PLACE
                | Block.UPDATE_SUPPRESS_DROPS;

        private final GameTestHelper helper;
        private final ServerLevel level;
        private final MinecraftServer server;
        private final ServerPlayer player;
        private final BlockPos at;
        private final ContextMap context;
        private final Set<Item> obtainable = new LinkedHashSet<>();
        private final Set<Block> blocks = new LinkedHashSet<>();
        private final Set<String> rolled = new HashSet<>();
        private final Set<String> transformed = new HashSet<>();
        private final Set<EntityType<?>> creatures;

        Derivation(GameTestHelper helper, Set<Block> world, Set<EntityType<?>> creatures) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.server = level.getServer();
            this.player = MockPlayers.join(helper, "Deriver").player();
            this.at = helper.absolutePos(new BlockPos(2, 2, 2));
            this.context = SlotDisplayContext.fromLevel(level);
            blocks.addAll(world);
            this.creatures = creatures;
            BuiltInRegistries.ITEM
                    .listElements()
                    .filter(holder -> !holder.key().identifier().getNamespace().equals(Identifier.DEFAULT_NAMESPACE))
                    .forEach(holder -> obtainable.add(holder.value()));
        }

        Set<Item> run() {
            rollOurTables();
            creatures.forEach(this::killCreature);
            boolean grew = true;
            while (grew) {
                int items = obtainable.size();
                int placed = blocks.size();
                for (Item item : List.copyOf(obtainable)) {
                    if (item instanceof BlockItem block) {
                        blocks.add(block.getBlock());
                    }
                }
                List<ItemStack> tools = tools();
                for (Block block : List.copyOf(blocks)) {
                    WeatheringCopper.getNext(block).ifPresent(blocks::add);
                    breakBlock(block, tools);
                    bucket(block);
                }
                transforms();
                craft();
                compost();
                grew = obtainable.size() != items || blocks.size() != placed;
            }
            return obtainable;
        }

        /**
         * Every loot table of ours that no block names: the wreck's locker. A table of another kind fails, to be taught
         * here.
         */
        private void rollOurTables() {
            Set<ResourceKey<LootTable>> ofBlocks = new HashSet<>();
            BuiltInRegistries.BLOCK.forEach(block -> block.getLootTable().ifPresent(ofBlocks::add));
            server.reloadableRegistries()
                    .lookup()
                    .lookupOrThrow(Registries.LOOT_TABLE)
                    .listElements()
                    .filter(table -> table.key().identifier().getNamespace().equals(DeepCharter.MOD_ID))
                    .filter(table -> !ofBlocks.contains(table.key()))
                    .forEach(table -> {
                        expect(
                                helper,
                                table.value().getParamSet() == LootContextParamSets.CHEST,
                                "the loot table %s is not a chest's, and this derivation rolls only chests",
                                table.key().identifier());
                        LootParams params = new LootParams.Builder(level)
                                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(at))
                                .withOptionalParameter(LootContextParams.THIS_ENTITY, player)
                                .create(LootContextParamSets.CHEST);
                        for (long seed = 1; seed <= SEEDS; seed++) {
                            table.value().getRandomItems(params, seed).forEach(this::obtain);
                        }
                    });
        }

        /** What a creature drops when a player kills it: its loot table. One with none (a pod) drops nothing here. */
        private void killCreature(EntityType<?> type) {
            Optional<ResourceKey<LootTable>> key = type.getDefaultLootTable();
            if (key.isEmpty()) {
                return;
            }
            Entity creature = type.create(level, EntitySpawnReason.COMMAND);
            if (creature == null) {
                return;
            }
            creature.setPos(Vec3.atCenterOf(at));
            LootParams params = new LootParams.Builder(level)
                    .withParameter(LootContextParams.THIS_ENTITY, creature)
                    .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(at))
                    .withParameter(
                            LootContextParams.DAMAGE_SOURCE,
                            level.damageSources().playerAttack(player))
                    .withOptionalParameter(LootContextParams.ATTACKING_ENTITY, player)
                    .withOptionalParameter(LootContextParams.DIRECT_ATTACKING_ENTITY, player)
                    .withOptionalParameter(LootContextParams.LAST_DAMAGE_PLAYER, player)
                    .create(LootContextParamSets.ENTITY);
            LootTable table = server.reloadableRegistries().getLootTable(key.get());
            for (long seed = 1; seed <= SEEDS; seed++) {
                table.getRandomItems(params, seed).forEach(this::obtain);
            }
            creature.discard();
        }

        private void obtain(ItemStack stack) {
            if (!stack.isEmpty()) {
                obtainable.add(stack.getItem());
            }
        }

        /**
         * The states a block can be in here: never waterlogged, since water comes only from a fluid the world holds.
         */
        private static List<BlockState> statesOf(Block block) {
            List<BlockState> states = block.getStateDefinition().getPossibleStates().stream()
                    .filter(state -> !state.hasProperty(BlockStateProperties.WATERLOGGED)
                            || !state.getValue(BlockStateProperties.WATERLOGGED))
                    .toList();
            return states.size() <= ALL_STATES_UP_TO ? states : List.of(block.defaultBlockState());
        }

        /** Tools that harvest a block: the bare hand and every obtainable item that is a tool. */
        private List<ItemStack> tools() {
            List<ItemStack> tools = new ArrayList<>();
            tools.add(ItemStack.EMPTY);
            for (Item item : obtainable) {
                ItemStack stack = new ItemStack(item);
                if (stack.has(DataComponents.TOOL)) {
                    tools.add(stack);
                }
            }
            return tools;
        }

        /**
         * Breaking each state of the block with each tool that may harvest it, which survival allows (a hardness of -1
         * does not).
         */
        private void breakBlock(Block block, List<ItemStack> tools) {
            Optional<ResourceKey<LootTable>> key = block.getLootTable();
            if (key.isEmpty()) {
                return;
            }
            LootTable table = server.reloadableRegistries().getLootTable(key.get());
            for (BlockState state : statesOf(block)) {
                if (state.isAir() || state.getDestroySpeed(level, at) < 0) {
                    continue;
                }
                boolean first = true;
                for (ItemStack tool : tools) {
                    if (state.requiresCorrectToolForDrops() && !tool.isCorrectToolForDrops(state)) {
                        continue;
                    }
                    int seeds = first ? SEEDS : OTHER_TOOL_SEEDS;
                    first = false;
                    if (!rolled.add(state + "|" + id(tool.getItem()))) {
                        continue;
                    }
                    LootParams params = new LootParams.Builder(level)
                            .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(at))
                            .withParameter(LootContextParams.BLOCK_STATE, state)
                            .withParameter(LootContextParams.TOOL, tool)
                            .withOptionalParameter(LootContextParams.THIS_ENTITY, player)
                            .create(LootContextParamSets.BLOCK);
                    for (long seed = 1; seed <= seeds; seed++) {
                        table.getRandomItems(params, seed).forEach(this::obtain);
                    }
                }
            }
        }

        /** A bucket fills from a fluid source the world holds. */
        private void bucket(Block block) {
            if (!obtainable.contains(Items.BUCKET)) {
                return;
            }
            for (BlockState state : statesOf(block)) {
                if (state.getFluidState().isSource()) {
                    obtain(new ItemStack(state.getFluidState().getType().getBucket()));
                }
            }
        }

        /**
         * A tool that transforms blocks (an axe strips a log), used on the top of every state of every block: the block
         * it leaves is then broken like any other, and what the transform drops is picked up. One tool of each
         * transform is enough.
         */
        private void transforms() {
            Map<Holder<BlockTransformer>, ItemStack> byTransform = new LinkedHashMap<>();
            for (Item item : obtainable) {
                ItemStack stack = new ItemStack(item);
                Holder<BlockTransformer> transform = stack.get(DataComponents.BLOCK_TRANSFORMER);
                if (transform != null) {
                    byTransform.putIfAbsent(transform, stack);
                }
            }
            AABB around = new AABB(at).inflate(2);
            for (ItemStack tool : byTransform.values()) {
                for (Block block : List.copyOf(blocks)) {
                    for (BlockState state : statesOf(block)) {
                        if (state.isAir()
                                || !state.getFluidState().isEmpty()
                                || !transformed.add(state + "|" + id(tool.getItem()))) {
                            continue;
                        }
                        level.setBlock(at, state, QUIET);
                        player.setItemInHand(InteractionHand.MAIN_HAND, tool.copy());
                        tool.copy()
                                .useOn(new UseOnContext(
                                        player,
                                        InteractionHand.MAIN_HAND,
                                        new BlockHitResult(Vec3.atCenterOf(at), Direction.UP, at, false)));
                        blocks.add(level.getBlockState(at).getBlock());
                        for (ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class, around)) {
                            obtain(drop.getItem());
                            drop.discard();
                        }
                    }
                }
            }
            level.setBlock(at, Blocks.AIR.defaultBlockState(), QUIET);
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }

        /**
         * Every recipe, the ones this build leaves out included: one whose every ingredient has an obtainable item,
         * made at an obtainable station (a crafting recipe that fits the inventory's 2 x 2 grid needs none), makes its
         * results obtainable. A special recipe (map cloning, dyeing) has no ingredient list to read and is skipped.
         */
        private void craft() {
            server.reloadableRegistries()
                    .lookup()
                    .lookupOrThrow(Registries.RECIPE)
                    .listElements()
                    .forEach(holder -> {
                        Recipe<?> recipe = holder.value();
                        if (recipe.isSpecial() || recipe.placementInfo().isImpossibleToPlace()) {
                            return;
                        }
                        for (Ingredient ingredient : recipe.placementInfo().ingredients()) {
                            if (ingredient.display().resolveForStacks(context).stream()
                                    .noneMatch(stack -> obtainable.contains(stack.getItem()))) {
                                return;
                            }
                        }
                        for (RecipeDisplay display : recipe.display()) {
                            if (stationObtainable(display)) {
                                display.result().resolveForStacks(context).forEach(this::obtain);
                            }
                        }
                    });
        }

        private boolean stationObtainable(RecipeDisplay display) {
            List<ItemStack> stations = display.craftingStation().resolveForStacks(context);
            if (stations.isEmpty() || fitsTheInventoryGrid(display)) {
                return true;
            }
            return stations.stream().anyMatch(station -> obtainable.contains(station.getItem()));
        }

        private static boolean fitsTheInventoryGrid(RecipeDisplay display) {
            return switch (display) {
                case ShapedCraftingRecipeDisplay shaped -> shaped.width() <= 2 && shaped.height() <= 2;
                case ShapelessCraftingRecipeDisplay shapeless ->
                    shapeless.ingredients().size() <= 4;
                default -> false;
            };
        }

        /** A composter turns any compostable item into bone meal. */
        private void compost() {
            if (obtainable.contains(Items.COMPOSTER)
                    && obtainable.stream().anyMatch(item -> new ItemStack(item).has(DataComponents.COMPOSTABLE))) {
                obtainable.add(Items.BONE_MEAL);
            }
        }
    }
}
