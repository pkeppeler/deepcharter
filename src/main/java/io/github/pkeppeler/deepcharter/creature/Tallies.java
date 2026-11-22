package io.github.pkeppeler.deepcharter.creature;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.layer.LayerChain.Band;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import org.jspecify.annotations.Nullable;

/**
 * Sends a {@link Tally} to a pod that holds ore, or a person who carries it, in layer 1 or 2 near the Conduit's casing (#917). Every
 * {@link TallyTuning#checkIntervalTicks} each such mark within {@link TallyTuning#casingRangeBlocks} of the casing's axis rolls once; a mark
 * that already has a Tally near it rolls nothing. A Tally appears in dark open air near its mark, clear of every player. Nothing is saved.
 *
 * <p>The casing is solid rock, so the Tally is met where the casing is, not made of it: it comes along the bore the pod made, and never
 * breaks a block (ADR 0072, Moloch's family takes and never breaks).
 */
public final class Tallies {
    /** Tries at finding a dark, open place for a Tally round its mark before the check passes. */
    private static final int PLACE_TRIES = 16;
    /** Cells of free air a Tally needs round its place: its own and the one above. */
    private static final int ROOM_BLOCKS = 2;

    private static final Set<String> LOGGED_FAILURES = new HashSet<>();

    private Tallies() {}

    public static void init() {
        ServerTickEvents.END_LEVEL_TICK.register(Tallies::tick);
    }

    /** The layer (1 or 2) a Tally may come in at {@code y} in {@code level}, or empty: the surface, unbuilt depth and every other level have none. */
    private static Optional<Integer> layerAt(ServerLevel level, int y) {
        return LamplessFigures.bandAt(level, y)
                .filter(Band::isLayer)
                .map(Band::layer)
                .filter(layer -> layer <= 2);
    }

    /** The chance that a check which finds a mark in {@code layer} sends a Tally. */
    public static double chance(int layer) {
        TallyTuning tuning = TallyTuning.DEFAULT;
        return layer == 1 ? tuning.layerOneChance() : tuning.layerTwoChance();
    }

    private static void tick(ServerLevel level) {
        if (level.getGameTime() % TallyTuning.DEFAULT.checkIntervalTicks() != 0
                || level.getDifficulty() == Difficulty.PEACEFUL
                || !level.getGameRules().get(GameRules.SPAWN_MOBS)) {
            return;
        }
        try {
            Optional<BlockPos> casing = Colony.anchor(level.getServer(), ColonyAnchor.CONDUIT);
            if (casing.isEmpty()) {
                return;
            }
            for (ServerPlayer player : level.players()) {
                Entity mark = markOf(player);
                if (mark != null) {
                    Optional<Integer> layer = layerAt(level, mark.getBlockY());
                    if (layer.isPresent() && nearCasing(mark, casing.get())) {
                        checkMark(level, mark, chance(layer.get()), level.getRandom());
                    }
                }
            }
        } catch (RuntimeException e) {
            if (LOGGED_FAILURES.add(
                    level.dimension().identifier() + " " + e.getClass().getName())) {
                DeepCharter.LOGGER.error(
                        "Sending a Tally failed in {}; this and later sends may not happen",
                        level.dimension().identifier(),
                        e);
            }
        }
    }

    /** What a person draws a Tally as: their pod when they ride one that draws, else themselves when they carry ore, else nothing. */
    private static @Nullable Entity markOf(Player person) {
        if (person.getVehicle() instanceof PodEntity pod) {
            return Tally.draws(pod) ? pod : null;
        }
        return Tally.draws(person) ? person : null;
    }

    /** Whether {@code mark} is within the casing's zone: square, round the axis of the casing at {@code casing}. */
    public static boolean nearCasing(Entity mark, BlockPos casing) {
        double range = TallyTuning.DEFAULT.casingRangeBlocks();
        return Math.abs(mark.getX() - (casing.getX() + 0.5)) <= range
                && Math.abs(mark.getZ() - (casing.getZ() + 0.5)) <= range;
    }

