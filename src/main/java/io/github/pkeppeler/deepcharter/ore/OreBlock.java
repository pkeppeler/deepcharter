package io.github.pkeppeler.deepcharter.ore;

import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import java.util.Locale;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;

/**
 * An ore block. It sits in a rock, and which rock is its {@link #HOST} state (ADR 0055): the zone fill sets it to the
 * rock it replaces, and a placed ore takes the rock of the layer it is placed in.
 */
public final class OreBlock extends Block {
    /**
     * The rocks an ore can sit in: the layers' own, then the strata layer 1 gained around its packed shale (#1277). A world
     * saves a host by its name, so a name never changes; the order of the values does not matter to a save.
     */
    public enum Host implements StringRepresentable {
        PACKED_SHALE(LayerBlocks.PACKED_SHALE),
        GREEN_SHALE(LayerBlocks.GREEN_SHALE),
        BASALT_CORE(LayerBlocks.BASALT_CORE),
        HARDPAN(LayerBlocks.HARDPAN),
        BLACK_SHALE(LayerBlocks.BLACK_SHALE);

        private static final Host[] VALUES = values();

        private final Block rock;

        Host(Block rock) {
            this.rock = rock;
        }

        /** The host of an ore that replaces {@code rock}. */
        static Host of(BlockState rock) {
            for (Host host : VALUES) {
                if (rock.is(host.rock)) {
                    return host;
                }
            }
            throw new IllegalArgumentException(
                    rock + " is not a rock an ore can sit in; give its layer a Host (ADR 0055)");
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

    /**
     * A placed ore sits in the rock of the layer it is placed in, which {@link LayerBlocks#layerGroundOf} names and which fails for a layer with no rock
     * block yet, so a new layer cannot place ore with a wrong host. A level that is no layer (the surface, a test's) has no layer rock, and a
     * placed ore there keeps the default host.
     */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return LayerBlocks.placedGroundOf(
                        context.getLevel(),
                        context.getClickedPos()
                                .relative(context.getClickedFace().getOpposite()),
                        context.getClickedPos().getY())
                .map(ground -> in(ground.defaultBlockState()))
                .orElseGet(this::defaultBlockState);
    }
}
