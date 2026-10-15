package io.github.pkeppeler.deepcharter.ore;

import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import java.util.Arrays;
import java.util.Locale;
import java.util.OptionalInt;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;

/**
 * An ore block. It sits in a rock, and which rock is its {@link #HOST} state (ADR 0055): the zone fill sets it to the
 * rock it replaces, and a placed ore takes the rock of the layer it is placed in.
 */
public final class OreBlock extends Block {
    /** The rocks an ore can sit in: the layers' own, in layer order. */
    public enum Host implements StringRepresentable {
        PACKED_SHALE(LayerBlocks.PACKED_SHALE),
        GREEN_SHALE(LayerBlocks.GREEN_SHALE);

        private final Block rock;

        Host(Block rock) {
            this.rock = rock;
        }

        /** The host of an ore that replaces {@code rock}. */
        static Host of(BlockState rock) {
            return Arrays.stream(values())
                    .filter(host -> rock.is(host.rock))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            rock + " is not a rock an ore can sit in; give its layer a Host (ADR 0055)"));
        }

        /** The host of an ore placed in a level: its layer's rock, and packed shale in a level that is no layer (the surface, a test's) or one with no rock block yet. */
        static Host in(Level level) {
            OptionalInt layer = LayerChain.layerOf(
                    level.dimensionTypeRegistration().unwrapKey().orElseThrow().identifier());
            return layer.isPresent() && layer.getAsInt() == 2 ? GREEN_SHALE : PACKED_SHALE;
        }

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static final EnumProperty<Host> HOST = EnumProperty.create("host", Host.class);

    public OreBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(HOST, Host.PACKED_SHALE));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(HOST);
    }

    /** This ore as it sits in {@code rock}, the block it replaces. */
    public BlockState in(BlockState rock) {
        return defaultBlockState().setValue(HOST, Host.of(rock));
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(HOST, Host.in(context.getLevel()));
    }
}
