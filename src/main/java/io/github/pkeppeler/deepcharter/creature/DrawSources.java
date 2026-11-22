package io.github.pkeppeler.deepcharter.creature;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * What draws creatures, or keeps them off, that a person puts in the world for a while (#985): the one place the thrown kit speaks to creature code,
 * so a creature that arrives later reads this and a new thrown item needs no change in the creature.
 *
 * <ul>
 *   <li>A <b>flare</b> lights the dark within its reach. The Retained are drawn to the dark ({@link LamplessFigure#draws}), so a pod within a burning
 *       flare's reach draws none. The flare is also a block of real light, which the light rules that read block light see on their own; the reach is
 *       the part of the rule that a block cannot carry, because a pod's lights are not block light.
 *   <li>A <b>decoy</b> is a noise. A creature drawn to the drill (the Sleeper's: Drifters, Listeners, the Hush) asks {@link #noiseTarget} where to
 *       head and goes to the decoy when one sounds within its pull, to the drill otherwise.
 * </ul>
 *
 * <p>Nothing here is saved: a source is a few minutes of a fight, and a server that stops ends it, as it ends the pod's fuse. The state is per level and
 * server thread only; it is read by game time, so an expired source is simply not found.
 */
public final class DrawSources {
    private record Flare(BlockPos pos, double radius, long until) {}

    private record Decoy(Vec3 pos, double pull, long until) {}

    private static final Map<ResourceKey<Level>, List<Flare>> FLARES = new HashMap<>();
    private static final Map<ResourceKey<Level>, List<Decoy>> DECOYS = new HashMap<>();

    private DrawSources() {}

    public static void init() {
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> clear());
    }

    /** Forgets every source, as a server that stops does. */
    public static void clear() {
        FLARES.clear();
        DECOYS.clear();
    }

    /** A flare that burns at {@code pos} until game time {@code untilTick}, keeping the dark off within {@code radius} blocks. */
    public static void addFlare(ServerLevel level, BlockPos pos, double radius, long untilTick) {
        List<Flare> flares = FLARES.computeIfAbsent(level.dimension(), key -> new ArrayList<>());
        flares.removeIf(
                flare -> flare.until() <= level.getGameTime() || flare.pos().equals(pos));
        flares.add(new Flare(pos.immutable(), radius, untilTick));
    }

    /** Whether {@code at} is within the reach of a flare that still burns. Either side of the wire may ask; the client's answer is always no. */
    public static boolean litByFlare(Level level, Vec3 at) {
        List<Flare> flares = FLARES.get(level.dimension());
        if (flares == null) {
            return false;
        }
        long now = level.getGameTime();
        return flares.stream()
                .anyMatch(flare -> flare.until() > now
                        && Vec3.atCenterOf(flare.pos()).distanceToSqr(at) <= flare.radius() * flare.radius());
    }

    /** A decoy that sounds at {@code pos} until game time {@code untilTick}, pulling creatures within {@code pullBlocks} of it. */
    public static void addDecoy(ServerLevel level, Vec3 pos, double pullBlocks, long untilTick) {
        List<Decoy> decoys = DECOYS.computeIfAbsent(level.dimension(), key -> new ArrayList<>());
        decoys.removeIf(decoy -> decoy.until() <= level.getGameTime());
        decoys.add(new Decoy(pos, pullBlocks, untilTick));
    }

    /**
     * Where a creature drawn to the drill, now at {@code from}, heads: the nearest decoy that still sounds and whose pull reaches it, or
     * {@code drillAt}, the noise of the drill it was drawn to, when there is none.
     */
    // test-seam: the hook a creature drawn to the drill calls. Drifters (#918) and Listeners (#928) are its callers and
    // are not built yet.
    public static Vec3 noiseTarget(ServerLevel level, Vec3 from, Vec3 drillAt) {
        List<Decoy> decoys = DECOYS.get(level.dimension());
        if (decoys == null) {
            return drillAt;
        }
        long now = level.getGameTime();
        Vec3 target = drillAt;
        double nearest = Double.MAX_VALUE;
        for (Decoy decoy : decoys) {
            double distance = decoy.pos().distanceToSqr(from);
            if (decoy.until() > now && distance <= decoy.pull() * decoy.pull() && distance < nearest) {
                nearest = distance;
                target = decoy.pos();
            }
        }
        return target;
    }
}
