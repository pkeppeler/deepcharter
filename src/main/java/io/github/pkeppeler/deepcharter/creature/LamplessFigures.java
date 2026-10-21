package io.github.pkeppeler.deepcharter.creature;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerChain.Band;
import io.github.pkeppeler.deepcharter.layer.LayerStructures;
import io.github.pkeppeler.deepcharter.layer.StructureKind;
import io.github.pkeppeler.deepcharter.layer.StructureSite;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Puts a {@link LamplessFigure} on the rail site of Prospector's Run that a player is near, when the site has none: at most
 * {@link CreatureTuning#maxPerSite} on a site and {@link CreatureTuning#maxPerLevel} in the level, and none until
 * {@link CreatureTuning#respawnDelayTicks} after the last one was killed. Nothing is saved: a figure is made from the rails as they stand.
 */
public final class LamplessFigures {
    /** The rail level, kept so that a tick does not build the key again. */
    private static final ResourceKey<Level> RAIL_LEVEL = LayerChain.dimension(StructureKind.RAILS.layer());

    /**
     * The levels where a figure appears of itself, in the open dark: layers 1 and 2. The surface is not one of them, and the colony, the
     * hangar and the founding Mole's bay are in the surface level; an outpost is lit.
     */
    private static final Set<ResourceKey<Level>> NATURAL_LEVELS =
            Set.of(LayerChain.dimension(1), LayerChain.dimension(2));

    /** The failures already logged, each as a level and an exception class, so that a later and different one is not silent. Server thread only. */
    private static final Set<String> LOGGED_FAILURES = new HashSet<>();

    /** Game time at which the last figure of each level was killed, for the rail's figures and for the game's spawner's apart. Server thread only. */
    private static final Map<ResourceKey<Level>, Long> LAST_KILL = new HashMap<>();

    private static final Map<ResourceKey<Level>, Long> LAST_NATURAL_KILL = new HashMap<>();

    private LamplessFigures() {}

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> clearRespawnDelays());
        ServerTickEvents.END_LEVEL_TICK.register(LamplessFigures::tick);
    }

    /** Forgets when figures were last killed, as a new server does: the game time of the last one means nothing to it. */
    public static void clearRespawnDelays() {
        LAST_KILL.clear();
        LAST_NATURAL_KILL.clear();
    }

    /** Whether a level looks for sites to put a figure on at this game time: the rail level, every {@link CreatureTuning#spawnIntervalTicks}. */
    public static boolean isSpawnTick(ResourceKey<Level> dimension, long gameTime) {
        return dimension.equals(RAIL_LEVEL) && gameTime % CreatureTuning.DEFAULT.spawnIntervalTicks() == 0;
    }

    static void killed(ServerLevel level, boolean natural) {
        (natural ? LAST_NATURAL_KILL : LAST_KILL).put(level.dimension(), level.getGameTime());
    }

    /** A tick path: it logs each kind of failure once for a level and carries on. */
    private static void tick(ServerLevel level) {
        if (!isSpawnTick(level.dimension(), level.getGameTime())) {
            return;
        }
        try {
            Optional<BlockPos> conduit = Colony.anchor(level.getServer(), ColonyAnchor.CONDUIT);
            if (conduit.isEmpty()) {
                return;
            }
            Set<StructureSite> visited = new HashSet<>();
            double range = CreatureTuning.DEFAULT.spawnRangeBlocks();
            for (ServerPlayer player : level.players()) {
                Band band = LayerChain.bandAt(level, player.getBlockY())
                        .orElseThrow(() -> new IllegalStateException(
                                "rail level " + level.dimension().identifier() + " is not in the layer chain"));
                StructureSite site = LayerStructures.nearest(
                        level.getSeed(),
                        StructureKind.RAILS,
                        band.minY(),
                        band.height(),
                        player.blockPosition(),
                        conduit.get());
                if (player.distanceToSqr(
                                        site.origin().getX() + 0.5,
                                        player.getY(),
                                        site.origin().getZ() + 0.5)
                                <= range * range
                        && visited.add(site)) {
                    spawnAt(level, site);
                }
            }
        } catch (RuntimeException e) {
            if (LOGGED_FAILURES.add(
                    level.dimension().identifier() + " " + e.getClass().getName())) {
                DeepCharter.LOGGER.error(
                        "Spawning the lampless figure failed in {}; this and later spawns may not happen",
                        level.dimension().identifier(),
                        e);
            }
        }
    }

    /**
     * Puts one figure on a rail of {@code site}, walking either way along it, when the site and the level have room for it and the
     * last figure was killed long enough ago. It goes where the rail is loaded and ticking, unlit, and clear of every player; empty when
     * there is no such place.
     */
    public static Optional<LamplessFigure> spawnAt(ServerLevel level, StructureSite site) {
        CreatureTuning tuning = CreatureTuning.DEFAULT;
        BoundingBox bounds = site.bounds();
        // entity-query: a figure is not saved (CreatureRegistry noSave), so it lives exactly while its chunk is loaded
        // and the query sees every one
        if (!levelHasRoom(level, false)
                || level.getEntities(
                                        CreatureRegistry.LAMPLESS_FIGURE,
                                        figure -> bounds.isInside(figure.blockPosition()))
                                .size()
                        >= tuning.maxPerSite()) {
            return Optional.empty();
        }
        List<BlockPos> places = places(level, site, tuning);
        if (places.isEmpty()) {
            return Optional.empty();
        }
        BlockPos place = places.get(level.getRandom().nextInt(places.size()));
        LamplessFigure figure = CreatureRegistry.LAMPLESS_FIGURE.create(level, EntitySpawnReason.EVENT);
        if (figure == null) {
            throw new IllegalStateException("The lampless figure could not be created in "
                    + level.dimension().identifier());
        }
        figure.setPos(place.getX() + 0.5, place.getY(), place.getZ() + 0.5);
        Direction along = site.alongZ() ? Direction.SOUTH : Direction.EAST;
        figure.setHeading(level.getRandom().nextBoolean() ? along : along.getOpposite());
        level.addFreshEntity(figure);
        return Optional.of(figure);
    }

    /**
     * Whether the level may take one more figure now, of the game's spawner ({@code natural}) or of the rail: under the cap, and long
     * enough since the last of that kind was killed. The two kinds are counted apart, so that the rail's figure of Prospector's Run, which is
     * fixed lore, is never held back by the figures of the caverns.
     */
    public static boolean levelHasRoom(ServerLevel level, boolean natural) {
        CreatureTuning tuning = CreatureTuning.DEFAULT;
        Long lastKill = (natural ? LAST_NATURAL_KILL : LAST_KILL).get(level.dimension());
        return (lastKill == null || level.getGameTime() - lastKill >= tuning.respawnDelayTicks())
                // entity-query: a figure is not saved (CreatureRegistry noSave), so it lives exactly while its chunk is
                // loaded and the query sees every one
                && level.getEntities(CreatureRegistry.LAMPLESS_FIGURE, figure -> figure.isNatural() == natural)
                                .size()
                        < tuning.maxPerLevel();
    }

    /** What the game's own spawner asks of a try at {@code pos}: a roll that keeps them rare, then the rules of {@link #canAppearAt} and room in the level. */
    static boolean mayAppearNaturally(ServerLevel level, BlockPos pos, RandomSource random) {
        return rolls(level, random) && canAppearAt(level, pos) && levelHasRoom(level, true);
    }

    /** The cheap check, first: one try in {@link CreatureTuning.Natural#chancePerTry} passes in layer 2, and fewer in layer 1. */
    public static boolean rolls(ServerLevel level, RandomSource random) {
        CreatureTuning.Natural natural = CreatureTuning.DEFAULT.natural();
        double chance = level.dimension().equals(LayerChain.dimension(1))
                ? natural.layerOneChancePerTry()
                : natural.chancePerTry();
        return random.nextDouble() < chance;
    }

    /**
     * Whether a figure may appear of itself with its feet at {@code pos}: in layer 1 or 2, in the dark (under the light at which a place counts as lit), and on a solid floor with room to stand. Never the surface, and never a lit space.
     */
    public static boolean canAppearAt(ServerLevel level, BlockPos pos) {
        CreatureTuning tuning = CreatureTuning.DEFAULT;
        if (!NATURAL_LEVELS.contains(level.dimension())
                || !level.isPositionEntityTicking(pos)
                || level.getBrightness(LightLayer.BLOCK, pos) >= tuning.litBlockLight()) {
            return false;
        }
        BlockPos floor = pos.below();
        if (!level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)) {
            return false;
        }
        for (int up = 0; up < tuning.natural().roomBlocks(); up++) {
            BlockPos cell = pos.above(up);
            BlockState state = level.getBlockState(cell);
            if (!state.getCollisionShape(level, cell).isEmpty()
                    || !state.getFluidState().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** The rails of the site where a figure may appear. Checks the position is ticking before it reads a block, so that no chunk loads. */
    private static List<BlockPos> places(ServerLevel level, StructureSite site, CreatureTuning tuning) {
        BoundingBox bounds = site.bounds();
        int from = site.alongZ() ? bounds.minZ() : bounds.minX();
        int to = site.alongZ() ? bounds.maxZ() : bounds.maxX();
        double clearance = tuning.spawnClearanceBlocks();
        List<BlockPos> places = new ArrayList<>();
        for (int along = from; along <= to; along++) {
            BlockPos pos = site.alongZ()
                    ? new BlockPos(site.origin().getX(), site.origin().getY(), along)
                    : new BlockPos(along, site.origin().getY(), site.origin().getZ());
            if (level.isPositionEntityTicking(pos)
                    && level.getBlockState(pos).is(BlockTags.RAILS)
                    && level.getBlockState(pos.above()).isAir()
                    && level.getBrightness(LightLayer.BLOCK, pos) < tuning.litBlockLight()
                    && level.getNearestPlayer(
                                    pos.getX() + 0.5,
                                    pos.getY(),
                                    pos.getZ() + 0.5,
                                    clearance,
                                    EntitySelector.NO_SPECTATORS)
                            == null) {
                places.add(pos);
            }
        }
        return places;
    }
}
