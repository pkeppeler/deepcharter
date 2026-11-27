package io.github.pkeppeler.deepcharter.surface;

import io.github.pkeppeler.deepcharter.DeepCharter;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The blocks of layer 3's giant fungi (#1202), the parasol and the spires: each a {@link GreatStalkBlock}, so breaking
 * one brings down what it holds ({@link StalkCollapse}) and it drops a fungal stalk. Their looks are generated block
 * models ({@code tools/fungus/make.py}): thin, sloped, tapered and tilted pieces wherever an outline shows, full cubes
 * only where a cap is solid. They have no items: the world grows them, and a player takes the stalks they drop.
 */
public final class GiantFungusBlocks {
    public static final BooleanProperty LIT = BlockStateProperties.LIT;
    public static final BooleanProperty CORNER = BooleanProperty.create("corner");
    public static final BooleanProperty DIAGONAL = BooleanProperty.create("diagonal");
    public static final BooleanProperty UMBO = BooleanProperty.create("umbo");
    public static final BooleanProperty TALL = BooleanProperty.create("tall");
    public static final BooleanProperty TAPER = BooleanProperty.create("taper");
    public static final BooleanProperty NEEDLE = BooleanProperty.create("needle");
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<RodPiece> PIECE = EnumProperty.create("piece", RodPiece.class);
    public static final EnumProperty<GiantPiece.Lip> LIP = EnumProperty.create("lip", GiantPiece.Lip.class);
    public static final EnumProperty<GiantPiece.Arm> ARM = EnumProperty.create("arm", GiantPiece.Arm.class);
    /** A cap cell's height at its middle, in steps of 4 pixels. */
    public static final IntegerProperty LEVEL = IntegerProperty.create("level", 1, 4);
    /** A cap cell's rise across it toward {@code facing}, in steps of 4 pixels. */
    public static final IntegerProperty RISE = IntegerProperty.create("rise", 0, 4);
    /** And toward the face clockwise of {@code facing}. */
    public static final IntegerProperty SIDE_RISE = IntegerProperty.create("side_rise", 0, 4);
    /** A rim cell's height on its inner side, in steps of 4 pixels: up to a cell over its own. */
    public static final IntegerProperty RIM_LEVEL = IntegerProperty.create("level", 2, 8);

    public static final IntegerProperty GIRTH = IntegerProperty.create("girth", 1, 4);
    private static final double PIXELS = 16;
    /** The width of a parasol stem's core, in pixels. */
    static final double STEM_WIDTH = 18;
    /** The width of an arm of a parasol's stem, in pixels. */
    static final double ARM_WIDTH = 12;
    /** How far the middle of an arm of a parasol's stem is from the middle of its core, in pixels. */
    static final double ARM_OFFSET = 14;
    /** The width of a spire of each girth, 1 to 4, in pixels. */
    static final double[] SPIRE_WIDTH = {5, 8, 11, 14};

    public static final Block PARASOL_STEM = register("parasol_stem", key -> new StemBlock(standing(key, 1.5F)));
    public static final Block PARASOL_RING = register("parasol_ring", key -> new RingBlock(standing(key, 1.0F)));
    public static final Block PARASOL_GILLS = register(
            "parasol_gills",
            key -> new GillsBlock(hanging(key)
                    .lightLevel(state -> state.getValue(LIT) ? GiantFungusTuning.DEFAULT.gillLampLight() : 0)));
    public static final Block PARASOL_CAP = register("parasol_cap", key -> new CapBlock(hanging(key)));
    public static final Block PARASOL_RIM = register(
            "parasol_rim", key -> new RimBlock(hanging(key).noCollision().noOcclusion()));
    public static final Block FUNGAL_FLARE =
            register("fungal_flare", key -> new FlareBlock(standing(key, 1.0F).noOcclusion()));
    public static final Block SPIRE =
            register("spire", key -> new SpireBlock(standing(key, 1.5F).noOcclusion()));
    public static final Block SPIRE_TIP = register(
            "spire_tip",
            key -> new TipBlock(standing(key, 0.5F)
                    .noCollision()
                    .noOcclusion()
                    .lightLevel(state -> state.getValue(LIT) ? GiantFungusTuning.DEFAULT.tipLight() : 0)));
    public static final Block SPIRE_BASE =
            register("spire_base", key -> new GreatStalkBlock(standing(key, 2.0F), false));

    private GiantFungusBlocks() {}

    /** Loads the class, which registers the blocks. */
    public static void register() {}

    private static BlockBehaviour.Properties standing(ResourceKey<Block> key, float strength) {
        return BlockBehaviour.Properties.of().setId(key).strength(strength).sound(SoundType.STEM);
    }