    /** One check of a mark: rolls {@code chance}, and sends a Tally if it passes and the mark has none near it. Returns the Tally sent. */
    public static Optional<Tally> checkMark(ServerLevel level, Entity mark, double chance, RandomSource random) {
        if (random.nextDouble() >= chance || talliesNear(level, mark) >= TallyTuning.DEFAULT.maxNear()) {
            return Optional.empty();
        }
        return sendTo(level, mark);
    }

    private static int talliesNear(ServerLevel level, Entity mark) {
        double sense = TallyTuning.DEFAULT.senseBlocks();
        // entity-query: round a mark that has a player aboard or is one, so the area is ticking and loaded; a Tally not
        // seen would only be
        // sent a second one, which the cap on the next check corrects
        return level.getEntities(CreatureRegistry.TALLY, mark.getBoundingBox().inflate(sense), tally -> true)
                .size();
    }

    /** Puts a Tally in dark open air round {@code mark}, {@code spawnMinBlocks} to {@code spawnMaxBlocks} off and clear of every player; empty when there is no such place. */
    public static Optional<Tally> sendTo(ServerLevel level, Entity mark) {
        TallyTuning tuning = TallyTuning.DEFAULT;
        for (int attempt = 0; attempt < PLACE_TRIES; attempt++) {
            double angle = level.getRandom().nextDouble() * Math.PI * 2;
            double reach = tuning.spawnMinBlocks()
                    + level.getRandom().nextDouble() * (tuning.spawnMaxBlocks() - tuning.spawnMinBlocks());
            BlockPos place = BlockPos.containing(
                    mark.getX() + Math.cos(angle) * reach,
                    mark.getY() + level.getRandom().nextInt(-3, 4),
                    mark.getZ() + Math.sin(angle) * reach);
            if (canAppearAt(level, place)) {
                Tally tally = CreatureRegistry.TALLY.create(level, EntitySpawnReason.EVENT);
                if (tally == null) {
                    throw new IllegalStateException("The Tally could not be created in "
                            + level.dimension().identifier());
                }
                tally.setPos(place.getX() + 0.5, place.getY() + 0.5, place.getZ() + 0.5);
                level.addFreshEntity(tally);
                return Optional.of(tally);
            }
        }
        return Optional.empty();
    }

    /** Whether a Tally may appear at {@code pos}: loaded and ticking, dark, two cells of free air, and clear of every player. */
    public static boolean canAppearAt(ServerLevel level, BlockPos pos) {
        if (!level.isPositionEntityTicking(pos)
                || level.getBrightness(LightLayer.BLOCK, pos) >= CreatureTuning.DEFAULT.litBlockLight()) {
            return false;
        }
        for (int up = 0; up < ROOM_BLOCKS; up++) {
            BlockPos cell = pos.above(up);
            BlockState state = level.getBlockState(cell);
            if (!state.getCollisionShape(level, cell).isEmpty()
                    || !state.getFluidState().isEmpty()) {
                return false;
            }
        }
        return level.getNearestPlayer(
                        pos.getX() + 0.5,
                        pos.getY(),
                        pos.getZ() + 0.5,
                        TallyTuning.DEFAULT.spawnMinBlocks() / 2,
                        EntitySelector.NO_SPECTATORS)
                == null;
    }

    /** The nearest pod or person within {@code sense} of {@code tally} that draws a Tally, or null. */
    static @Nullable Entity nearestMark(ServerLevel level, Tally tally, double sense) {
        Entity best = null;
        double bestDistance = sense * sense;
        for (Player person : level.players()) {
            Entity mark = markOf(person);
            if (mark != null && mark.distanceToSqr(tally) <= bestDistance) {
                best = mark;
                bestDistance = mark.distanceToSqr(tally);
            }
        }
        return best;
    }
}
