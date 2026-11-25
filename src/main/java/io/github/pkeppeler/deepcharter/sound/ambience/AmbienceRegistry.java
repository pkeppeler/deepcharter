package io.github.pkeppeler.deepcharter.sound.ambience;

import io.github.pkeppeler.deepcharter.DeepCharter;
import java.util.function.Function;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * Registers the Company horn and the Company punch clock. Neither has an item: a structure sets each, and each breaks without a drop.
 */
public final class AmbienceRegistry {
    public static final Block COMPANY_HORN = register(
            "company_horn",
            key -> new CompanyHornBlock(BlockBehaviour.Properties.of()
                    .setId(key)
                    .instabreak()
                    .noCollision()
                    .noOcclusion()
                    .noLootTable()
                    .sound(SoundType.METAL)));

    /** The punch clock of the hall in Shift Change, which rarely calls an hour (#938). Scenery like the horn. */
    public static final Block COMPANY_CLOCK = register(
            "company_clock",
            key -> new CompanyClockBlock(BlockBehaviour.Properties.of()
                    .setId(key)
                    .instabreak()
                    .noCollision()
                    .noOcclusion()
                    .noLootTable()
                    .sound(SoundType.METAL)));

    private AmbienceRegistry() {}

    /** Loads this class, which registers everything above. */
    public static void register() {}

    private static Block register(String path, Function<ResourceKey<Block>, Block> factory) {
        ResourceKey<Block> key =
                ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path));
        return Registry.register(BuiltInRegistries.BLOCK, key, factory.apply(key));
    }
}
