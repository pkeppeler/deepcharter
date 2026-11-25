package io.github.pkeppeler.deepcharter.creature;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.scanner.LoadedBlocks;
import io.github.pkeppeler.deepcharter.wreck.Wrecks;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The Drifters' world (#973): what makes a noise in the hollows, and when a source of noise draws a new drifter. Placement keys on the biome tag
 * {@link #FUNGAL_HOLLOWS}, which holds layer 3's zone biomes (#972). The count is of pods, not of people: see
 * {@link DrifterTuning.Spawn}. Nothing is saved.
 */
public final class Drifters {
    /**
     * The biomes in which drifters appear: layer 3's zones, and in the test data pack a test biome too. The same tag as #976's
     * {@code Overgrowth.HOLLOWS}: whichever of the two PRs merges second folds one constant into the other.
     */
    public static final TagKey<Biome> FUNGAL_HOLLOWS =
            TagKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "fungal_hollows"));

    /**
     * What a drifter hears: a place and how far it carries. {@code listener} is the person the cue goes to when a drifter is first drawn by it,
     * and {@code pod} is whether it is a drilling pod's noise and not a person's steps.
     */
    public record Noise(Vec3 pos, double radius, UUID listener, boolean pod) {}

    /** A place that draws drifters: where it is, in the level a look found it, and whether it is a pod's drill and not the crew on foot. */
    public record Source(Vec3 pos, boolean pod) {}

    /** What each level's last look found. Server thread only; a level with nothing has no entry. */
    private static final Map<ResourceKey<Level>, List<Noise>> NOISES = new HashMap<>();

    /** Where a person stood on foot, and in which level. */
    private record Seen(ResourceKey<Level> dimension, UUID person) {}

    /** Where each person on foot stood at the last look, to tell walking from standing. Server thread only. */
    private static Map<Seen, Vec3> lastSeen = new HashMap<>();

    /** The source of chance for the rolls and placements of a zone, in place of the level's, by zone. Server thread only. */
    private static final Map<DrifterAlarm.Zone, RandomSource> SEEDED = new HashMap<>();

    private Drifters() {}

    /**
     * Makes the tick roll and place for sources in {@code zone} with {@code random} in place of the level's, so a test's counts do not
     * depend on chance; null restores the level's. Other zones, and other levels, keep the level's.
     */
    // test-seam: a test seeds the chance of a zone it alone uses so that its drifter counts are exact, not a
    // probability
    public static void seed(DrifterAlarm.Zone zone, @Nullable RandomSource random) {
        if (random == null) {
            SEEDED.remove(zone);
        } else {
            SEEDED.put(zone, random);
        }
    }

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> clear());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> clear());
        ServerTickEvents.END_LEVEL_TICK.register(Drifters::tick);
    }

    private static void clear() {
        NOISES.clear();
        lastSeen = new HashMap<>();
        SEEDED.clear();
    }

    /** The nearest noise that reaches {@code from}, as far as its radius times {@code senseMult}. */
    public static Optional<Noise> heardBy(ServerLevel level, Vec3 from, double senseMult) {
        Noise nearest = null;
        double best = Double.MAX_VALUE;
        for (Noise noise : NOISES.getOrDefault(level.dimension(), List.of())) {
            double reach = noise.radius() * senseMult;
            double distance = noise.pos().distanceToSqr(from);
            if (distance <= reach * reach && distance < best) {
                best = distance;
                nearest = noise;
            }
        }
        return Optional.ofNullable(nearest);
    }

    private static void tick(ServerLevel level) {
        DrifterTuning tuning = DrifterTuning.DEFAULT;
        long now = level.getGameTime();
        if (now % tuning.noise().lookTicks() == 0) {
            look(level);
        }
        if (now % tuning.spawn().checkTicks() == 0 && mayPlace(level)) {
            List<Source> sources = sources(level);
            for (Source source : sources) {
                Optional<DrifterAlarm.Zone> zone = zoneOf(level, source);
                RandomSource random = zone.map(SEEDED::get).orElse(level.getRandom());
                if (zone.isPresent()
                        && rolls(
                                zone.get().index(),
                                DrifterAlarm.isRinging(
                                        level, BlockPos.containing(source.pos()).getY()),
                                random)) {
                    int inZone = (int) sources.stream()
                            .filter(other -> zoneOf(level, other).equals(zone))
                            .count();
                    spawnFor(level, source, inZone, random);
                }
            }
        }
    }

    /** A mob the mod places itself honours the game's off switches: the {@code spawn_mobs} rule, and Peaceful, which removes monsters. */
    private static boolean mayPlace(ServerLevel level) {
        return level.getGameRules().get(GameRules.SPAWN_MOBS) && level.getDifficulty() != Difficulty.PEACEFUL;
    }

    private static Optional<DrifterAlarm.Zone> zoneOf(ServerLevel level, Source source) {
        return DrifterAlarm.Zone.at(level, BlockPos.containing(source.pos()).getY());
    }

    /**
     * Listens to the level: every pod drilling with a person aboard makes the drill's noise, and every person on foot makes the sprint's
     * or the footstep's, by how far they went since the last look in this level. A spectator, a creative player, one who sneaks or stands,
     * and one who was moved a long way at once (a teleport, a change of level) make none.
     */
    private static void look(ServerLevel level) {
        DrifterTuning.Noise tuning = DrifterTuning.DEFAULT.noise();
        List<Noise> heard = new ArrayList<>();
        Set<PodEntity> pods = new HashSet<>();
        Map<Seen, Vec3> now = new HashMap<>();
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) {
                continue;
            }
            if (player.getVehicle() instanceof PodEntity pod) {
                if (pod.drilling() && !Wrecks.isWreck(pod) && pods.add(pod)) {
                    // The cue goes to the pilot, whoever the loop met first.
                    UUID listener =
                            pod.getControllingPassenger() instanceof Player pilot ? pilot.getUUID() : player.getUUID();
                    heard.add(new Noise(pod.position(), tuning.drill(), listener, true));
                }
                continue;
            }
            Seen key = new Seen(level.dimension(), player.getUUID());
            now.put(key, player.position());
            Vec3 before = lastSeen.get(key);
            double moved = before == null ? 0 : horizontalDistance(before, player.position()) / tuning.lookTicks();
            if (!player.isCreative()
                    && !player.isShiftKeyDown()
                    && moved >= tuning.footstepMin()
                    && moved <= tuning.footstepMax()) {
                heard.add(new Noise(
                        player.position(),
                        player.isSprinting() ? tuning.sprint() : tuning.footstep(),
                        player.getUUID(),
                        false));
            }
        }
        lastSeen = forget(level, now);
        if (heard.isEmpty()) {
            NOISES.remove(level.dimension());
        } else {
            NOISES.put(level.dimension(), heard);
        }
    }

    /** The people seen on foot in this level now, and those seen in other levels, but not those who are no longer online. */
    private static Map<Seen, Vec3> forget(ServerLevel level, Map<Seen, Vec3> seenHere) {
        Map<Seen, Vec3> kept = new HashMap<>(seenHere);
        for (Map.Entry<Seen, Vec3> entry : lastSeen.entrySet()) {
            if (!entry.getKey().dimension().equals(level.dimension())
                    && level.getServer()
                                    .getPlayerList()
                                    .getPlayer(entry.getKey().person())
                            != null) {
                kept.put(entry.getKey(), entry.getValue());
            }
        }
        return kept;
    }

    private static double horizontalDistance(Vec3 a, Vec3 b) {
        return Math.hypot(a.x - b.x, a.z - b.z);
    }

    /**
     * The sources of a level: a pod drilling with a person aboard, once, and the people on foot who make a noise, together, at the nearest to
     * the first of them.
     */
    public static List<Source> sources(ServerLevel level) {
        List<Source> sources = new ArrayList<>();
        boolean foot = false;
        for (Noise noise : NOISES.getOrDefault(level.dimension(), List.of())) {
            if (noise.pod()) {
                sources.add(new Source(noise.pos(), true));
            } else if (!foot) {
                foot = true;
                sources.add(new Source(noise.pos(), false));
            }
        }
        return sources;
    }

    /** Whether one look at a source in zone {@code zone} (0 is the top) places a drifter, with the alarm of the zone ringing or not. */
    public static boolean rolls(int zone, boolean alarm, RandomSource random) {
        return random.nextDouble() < chance(zone, alarm);
    }

    /** The chance that one look at a source in zone {@code zone} places a drifter, with the alarm ringing there or not. */
    public static double chance(int zone, boolean alarm) {
        DrifterTuning tuning = DrifterTuning.DEFAULT;
        return Math.min(
                1.0,
                tuning.spawn().chancePerCheck(zone) * (alarm ? tuning.alarm().chanceMult() : 1.0));
    }

    /**
     * Places one drifter for {@code source}, one of {@code sourcesInZone} sources in its zone, if the source is in a hollows biome, the zone has
     * room for one (what the zone's sources allow, up to the zone's cap), and a free place in the hollows is loaded and ticking at the right
     * distance. It comes already drawn to the source. Empty when it did not.
     */
    public static Optional<Drifter> spawnFor(ServerLevel level, Source source, int sourcesInZone, RandomSource random) {
        return spawnFor(level, source, sourcesInZone, random, DrifterTuning.DEFAULT.spawn());
    }

    /**
     * {@link #spawnFor(ServerLevel, Source, int, RandomSource)} with the spawn tuning given, so a test can skew the
     * form weights.
     */
    public static Optional<Drifter> spawnFor(
            ServerLevel level, Source source, int sourcesInZone, RandomSource random, DrifterTuning.Spawn tuning) {
        BlockPos from = BlockPos.containing(source.pos());
        Optional<DrifterAlarm.Zone> zone = zoneOf(level, source);
        if (zone.isEmpty() || !level.getBiome(from).is(FUNGAL_HOLLOWS)) {
            return Optional.empty();
        }
        boolean alarm = DrifterAlarm.isRinging(level, from.getY());
        int perSource =
                tuning.perSource() + (alarm ? DrifterTuning.DEFAULT.alarm().extraEach() : 0);
        int room = Math.min(tuning.zoneCap().get(zone.get().index()), perSource * sourcesInZone);
        // entity-query: a drifter is not saved (CreatureRegistry noSave), so it lives exactly while its chunk is loaded
        // and the query sees every one; the count is of drifters that act
        List<? extends Drifter> alive = level.getEntities(
                CreatureRegistry.DRIFTER,
                drifter -> DrifterAlarm.Zone.at(level, drifter.getBlockY()).equals(zone));
        if (alive.size() >= room) {
            return Optional.empty();
        }
        Optional<Vec3> place = place(level, from, zone.get(), random, tuning);
        if (place.isEmpty()) {
            return Optional.empty();
        }
        Drifter drifter = CreatureRegistry.DRIFTER.create(level, EntitySpawnReason.EVENT);
        if (drifter == null) {
            throw new IllegalStateException(
                    "The drifter could not be created in " + level.dimension().identifier());
        }
        drifter.setPos(place.get());
        // After the place, so the form's roll never moves where this drifter appears; later rolls of the tick still
        // shift.
        drifter.setVariant(DrifterVariant.pick(random, tuning.variantWeights()));
        drifter.hunt(source.pos());
        level.addFreshEntity(drifter);
        return Optional.of(drifter);
    }

    /**
     * A free place, loaded and ticking, in a hollows biome, in the source's own zone and the world's bounds, between the least and the most
     * distance from {@code from}.
     */
    private static Optional<Vec3> place(
            ServerLevel level, BlockPos from, DrifterAlarm.Zone zone, RandomSource random, DrifterTuning.Spawn tuning) {
        Vec3 source = Vec3.atCenterOf(from);
        for (int attempt = 0; attempt < tuning.tries(); attempt++) {
            double angle = random.nextDouble() * Mth.TWO_PI;
            double distance = Mth.lerp(random.nextDouble(), tuning.minBlocks(), tuning.maxBlocks());
            BlockPos pos = from.offset((int) Math.round(Math.cos(angle) * distance), random.nextInt(-8, 9), (int)
                    Math.round(Math.sin(angle) * distance));
            double away = Vec3.atBottomCenterOf(pos).distanceTo(source);
            if (level.isInWorldBounds(pos)
                    && level.isInWorldBounds(pos.above())
                    && away >= tuning.minBlocks()
                    && DrifterAlarm.Zone.at(level, pos.getY()).equals(Optional.of(zone))
                    && DrifterAlarm.Zone.at(level, pos.getY() + 1).equals(Optional.of(zone))
                    && level.isPositionEntityTicking(pos)
                    && level.getBiome(pos).is(FUNGAL_HOLLOWS)
                    && room(level, pos)) {
                return Optional.of(Vec3.atBottomCenterOf(pos));
            }
        }
        return Optional.empty();
    }

    /** Whether a bladder's box, with its feet at {@code pos}, is all open air or gas. */
    private static boolean room(ServerLevel level, BlockPos pos) {
        AABB box = CreatureRegistry.DRIFTER.getDimensions().makeBoundingBox(Vec3.atBottomCenterOf(pos));
        LoadedBlocks blocks = new LoadedBlocks(level);
        return BlockPos.betweenClosedStream(box).allMatch(cell -> {
            BlockState state = blocks.getBlockState(cell);
            return state.getCollisionShape(blocks, cell).isEmpty()
                    && state.getFluidState().isEmpty();
        });
    }

    /** Whether a person's or a pod's box touches the drifter's, for the burst: a query round a drifter that ticks. */
    static boolean touched(ServerLevel level, Drifter drifter) {
        DrifterTuning tuning = DrifterTuning.DEFAULT;
        AABB skin = drifter.getBoundingBox().inflate(tuning.touchBlocks());
        AABB beside = drifter.getBoundingBox().inflate(tuning.drilledBeside());
        // entity-query: round a drifter that ticks, so its chunk is loaded; a pod or person it does not see is not
        // near it
        boolean pod = !level.getEntitiesOfClass(
                        PodEntity.class,
                        beside,
                        near -> !Wrecks.isWreck(near)
                                && (near.drilling() || near.getBoundingBox().intersects(skin)))
                .isEmpty();
        // entity-query: as above
        return pod
                || !level.getEntitiesOfClass(Player.class, skin, person -> !person.isSpectator())
                        .isEmpty();
    }
}