    private static BlockBehaviour.Properties hanging(ResourceKey<Block> key) {
        return BlockBehaviour.Properties.of().setId(key).strength(1.0F).sound(SoundType.FUNGUS);
    }

    private static Block register(String path, Function<ResourceKey<Block>, Block> factory) {
        ResourceKey<Block> key =
                ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path));
        return Registry.register(BuiltInRegistries.BLOCK, key, factory.apply(key));
    }

    /**
     * A box drawn as the models are, for a block facing east ({@code x} along {@code facing}), turned to {@code facing}
     * as the blockstates turn the models: a quarter turn clockwise, seen from above, for each step from east through
     * south, west and north.
     */
    private static VoxelShape turned(
            double x0, double y0, double z0, double x1, double y1, double z1, Direction facing) {
        return switch (facing) {
            case SOUTH -> Block.box(PIXELS - z1, y0, x0, PIXELS - z0, y1, x1);
            case WEST -> Block.box(PIXELS - x1, y0, PIXELS - z1, PIXELS - x0, y1, PIXELS - z0);
            case NORTH -> Block.box(z0, y0, PIXELS - x1, z1, y1, PIXELS - x0);
            // enum-default: east is the box as drawn, and up and down are never a facing
            default -> Block.box(x0, y0, z0, x1, y1, z1);
        };
    }

    /**
     * The box round a rod of {@code width} pixels in {@code piece}, as the model leans it east: the rod's middle,
     * clipped to the cell. A leaning rod's box is its middle's, so a player walks into it about where it is drawn.
     */
    static VoxelShape rod(RodPiece piece, Direction facing, double width) {
        return rod(piece, facing, width, 0, 0);
    }

    /**
     * The box of {@link #rod(RodPiece, Direction, double)} moved {@code along} pixels toward {@code facing} and {@code
     * across} toward the face clockwise of it, clipped to the cell.
     */
    private static VoxelShape rod(RodPiece piece, Direction facing, double width, double along, double across) {
        double middle = 8 + (piece.from() + piece.to()) / 2 + along;
        double low = Math.clamp(middle - width / 2, 0, PIXELS - 1);
        double high = Math.clamp(middle + width / 2, low + 1, PIXELS);
        double sideLow = Math.clamp(8 + across - width / 2, 0, PIXELS - 1);
        double sideHigh = Math.clamp(8 + across + width / 2, sideLow + 1, PIXELS);
        return turned(low, piece.bottom(), sideLow, high, piece.top(), sideHigh, facing);
    }

    /**
     * The box of an arm of a parasol's stem: a rod beside its core's, {@link #ARM_OFFSET} pixels from the core's
     * middle, so the cell's own part of it hugs the core's side.
     */
    static VoxelShape arm(RodPiece piece, Direction facing, GiantPiece.Arm arm) {
        double toward = ARM_OFFSET - PIXELS;
        return switch (arm) {
            case NONE -> rod(piece, facing, STEM_WIDTH);
            case FRONT -> rod(piece, facing, ARM_WIDTH, toward, 0);
            case BACK -> rod(piece, facing, ARM_WIDTH, -toward, 0);
            case LEFT -> rod(piece, facing, ARM_WIDTH, 0, -toward);
            case RIGHT -> rod(piece, facing, ARM_WIDTH, 0, toward);
        };
    }

    /**
     * A cell of a parasol's stem: its core, a thick rod, or an arm beside the core, leaning where the stem curves.
     */
    static final class StemBlock extends GreatStalkBlock {
        StemBlock(Properties properties) {
            super(properties.noOcclusion(), false);
            registerDefaultState(defaultBlockState()
                    .setValue(PIECE, RodPiece.STRAIGHT)
                    .setValue(FACING, Direction.EAST)
                    .setValue(ARM, GiantPiece.Arm.NONE));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            super.createBlockStateDefinition(builder);
            builder.add(PIECE, FACING, ARM);
        }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            return arm(state.getValue(PIECE), state.getValue(FACING), state.getValue(ARM));
        }
    }

    /**
     * The ring round a parasol's stem: a straight cell of stem with a drooping skirt, which a player passes through.
     */
    static final class RingBlock extends GreatStalkBlock {
        private static final VoxelShape STEM = rod(RodPiece.STRAIGHT, Direction.EAST, STEM_WIDTH);

        RingBlock(Properties properties) {
            super(properties.noOcclusion(), false);
        }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            return STEM;
        }
    }

    /**
     * The underside of a parasol's cap: a full block, its gills drawn glowing on its down face. A lit one is a gill
     * lamp.
     */
    static final class GillsBlock extends GreatStalkBlock {
        GillsBlock(Properties properties) {
            super(properties, true);
            registerDefaultState(defaultBlockState()
                    .setValue(FACING, Direction.EAST)
                    .setValue(DIAGONAL, false)
                    .setValue(LIT, false));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            super.createBlockStateDefinition(builder);
            builder.add(FACING, DIAGONAL, LIT);
        }
    }

    /**
     * A cell of a parasol's cap above its gills: a full block inside the cap, and at its top a scale tilted to the
     * dome's slope. Only a full cell hides the faces beside it.
     */
    static final class CapBlock extends GreatStalkBlock {
        CapBlock(Properties properties) {
            super(properties, true);
            registerDefaultState(defaultBlockState()
                    .setValue(LEVEL, 4)
                    .setValue(RISE, 0)
                    .setValue(SIDE_RISE, 0)
                    .setValue(FACING, Direction.EAST)
                    .setValue(UMBO, false));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            super.createBlockStateDefinition(builder);
            builder.add(LEVEL, RISE, SIDE_RISE, FACING, UMBO);
        }

        private static boolean full(BlockState state) {
            return state.getValue(LEVEL) == 4 && state.getValue(RISE) == 0 && state.getValue(SIDE_RISE) == 0;
        }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            return full(state) ? Shapes.block() : Block.box(0, 0, 0, 16, Math.max(2, state.getValue(LEVEL) * 4), 16);
        }

        @Override
        protected VoxelShape getOcclusionShape(BlockState state) {
            return full(state) ? Shapes.block() : Shapes.empty();
        }
    }

    /** A cell of a parasol's rim: a thin lip with no collision, so a pod passes under or through it. */
    static final class RimBlock extends GreatStalkBlock {
        private static final VoxelShape OUTLINE = Block.box(0, 0, 0, 16, 4, 16);

        RimBlock(Properties properties) {
            super(properties, true);
            registerDefaultState(defaultBlockState()
                    .setValue(FACING, Direction.EAST)
                    .setValue(CORNER, false)
                    .setValue(RIM_LEVEL, 4)
                    .setValue(LIP, GiantPiece.Lip.DOWN));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            super.createBlockStateDefinition(builder);
            builder.add(FACING, CORNER, RIM_LEVEL, LIP);
        }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            return OUTLINE;
        }
    }

    /** A root flare: a wedge from the floor up to the stem or the mound, low enough to walk over. */
    static final class FlareBlock extends GreatStalkBlock {
        private static final VoxelShape LOW = Block.box(0, 0, 0, 16, 4, 16);
        private static final VoxelShape HIGH = Block.box(0, 0, 0, 16, 8, 16);

        FlareBlock(Properties properties) {
            super(properties, false);
            registerDefaultState(defaultBlockState()
                    .setValue(FACING, Direction.EAST)
                    .setValue(CORNER, false)
                    .setValue(TALL, false));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            super.createBlockStateDefinition(builder);
            builder.add(FACING, CORNER, TALL);
        }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            return state.getValue(TALL) ? HIGH : LOW;
        }
    }

    /** A cell of a spire: a rod of its girth, leaning or forking where the spire does, narrowing in a taper cell. */
    static final class SpireBlock extends GreatStalkBlock {
        SpireBlock(Properties properties) {
            super(properties, false);
            registerDefaultState(defaultBlockState()
                    .setValue(GIRTH, 1)
                    .setValue(PIECE, RodPiece.STRAIGHT)
                    .setValue(FACING, Direction.EAST)
                    .setValue(TAPER, false));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            super.createBlockStateDefinition(builder);
            builder.add(GIRTH, PIECE, FACING, TAPER);
        }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            return rod(state.getValue(PIECE), state.getValue(FACING), SPIRE_WIDTH[state.getValue(GIRTH) - 1]);
        }
    }

    /**
     * The glowing top of a spire, its needle or a glowing cell under it: no collision, and a lit needle is a real
     * light.
     */
    static final class TipBlock extends GreatStalkBlock {
        TipBlock(Properties properties) {
            super(properties, false);
            registerDefaultState(defaultBlockState()
                    .setValue(PIECE, RodPiece.STRAIGHT)
                    .setValue(FACING, Direction.EAST)
                    .setValue(NEEDLE, true)
                    .setValue(LIT, false));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            super.createBlockStateDefinition(builder);
            builder.add(PIECE, FACING, NEEDLE, LIT);
        }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            return rod(state.getValue(PIECE), state.getValue(FACING), SPIRE_WIDTH[0]);
        }
    }
}
