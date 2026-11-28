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
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
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
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The tow cable between two pods. A person on foot, holding a {@link PodRegistry#TOW_CABLE}, right-clicks one pod to
 * pick it and then another within {@link TowTuning#reachFor} of it: the first pod tows the second. Right-clicking a
 * towed pod with the cable takes it off. A person in a pod cannot work the cable (#1605): the crosshair of a seated
 * player meets their own hull, so the click only ever picked the pod they sat in. A pod moved where its owner did not
 * choose needs a rule for who may do it: a player may hook a pod only when {@link PodComponents#mayAccess} lets their
 * charter at it, and the tower's charter or the towed pod's owner charter may free it, so an owner can always recover a
 * pod someone else towed. A wreck is towed home the same way, but cannot be the tower.
 *
 * <p>The first click is a person's attachment, {@link #PICK}, synced to that person only (the client draws a line from
 * the pod to them) and never saved. It ends by the hook, a click on nothing with the cable ({@link TowCableItem}), the
 * cable leaving the hands, the person boarding a pod, the pod going, or {@link TowTuning#pickTicks}; a refused second
 * click keeps it.
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

    /** Ticks after a pick in which the item use of the same click is not a click on nothing; under a next click. */
    private static final int PICK_GRACE_TICKS = 5;

    private static final double EPSILON = 1e-7;
    /** Blocks a tick the line must drag a towed pod, across the ground, for it to turn toward the drag. */
    private static final double TRAIL_MIN_DRAG = 0.02;
    /** Degrees a tick a towed pod turns toward the way it is dragged. */
    private static final float TRAIL_TURN_DEGREES = 10f;
    /**
     * Blocks a pod hangs below its tower at least for the line to drape over a lip: beside it a line that is blocked is not a line
     * over an edge. 0.25, not more: a hull's bottom corners meet a lip at about 0.46 of drop.
     */
    private static final double DRAPE_MIN_DROP = 0.25;
    /** Blocks a pod may be past the length of its line and still count as on it: the clamp onto the line's sphere leaves rounding. */
    private static final double LINE_SLACK = 1e-3;
    /** Blocks of open space around a pod for the line to drape over a lip for it: in a shaft or a tunnel the pod follows the path instead. */
    private static final double DRAPE_ROOM = 0.5;
    /** Share of a block's half diagonal that a lip the line drapes over stands off the block, so that the line clears it. */
    private static final double DRAPE_CLEARANCE = 0.04;
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
        /** The lip the line is draped over while the tower does not see the pod, in the middle-of-the-hull space the line is measured in. */
        Optional<Vec3> bend = Optional.empty();
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

    /**
     * Server only, not saved: the game tick each person picked their pod at. Weak, and holds no entity, so a person who
     * leaves keeps nothing.
     */
    private static final Map<ServerPlayer, Long> PICKED_AT = Collections.synchronizedMap(new WeakHashMap<>());

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

    /** The pod a person has picked with the cable, the first click of a hook. */
    public record Pick(Optional<UUID> pod) {
        public static final Pick EMPTY = new Pick(Optional.empty());
        public static final StreamCodec<ByteBuf, Pick> STREAM =
                ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC).map(Pick::new, Pick::pod);
    }

    public static final int PICK_VERSION = 1;

    /**
     * Not persistent: a pick is a session's, and a new session starts with none. {@link Versioned} is for the sync, so
     * that a client on another build reads it as a marker. Synced to the person only: nobody else draws the line.
     */
    public static final AttachmentType<Versioned<Pick>> PICK = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "tow_pick"),
            builder -> builder.initializer(() -> Versioned.of(Pick.EMPTY))
                    .syncWith(Versioned.streamCodec(PICK_VERSION, Pick.STREAM), AttachmentSyncPredicate.targetOnly()));

    /** Why a cable cannot be fitted from one pod to another. */
    public enum Refusal {
        NOT_ON_FOOT,
        TOWER_IS_WRECK,
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
            String key = "message.deepcharter.towing.refused." + name().toLowerCase(Locale.ROOT);
            return this == TOO_FAR ? Component.translatable(key, reachBlocks()) : Component.translatable(key);
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
        ServerTickEvents.END_SERVER_TICK.register(PodTowing::lapsePicks);
    }

    /**
     * The UUID of the pod the person has picked with the cable, whether or not it is in the world. Safe on either side.
     */
    public static Optional<UUID> pickedPodId(Player person) {
        return Versioned.readable(person, PICK).flatMap(Pick::pod);
    }

    /** Server only: the person picks {@code pod} as the first of the two to hook. */
    private static void pick(ServerPlayer person, PodEntity pod) {
        person.setAttached(PICK, Versioned.of(new Pick(Optional.of(pod.getUUID()))));
        PICKED_AT.put(person, person.level().getGameTime());
    }

    /**
     * True when the person picked their pod within {@link #PICK_GRACE_TICKS}. One right-click on a pod sends the use of
     * the pod and then, when the client's own result passes, the use of the item, so {@link TowCableItem} must not read
     * that second use as a click on nothing.
     */
    static boolean pickedJustNow(ServerPlayer person) {
        Long at = PICKED_AT.get(person);
        return at != null && person.level().getGameTime() - at <= PICK_GRACE_TICKS;
    }

    /** Server only: forgets the person's pick. Returns false, and changes nothing, when they had none. */
    static boolean clearPick(ServerPlayer person) {
        if (pickedPodId(person).isEmpty()) {
            return false;
        }
        person.removeAttached(PICK);
        PICKED_AT.remove(person);
        return true;
    }

    /**
     * Server only: a click on nothing with the cable. Returns false, and says nothing, when there was no pick to put
     * away.
     */
    static boolean putAway(ServerPlayer person) {
        if (!clearPick(person)) {
            return false;
        }
        person.sendOverlayMessage(Component.translatable("message.deepcharter.towing.put_away"));
        return true;
    }

    /**
     * The pod the person has picked, when it is in their level and still there. A pick of a pod that is gone is
     * dropped.
     */
    private static Optional<PodEntity> picked(ServerPlayer person) {
        Optional<UUID> id = pickedPodId(person);
        if (id.isEmpty()) {
            return Optional.empty();
        }
        // entity-query: a picked pod not loaded in the person's level reads as gone, and dropping the pick is the
        // intended outcome, since the person was told to hook a pod they can see
        if (person.level().getEntity(id.get()) instanceof PodEntity pod && !pod.isRemoved()) {
            return Optional.of(pod);
        }
        clearPick(person);
        person.sendOverlayMessage(Component.translatable("message.deepcharter.towing.lapsed"));
        return Optional.empty();
    }

    /**
     * Ends a pick that has outlived {@link TowTuning#pickTicks}, whose cable left the hands, whose person boarded a
     * pod, or whose pod is gone.
     */
    private static void lapsePicks(MinecraftServer server) {
        for (ServerPlayer person : server.getPlayerList().getPlayers()) {
            if (pickedPodId(person).isEmpty()) {
                continue;
            }
            Long pickedAt = PICKED_AT.get(person);
            boolean holding = person.getMainHandItem().is(PodRegistry.TOW_CABLE)
                    || person.getOffhandItem().is(PodRegistry.TOW_CABLE);
            if (person.isPassenger() || !holding) {
                clearPick(person);
            } else if (pickedAt == null || person.level().getGameTime() - pickedAt > TowTuning.DEFAULT.pickTicks()) {
                clearPick(person);
                person.sendOverlayMessage(Component.translatable("message.deepcharter.towing.lapsed"));
            } else {
                picked(person);
            }
        }
    }

    /** Lets a test in another package age a pick, which {@link #lapsePicks} then ends. */
    // test-seam: ages a pick so a test need not wait out TowTuning.pickTicks (30 s) in the shared server's clock
    static void agePick(ServerPlayer person, long ticks) {
        PICKED_AT.computeIfPresent(person, (key, at) -> at - ticks);
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

    /** Whole blocks within which two pods can be hooked, as the refusal says it and the handbook teaches it. */
    public static int reachBlocks() {
        return (int) Math.floor(reach(TowTuning.DEFAULT));
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

    /**
     * The cable's base mass and the towed pod's whole weight: its bay and its own extra mass (a rider's pack, its lining). A towed pod
     * tows nothing (the guard below, and {@link #refusal}), so its own extra mass has no tow in it and this never recurses further.
     */
    private static float towedMass(PodEntity tower) {
        if (isTowed(tower)) {
            return 0f;
        }
        float mass = 0f;
        for (PodEntity towed : towedBy(tower, TowTuning.DEFAULT)) {
            mass += TowTuning.DEFAULT.baseMass() + towed.cargoMass() + PodEvents.extraMass(towed);
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
     * Moves the towed pod as a weight on the line. Where the pod or the line to the tower is not in open space it goes along
     * the tower's path, or failing that to the nearest open place on the line, so that the line never drags it into rock. A
     * pod below its tower in open space is the exception: the line drapes over the lip that blocks it ({@link #drape}) and
     * the pod hangs below the lip.
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
        Vec3 eye = tower.getBoundingBox().getCenter();
        Vec3 middle = towed.getBoundingBox().getCenter().subtract(towed.position());
        // The line stays draped while the tower still does not see the pod where it was and the line reaches the lip.
        Optional<Vec3> bend = line.bend.filter(lip -> eye.distanceTo(lip) < rope
                && clear(towed, eye, lip)
                && !sees(towed, eye, towed.getBoundingBox().move(held.subtract(towed.position()))));

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
        Vec3 pivot = anchor;
        double left = rope;
        if (bend.isPresent()) {
            pivot = bend.get().subtract(middle);
            left = rope - eye.distanceTo(bend.get());
        }
        next = clamp(next, pivot, left);
        Vec3 from = bend.orElse(eye);
        Vec3 carried = null;
        if (!fits(towed, from, next, bend.isEmpty())) {
            Optional<Drape> draped = bend.isPresent() ? Optional.empty() : drape(towed, eye, middle, next, rope);
            Optional<Vec3> caught =
                    bend.isPresent() ? caughtOnLip(towed, bend.get(), pivot, left, held, next) : Optional.empty();
            if (draped.isPresent()) {
                bend = Optional.of(draped.get().lip());
                pivot = bend.get().subtract(middle);
                next = draped.get().next();
            } else if (caught.isPresent()) {
                next = caught.get();
                carried = Vec3.ZERO;
            } else {
                bend = Optional.empty();
                pivot = anchor;
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
        }
        line.bend = bend;
        line.velocity = carried == null ? withoutPullAgainstLine(moved, next.subtract(pivot)) : Vec3.ZERO;
        Vec3 dragged = next.subtract(towed.position());
        towed.setPos(next);
        trail(towed, dragged);
        towed.setDeltaMovement(Vec3.ZERO);
        towed.resetFallDistance();
    }

    /**
     * Turns a towed pod toward the way the line drags it, as a trailer follows its tower. The server steers the pod's
     * yaw now (ADR 0095) and nothing else turns a pod no one pilots, so without this it would be dragged at the yaw it
     * was left with.
     */
    private static void trail(PodEntity towed, Vec3 dragged) {
        if (dragged.x * dragged.x + dragged.z * dragged.z < TRAIL_MIN_DRAG * TRAIL_MIN_DRAG) {
            return;
        }
        float heading = (float) (Mth.atan2(-dragged.x, dragged.z) * Mth.RAD_TO_DEG);
        towed.setYRot(Mth.wrapDegrees(Mth.approachDegrees(towed.getYRot(), heading, TRAIL_TURN_DEGREES)));
    }

    /**
     * Where a pod hanging from a lip goes when its swing would carry it into rock, as in under the ledge it hangs from: down the
     * line it hangs on if the way is open, else nowhere. The line is caught on the lip and the pod keeps to the side it is on.
     */
    private static Optional<Vec3> caughtOnLip(
            PodEntity towed, Vec3 lip, Vec3 pivot, double left, Vec3 held, Vec3 next) {
        Vec3 down = new Vec3(held.x, next.y, held.z);
        if (pivot.distanceTo(down) <= left + LINE_SLACK && fits(towed, lip, down, false)) {
            return Optional.of(down);
        }
        return pivot.distanceTo(held) <= left + LINE_SLACK && fits(towed, lip, held, false)
                ? Optional.of(held)
                : Optional.empty();
    }

    /** {@code at} brought back onto the sphere of radius {@code rope} about {@code centre} if it lies outside it. */
    private static Vec3 clamp(Vec3 at, Vec3 centre, double rope) {
        Vec3 out = at.subtract(centre);
        return out.lengthSqr() > rope * rope ? centre.add(out.normalize().scale(rope)) : at;
    }

    /** A line bent over a lip: where it bends, in the same space as the pod's middle, and where the pod then is. */
    private record Drape(Vec3 lip, Vec3 next) {}

    /**
     * Drapes the line over the edge that blocks it, for a pod below its tower and in open space: of the corners of the block the
     * line from {@code eye} (the tower's centre) to the pod at {@code next} runs into, the nearest one both see. The pod then hangs
     * on what is left of the line. Empty where no corner serves, as in a bend of rock the pod must follow the path round.
     */
    private static Optional<Drape> drape(PodEntity towed, Vec3 eye, Vec3 middle, Vec3 next, double rope) {
        Vec3 podMiddle = next.add(middle);
        AABB hull = towed.getBoundingBox().move(next.subtract(towed.position()));
        if (podMiddle.y > eye.y - DRAPE_MIN_DROP || !inOpenSpace(towed.level(), towed, hull.inflate(DRAPE_ROOM))) {
            return Optional.empty();
        }
        Optional<BlockHitResult> hit = blocker(towed, eye, hull);
        if (hit.isEmpty()) {
            return Optional.empty();
        }
        Vec3 centre = Vec3.atCenterOf(hit.get().getBlockPos());
        List<Vec3> lips = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Vec3 corner = centre.add((i & 1) - 0.5, ((i >> 1) & 1) - 0.5, ((i >> 2) & 1) - 0.5);
            lips.add(corner.add(corner.subtract(centre).scale(DRAPE_CLEARANCE)));
        }
        lips.sort(Comparator.comparingDouble(lip -> eye.distanceTo(lip) + lip.distanceTo(podMiddle)));
        for (Vec3 lip : lips) {
            double left = rope - eye.distanceTo(lip);
            if (left <= 0 || !clear(towed, eye, lip)) {
                continue;
            }
            Vec3 hung = clamp(podMiddle, lip, left).subtract(middle);
            if (fits(towed, lip, hung, false)) {
                return Optional.of(new Drape(lip, hung));
            }
        }
        return Optional.empty();
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
     * True when the towed pod's box is in open space at {@code at} and no block lies between {@code from} and the box's middle, or with
     * {@code hull} its corners too: a line from the tower is not left cutting rock by the edge of the pod's hull. A line from a lip
     * it is draped over runs to the middle only, for the hull swings in under the lip.
     */
    private static boolean fits(PodEntity towed, Vec3 from, Vec3 at, boolean hull) {
        AABB box = towed.getBoundingBox().move(at.subtract(towed.position()));
        return inOpenSpace(towed.level(), towed, box)
                && (hull ? sees(towed, from, box) : clear(towed, from, box.getCenter()));
    }

    /**
     * True when no block lies between {@code from} and the middle of {@code box} or any of its corners. The box itself is not swept
     * along the line (#1122): a pod below its tower's floor would then be tested against the floor under the tower.
     */
    private static boolean sees(PodEntity towed, Vec3 from, AABB box) {
        return blocker(towed, from, box).isEmpty();
    }

    /** The first block on a line from {@code from} to the middle or a corner of {@code box}, if there is one. */
    private static Optional<BlockHitResult> blocker(PodEntity towed, Vec3 from, AABB box) {
        List<Vec3> ends = new ArrayList<>();
        ends.add(box.getCenter());
        for (int i = 0; i < 8; i++) {
            ends.add(new Vec3(
                    (i & 1) == 0 ? box.minX : box.maxX,
                    (i & 2) == 0 ? box.minY : box.maxY,
                    (i & 4) == 0 ? box.minZ : box.maxZ));
        }
        for (Vec3 end : ends) {
            BlockHitResult hit = clip(towed, from, end);
            if (hit.getType() == HitResult.Type.BLOCK) {
                return Optional.of(hit);
            }
        }
        return Optional.empty();
    }

    private static BlockHitResult clip(PodEntity towed, Vec3 from, Vec3 to) {
        return towed.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, towed));
    }

    /** True when no block lies on the straight line from {@code from} to {@code to}. */
    private static boolean clear(PodEntity towed, Vec3 from, Vec3 to) {
        return clip(towed, from, to).getType() == HitResult.Type.MISS;
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

    /**
     * A pod that crosses a breach takes the pods it tows across too, to the same spot. A crossing inside one level (the campaign
     * world) moves nothing, and the cable alone brings the towed pod along.
     */
    private static void carryAcross(Entity entity, ServerLevel from, ServerLevel to, int fromLayer, int toLayer) {
        if (from == to || !(entity instanceof PodEntity tower)) {
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
        if (!(player instanceof ServerPlayer person)) {
            // The client guesses a hit and the server settles it.
            return InteractionResult.SUCCESS;
        }
        if (person.isPassenger()) {
            return refuse(person, Refusal.NOT_ON_FOOT);
        }
        MinecraftServer server = person.level().getServer();
        if (!Charters.isReadable(server)) {
            return refuse(person, Refusal.UNREADABLE);
        }
        Optional<Charter> charter = Charters.readableCharterOf(server, person.getUUID());
        if (isTowed(target)) {
            if (!mayFree(target, charter)) {
                return refuse(person, Refusal.NOT_ALLOWED_TO_FREE);
            }
            clearPick(person);
            detach(target);
            person.sendOverlayMessage(Component.translatable("message.deepcharter.towing.detached"));
            return InteractionResult.SUCCESS;
        }
        Optional<PodEntity> first = picked(person);
        return first.isEmpty() ? pickFirst(person, target, charter) : hookSecond(person, first.get(), target, charter);
    }

    /** The first click: picks {@code target} as the tower, if it may tow. */
    private static InteractionResult pickFirst(ServerPlayer person, PodEntity target, Optional<Charter> charter) {
        Optional<Refusal> refusal = Optional.empty();
        if (Versioned.readable(target, STATE).isEmpty()) {
            refusal = Optional.of(Refusal.UNREADABLE);
        } else if (!PodComponents.mayAccess(target, charter)) {
            refusal = Optional.of(Refusal.NOT_ALLOWED_TO_TOW);
        } else if (PodTether.isWrecked(target)) {
            refusal = Optional.of(Refusal.TOWER_IS_WRECK);
        } else if (!towedBy(target, TowTuning.DEFAULT).isEmpty()) {
            refusal = Optional.of(Refusal.ALREADY_TOWING);
        }
        if (refusal.isPresent()) {
            return refuse(person, refusal.get());
        }
        pick(person, target);
        person.sendOverlayMessage(Component.translatable("message.deepcharter.towing.picked"));
        return InteractionResult.SUCCESS;
    }

    /** The second click: hooks {@code target} to the pod picked first. A refusal keeps the pick. */
    private static InteractionResult hookSecond(
            ServerPlayer person, PodEntity tower, PodEntity target, Optional<Charter> charter) {
        if (tower == target) {
            return refuse(person, Refusal.SAME_POD);
        }
        if (!PodComponents.mayAccess(target, charter)) {
            return refuse(person, Refusal.NOT_ALLOWED_TO_TOW);
        }
        if (PodTether.isWrecked(tower)) {
            return refuse(person, Refusal.TOWER_IS_WRECK);
        }
        Optional<Refusal> refusal = refusal(tower, target);
        if (refusal.isPresent()) {
            return refuse(person, refusal.get());
        }
        attach(tower, target);
        clearPick(person);
        person.sendOverlayMessage(Component.translatable("message.deepcharter.towing.attached"));
        tell(tower, "towing_now");
        tell(target, "being_towed");
        return InteractionResult.SUCCESS;
    }

    private static InteractionResult refuse(ServerPlayer person, Refusal refusal) {
        person.sendOverlayMessage(refusal.message());
        return InteractionResult.FAIL;
    }

    /** Tells the players seated in {@code pod}. */
    private static void tell(PodEntity pod, String message) {
        for (Entity rider : pod.getPassengers()) {
            if (rider instanceof ServerPlayer crew) {
                crew.sendOverlayMessage(Component.translatable("message.deepcharter.towing." + message));
            }
        }
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
