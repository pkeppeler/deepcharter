package io.github.pkeppeler.deepcharter.pod;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.scanner.LoadedBlocks;
import io.github.pkeppeler.deepcharter.scanner.ScanSlice;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** The echoes a ping gets from the world itself: the blocks around the pod, and the creatures that echo. Both listen to {@link SonarEvents#COLLECT}. */
final class SonarEchoes {
    /** Creatures that return an echo. Data, so a creature joins with a line of JSON ({@code tags/entity_type/sonar_echoes.json}); the hollow ones are not in it. */
    static final TagKey<EntityType<?>> ECHOING =
            TagKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "sonar_echoes"));

    private SonarEchoes() {}

    static void init() {
        SonarEvents.COLLECT.register(SonarEchoes::blocks);
        SonarEvents.COLLECT.register(SonarEchoes::creatures);
    }

    /**
     * Reads the loaded cells within range, a cell being a cube of {@link SonarTuning#cellBlocks()} blocks on the world's grid. Ore in a cell makes
     * it an ore echo, else a gas pocket makes it gas (an empty cave beyond {@link SonarTuning#gasResolveBlocks()}), else a cell that is at least half
     * air is a cave. Chunks that are not loaded return nothing, as the scanner shows them.
     */
    private static void blocks(SonarPing ping, SonarEvents.Sink sink) {
        SonarTuning tuning = SonarTuning.DEFAULT;
        ServerLevel level = ping.level();
        int cell = tuning.cellBlocks();
        double range = ping.rangeBlocks();
        Vec3 origin = ping.origin();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        int cellVolume = cell * cell * cell;
        for (int cx = Math.floorDiv(Mth.floor(origin.x - range), cell);
                cx <= Math.floorDiv(Mth.floor(origin.x + range), cell);
                cx++) {
            for (int cy = Math.floorDiv(Math.max(level.getMinY(), Mth.floor(origin.y - range)), cell);
                    cy <= Math.floorDiv(Math.min(level.getMaxY(), Mth.floor(origin.y + range)), cell);
                    cy++) {
                for (int cz = Math.floorDiv(Mth.floor(origin.z - range), cell);
                        cz <= Math.floorDiv(Mth.floor(origin.z + range), cell);
                        cz++) {
                    BlockPos corner = new BlockPos(cx * cell, cy * cell, cz * cell);
                    BlockPos middle = corner.offset(cell / 2, cell / 2, cell / 2);
                    double away = Math.sqrt(middle.distToCenterSqr(origin));
                    if (away > range || away < tuning.blindBlocks() || !LoadedBlocks.isLoaded(level, corner)) {
                        continue;
                    }
                    boolean ore = false;
                    boolean gas = false;
                    int air = 0;
                    for (int dx = 0; dx < cell && !ore; dx++) {
                        for (int dy = 0; dy < cell && !ore; dy++) {
                            for (int dz = 0; dz < cell && !ore; dz++) {
                                BlockState state =
                                        level.getBlockState(at.set(corner).move(dx, dy, dz));
                                ore = state.is(ScanSlice.ORES);
                                gas |= state.is(HazardBlocks.GAS_POCKET);
                                air += state.isAir() ? 1 : 0;
                            }
                        }
                    }
                    if (ore) {
                        sink.add(SonarKind.ORE, middle);
                    } else if (gas) {
                        sink.add(away <= tuning.gasResolveBlocks() ? SonarKind.GAS : SonarKind.CAVE, middle);
                    } else if (air * 2 >= cellVolume) {
                        sink.add(SonarKind.CAVE, middle);
                    }
                }
            }
        }
    }

    /** A creature in {@link #ECHOING} within range returns an echo at its block: moved when it is going faster than {@link SonarTuning#movingSpeed()}, else ore (a body standing still is a dense return). */
    private static void creatures(SonarPing ping, SonarEvents.Sink sink) {
        double range = ping.rangeBlocks();
        double movingSquared = SonarTuning.DEFAULT.movingSpeed() * SonarTuning.DEFAULT.movingSpeed();
        // entity-query: a ping reads what is loaded around the pod; a creature in an unloaded chunk makes no echo,
        // which is a miss, not a wrong answer.
        for (Entity creature : ping.level()
                .getEntities(
                        ping.pod(),
                        AABB.ofSize(ping.origin(), 2 * range, 2 * range, 2 * range),
                        e -> e.is(ECHOING) && !ping.pod().hasPassenger(e))) {
            if (creature.position().distanceToSqr(ping.origin()) <= range * range) {
                sink.add(
                        creature.getDeltaMovement().lengthSqr() >= movingSquared ? SonarKind.MOVED : SonarKind.ORE,
                        creature.blockPosition());
            }
        }
    }
}
