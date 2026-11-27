package io.github.pkeppeler.deepcharter.layer;

import io.github.pkeppeler.deepcharter.DeepCharter;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;

/**
 * Blocks of the layer feature: the breach crust, the rock of layers 1 and 2 with its rubble, layer 1's strata and the
 * relics of its abandoned shafts, and the basalt of layer 3. Their looks (the textures of the rock, the rubble and the
 * basalt's columns) are blockstate variants in the resource pack, written by {@code tools/layer_concepts/make.py}.
 */
public final class LayerBlocks {
    private static final Identifier BREACH_CRUST_ID =
            Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "breach_crust");

    // Bedrock's blast resistance: no vanilla explosion breaks a crust, which only the pod's drill and a creative hand
    // open (#1110).
    // The layer material rule (worldgen/material_rule/layer.json) puts 3 of these at each floor, as LayerTuning says.
    public static final Block BREACH_CRUST = registerBlock(
            BREACH_CRUST_ID,
            key -> new Block(BlockBehaviour.Properties.of()
                    .setId(key)
                    .strength(5.0F, Blocks.BEDROCK.getExplosionResistance())
                    .pushReaction(PushReaction.IMMOVEABLE)
                    .sound(SoundType.STONE)
                    .noLootTable()));

    /**
     * The grained crust (#746, ADR 0060): crust with a vertical grain that flexes on a slow pulse. It takes the place of the breach crust at a
     * seam or a decoy (#216), so it is as blast-proof and immovable as the breach crust (#1110). The id is permanent. Its name and the
     * messages that explain it are placeholders until the lore session (#12) names them.
     */
    public static final Block GRAINED_CRUST = registerBlock(
            id("grained_crust"),
            key -> new GrainedCrustBlock(BlockBehaviour.Properties.of()
                    .setId(key)
                    .strength(5.0F, Blocks.BEDROCK.getExplosionResistance())
                    .pushReaction(PushReaction.IMMOVEABLE)
                    .sound(SoundType.STONE)
                    .noLootTable()));

    /**
     * The moss growth that closes an unvisited bore (#974, {@link Overgrowth}). A soft block with no drop: a hand cuts
     * it in seconds, the drill bores it at extra fuel.
     */
    public static final Block OVERGROWTH = registerBlock(
            id("overgrowth"),
            key -> new OvergrowthBlock(BlockBehaviour.Properties.of()
                    .setId(key)
                    .strength(OvergrowthTuning.DEFAULT.hardness())
                    .sound(SoundType.MOSS)
                    .noLootTable()));

    /**
     * The moss film round the growth (#974, {@link OvergrowthVeneerBlock}): a thin layer on the faces of rock, with no
     * collision, no light cost and no drop, that goes before the fill, spills round a closed mouth and hangs from a
     * roof. It has no item: only {@link Overgrowth} places it. It glows faintly in the dark as a warning: its models
     * set a face {@code light_emission} (the rock does the same), which draws the film brighter while the block's light
     * level stays 0, so it lights nothing.
     */
    public static final Block OVERGROWTH_VENEER = registerBlockOnly(
            id("overgrowth_veneer"),
            key -> new OvergrowthVeneerBlock(BlockBehaviour.Properties.of()
                    .setId(key)
                    .instabreak()
                    .sound(SoundType.MOSS)
                    .noCollision()
                    .noOcclusion()
                    .replaceable()
                    .pushReaction(PushReaction.POPPED)
                    .noLootTable()));

    /**
     * The roof bolt (#987): set in a roof by the bolt driver, it holds the roof round it against a cave-in ({@link CaveIns}). The id is
     * permanent, since a bolt set in the roof stays in a saved world. It has no item: only the driver sets one.
     */
    public static final Block ROOF_BOLT = registerBlockOnly(
            id("roof_bolt"),
            key -> new RoofBoltBlock(BlockBehaviour.Properties.of()
                    .setId(key)
                    .strength(0.5F, 6.0F)
                    .sound(SoundType.CHAIN)
                    .noCollision()
                    .noOcclusion()
                    .noLootTable()));

    /** The rust-brown rock of layer 1, which the layer's noise settings fill the ground with. */
    public static final Block PACKED_SHALE = registerBlock(id("packed_shale"), LayerBlocks::rock);
    /**
     * The olive hardpan of layer 1's Topsoil Claims (#1277), over the packed shale: the campaign's material rule lays
     * it.
     */
    public static final Block HARDPAN = registerBlock(id("hardpan"), LayerBlocks::rock);
    /**
     * The cold black shale of layer 1's Deep Claim (#1277), under the packed shale: the campaign's material rule lays
     * it.
     */
    public static final Block BLACK_SHALE = registerBlock(id("black_shale"), LayerBlocks::rock);
    /** The grey-green rock of layer 2, which the layer's noise settings fill the ground with. */
    public static final Block GREEN_SHALE = registerBlock(id("green_shale"), LayerBlocks::rock);
    /**
     * The dark basalt of layer 3 (#972), which the noise settings fill its ground with and the zone fill turns into ore and hazards: a plain cube,
     * the rock behind the columns that line every face open to the caverns (#504).
     */
    public static final Block BASALT_CORE = registerBlock(id("basalt_core"), LayerBlocks::rock);
    /**
     * The columnar basalt of layer 3 (#504): a hexagonal column that lines each face of the basalt open to the caverns
     * ({@link io.github.pkeppeler.deepcharter.layer.gen.AirFaceLiningFeature}). The column does not fill its cell, so it does not occlude: the
     * dark core behind shows through its corners, and its flank faces take the light of their own cell.
     */
    public static final Block COLUMNAR_BASALT = registerBlock(
            id("columnar_basalt"),
            key -> new Block(BlockBehaviour.Properties.of()
                    .setId(key)
                    .strength(1.5F, 6.0F)
                    .sound(SoundType.BASALT)
                    .requiresCorrectToolForDrops()
                    .noOcclusion()));
    /** The cold-glowing fungus of layer 3's floors (#972), its natural light. */
    public static final Block GLOWCAP = registerBlock(
            id("glowcap"),
            key -> new GlowcapBlock(BlockBehaviour.Properties.of()
                    .setId(key)
                    .instabreak()
                    .sound(SoundType.FUNGUS)
                    .noCollision()
                    .noOcclusion()
                    .lightLevel(state -> LayerTuning.DEFAULT.glowcapLight())));
    /**
     * A first-generation miner's lunch pail (#1277), left at the foot of an abandoned shaft of layer 1. It breaks by
     * hand into itself.
     */
    public static final Block LUNCH_PAIL = registerBlock(
            id("lunch_pail"),
            key -> new ClaimRelicBlock(
                    BlockBehaviour.Properties.of()
                            .setId(key)
                            .strength(0.5F)
                            .sound(SoundType.LANTERN)
                            .noOcclusion(),
                    Block.box(4, 0, 4, 12, 8, 12)));
    /**
     * A prayer card (#1277), left leaning in a candle niche of an abandoned shaft of layer 1, among the candle stubs.
     * It is never explained (LORE section 2, faith is quiet). It breaks by hand into itself.
     */
    public static final Block PRAYER_CARD = registerBlock(
            id("prayer_card"),
            key -> new ClaimRelicBlock(
                    BlockBehaviour.Properties.of()
                            .setId(key)
                            .instabreak()
                            .sound(SoundType.WOOL)
                            .noCollision()
                            .noOcclusion(),
                    Block.box(4, 0, 13, 12, 10, 16)));
    /** Broken rock of layer 1, for the rubble heaps of its formation. */
    public static final Block PACKED_SHALE_RUBBLE = registerBlock(id("packed_shale_rubble"), LayerBlocks::rubble);
    /** Broken rock of layer 2, for the rubble heaps of its formation. */
    public static final Block GREEN_SHALE_RUBBLE = registerBlock(id("green_shale_rubble"), LayerBlocks::rubble);

    private LayerBlocks() {}

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path);
    }

    /** Vanilla stone's strength and sound: the rock is the ground the pod drills and a hand mines, and only its look is the layer's. */
    private static Block rock(ResourceKey<Block> key) {
        return stone(key, 1.5F);
    }

    private static Block rubble(ResourceKey<Block> key) {
        return stone(key, 2.0F);
    }

    private static Block stone(ResourceKey<Block> key, float destroyTime) {
        return new Block(BlockBehaviour.Properties.of()
                .setId(key)
                .strength(destroyTime, 6.0F)
                .sound(SoundType.STONE)
                .requiresCorrectToolForDrops());
    }

    /** The rock that a layer's noise settings fill the ground with, and that the zone fill (ADR 0015) turns into ore and hazards. */
    public static final TagKey<Block> GROUND = TagKey.create(Registries.BLOCK, id("layer_ground"));

    /**
     * The ground of the layer that holds height {@code y} in {@code level}, for a room that is sealed or filled there: the layer's own rock,
     * so that no block of vanilla stone shows in it. Anywhere else (the surface, unbuilt depth, a test's level) it is vanilla stone.
     */
    public static Block groundOf(Level level, int y) {
        return layerGroundOf(level, y).orElse(Blocks.STONE);
    }

    /**
     * The rock of the layer that holds height {@code y} in {@code level}, and in the campaign world's layer 1 the rock
     * of the zone's stratum ({@link #stratum}; a layer dimension has no strata); empty where there is no layer (the
     * surface, unbuilt depth, a test's level). Fails for a layer with no rock block yet.
     */
    public static Optional<Block> layerGroundOf(Level level, int y) {
        return LayerChain.layerAt(level, y)
                .map(band -> LayerChain.isCampaign(level) ? stratum(band, y) : layerGround(band.layer()));
    }

    /**
     * Rows under each zone edge of layer 1 where the contact between its strata lies before the contour noise and the
     * surface depth move it (#1277). {@code worldgen/material_rule/claim_strata.json} lays the same contacts and rocks,
     * which {@code ClaimFeaturesTest} holds to {@link #stratum}.
     */
    public static final int CLAIM_CONTACT_UNDER_EDGE = 6;

    /**
     * The rock at height {@code y} of {@code band} in the campaign world: in layer 1 Topsoil Claims' hardpan, Stone
     * Benches' packed shale or Deep Claim's black shale, split at the strata's contacts before the noise moves them
     * (#1277); in every other layer {@link #layerGround(int)}. The rule moves a contact up to a dozen rows, so within
     * that of a contact this names the rock most of the wall is, not always the block there.
     */
    public static Block stratum(LayerChain.Band band, int y) {
        if (band.layer() != 1) {
            return layerGround(band.layer());
        }
        if (y >= Zones.span(band.minY(), band.height(), 0).low() - CLAIM_CONTACT_UNDER_EDGE) {
            return HARDPAN;
        }
        if (y >= Zones.span(band.minY(), band.height(), 1).low() - CLAIM_CONTACT_UNDER_EDGE) {
            return PACKED_SHALE;
        }
        return BLACK_SHALE;
    }

    /**
     * The rock a block placed against {@code against} in {@code level} at height {@code y} sits in: the layer rock it
     * is set against when that is the layer's ground (so an ore set in a wall near a strata contact takes the wall's
     * rock), else {@link #layerGroundOf}.
     */
    public static Optional<Block> placedGroundOf(Level level, BlockPos against, int y) {
        Optional<Block> ground = layerGroundOf(level, y);
        BlockState wall = level.getBlockState(against);
        return ground.isPresent() && wall.is(GROUND) ? Optional.of(wall.getBlock()) : ground;
    }

    /** The rock of layer {@code layer}, counted from 1; fails for a layer with no rock block yet. */
    public static Block layerGround(int layer) {
        return switch (layer) {
            case 1 -> PACKED_SHALE;
            case 2 -> GREEN_SHALE;
            case 3 -> BASALT_CORE;
            default -> throw new IllegalStateException("Layer " + layer + " has no rock block yet");
        };
    }

    /** Loads the class, which registers the blocks. */
    public static void register() {}

    private static Block registerBlockOnly(Identifier id, Function<ResourceKey<Block>, Block> factory) {
        ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, id);
        return Registry.register(BuiltInRegistries.BLOCK, blockKey, factory.apply(blockKey));
    }

    private static Block registerBlock(Identifier id, Function<ResourceKey<Block>, Block> factory) {
        ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, id);
        Block block = Registry.register(BuiltInRegistries.BLOCK, blockKey, factory.apply(blockKey));
        ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, id);
        Registry.register(
                BuiltInRegistries.ITEM,
                itemKey,
                new BlockItem(block, new Item.Properties().setId(itemKey).useBlockDescriptionPrefix()));
        return block;
    }
}
