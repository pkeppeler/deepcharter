package io.github.pkeppeler.deepcharter.layer;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.layer.LayerChain.Band;
import java.util.Optional;
import java.util.function.Function;
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

/**
 * Blocks of the layer feature: the breach crust, and the rock of layers 1 and 2 with its rubble. Their looks (ten textures of the rock, two of the
 * rubble) are blockstate variants in the resource pack, written by {@code tools/layer_concepts/make.py}.
 */
public final class LayerBlocks {
    private static final Identifier BREACH_CRUST_ID =
            Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "breach_crust");

    // The layer material rule (worldgen/material_rule/layer.json) puts 3 of these at each floor, as LayerTuning says.
    public static final Block BREACH_CRUST = registerBlock(
            BREACH_CRUST_ID,
            key -> new Block(BlockBehaviour.Properties.of()
                    .setId(key)
                    .strength(5.0F, 6.0F)
                    .sound(SoundType.STONE)
                    .noLootTable()));

    /**
     * The grained crust (#746, ADR 0060): crust with a vertical grain that flexes on a slow pulse. The id is permanent. Its name and the
     * messages that explain it are placeholders until the lore session (#12) names them.
     */
    public static final Block GRAINED_CRUST = registerBlock(
            id("grained_crust"),
            key -> new Block(BlockBehaviour.Properties.of()
                    .setId(key)
                    .strength(5.0F, 6.0F)
                    .sound(SoundType.STONE)
                    .noLootTable()));

    /** The rust-brown rock of layer 1, which the layer's noise settings fill the ground with. */
    public static final Block PACKED_SHALE = registerBlock(id("packed_shale"), LayerBlocks::rock);
    /** The grey-green rock of layer 2, which the layer's noise settings fill the ground with. */
    public static final Block GREEN_SHALE = registerBlock(id("green_shale"), LayerBlocks::rock);
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
     * The ground of the layer that a level is, for a room that is sealed or filled in it: the layer's own rock, so that no block of vanilla stone shows
     * in it. Any other level (the surface, a test's) has vanilla stone.
     */
    public static Block groundOf(Level level) {
        return layerGroundOf(level).orElse(Blocks.STONE);
    }

    /** The rock of the layer that a level is; empty for a level that is no layer (the surface, a test's). Fails for a layer with no rock block yet. */
    public static Optional<Block> layerGroundOf(Level level) {
        Optional<Band> band = LayerChain.layerAt(level, level.getMinY());
        if (band.isEmpty()) {
            return Optional.empty();
        }
        int layer = band.get().layer();
        return Optional.of(
                switch (layer) {
                    case 1 -> PACKED_SHALE;
                    case 2 -> GREEN_SHALE;
                    default ->
                        throw new IllegalStateException(
                                "Layer " + layer + " has no rock block yet (layer 3 waits for M3, #504)");
                });
    }

    /** Loads the class, which registers the blocks. */
    public static void register() {}

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
