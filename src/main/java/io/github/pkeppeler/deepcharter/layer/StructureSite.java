package io.github.pkeppeler.deepcharter.layer;

import io.github.pkeppeler.deepcharter.colony.ColonyTuning;
import java.util.SplittableRandom;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

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
    public static StructureSite in(
            long worldSeed, StructureKind kind, int minY, int levelHeight, int cellX, int cellZ, BlockPos conduit) {
        LayerTuning tuning = LayerTuning.DEFAULT;
        if (kind == StructureKind.KELLS_SHAFT) {
            return shaftOf(in(worldSeed, StructureKind.RAILS, minY, levelHeight, cellX, cellZ, conduit), minY);
        }
        Zones.Span zone = Zones.span(minY, levelHeight, kind.zone());
        Zones.Span usable = new Zones.Span(
                Math.max(zone.low(), minY + tuning.structureFloorMargin()),
                Math.min(zone.high(), minY + levelHeight - 1 - tuning.structureCeilingMargin()));
        int height = kind.heightIn(usable);
        // The hollow's floor needs a block below it and its roof one above it, all inside the usable heights.
        int floors = usable.high() - height - usable.low();
        if (height < 1 || floors < 1) {
            throw new IllegalStateException(
                    kind + " needs " + height + " blocks of hollow plus a floor and a roof, but zone " + kind.zone()
                            + " has " + usable.size() + " usable blocks");
        }
        SplittableRandom random = new SplittableRandom(worldSeed
                ^ kind.name().hashCode() * 0x9E3779B97F4A7C15L
                ^ cellX * 341873128712L
                ^ cellZ * 132897987541L);
        int spacing = tuning.structureSpacing();
        int margin = kind.margin();
        int casing = ColonyTuning.DEFAULT.conduitRadius();
        for (int attempt = 0; attempt < PLACEMENT_TRIES; attempt++) {
            int x = cellX * spacing + margin + random.nextInt(spacing - 2 * margin);
            int z = cellZ * spacing + margin + random.nextInt(spacing - 2 * margin);
            int y = usable.low() + 1 + random.nextInt(floors);
            StructureSite site =
                    new StructureSite(kind, new BlockPos(x, y, z), random.nextBoolean(), height, random.nextLong());
            // The Kells' shaft hangs off the end of the rail line, so a line whose shaft would meet the casing is
            // passed over.
            boolean shaftClear =
                    kind != StructureKind.RAILS || !meets(shaftOf(site, minY).bounds(), conduit, casing);
            if (shaftClear && !meets(site.bounds(), conduit, casing)) {
                return site;
            }
        }
        throw new IllegalStateException(kind + " found no place in cell (" + cellX + ", " + cellZ
                + ") clear of the Conduit at " + conduit.toShortString());
    }

    /**
     * The Kells' shaft of the rail line {@code rails}: it stands {@link StructureKind#KELLS_OFFSET} blocks along the line from
     * its centre, past the end the line stops at, and runs down from the floor of the line to the first block above the crust.
     */
    private static StructureSite shaftOf(StructureSite rails, int minY) {
        int bottom = minY + LayerTuning.DEFAULT.crustThickness();
        // The hollow's top row is the roof of the drift that joins the line, 2 above its floor.
        int height = rails.origin().getY() + StructureKind.DRIFT_HEIGHT - bottom;
        BlockPos origin = new BlockPos(
                rails.origin().getX() + (rails.alongZ() ? 0 : StructureKind.KELLS_OFFSET),
                bottom,
                rails.origin().getZ() + (rails.alongZ() ? StructureKind.KELLS_OFFSET : 0));
        return new StructureSite(
                StructureKind.KELLS_SHAFT, origin, rails.alongZ(), height, rails.seed() ^ 0x5DEECE66DL);
    }

    private static boolean meets(BoundingBox bounds, BlockPos conduit, int casing) {
        return bounds.intersects(
                conduit.getX() - casing, conduit.getZ() - casing, conduit.getX() + casing, conduit.getZ() + casing);
    }

    /** The blocks the structure may change: its hollow, its floor and roof, and the walls round them. */
    public BoundingBox bounds() {
        int reachX = alongZ ? kind.halfV() : kind.halfU();
        int reachZ = alongZ ? kind.halfU() : kind.halfV();
        return new BoundingBox(
                origin.getX() - reachX,
                origin.getY() - 1,
                origin.getZ() - reachZ,
                origin.getX() + reachX,
                origin.getY() + height,
                origin.getZ() + reachZ);
    }
}
