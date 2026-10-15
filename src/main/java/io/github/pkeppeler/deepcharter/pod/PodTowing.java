package io.github.pkeppeler.deepcharter.pod;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.layer.BreachEvents;
import io.netty.buffer.ByteBuf;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The tow cable between two pods. A player riding a pod uses a {@link PodRegistry#TOW_CABLE} on another pod within
 * {@link TowTuning#reachFor} to make it the towed pod of theirs, and uses it on a towed pod to take the cable off. A pod moved
 * where its owner did not choose needs a rule for who may do it: a player may tow a pod only when {@link PodComponents#mayAccess}
 * lets their charter at it, and the tower's charter or the towed pod's owner charter may free it, so an owner can always recover a
 * pod someone else towed.
 *
 * <p>The link is one versioned attachment on the towed pod, {@link #STATE}, holding the tower's UUID. It is saved and synced, and a
 * pod keeps its UUID when a breach recreates it, so the link outlives unloading and crossing. While the tower is in the towed pod's
 * level the towed pod passes through blocks ({@link PodEvents#IGNORES_BLOCK_COLLISION}), hangs or trails on a line of
 * {@link TowTuning#trailDistance} from the tower, moved at the end of each tick ({@link PodEvents#AFTER_TICK}, see {@link #follow}),
 * and its mass and its cargo's cut the tower's lift ({@link PodEvents#EXTRA_MASS}). With no tower in the level it is an ordinary pod
 * that still remembers the cable. A pod freed, or left by its tower, inside blocks is moved to the nearest open space. A tower with a
 * player crossing a breach carries the pod it tows across with it, to the trail distance from the tower or the nearest open place
 * to it. The client draws the cable as a chain of links between the two pods (see {@code TowChainRenderer}).
 *
 * <p>A tower tows one pod, a towed pod tows none, and a pod is on one cable. The listeners run every tick and on a crossing, so they
 * never throw on an unreadable state: they log once for each pod and read it as no cable. {@link #attach} and {@link #detach} are
 * explicit changes and do throw.
 */
public final class PodTowing {
    public static final int VERSION = 1;

    /** Blocks along a search line between the candidate positions for a pod to leave rock by. */
    private static final double OPEN_STEP = 0.25;
    /** Blocks a pod is moved at most to leave rock when its tower gives no direction. */
    private static final double OPEN_REACH = 8.0;

    private static final Vec3[] OPEN_DIRECTIONS = {
        new Vec3(0, 1, 0),
        new Vec3(1, 0, 0),
        new Vec3(-1, 0, 0),
        new Vec3(0, 0, 1),
        new Vec3(0, 0, -1),
        new Vec3(0, -1, 0)
    };

    /** Pods that found no open space to leave rock for, so a tick path logs once for each pod. */
    private static final Set<PodEntity> STUCK_LOGGED =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    /** The motion of each towed pod on its line. Server only and not saved: a loaded pod starts at rest with no path behind its tower. */
    private static final Map<PodEntity, Line> LINES = Collections.synchronizedMap(new WeakHashMap<>());

    private static final double EPSILON = 1e-7;
    /** Blocks along the line between the samples that test it for rock: less than any wall is thick. */
    private static final double FIT_STEP = 0.5;
    /** Blocks the tower moves between two points of the path it leaves. */
    private static final double PATH_STEP = 0.25;
    /** Blocks the tower moves in one tick, at the least, for it to count as having been carried across a breach: the path starts over. */
    private static final double PATH_JUMP = 4.0;
    /** Blocks a breach crossing shortens the towed pod's arrival distance by, for each try, when the place is not open. */
    private static final double ARRIVAL_STEP = 0.5;
    /** Ticks between two renewals of the ticket that keeps a towed pod's chunk ticking: well under the ticket's 40. */
    private static final int TICKET_INTERVAL = 5;
    /** The ticket radius that makes its chunk tick entities, as an ender pearl's does. */
    private static final int TICKET_RADIUS = 2;

    /** How a towed pod moves on its line: its speed, whether it rests on a floor, and the path its tower has taken. */
    private static final class Line {
        Vec3 velocity = Vec3.ZERO;
        boolean resting;
        /** Where the tower has been, newest first, {@link #PATH_STEP} apart, for as far back as the line is long. */
        private final ArrayDeque<Vec3> path = new ArrayDeque<>();

        /** Adds the tower's place to the path and drops what lies further back than {@code rope}. */
        void remember(Vec3 anchor, double rope) {
            Vec3 last = path.peekFirst();
            if (last != null && last.distanceToSqr(anchor) > PATH_JUMP * PATH_JUMP) {
                path.clear();
                last = null;
            }
            if (last == null || last.distanceToSqr(anchor) >= PATH_STEP * PATH_STEP) {
                path.addFirst(anchor);
            }
            double arc = 0;
            Vec3 previous = anchor;
            boolean past = false;
            for (Iterator<Vec3> it = path.iterator(); it.hasNext(); ) {
                Vec3 point = it.next();
                if (past) {
                    it.remove();
                    continue;
                }
                arc += previous.distanceTo(point);
                previous = point;
                past = arc > rope + PATH_STEP;
            }
        }

        /**
         * Where on the path the towed pod may be put, best first: the point {@code rope} blocks back along it from {@code anchor}, and the
         * two points of the path either side of that, which are places the tower itself has been, so a corner that the straight
         * piece between them cuts is not. If the path is shorter than {@code rope}, its oldest point. Empty if there is no path.
         */
        List<Vec3> pathPoints(Vec3 anchor, double rope) {
            Vec3 previous = anchor;
            double left = rope;
            for (Vec3 point : path) {
                double segment = previous.distanceTo(point);
                if (segment >= left) {
                    Vec3 between = segment < EPSILON
                            ? previous
                            : previous.add(point.subtract(previous).scale(left / segment));
                    return List.of(between, point, previous);
                }
                left -= segment;
                previous = point;
            }
            return path.isEmpty() ? List.of() : List.of(previous);
        }
    }

    /** The pod this one is towed by, if any. */
    public record State(Optional<UUID> tower) {
        public static final State EMPTY = new State(Optional.empty());
        public static final MapCodec<State> BODY = RecordCodecBuilder.mapCodec(instance -> instance.group(
                        UUIDUtil.CODEC.optionalFieldOf("tower").forGetter(State::tower))
                .apply(instance, State::new));
        public static final StreamCodec<ByteBuf, State> STREAM =
                ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC).map(State::new, State::tower);
    }

    public static final AttachmentType<Versioned<State>> STATE = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "pod_towing"),
            builder -> builder.persistent(Versioned.codec(VERSION, State.BODY))
                    .initializer(() -> Versioned.of(State.EMPTY))
                    .syncWith(Versioned.streamCodec(VERSION, State.STREAM), AttachmentSyncPredicate.all()));

    /** Why a cable cannot be fitted from one pod to another. */
    public enum Refusal {
        NOT_RIDING,
        NOT_ALLOWED_TO_TOW,
        NOT_ALLOWED_TO_FREE,
        SAME_POD,
        TOO_FAR,
        ALREADY_TOWED,
        TOWER_IS_TOWED,
        TOWED_TOWS,
        ALREADY_TOWING,
        UNREADABLE;

        public Component message() {
            return Component.translatable("message.deepcharter.towing.refused." + name().toLowerCase(Locale.ROOT));
        }
    }

    private PodTowing() {}

    public static void init() {
        PodEvents.EXTRA_MASS.register(PodTowing::towedMass);
        PodEvents.IGNORES_BLOCK_COLLISION.register(pod -> tower(pod).isPresent());
        PodEvents.AFTER_TICK.register(PodTowing::follow);
        PodEvents.AFTER_TICK.register(PodTowing::keepTowedChunksTicking);
        BreachEvents.CROSSED.register(PodTowing::carryAcross);
        UseEntityCallback.EVENT.register(PodTowing::onUse);
    }

    /** The UUID of the pod towing this one, whether or not it is in the world. An unreadable cable reads as none (logged once). Never throws. */
    public static Optional<UUID> towerId(PodEntity pod) {
        return Versioned.readable(pod, STATE).flatMap(State::tower);
    }

    /** True when the pod is on a cable, even if its tower is away. Safe on either side. */
    public static boolean isTowed(PodEntity pod) {
        return towerId(pod).isPresent();
    }

    /** Why {@code tower} cannot tow {@code towed} now, or empty. Never throws. */
    public static Optional<Refusal> refusal(PodEntity tower, PodEntity towed) {
        return refusal(tower, towed, TowTuning.DEFAULT);
    }

    /** As {@link #refusal(PodEntity, PodEntity)} with the tunables {@code tuning}; package-private so that a test can give a short reach. */
    static Optional<Refusal> refusal(PodEntity tower, PodEntity towed, TowTuning tuning) {
        if (tower == towed) {
            return Optional.of(Refusal.SAME_POD);
        }
        // Both are read before the test, so each unreadable pod is logged.
        boolean towerReadable = Versioned.readable(tower, STATE).isPresent();
        boolean towedReadable = Versioned.readable(towed, STATE).isPresent();
        if (!towerReadable || !towedReadable) {
            return Optional.of(Refusal.UNREADABLE);
        }
        if (isTowed(towed)) {
            return Optional.of(Refusal.ALREADY_TOWED);
        }
        if (isTowed(tower)) {
            return Optional.of(Refusal.TOWER_IS_TOWED);
        }
        if (!towedBy(towed, tuning).isEmpty()) {
            return Optional.of(Refusal.TOWED_TOWS);
        }
        if (!towedBy(tower, tuning).isEmpty()) {
            return Optional.of(Refusal.ALREADY_TOWING);
        }
        double reach = reach(tuning);
        if (tower.level() != towed.level() || tower.distanceToSqr(towed) > reach * reach) {
            return Optional.of(Refusal.TOO_FAR);
        }
        return Optional.empty();
    }

    /**
     * Server only: puts {@code towed} on a cable from {@code tower}.
     *
     * @throws IllegalStateException if the cable cannot be fitted (see {@link #refusal}), or on the client
     */
    public static void attach(PodEntity tower, PodEntity towed) {
        requireServer(towed);
        Optional<Refusal> refusal = refusal(tower, towed);
        if (refusal.isPresent()) {
            throw new IllegalStateException(
                    "pod " + tower.getUUID() + " cannot tow pod " + towed.getUUID() + ": " + refusal.get());
        }
        Versioned.modifyOrThrow(towed, STATE, state -> new State(Optional.of(tower.getUUID())));
    }

    /**
     * Server only: takes the cable off {@code towed}. Returns false, and changes nothing, when it had none. Throws on an unreadable
     * state, naming the attachment, so the saved data is never overwritten.
     */
    public static boolean detach(PodEntity towed) {
        requireServer(towed);
        if (Versioned.orThrow(towed, STATE).tower().isEmpty()) {
            return false;
        }
        Optional<Vec3> toward = tower(towed).map(PodEntity::position);
        Versioned.modifyOrThrow(towed, STATE, state -> State.EMPTY);
        LINES.remove(towed);
        leaveRock(towed, toward);
        return true;
    }

    /** The tower of a towed pod, when it is in the same level and still there. */
    public static Optional<PodEntity> tower(PodEntity towed) {
        Optional<UUID> id = towerId(towed);
        // entity-query: a tower that is not loaded reads as away, the state of a cable whose tower left; the towed pod
        // then only frees itself from rock and keeps its cable
        if (id.isEmpty()
                || !(towed.level() instanceof ServerLevel level)
                || !(level.getEntity(id.get()) instanceof PodEntity tower)
                || tower.isRemoved()) {
            return Optional.empty();
        }
        return Optional.of(tower);
    }

    /** The widest chassis registered, found on first use: pods exist only after the registry is frozen. */
    private static final class Widest {
        static final Chassis CHASSIS = PodRegistry.chassis().stream()
                .max(Comparator.comparingDouble(Chassis::width))
                .orElseThrow(() -> new IllegalStateException("no pod chassis is registered"));
    }

    /** Blocks from the tower within which a cable fits: {@link TowTuning#reachFor} for the widest chassis, so a wide chassis still trails within it. */
    private static double reach(TowTuning tuning) {
        return tuning.reachFor(Widest.CHASSIS);
    }

    /** The pods on a cable from {@code tower} that are within its reach. */
    static List<PodEntity> towedBy(PodEntity tower, TowTuning tuning) {
        // entity-query: a cable's reach round a ticking tower, and keepTowedChunksTicking holds the chunks of the pods
        // it tows entity-ticking
        return tower.level()
                .getEntitiesOfClass(
                        PodEntity.class,
                        tower.getBoundingBox().inflate(reach(tuning)),
                        other -> other != tower
                                && towerId(other)
                                        .filter(tower.getUUID()::equals)
                                        .isPresent());
    }

    private static float towedMass(PodEntity tower) {
        if (isTowed(tower)) {
            return 0f;
        }
        float mass = 0f;
        for (PodEntity towed : towedBy(tower, TowTuning.DEFAULT)) {
            mass += TowTuning.DEFAULT.baseMass() + towed.cargoMass();
        }
        return mass;
    }

    /**
     * A towed pod moves only on its own tick, and a line of 7 blocks or more leaves it in a chunk that does not tick when its tower
     * is at a chunk's edge. So the tower asks, every {@link #TICKET_INTERVAL} ticks, for the chunks of the pods it tows to tick, with
     * the ticket an ender pearl takes. It runs out on its own {@link TicketType#ENDER_PEARL} timeout, so there is nothing to release.
     */
    private static void keepTowedChunksTicking(PodEntity tower) {
        if (tower.tickCount % TICKET_INTERVAL != 0 || !(tower.level() instanceof ServerLevel level) || isTowed(tower)) {
            return;
        }
        for (PodEntity towed : towedBy(tower, TowTuning.DEFAULT)) {
            ChunkPos chunk = towed.chunkPosition();
            if (!chunk.equals(tower.chunkPosition())) {
                level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, chunk, TICKET_RADIUS);
            }
        }
    }

    /**
     * Moves the towed pod as a weight on the line. Where the pod or the line to the tower is not in open space it goes along the
     * tower's path, or failing that to the nearest open place on the line, so that the line never drags it into rock.
     */
    private static void follow(PodEntity towed) {
        Optional<PodEntity> found = tower(towed);
        if (found.isEmpty()) {
            LINES.remove(towed);
            if (isTowed(towed)) {
                leaveRock(towed, Optional.empty());
            }
            return;
        }
        PodEntity tower = found.get();
        TowTuning tuning = TowTuning.DEFAULT;
        Line line = LINES.computeIfAbsent(towed, pod -> new Line());
        double rope = tuning.trailDistance(tower, towed);
        Vec3 anchor = tower.position();
        Vec3 held = new Vec3(towed.xo, towed.yo, towed.zo);
        line.remember(anchor, rope);

        double slowing = line.resting ? tuning.groundFriction() : tuning.drag();
        Vec3 wanted = new Vec3(
                line.velocity.x * slowing,
                (line.velocity.y - towed.gravityPull(tuning.gravity())) * tuning.drag(),
                line.velocity.z * slowing);
        AABB box = towed.getBoundingBox().move(held.subtract(towed.position()));
        List<VoxelShape> blocks = new ArrayList<>();
        towed.level().getBlockCollisions(towed, box.expandTowards(wanted)).forEach(blocks::add);
        Vec3 moved = collide(wanted, box, blocks);
        line.resting = wanted.y < 0 && moved.y > wanted.y + EPSILON;
        Vec3 next = restOnTower(tower, towed, held.add(moved));
        Vec3 out = next.subtract(anchor);
        if (out.lengthSqr() > rope * rope) {
            next = anchor.add(out.normalize().scale(rope));
        }
        Vec3 carried = null;
        if (!fits(towed, anchor, next)) {
            carried = line.pathPoints(anchor, rope).stream()
                    .filter(point -> openAt(towed.level(), towed, point))
                    .findFirst()
                    .or(() -> held.distanceToSqr(anchor) > rope * rope
                            ? nearestOpenOnLine(towed, anchor, held, rope)
                            : Optional.empty())
                    .orElse(held);
            next = carried;
            line.resting = false;
        }
        line.velocity = carried == null ? withoutPullAgainstLine(moved, next.subtract(anchor)) : Vec3.ZERO;
        towed.setPos(next);
        towed.setDeltaMovement(Vec3.ZERO);
        towed.resetFallDistance();
    }

    /**
     * The open place farthest from {@code anchor} that is at most {@code rope} from it, on the line toward {@code toward}: for a pod
     * that no point of the path suits, so that it is not left behind as its tower leaves.
     */
    private static Optional<Vec3> nearestOpenOnLine(PodEntity towed, Vec3 anchor, Vec3 toward, double rope) {
        Vec3 direction = toward.subtract(anchor);
        direction = direction.lengthSqr() < EPSILON ? new Vec3(0, -1, 0) : direction.normalize();
        for (double distance = rope; distance > OPEN_STEP; distance -= OPEN_STEP) {
            Vec3 spot = anchor.add(direction.scale(distance));
            if (openAt(towed.level(), towed, spot)) {
                return Optional.of(spot);
            }
        }
        return Optional.empty();
    }

    /** How far {@code box} moves along {@code move} before the {@code blocks}, one axis at a time as a pod does: up or down first, then the longer of the two horizontal ones. */
    private static Vec3 collide(Vec3 move, AABB box, List<VoxelShape> blocks) {
        double y = Shapes.collide(Direction.Axis.Y, box, blocks, move.y);
        box = box.move(0, y, 0);
        boolean zFirst = Math.abs(move.z) > Math.abs(move.x);
        double x = zFirst ? 0 : Shapes.collide(Direction.Axis.X, box, blocks, move.x);
        box = box.move(x, 0, 0);
        double z = Shapes.collide(Direction.Axis.Z, box, blocks, move.z);
        if (zFirst) {
            box = box.move(0, 0, z);
            x = Shapes.collide(Direction.Axis.X, box, blocks, move.x);
        }
        return new Vec3(x, y, z);
    }

    /** {@code speed} less the part of it that points away from the tower along {@code line}, which the line takes up. The pull of the line gives the pod no speed of its own. */
    private static Vec3 withoutPullAgainstLine(Vec3 speed, Vec3 line) {
        double length = line.length();
        if (length < EPSILON) {
            return speed;
        }
        Vec3 along = line.scale(1.0 / length);
        return speed.subtract(along.scale(Math.max(0.0, speed.dot(along))));
    }

    /** Lifts {@code at} onto the roof of the tower when the towed pod is falling on it, so a pod that drops down a shaft the tower is drilling rests on the tower and not in it. */
    private static Vec3 restOnTower(PodEntity tower, PodEntity towed, Vec3 at) {
        AABB roof = tower.getBoundingBox();
        AABB box = towed.getBoundingBox().move(at.subtract(towed.position()));
        boolean above = box.minY >= roof.getCenter().y && box.minY < roof.maxY;
        return above && box.intersects(roof) ? new Vec3(at.x, roof.maxY, at.z) : at;
    }

    /** True when no block meets {@code box}. Pods and other entities do not count: the towed pod and its tower overlap by design at times. */
    private static boolean inOpenSpace(Level level, PodEntity pod, AABB box) {
        return !level.getBlockCollisions(pod, box).iterator().hasNext();
    }

    /** {@link #inOpenSpace} for {@code pod}'s box moved to stand at {@code spot} in {@code level}. */
    private static boolean openAt(Level level, PodEntity pod, Vec3 spot) {
        return inOpenSpace(level, pod, pod.getBoundingBox().move(spot.subtract(pod.position())));
    }

    /**
     * True when the towed pod's box is in open space at {@code at} and all along the line from {@code anchor} to it, so the line does
     * not cut through rock. Sampled every {@link #FIT_STEP} blocks, closer than the narrowest wall.
     */
    private static boolean fits(PodEntity towed, Vec3 anchor, Vec3 at) {
        Vec3 line = at.subtract(anchor);
        int samples = (int) Math.ceil(line.length() / FIT_STEP);
        AABB box = towed.getBoundingBox().move(anchor.subtract(towed.position()));
        for (int i = samples; i >= 0; i--) {
            Vec3 sample = line.scale(samples == 0 ? 0 : (double) i / samples);
            if (!inOpenSpace(towed.level(), towed, box.move(sample))) {
                return false;
            }
        }
        return true;
    }

    /**
     * If the pod's box is inside blocks, moves it to the nearest open position along the line toward {@code toward}, or, with no
     * line or none open on it, the nearest along an axis. A pod passes through rock while towed and must not be left in it when
     * the cable comes off or the tower is lost. Logs once and leaves the pod if there is no open space within reach. Never throws.
     */
    private static void leaveRock(PodEntity pod, Optional<Vec3> toward) {
        Level level = pod.level();
        AABB box = pod.getBoundingBox();
        if (level.noCollision(pod, box)) {
            return;
        }
        Vec3 line = toward.map(target -> target.subtract(pod.position())).orElse(Vec3.ZERO);
        Optional<Vec3> open = line.lengthSqr() < 1e-6
                ? Optional.empty()
                : firstOpen(pod, box, List.of(line.normalize()), line.length());
        open = open.or(() -> firstOpen(pod, box, List.of(OPEN_DIRECTIONS), OPEN_REACH));
        if (open.isEmpty()) {
            if (STUCK_LOGGED.add(pod)) {
                DeepCharter.LOGGER.error(
                        "Pod {} is inside blocks with no open space within {} blocks: it is left where it is",
                        pod.getUUID(),
                        OPEN_REACH);
            }
            return;
        }
        pod.setPos(pod.position().add(open.get()));
        pod.setDeltaMovement(Vec3.ZERO);
        pod.resetFallDistance();
    }

    /** The smallest move, tried in the order of {@code directions} at each distance, that puts {@code box} in open space. */
    private static Optional<Vec3> firstOpen(PodEntity pod, AABB box, List<Vec3> directions, double reach) {
        for (double distance = OPEN_STEP; distance <= reach; distance += OPEN_STEP) {
            for (Vec3 direction : directions) {
                Vec3 move = direction.scale(distance);
                if (pod.level().noCollision(pod, box.move(move))) {
                    return Optional.of(move);
                }
            }
        }
        return Optional.empty();
    }

    /** A pod that crosses a breach takes the pods it tows across too, to the same spot. */
    private static void carryAcross(Entity entity, ServerLevel from, ServerLevel to, int fromLayer, int toLayer) {
        if (!(entity instanceof PodEntity tower)) {
            return;
        }
        // entity-query: the pods on this tower's cable, whose chunks keepTowedChunksTicking holds entity-ticking while
        // the tower flies
        List<? extends PodEntity> towed = from.getEntities(
                EntityTypeTest.forClass(PodEntity.class),
                pod -> towerId(pod).filter(tower.getUUID()::equals).isPresent());
        for (PodEntity pod : towed) {
            if (!pod.canTeleport(from, to)) {
                DeepCharter.LOGGER.error(
                        "Pod {} cannot cross to {} with its tower {}: it stays behind on its cable",
                        pod.getUUID(),
                        to.dimension(),
                        tower.getUUID());
                continue;
            }
            Entity arrived = pod.teleport(new TeleportTransition(
                    to,
                    trailSpot(to, tower, pod),
                    Vec3.ZERO,
                    pod.getYRot(),
                    pod.getXRot(),
                    TeleportTransition.DO_NOTHING));
            if (arrived == null) {
                DeepCharter.LOGGER.error(
                        "Vanilla refused to take pod {} to {} with its tower {}: it stays behind on its cable",
                        pod.getUUID(),
                        to.dimension(),
                        tower.getUUID());
                continue;
            }
            arrived.getPassengersAndSelf().forEach(crossed -> {
                crossed.resetFallDistance();
                BreachEvents.CROSSED.invoker().onCrossed(crossed, from, to, fromLayer, toLayer);
            });
        }
    }

    /**
     * Where the towed pod arrives: {@link TowTuning#trailDistance} from the tower, level with it on the side the pod was on (behind
     * the tower if it was straight above or below), or hanging straight below it. If the box does not fit there in the level it
     * arrives in, the distance shortens by {@link #ARRIVAL_STEP} until it does, down to the hulls just apart, and then the pod arrives
     * in the tower's place, which is open space.
     */
    private static Vec3 trailSpot(ServerLevel to, PodEntity tower, PodEntity towed) {
        Vec3 side = new Vec3(towed.getX() - tower.getX(), 0, towed.getZ() - tower.getZ());
        if (side.lengthSqr() < 1e-6) {
            side = Vec3.directionFromRotation(0, tower.getYRot()).scale(-1);
        }
        side = side.normalize();
        double full = TowTuning.DEFAULT.trailDistance(tower, towed);
        double least = (tower.chassis().width() + towed.chassis().width()) / 2.0 + ARRIVAL_STEP;
        Vec3[] directions = {side, new Vec3(0, -1, 0)};
        for (double distance = full; distance >= least; distance -= ARRIVAL_STEP) {
            for (Vec3 direction : directions) {
                Vec3 spot = tower.position().add(direction.scale(distance));
                if (openAt(to, towed, spot)) {
                    return spot;
                }
            }
        }
        return tower.position();
    }

    private static InteractionResult onUse(
            Player player, Level level, InteractionHand hand, Entity entity, EntityHitResult hit) {
        if (!(entity instanceof PodEntity target)
                || !player.getItemInHand(hand).is(PodRegistry.TOW_CABLE)
                || player.isSpectator()) {
            return InteractionResult.PASS;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            // The client guesses a hit and the server settles it.
            return InteractionResult.SUCCESS;
        }
        MinecraftServer server = serverPlayer.level().getServer();
        if (!Charters.isReadable(server)) {
            serverPlayer.sendOverlayMessage(Refusal.UNREADABLE.message());
            return InteractionResult.FAIL;
        }
        Optional<Charter> charter = Charters.readableCharterOf(server, serverPlayer.getUUID());
        if (isTowed(target)) {
            if (!mayFree(target, charter)) {
                serverPlayer.sendOverlayMessage(Refusal.NOT_ALLOWED_TO_FREE.message());
                return InteractionResult.FAIL;
            }
            detach(target);
            serverPlayer.sendOverlayMessage(Component.translatable("message.deepcharter.towing.detached"));
            return InteractionResult.SUCCESS;
        }
        if (!(player.getVehicle() instanceof PodEntity tower)) {
            serverPlayer.sendOverlayMessage(Refusal.NOT_RIDING.message());
            return InteractionResult.FAIL;
        }
        if (!PodComponents.mayAccess(target, charter)) {
            serverPlayer.sendOverlayMessage(Refusal.NOT_ALLOWED_TO_TOW.message());
            return InteractionResult.FAIL;
        }
        Optional<Refusal> refusal = refusal(tower, target);
        if (refusal.isPresent()) {
            serverPlayer.sendOverlayMessage(refusal.get().message());
            return InteractionResult.FAIL;
        }
        attach(tower, target);
        serverPlayer.sendOverlayMessage(Component.translatable("message.deepcharter.towing.attached"));
        for (Entity rider : target.getPassengers()) {
            if (rider instanceof ServerPlayer pilot) {
                pilot.sendOverlayMessage(Component.translatable("message.deepcharter.towing.being_towed"));
            }
        }
        return InteractionResult.SUCCESS;
    }

    /** The towed pod's owner can always take the cable off, and so can whoever may use the tower. */
    private static boolean mayFree(PodEntity towed, Optional<Charter> charter) {
        return PodComponents.mayAccess(towed, charter)
                || tower(towed)
                        .filter(tower -> PodComponents.mayAccess(tower, charter))
                        .isPresent();
    }

    private static void requireServer(PodEntity pod) {
        if (pod.level().isClientSide()) {
            throw new IllegalStateException("a tow cable is fitted on the server only");
        }
    }
}
