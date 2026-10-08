package io.github.pkeppeler.deepcharter.layer;

import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.LadderBlock;

import io.github.pkeppeler.deepcharter.handbook.NoteBlock;

/**
 * The structures of the first two layers (lore canon, section 11). Each one stands in one zone, and is drawn in its own
 * coordinates by {@link #draw}: {@code u} along its long axis, {@code v} across it, {@code y} up from the floor of its hollow.
 * The Note numbers are the permanent numbers of {@code docs/lore/notes.md} (ADR 0020).
 */
public enum StructureKind {
	/** A hand-dug first-generation shaft through Topsoil Claims, with a candle niche that holds N05, the survey stake tag. */
	TOPSOIL_SHAFT(1, 0, 2, 2, OptionalInt.empty()) {
		@Override
		void draw(StructurePlan p, int height) {
			shaft(p, height, 5);
		}
	},
	/** The same shaft through Stone Benches, with N06, the note in a lunch pail. */
	BENCHES_SHAFT(1, 1, 2, 2, OptionalInt.empty()) {
		@Override
		void draw(StructurePlan p, int height) {
			shaft(p, height, 6);
		}
	},
	/** The same shaft through Deep Claim, with N07, the prayer card. */
	DEEP_SHAFT(1, 2, 2, 2, OptionalInt.empty()) {
		@Override
		void draw(StructurePlan p, int height) {
			shaft(p, height, 7);
		}
	},
	/**
	 * A collapsed gallery in the Upper Levels: timber props, ore piled and never hauled, rubble across the far end and the
	 * quota board (N08) on the near end wall.
	 */
	GALLERY(2, 0, 13, 2, OptionalInt.of(4)) {
		@Override
		void draw(StructurePlan p, int height) {
			p.air(-12, 0, -1, 12, 3, 1);
			p.box(-12, -1, -1, 12, -1, 1, Blocks.COBBLESTONE.defaultBlockState());
			for (int u = -12; u <= 12; u++) {
				if (!p.roll(u, 0, 0, 0.15)) {
					p.set(u, 0, 0, p.railAlongU());
				}
			}
			for (int u = -8; u <= 8; u += 4) {
				for (int v = -1; v <= 1; v += 2) {
					p.box(u, 0, v, u, 2, v, Blocks.OAK_FENCE.defaultBlockState());
				}
				p.box(u, 3, -1, u, 3, 1, Blocks.OAK_PLANKS.defaultBlockState());
			}
			for (int u = 5; u <= 12; u++) {
				for (int v = -1; v <= 1; v++) {
					for (int y = 0; y <= 3; y++) {
						if (p.roll(u, y, v, (u - 4) / 8.0)) {
							p.set(u, y, v, Blocks.COBBLESTONE.defaultBlockState());
						}
					}
				}
			}
			p.box(-7, 0, 1, -6, 0, 1, Blocks.RAW_IRON_BLOCK.defaultBlockState());
			p.set(-7, 0, -1, Blocks.RAW_IRON_BLOCK.defaultBlockState());
			p.box(-13, 1, -1, -13, 2, 1, Blocks.CONCRETE.black().defaultBlockState());
			p.set(-12, 0, 0, Blocks.SPRUCE_PLANKS.defaultBlockState());
			p.set(-12, 1, 0, NoteBlock.stateOf(8));
		}
	},
	/**
	 * The colony's underground rail hub in Shift Change: two tracks, a wall of shelves for the time cards, the punch clock that
	 * is still lit and the Note (N09) on the table before the shelves.
	 */
	PUNCH_CLOCK(2, 1, 8, 8, OptionalInt.of(5)) {
		@Override
		void draw(StructurePlan p, int height) {
			p.air(-8, 0, -8, 8, 4, 8);
			p.box(-8, -1, -8, 8, -1, 8, Blocks.POLISHED_ANDESITE.defaultBlockState());
			for (int v = -5; v <= 5; v += 10) {
				for (int u = -8; u <= 8; u++) {
					if (!p.roll(u, 0, v, 0.1)) {
						p.set(u, 0, v, p.railAlongU());
					}
				}
			}
			for (int u = -4; u <= 4; u += 8) {
				for (int v = -2; v <= 2; v += 4) {
					p.box(u, 0, v, u, 4, v, Blocks.STONE_BRICKS.defaultBlockState());
				}
			}
			p.box(7, 0, -6, 8, 3, 6, Blocks.BOOKSHELF.defaultBlockState());
			p.box(-7, 0, 0, -7, 1, 0, Blocks.IRON_BLOCK.defaultBlockState());
			p.set(-7, 2, 0, Blocks.SEA_LANTERN.defaultBlockState());
			p.set(5, 0, 0, Blocks.SPRUCE_PLANKS.defaultBlockState());
			p.set(5, 1, 0, NoteBlock.stateOf(9));
		}
	},
	/** The rail line of Prospector's Run: a long timbered drift with a single track, broken in places, and nothing at the end of it. */
	RAILS(2, 2, 32, 2, OptionalInt.of(4)) {
		@Override
		void draw(StructurePlan p, int height) {
			p.air(-32, 0, -2, 32, 3, 2);
			p.box(-32, -1, -2, 32, -1, 2, Blocks.COARSE_DIRT.defaultBlockState());
			for (int u = -32; u <= 32; u++) {
				if (!p.roll(u, 0, 0, 0.12)) {
					p.set(u, 0, 0, p.railAlongU());
				}
			}
			for (int u = -32; u <= 32; u += 8) {
				for (int v = -2; v <= 2; v += 4) {
					p.box(u, 0, v, u, 2, v, Blocks.OAK_LOG.defaultBlockState());
				}
				p.box(u, 3, -2, u, 3, 2, Blocks.OAK_PLANKS.defaultBlockState());
			}
		}
	},
	/**
	 * A wreck site in Prospector's Run: a bay with a scorched floor, a rail that ends in it, and debris at the edges. The middle
	 * is clear for a pod, which a later issue sets there (the site is {@code origin}).
	 */
	WRECK(2, 2, 6, 6, OptionalInt.of(5)) {
		@Override
		void draw(StructurePlan p, int height) {
			p.air(-6, 0, -6, 6, 4, 6);
			p.box(-6, -1, -6, 6, -1, 6, Blocks.DEEPSLATE_TILES.defaultBlockState());
			p.box(-2, -1, -2, 2, -1, 2, Blocks.BLACKSTONE.defaultBlockState());
			p.box(-6, 0, 0, -3, 0, 0, p.railAlongU());
			for (int u = -6; u <= 6; u++) {
				for (int v = -6; v <= 6; v++) {
					if (Math.max(Math.abs(u), Math.abs(v)) >= 3 && p.roll(u, 0, v, 0.12)) {
						p.set(u, 0, v, p.roll(u, 1, v, 0.5) ? Blocks.IRON_BLOCK.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState());
					}
				}
			}
		}
	};

