package io.github.pkeppeler.deepcharter.sound.phonograph;

import io.github.pkeppeler.deepcharter.DeepCharter;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * Registers the phonograph, the colony's phonograph, the wreck's phonograph, the wax cylinder and the component that names its recording. The colony's is the
 * same block as the player's, as Company property: unbreakable like the rest of the colony's blocks, with no item and no drop.
 */
public final class PhonographRegistry {
    /** The recording on a wax cylinder, saved as its name. */
    public static final DataComponentType<Recording> RECORDING = Registry.register(
            BuiltInRegistries.DATA_COMPONENT_TYPE,
            id("recording"),
            DataComponentType.<Recording>builder()
                    .persistent(Recording.CODEC)
                    .networkSynchronized(Recording.STREAM_CODEC)
                    .build());

    /** A wax cylinder. One of them, so a stack of one: wax is fragile. Without a component it holds the first recording. */
    public static final Item WAX_CYLINDER = registerItem(
            "wax_cylinder",
            key -> new Item(new Item.Properties().setId(key).stacksTo(1).component(RECORDING, Recording.values()[0])));

    /** The phonograph a player crafts and carries: it holds one cylinder at a time. */
    public static final Block PHONOGRAPH = registerBlock(key -> new PhonographBlock(
            BlockBehaviour.Properties.of()
                    .setId(key)
                    .strength(1.0F)
                    .sound(SoundType.WOOD)
                    .noOcclusion(),
            false));

    /**
     * The colony's phonograph, on the counter of the Lamp and Pick: Company property that holds the rack of the colony's cylinders, which
     * cannot be ejected or taken. It has no item (it is never placed by hand) and no loot table.
     */
    public static final Block COLONY_PHONOGRAPH = register(
            "colony_phonograph",
            key -> new PhonographBlock(
                    BlockBehaviour.Properties.of()
                            .setId(key)
                            .strength(-1.0F, Blocks.BEDROCK.getExplosionResistance())
                            .sound(SoundType.WOOD)
                            .noOcclusion()
                            .noLootTable(),
                    true));

    public static final BlockEntityType<PhonographBlockEntity> BLOCK_ENTITY = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE,
            id("phonograph"),
            new BlockEntityType<>(PhonographBlockEntity::new, Set.of(PHONOGRAPH, COLONY_PHONOGRAPH)));

    /**
     * The cabin phonograph of a wrecked pod (#861): the colony's kind of phonograph, Company-grade unbreakable, with no item and no loot,
     * but it holds no cylinder and plays one song of its own, for a player who comes close.
     */
    public static final Block WRECK_PHONOGRAPH = register(
            "wreck_phonograph",
            key -> new WreckPhonographBlock(BlockBehaviour.Properties.of()
                    .setId(key)
                    .strength(-1.0F, Blocks.BEDROCK.getExplosionResistance())
                    .sound(SoundType.WOOD)
                    .noOcclusion()
                    .noLootTable()));

    public static final BlockEntityType<WreckPhonographBlockEntity> WRECK_BLOCK_ENTITY = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE,
            id("wreck_phonograph"),
            new BlockEntityType<>(WreckPhonographBlockEntity::new, Set.of(WRECK_PHONOGRAPH)));

    private PhonographRegistry() {}

    /** Loads this class, which registers everything above. */
    public static void register() {}

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path);
    }

    private static Item registerItem(String path, Function<ResourceKey<Item>, Item> factory) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id(path));
        return Registry.register(BuiltInRegistries.ITEM, key, factory.apply(key));
    }

    private static Block register(String path, Function<ResourceKey<Block>, Block> factory) {
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, id(path));
        return Registry.register(BuiltInRegistries.BLOCK, key, factory.apply(key));
    }

    /** The phonograph block and its item. */
    private static Block registerBlock(Function<ResourceKey<Block>, Block> factory) {
        String path = "phonograph";
        Block block = register(path, factory);
        registerItem(
                path,
                key -> new BlockItem(block, new Item.Properties().setId(key).useBlockDescriptionPrefix()));
        return block;
    }
}
