package io.github.pkeppeler.deepcharter.colony;

import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The display-only pieces of the colony: statues, sheave wheels, gallery segments. Each is a state of this block, never placed
 * in the world: a block-display entity draws its model, scaled and turned (tools/colony/sculptures.py). It has no item.
 */
final class KitSculptureBlock extends Block {
	/** The pieces; tools/colony/sculptures.py writes a model for each. */
	enum Piece implements StringRepresentable {
		FOUNDER_A, FOUNDER_A_HANDS, FOUNDER_B, FOUNDER_B_HANDS, FOUNDER_C, FOUNDER_C_HANDS, SHEAVE, GALLERY;

		@Override
		public String getSerializedName() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	static final EnumProperty<Piece> PIECE = EnumProperty.create("piece", Piece.class);

	KitSculptureBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(PIECE, Piece.FOUNDER_A));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(PIECE);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return Shapes.empty();
	}
}
