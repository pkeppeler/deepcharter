package io.github.pkeppeler.deepcharter.test.mixin;

import java.util.function.Predicate;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * For the {@code hollows-concepts} scenario (#1147): the light a block state gives and whether it is drawn at full brightness, both fixed
 * when the state is built. The scenario sets them on the glowcap's states to show its light options and puts them back after. An
 * accessor, so the hot paths that read the fields are not touched.
 */
@Mixin(value = BlockBehaviour.BlockStateBase.class, remap = false)
public interface BlockStateAccess {
    @Mutable
    @Accessor("lightEmission")
    void deepcharter$setLightEmission(int light);

    @Accessor("emissiveRendering")
    Predicate<BlockState> deepcharter$emissiveRendering();

    @Mutable
    @Accessor("emissiveRendering")
    void deepcharter$setEmissiveRendering(Predicate<BlockState> emissive);
}
