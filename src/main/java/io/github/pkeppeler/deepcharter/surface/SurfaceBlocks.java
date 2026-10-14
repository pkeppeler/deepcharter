package io.github.pkeppeler.deepcharter.surface;

import io.github.pkeppeler.deepcharter.DeepCharter;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.storage.loot.providers.number.ints.ContextIntProviders;

/**
 * The blocks of the surface. The surface material rule ({@code worldgen/material_rule/surface.json}) places the ground; the surface features
 * ({@code worldgen/placed_feature/salvage_litter.json} and {@code fungal_stalks.json}) place the crates and the stalks. Their
 * looks are blockstate variants in the resource pack: how many textures a block has is data, not code.
 */
public final class SurfaceBlocks {
    /** Loose plains topsoil. */
    public static final Block REGOLITH = registerBlock(
            id("regolith"),
            key -> new Block(
                    BlockBehaviour.Properties.of().setId(key).strength(0.5F).sound(SoundType.GRAVEL)));

    /** The packed layer under the topsoil. */
    public static final Block REGOLITH_PACKED = registerBlock(
            id("regolith_packed"),
            key -> new Block(
                    BlockBehaviour.Properties.of().setId(key).strength(0.8F).sound(SoundType.GRAVEL)));

    /** Mesa topsoil. */
    public static final Block OCHRE_REGOLITH = registerBlock(
            id("ochre_regolith"),
            key -> new Block(
                    BlockBehaviour.Properties.of().setId(key).strength(0.5F).sound(SoundType.GRAVEL)));

    /** The rust-red rock under the packed layer, down to where vanilla stone starts. */
    public static final Block REGOLITH_ROCK = registerBlock(
            id("regolith_rock"),
            key -> new Block(BlockBehaviour.Properties.of()
                    .setId(key)
                    .strength(1.5F, 6.0F)
                    .sound(SoundType.STONE)));

    /** Black basalt outcrops. */
    public static final Block BASALT_OUTCROP = registerBlock(
            id("basalt_outcrop"),
            key -> new Block(BlockBehaviour.Properties.of()
                    .setId(key)
                    .strength(2.0F, 6.0F)
                    .sound(SoundType.BASALT)));

    /**
     * A Company salvage crate: scattered round the colony, it breaks into wood, cloth and scrap. The ore processor presses more from a
     * pod's spoil (#470), so its item is held here.
     */
    public static final Block SALVAGE_CRATE = registerWoodBlock(
            id("salvage_crate"),
            key -> new SalvageCrateBlock(BlockBehaviour.Properties.of()
                    .setId(key)
                    .strength(1.2F)
                    .sound(SoundType.WOOD)
                    .noOcclusion()));

    // The rest of the bootstrap's blocks (art direction section 11). Worldgen features and loot tables name them by id,
    // so nothing in code holds one.
    static {
        // A pale fungal stalk, grown in craters and caves: the stand-in for a log.
        registerWoodBlock(
                id("fungal_stalk"),
                key -> new FungalStalkBlock(BlockBehaviour.Properties.of()
                        .setId(key)
                        .strength(1.0F)
                        .sound(SoundType.STEM)
                        .noOcclusion()));
        // Planks pressed from stalk: any recipe that takes planks takes them.
        registerWoodBlock(
                id("stalk_planks"),
                key -> new Block(BlockBehaviour.Properties.of()
                        .setId(key)
                        .strength(1.5F, 3.0F)
                        .sound(SoundType.WOOD)));
    }

    private SurfaceBlocks() {}

    /** Loads the class, which registers the blocks. */
    public static void register() {}

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path);
    }

    private static Block registerBlock(Identifier id, Function<ResourceKey<Block>, Block> factory) {
        return register(id, factory, properties -> properties);
    }

    /** A block whose item burns in a furnace as any wood does: the cooking time of a wooden block, from the game's own data. */
    private static Block registerWoodBlock(Identifier id, Function<ResourceKey<Block>, Block> factory) {
        return register(
                id, factory, properties -> properties.cookingFuel(ContextIntProviders.COOKING_TIME_WOOD_BLOCKS));
    }

    private static Block register(
            Identifier id, Function<ResourceKey<Block>, Block> factory, UnaryOperator<Item.Properties> item) {
        ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, id);
        Block block = Registry.register(BuiltInRegistries.BLOCK, blockKey, factory.apply(blockKey));
        ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, id);
        Registry.register(
                BuiltInRegistries.ITEM,
                itemKey,
                new BlockItem(
                        block, item.apply(new Item.Properties().setId(itemKey).useBlockDescriptionPrefix())));
        return block;
    }
}
