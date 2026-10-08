package io.github.pkeppeler.deepcharter.layer;

import java.util.SplittableRandom;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import io.github.pkeppeler.deepcharter.colony.ColonyTuning;

/**
 * Where one structure stands: a function of the world seed, the kind, the spacing cell and the Conduit's column, so a chunk
 * can ask for the sites that touch it without any state (ADR 0025).
 *
 * @param kind    what stands here
 * @param origin  the centre column at the floor of the hollow, so the floor block is one below
 * @param alongZ  true when the structure's long axis runs north to south
 * @param height  blocks of hollow above the floor
 * @param seed    seeds the small random choices inside the structure (rubble, broken rails)
 */
public record StructureSite(StructureKind kind, BlockPos origin, boolean alongZ, int height, long seed) {
	/** How many places a cell tries before it gives up: the casing is 3 blocks across and a cell 384. */
	private static final int PLACEMENT_TRIES = 64;

	/**
	 * The site of {@code kind} in the spacing cell {@code (cellX, cellZ)} of a layer of {@code levelHeight} blocks from
	 * {@code minY}. The structure lies wholly inside its cell, with its shell too, in its zone and clear of the layer's floor
	 * and roof. A place where its {@link #bounds} would meet the casing of the Conduit at {@code conduit} is passed over for the
	 * next one the seed gives.
	 */
	public static StructureSite in(long worldSeed, StructureKind kind, int minY, int levelHeight, int cellX, int cellZ, BlockPos conduit) {
		LayerTuning tuning = LayerTuning.DEFAULT;
		Zones.Span zone = Zones.span(minY, levelHeight, kind.zone());
		Zones.Span usable = new Zones.Span(Math.max(zone.low(), minY + tuning.structureFloorMargin()),
				Math.min(zone.high(), minY + levelHeight - 1 - tuning.structureCeilingMargin()));
		int height = kind.heightIn(usable);
		// The hollow's floor needs a block below it and its roof one above it, all inside the usable heights.
		int floors = usable.high() - height - usable.low();
		if (height < 1 || floors < 1) {
			throw new IllegalStateException(kind + " needs " + height + " blocks of hollow plus a floor and a roof, but zone " + kind.zone()
					+ " has " + usable.size() + " usable blocks");
		}
		SplittableRandom random = new SplittableRandom(worldSeed ^ kind.name().hashCode() * 0x9E3779B97F4A7C15L
				^ cellX * 341873128712L ^ cellZ * 132897987541L);
		int spacing = tuning.structureSpacing();
		int margin = kind.reach() + 1;
		for (int attempt = 0; attempt < PLACEMENT_TRIES; attempt++) {
			int x = cellX * spacing + margin + random.nextInt(spacing - 2 * margin);
			int z = cellZ * spacing + margin + random.nextInt(spacing - 2 * margin);
			int y = usable.low() + 1 + random.nextInt(floors);
			StructureSite site = new StructureSite(kind, new BlockPos(x, y, z), random.nextBoolean(), height, random.nextLong());
			if (!site.bounds().intersects(conduit.getX() - ColonyTuning.DEFAULT.conduitRadius(), conduit.getZ() - ColonyTuning.DEFAULT.conduitRadius(),
					conduit.getX() + ColonyTuning.DEFAULT.conduitRadius(), conduit.getZ() + ColonyTuning.DEFAULT.conduitRadius())) {
				return site;
			}
		}
		throw new IllegalStateException(kind + " found no place in cell (" + cellX + ", " + cellZ + ") clear of the Conduit at " + conduit.toShortString());
	}

	/** The blocks the structure may change: its hollow, its floor and roof, and the walls round them. */
	public BoundingBox bounds() {
		int reachX = alongZ ? kind.halfV() : kind.halfU();
		int reachZ = alongZ ? kind.halfU() : kind.halfV();
		return new BoundingBox(origin.getX() - reachX, origin.getY() - 1, origin.getZ() - reachZ,
				origin.getX() + reachX, origin.getY() + height, origin.getZ() + reachZ);
	}
}
