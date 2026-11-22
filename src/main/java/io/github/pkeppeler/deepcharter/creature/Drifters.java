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
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The Drifters' world (#973): what makes a noise in the hollows, and when a source of noise draws a new drifter. Spawning keys on the biome tag
 * {@link #FUNGAL_HOLLOWS}, which the shipped data leaves empty (the worldgen of layer 3, #972, fills it): a source in a biome outside it draws
 * nothing, and so does a drifter's place outside it. Nothing is saved: a drifter is made from the noise as it stands.
 *
 * <p>The count is of pods, not of people ({@link DrifterTuning.Spawn}): a source is a pod that is drilling with a person aboard, once however
 * many sit in it, and the people on foot who make a noise together are one more. Each source rolls once a look at the chance of its zone
 * (a third of a layer by height: {@link DrifterAlarm.Zone}), up to what one source may have alive and what the zone may hold, and the
 * alarm of the zone ({@link DrifterAlarm}) multiplies the chance and the allowance.
 */
public final class Drifters {
    /** The biomes in which drifters appear. Empty in the shipped data; the test data pack names a test biome. */
    public static final TagKey<Biome> FUNGAL_HOLLOWS =
            TagKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "fungal_hollows"));

    /**
     * What a drifter hears: a place and how far it carries. {@code listener} is the person the cue goes to when a drifter is first drawn by it,
     * and {@code pod} is whether it is a drilling pod's noise and not a person's steps.
     */
    public record Noise(Vec3 pos, double radius, UUID listener, boolean pod) {}

    /** A place that draws drifters: where it is, in the level a look found it. */
    public record Source(Vec3 pos) {}

    /** What each level's last look found. Server thread only; a level with nothing has no entry. */
    private static final Map<ResourceKey<Level>, List<Noise>> NOISES = new HashMap<>();

    /** Where each person on foot stood at the last look, to tell walking from standing. Server thread only. */
    private static Map<UUID, Vec3> lastSeen = new HashMap<>();

    private Drifters() {}

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> clear());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> clear());
        ServerTickEvents.END_LEVEL_TICK.register(Drifters::tick);
    }

    private static void clear() {
        NOISES.clear();
        lastSeen = new HashMap<>();
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
        if (now % tuning.spawn().checkTicks() == 0) {
            List<Source> sources = sources(level);
            for (Source source : sources) {
                Optional<DrifterAlarm.Zone> zone = zoneOf(level, source);
                if (zone.isPresent()
                        && rolls(
                                zone.get().index(),
                                DrifterAlarm.isRinging(
                                        level, BlockPos.containing(source.pos()).getY()),
                                level.getRandom())) {
                    int inZone = (int) sources.stream()
                            .filter(other -> zoneOf(level, other).equals(zone))
                            .count();
                    spawnFor(level, source, inZone, level.getRandom());
                }
            }
        }
    }

    private static Optional<DrifterAlarm.Zone> zoneOf(ServerLevel level, Source source) {
        return DrifterAlarm.Zone.at(level, BlockPos.containing(source.pos()).getY());
    }

    /**
     * Listens to the level: every pod drilling with a person aboard makes the drill's noise, and every person on foot makes the sprint's
     * or the footstep's, by how far they went since the last look. A spectator, and a person who sneaks or stands, make none.
     */
    private static void look(ServerLevel level) {
        DrifterTuning.Noise tuning = DrifterTuning.DEFAULT.noise();
        List<Noise> heard = new ArrayList<>();
        Set<PodEntity> pods = new HashSet<>();
        Map<UUID, Vec3> now = new HashMap<>();
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) {
                continue;
            }
            if (player.getVehicle() instanceof PodEntity pod) {
                if (pod.drilling() && !Wrecks.isWreck(pod) && pods.add(pod)) {
                    heard.add(new Noise(pod.position(), tuning.drill(), player.getUUID(), true));
                }
                continue;
            }
            now.put(player.getUUID(), player.position());
            Vec3 before = lastSeen.get(player.getUUID());
            double moved = before == null ? 0 : horizontalDistance(before, player.position()) / tuning.lookTicks();
            if (moved >= tuning.footstepMin() && !player.isShiftKeyDown()) {
                heard.add(new Noise(
                        player.position(),
                        player.isSprinting() ? tuning.sprint() : tuning.footstep(),
                        player.getUUID(),
                        false));
            }
        }
        lastSeen = merge(level, now);
        if (heard.isEmpty()) {
            NOISES.remove(level.dimension());
        } else {
            NOISES.put(level.dimension(), heard);
        }
    }

    /** The people seen on foot in this level now, and those seen in another level at the last look, so one level's look does not forget another's. */
    private static Map<UUID, Vec3> merge(ServerLevel level, Map<UUID, Vec3> seenHere) {
        Map<UUID, Vec3> merged = new HashMap<>(seenHere);
        for (Map.Entry<UUID, Vec3> entry : lastSeen.entrySet()) {
            if (level.getPlayerByUUID(entry.getKey()) == null) {
                merged.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }
        return merged;
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
                sources.add(new Source(noise.pos()));
            } else if (!foot) {
                foot = true;
                sources.add(new Source(noise.pos()));
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
        DrifterTuning.Spawn tuning = DrifterTuning.DEFAULT.spawn();
        BlockPos from = BlockPos.containing(source.pos());
        Optional<DrifterAlarm.Zone> zone = DrifterAlarm.Zone.at(level, from.getY());
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
        Optional<Vec3> place = place(level, from, random, tuning);
        if (place.isEmpty()) {
            return Optional.empty();
        }
        Drifter drifter = CreatureRegistry.DRIFTER.create(level, EntitySpawnReason.EVENT);
        if (drifter == null) {
            throw new IllegalStateException(
                    "The drifter could not be created in " + level.dimension().identifier());
        }
        drifter.setPos(place.get());
        drifter.hunt(source.pos());
        level.addFreshEntity(drifter);
        return Optional.of(drifter);
    }

    /** A free place, loaded and ticking, in a hollows biome, between the least and the most distance from {@code from}. */
    private static Optional<Vec3> place(
            ServerLevel level, BlockPos from, RandomSource random, DrifterTuning.Spawn tuning) {
        for (int attempt = 0; attempt < tuning.tries(); attempt++) {
            double angle = random.nextDouble() * Mth.TWO_PI;
            double distance = Mth.lerp(random.nextDouble(), tuning.minBlocks(), tuning.maxBlocks());
            BlockPos pos = from.offset((int) Math.round(Math.cos(angle) * distance), random.nextInt(-8, 9), (int)
                    Math.round(Math.sin(angle) * distance));
            if (level.isPositionEntityTicking(pos) && level.getBiome(pos).is(FUNGAL_HOLLOWS) && room(level, pos)) {
                return Optional.of(Vec3.atBottomCenterOf(pos));
            }
        }
        return Optional.empty();
    }

    /** Whether a bladder's box, with its feet at {@code pos}, is all open air or gas. */
    private static boolean room(ServerLevel level, BlockPos pos) {
        AABB box = CreatureRegistry.DRIFTER.getDimensions().makeBoundingBox(Vec3.atBottomCenterOf(pos));
        return new LoadedBlocks(level)
                .getBlockStates(box)
                .allMatch(state -> state.getCollisionShape(level, pos).isEmpty()
                        && state.getFluidState().isEmpty());
    }

    /** The drifters whose box a person's or a pod's box touches, for the burst: a query round a drifter that ticks. */
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