	private final int layer;
	private final int zone;
	private final int halfU;
	private final int halfV;
	private final OptionalInt fixedHeight;

	/**
	 * @param layer       the layer it stands in, counted from 1
	 * @param zone        the zone it stands in, 0 for the top third
	 * @param halfU       blocks from the centre to the edge along its long axis
	 * @param halfV       blocks from the centre to the edge across it
	 * @param fixedHeight blocks of hollow; empty for a structure that fills the usable height of its zone
	 */
	StructureKind(int layer, int zone, int halfU, int halfV, OptionalInt fixedHeight) {
		this.layer = layer;
		this.zone = zone;
		this.halfU = halfU;
		this.halfV = halfV;
		this.fixedHeight = fixedHeight;
	}

	/** Draws the structure in its own coordinates; {@code height} is the blocks of hollow, from {@link #heightIn}. */
	abstract void draw(StructurePlan p, int height);

	public int layer() {
		return layer;
	}

	public int zone() {
		return zone;
	}

	int halfU() {
		return halfU;
	}

	int halfV() {
		return halfV;
	}

	/** Blocks from the centre to the edge on the longer axis, which the spacing cell must leave room for. */
	int reach() {
		return Math.max(halfU, halfV);
	}

	/** Blocks of hollow in {@code usable} heights: the floor and the roof each take one block of them. */
	int heightIn(Zones.Span usable) {
		return fixedHeight.orElse(usable.size() - 2);
	}

	/** The kinds that stand in {@code layer}; none for a layer with no structures yet. */
	public static List<StructureKind> inLayer(int layer) {
		return Arrays.stream(values()).filter(kind -> kind.layer == layer).toList();
	}

	/** A 3 x 3 shaft with a ladder, timber collars, and in the middle a candle niche in the east wall: the Note, and a lit candle beside it. */
	private static void shaft(StructurePlan p, int height, int note) {
		p.air(-1, 0, -1, 1, height - 1, 1);
		p.box(-1, -1, -1, 1, -1, 1, Blocks.STONE_BRICKS.defaultBlockState());
		for (int y = 3; y < height; y += 8) {
			for (int u = -2; u <= 2; u++) {
				for (int v = -2; v <= 2; v++) {
					if (Math.max(Math.abs(u), Math.abs(v)) == 2) {
						p.set(u, y, v, Blocks.OAK_PLANKS.defaultBlockState());
					}
				}
			}
		}
		for (int y = 0; y < height; y++) {
			p.set(-1, y, -1, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, p.facing(Direction.EAST)));
		}
		int niche = height / 2;
		p.air(2, niche, 0, 2, niche + 1, 1);
		p.box(2, niche - 1, 0, 2, niche - 1, 1, Blocks.STONE_BRICKS.defaultBlockState());
		p.box(2, niche + 2, 0, 2, niche + 2, 1, Blocks.STONE_BRICKS.defaultBlockState());
		p.set(2, niche, 0, NoteBlock.stateOf(note));
		p.set(2, niche, 1, Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.LIT, true));
	}
}
