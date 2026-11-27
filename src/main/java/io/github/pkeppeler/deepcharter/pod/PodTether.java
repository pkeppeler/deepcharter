package io.github.pkeppeler.deepcharter.pod;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.creature.DrawSources;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.repair.RepairRegistry;
import io.github.pkeppeler.deepcharter.wreck.Wrecks;
import io.netty.buffer.ByteBuf;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The tether (#986): a line from a person on foot to the winch of their pod. A person uses a {@link TetherItem} on a pod their charter
 * may access ({@link PodComponents#mayAccess}) to clip on, and the line pays out {@link TetherTuning#length} blocks. Using the tether
 * in the air starts the winch, which hauls the person home through open air at {@link TetherTuning#winchSpeed}; the pilot of the pod
 * can start it for everyone clipped to it too. A taut line holds a person at its length, which catches a fall.
 *
 * <p>The winch never pulls through rock. A line that can run straight to the person, or over one corner (up from the person to the
 * winch's height, then across: the lip of a drop), is clear, and a line's length counts the corner. A line with rock across both is
 * snagged: the winch does not haul, and a line that stays snagged for {@link TetherTuning#snagTicks} snaps. Lava across a route snags it
 * as rock does, and a haul that closes less than {@link TetherTuning#stallProgress} blocks on the pod in {@link TetherTuning#stallTicks}
 * ticks (a person stuck on something the line's ray missed) counts as snagged. A line snaps too when it is stretched past
 * {@link TetherTuning#snapMargin}, and a snapped tether cannot be clipped on again for {@link TetherTuning#snapCooldownTicks}. Unhooking
 * a line that is snagged or taut, or stopping a snagged winch, costs the same cooldown, so the price of jamming a winch
 * cannot be dodged. The pull is a velocity the client moves itself by, with its own collision, so it is a push along
 * clear air and never a teleport. A winch is loud while it hauls: it sounds a decoy at the pod in {@link DrawSources},
 * renewed every {@link TetherTuning#noiseInterval} ticks and silenced the moment the winch stops, so a creature drawn
 * to the drill hears it.
 *
 * <p>Using the tether on a second pod while clipped hooks that pod to the first one with the tow cable's own rules ({@link PodTowing}),
 * so a person on foot can tow a wreck.
 *
 * <p>The clip is a person's attachment, {@link #STATE}, synced so that everyone sees the line, and never saved: it holds the pod's UUID,
 * and every way the link can go stale ends it ({@link Unclipped}) on the next server tick, so a person never keeps a reference to a pod
 * that is gone, and a new session starts free. Listeners never throw on an unreadable state: it reads as no clip.
 */
public final class PodTether {
    public static final int VERSION = 1;

    /** The pod a person on foot is clipped to, and whether its winch is hauling them. */
    public record State(Optional<UUID> pod, boolean winching) {
        public static final State EMPTY = new State(Optional.empty(), false);
        public static final MapCodec<State> BODY = RecordCodecBuilder.mapCodec(instance -> instance.group(
                        UUIDUtil.CODEC.optionalFieldOf("pod").forGetter(State::pod),
                        Codec.BOOL.optionalFieldOf("winching", false).forGetter(State::winching))
                .apply(instance, State::new));
        public static final StreamCodec<ByteBuf, State> STREAM = StreamCodec.composite(
                ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC),
                State::pod,
                ByteBufCodecs.BOOL,
                State::winching,
                State::new);
    }

    /**
     * Not persistent: a clip is a session's. {@link Versioned} is for the sync, so that a client on another build reads it as a marker.
     * Nothing copies it to a respawned player.
     */
    public static final AttachmentType<Versioned<State>> STATE = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "pod_tether"),
            builder -> builder.initializer(() -> Versioned.of(State.EMPTY))
                    .syncWith(Versioned.streamCodec(VERSION, State.STREAM), AttachmentSyncPredicate.all()));

    /** Why a person cannot clip to a pod. */
    public enum Refusal {
        NOT_ON_FOOT,
        WRECK,
        NOT_ALLOWED,
        TOO_FAR,
        ALREADY_CLIPPED,
        COOLING_DOWN,
        UNREADABLE;

        public Component message() {
            return Component.translatable("message.deepcharter.tether.refused." + name().toLowerCase(Locale.ROOT));
        }
    }

    /** How a clip ended. The ones that tell the person say why. */
    public enum Unclipped {
        /** By hand. */
        UNHOOKED(true, false),
        DIED(false, false),
        /** The person got into a pod. */
        BOARDED(false, false),
        /** The pod was removed, unloaded or is in another level than the person. */
        POD_GONE(true, false),
        /** The pod is a wreck. */
        POD_LOST(true, false),
        /** The line was stretched past its margin. */
        SNAPPED(true, true),
        /** The line stayed snagged on rock. */
        SNAGGED(true, true),
        /** The person is no longer of the charter that owns the pod. */
        NOT_ALLOWED(true, false);

        private final boolean tells;
        private final boolean snaps;

        Unclipped(boolean tells, boolean snaps) {
            this.tells = tells;
            this.snaps = snaps;
        }

        public Component message() {
            return Component.translatable("message.deepcharter.tether.unclipped." + name().toLowerCase(Locale.ROOT));
        }
    }

    /** Server only, not saved: how long each person's line has been snagged and how long its winch has hauled. Weak, so a person who leaves keeps nothing. */
    private static final Map<ServerPlayer, Strain> STRAINS = Collections.synchronizedMap(new WeakHashMap<>());

    /** Ticks a winch's decoy outlasts its renewal interval by, so the noise has no gap between clanks. */
    private static final int DECOY_MARGIN = 2;

    private static final double EPSILON = 1e-7;
    /** Blocks past the length that a taut line is pulled back by on top of the overshoot, so a person at the end of the line is not left at rest on the edge of it. */
    private static final double HOLD_SLACK = 0.05;

    /**
     * Blocks past the arrive distance that still count as home. A person at the hull sags a tick's fall below the winch's pull each tick, so
     * they would hover just short of the arrive distance with the winch still running.
     */
    private static final double ARRIVE_SLACK = 0.3;

    private static final class Strain {
        int snagged;
        int hauled;
        /** The line is longer than its length: held at the end of it. */
        boolean taut;
        /** Ticks into the stall window, and the line the person had at its start. */
        int window;

        double mark;
        /** The last window closed on the pod by less than the tuned progress. */
        boolean stalled;
        /** This winch's decoy in {@link DrawSources}, until it is renewed or silenced. */
        @Nullable
        Decoy decoy;
    }

    /** A decoy's place and the level it sounds in: the person may have changed level since. */
    private record Decoy(ServerLevel level, Vec3 at) {}

    private PodTether() {}

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(PodTether::tick);
        UseEntityCallback.EVENT.register(PodTether::onUse);
    }

    private static State read(Player person) {
        return Versioned.readable(person, STATE).orElse(State.EMPTY);
    }

    /** The UUID of the pod the person is clipped to, whether or not it is in the world. Safe on either side. */
    public static Optional<UUID> podId(Player person) {
        return read(person).pod();
    }

    public static boolean isClipped(Player person) {
        return podId(person).isPresent();
    }

    /** True while the person's winch is hauling them. Safe on either side. */
    public static boolean isWinching(Player person) {
        return read(person).winching();
    }

    /** The pod the person is clipped to, when it is in their level and still there. */
    public static Optional<PodEntity> pod(ServerPlayer person) {
        Optional<UUID> id = podId(person);
        // entity-query: a pod that is not loaded in the person's level reads as gone, and the line ending is the
        // intended outcome (POD_GONE), so a miss costs nothing the person did not already lose
        if (id.isEmpty() || !(person.level().getEntity(id.get()) instanceof PodEntity pod) || pod.isRemoved()) {
            return Optional.empty();
        }
        return Optional.of(pod);
    }

    /** Where the line is fixed on the pod: the middle of its hull. */
    public static Vec3 anchor(PodEntity pod) {
        return pod.getBoundingBox().getCenter();
    }

    /** Why {@code person} cannot clip to {@code pod} now, or empty. Never throws. */
    public static Optional<Refusal> clipRefusal(ServerPlayer person, PodEntity pod) {
        if (person.isPassenger()) {
            return Optional.of(Refusal.NOT_ON_FOOT);
        }
        MinecraftServer server = person.level().getServer();
        if (!Charters.isReadable(server)) {
            return Optional.of(Refusal.UNREADABLE);
        }
        if (isWrecked(pod)) {
            return Optional.of(Refusal.WRECK);
        }
        if (person.getCooldowns().isOnCooldown(new ItemStack(RepairRegistry.item(Consumable.TETHER)))) {
            return Optional.of(Refusal.COOLING_DOWN);
        }
        Optional<Charter> charter = Charters.readableCharterOf(server, person.getUUID());
        if (!PodComponents.mayAccess(pod, charter)) {
            return Optional.of(Refusal.NOT_ALLOWED);
        }
        if (isClipped(person)) {
            return Optional.of(Refusal.ALREADY_CLIPPED);
        }
        double reach = TetherTuning.DEFAULT.clipReach();
        if (pod.level() != person.level() || pod.getBoundingBox().distanceToSqr(person.position()) > reach * reach) {
            return Optional.of(Refusal.TOO_FAR);
        }
        return Optional.empty();
    }

    /**
     * Server only: clips {@code person} to {@code pod}.
     *
     * @throws IllegalStateException if the clip is refused (see {@link #clipRefusal})
     */
    public static void clip(ServerPlayer person, PodEntity pod) {
        Optional<Refusal> refusal = clipRefusal(person, pod);
        if (refusal.isPresent()) {
            throw new IllegalStateException(
                    "person " + person.getUUID() + " cannot clip to pod " + pod.getUUID() + ": " + refusal.get());
        }
        silence(STRAINS.remove(person));
        person.setAttached(STATE, Versioned.of(new State(Optional.of(pod.getUUID()), false)));
    }

    /**
     * Server only: ends the person's clip and its winch. A snap also leaves the tether on a cooldown, and so does unhooking a line that
     * is snagged or taut. Returns false, and changes nothing, when they were not clipped.
     */
    public static boolean unclip(ServerPlayer person, Unclipped cause) {
        if (!isClipped(person)) {
            return false;
        }
        boolean jammed = cause == Unclipped.UNHOOKED && isJammed(person);
        person.removeAttached(STATE);
        silence(STRAINS.remove(person));
        DeepCharter.LOGGER.debug("[tether] {} unclipped: {}", person.getName().getString(), cause);
        if (cause.snaps || jammed) {
            person.getCooldowns()
                    .addCooldown(
                            new ItemStack(RepairRegistry.item(Consumable.TETHER)),
                            TetherTuning.DEFAULT.snapCooldownTicks());
        }
        if (cause.tells && person.isAlive()) {
            person.sendOverlayMessage(cause.message());
        }
        return true;
    }

    /**
     * Server only: starts or stops the person's winch. Returns whether it is hauling now: false for a person who is not clipped, and for
     * a pod with no power, which the person is told.
     */
    public static boolean setWinching(ServerPlayer person, boolean winching) {
        State state = read(person);
        if (state.pod().isEmpty()) {
            return false;
        }
        if (winching) {
            Optional<PodEntity> pod = pod(person);
            if (pod.isEmpty() || !PodEvents.isPowered(pod.get())) {
                person.sendOverlayMessage(Component.translatable("message.deepcharter.tether.no_power"));
                return false;
            }
        }
        if (state.winching() != winching) {
            silence(STRAINS.remove(person));
            person.setAttached(STATE, Versioned.of(new State(state.pod(), winching)));
        }
        return winching;
    }

    /** True while the person's line is snagged or held taut: a haul that cannot make way, or a line at its end. */
    private static boolean isJammed(ServerPlayer person) {
        Strain strain = STRAINS.get(person);
        return strain != null && (strain.snagged > 0 || strain.taut);
    }

    private static boolean isWrecked(PodEntity pod) {
        return Wrecks.isWreck(pod) || pod.hull() <= 0f;
    }

    /** Blocks between the middles of the pod and the person at which the winch counts them home. */
    private static double arriveDistance(PodEntity pod, ServerPlayer person) {
        return pod.chassis().width() / 2.0 + TetherTuning.DEFAULT.arriveGap() + person.getBbWidth() / 2.0;
    }

    /** Clear of rock and of lava. The person's own cell does not count, so a person in lava can be hauled out of it. */
    private static boolean clearLine(ServerLevel level, Vec3 from, Vec3 to, ServerPlayer person) {
        if (level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, person))
                        .getType()
                != HitResult.Type.MISS) {
            return false;
        }
        BlockPos own = person.blockPosition();
        return BlockGetter.traverseBlocks(
                from,
                to,
                level,
                (getter, pos) ->
                        (!pos.equals(own) && getter.getFluidState(pos).is(FluidTags.LAVA)) ? Boolean.FALSE : null,
                getter -> Boolean.TRUE);
    }

    /**
     * How the line runs from the winch to the person: straight, over one corner, or not at all.
     *
     * @param aim    where a haul pushes toward: the winch for a straight line, the corner for a bent one, empty when blocked
     * @param length blocks of line it takes; the straight distance when blocked
     */
    private record Route(Optional<Vec3> aim, double length) {
        boolean clear() {
            return aim.isPresent();
        }
    }

    private static Route route(ServerLevel level, Vec3 from, Vec3 at, ServerPlayer person) {
        double straight = from.distanceTo(at);
        if (clearLine(level, from, at, person)) {
            return new Route(Optional.of(from), straight);
        }
        // The lip of a drop: up the face from the person to the winch's own height, then across at that height.
        Vec3 corner = new Vec3(at.x, from.y, at.z);
        if (corner.distanceToSqr(at) > EPSILON
                && clearLine(level, at, corner, person)
                && clearLine(level, corner, from, person)) {
            return new Route(Optional.of(corner), at.distanceTo(corner) + corner.distanceTo(from));
        }
        return new Route(Optional.empty(), straight);
    }

    private static void tick(MinecraftServer server) {
        for (ServerPlayer person : server.getPlayerList().getPlayers()) {
            if (isClipped(person)) {
                tickLine(person);
            }
        }
    }

    private static void tickLine(ServerPlayer person) {
        if (!person.isAlive() || person.isSpectator()) {
            unclip(person, Unclipped.DIED);
            return;
        }
        if (person.isPassenger()) {
            unclip(person, Unclipped.BOARDED);
            return;
        }
        Optional<PodEntity> found = pod(person);
        if (found.isEmpty()) {
            unclip(person, Unclipped.POD_GONE);
            return;
        }
        PodEntity pod = found.get();
        if (isWrecked(pod)) {
            unclip(person, Unclipped.POD_LOST);
            return;
        }
        MinecraftServer server = person.level().getServer();
        // A person the charter no longer holds is off its winch. An unreadable charter registry holds nobody off it:
        // clipping
        // is refused then, and a line already on stays.
        if (Charters.isReadable(server)
                && !PodComponents.mayAccess(pod, Charters.readableCharterOf(server, person.getUUID()))) {
            unclip(person, Unclipped.NOT_ALLOWED);
            return;
        }
        TetherTuning tuning = TetherTuning.DEFAULT;
        ServerLevel level = person.level();
        Vec3 from = anchor(pod);
        Vec3 at = person.getBoundingBox().getCenter();
        double distance = from.distanceTo(at);
        Route route = route(level, from, at, person);
        double line = route.length();
        if (line > tuning.length() + tuning.snapMargin()) {
            unclip(person, Unclipped.SNAPPED);
            return;
        }
        boolean clear = route.clear();
        Strain strain = STRAINS.computeIfAbsent(person, key -> new Strain());
        strain.taut = line > tuning.length();
        boolean hauling = false;
        if (isWinching(person)) {
            if (!PodEvents.isPowered(pod)) {
                setWinching(person, false);
                person.sendOverlayMessage(Component.translatable("message.deepcharter.tether.no_power"));
            } else if (distance <= arriveDistance(pod, person) + ARRIVE_SLACK) {
                setWinching(person, false);
                person.sendOverlayMessage(Component.translatable("message.deepcharter.tether.arrived"));
            } else if (!clear) {
                strain.window = 0;
                strain.stalled = false;
                if (snag(person, strain, tuning)) {
                    return;
                }
            } else {
                boolean stalled = stalled(strain, line, tuning);
                if (stalled) {
                    if (snag(person, strain, tuning)) {
                        return;
                    }
                } else {
                    strain.snagged = 0;
                }
                Vec3 aim = route.aim().orElseThrow();
                boolean straight = aim.equals(from);
                double step = straight
                        ? Math.min(tuning.winchSpeed(), distance - arriveDistance(pod, person))
                        : tuning.winchSpeed();
                push(person, aim.subtract(at), step);
                if (!stalled) {
                    loud(level, pod, strain, tuning);
                }
                hauling = true;
            }
        } else {
            strain.snagged = 0;
        }
        if (!hauling && line > tuning.length() && clear) {
            // Taut: the line holds the person at its length, which catches a fall too.
            push(
                    person,
                    route.aim().orElseThrow().subtract(at),
                    Math.min(tuning.winchSpeed(), line - tuning.length() + HOLD_SLACK));
        }
    }

    /** Counts a tick of snag, and snaps the line when it has lasted. Returns whether it snapped. */
    private static boolean snag(ServerPlayer person, Strain strain, TetherTuning tuning) {
        strain.snagged++;
        if (strain.snagged == 1) {
            person.sendOverlayMessage(Component.translatable("message.deepcharter.tether.snagged"));
        }
        if (strain.snagged >= tuning.snagTicks()) {
            unclip(person, Unclipped.SNAGGED);
            return true;
        }
        return false;
    }

    /**
     * A haul that is clear by the line's ray but gets the person nowhere: over each {@link TetherTuning#stallTicks} window the line
     * must shorten by {@link TetherTuning#stallProgress}. The person is stuck on something the ray missed (a body is wider and
     * taller than a ray). Returns whether the last window closed with no progress.
     */
    private static boolean stalled(Strain strain, double line, TetherTuning tuning) {
        if (strain.window == 0) {
            strain.mark = line;
        }
        if (++strain.window >= tuning.stallTicks()) {
            strain.stalled = strain.mark - line < tuning.stallProgress();
            strain.window = 0;
        }
        return strain.stalled;
    }

    /** Gives the person a velocity of {@code speed} along {@code direction}, which their client moves them by, with its own collision. */
    private static void push(ServerPlayer person, Vec3 direction, double speed) {
        if (direction.lengthSqr() < EPSILON) {
            return;
        }
        person.setDeltaMovement(direction.normalize().scale(speed));
        // A player is moved by its own client, which has to be told: the server-side velocity is not read by it.
        person.connection.send(new ClientboundSetEntityMotionPacket(person));
        person.resetFallDistance();
    }

    /**
     * Sounds the winch in {@link DrawSources} until the next clank is due. The pod may have moved, so the old decoy
     * goes first: one entry per winch.
     */
    private static void loud(ServerLevel level, PodEntity pod, Strain strain, TetherTuning tuning) {
        if (strain.hauled++ % tuning.noiseInterval() == 0) {
            Vec3 at = anchor(pod);
            // PLACEHOLDER sound until the sound pass.
            level.playSound(null, at.x, at.y, at.z, SoundEvents.GRINDSTONE_USE, SoundSource.NEUTRAL, 1.2F, 0.6F);
            removeOwnDecoy(strain);
            DrawSources.addDecoy(
                    level, at, tuning.noiseRadius(), level.getGameTime() + tuning.noiseInterval() + DECOY_MARGIN);
            strain.decoy = new Decoy(level, at);
        }
    }

    /**
     * Ends the winch's decoy, if it has one. People clipped to one pod share its anchor and
     * {@link DrawSources#removeDecoy} removes every decoy there, so the decoy stays while another winch still hauls at
     * it. A person who disconnects is not unclipped: their decoy lapses within {@code noiseInterval + DECOY_MARGIN}
     * ticks.
     */
    private static void silence(@Nullable Strain strain) {
        if (strain == null || strain.decoy == null) {
            return;
        }
        Decoy decoy = strain.decoy;
        boolean shared;
        synchronized (STRAINS) {
            shared = STRAINS.values().stream().anyMatch(other -> other != strain && decoy.equals(other.decoy));
        }
        if (!shared) {
            removeOwnDecoy(strain);
        }
        strain.decoy = null;
    }

    private static void removeOwnDecoy(Strain strain) {
        if (strain.decoy != null) {
            DrawSources.removeDecoy(strain.decoy.level(), strain.decoy.at());
        }
    }

    /** What the tether does when it is used in the air: unhooks (sneaking), starts or stops the winch (clipped), or reels in a crew from the seat. */
    static InteractionResult useInAir(ServerPlayer person) {
        if (person.isShiftKeyDown()) {
            return unclip(person, Unclipped.UNHOOKED) ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }
        if (isClipped(person)) {
            boolean wasWinching = isWinching(person);
            if (wasWinching && isJammed(person)) {
                // A jammed winch cannot be stopped without cutting the line: stopping it would dodge the snap.
                unclip(person, Unclipped.SNAGGED);
                return InteractionResult.SUCCESS;
            }
            boolean hauling = setWinching(person, !wasWinching);
            if (hauling) {
                person.sendOverlayMessage(Component.translatable("message.deepcharter.tether.hauling"));
            } else if (wasWinching) {
                person.sendOverlayMessage(Component.translatable("message.deepcharter.tether.hauling_stopped"));
            }
            return InteractionResult.SUCCESS;
        }
        if (person.getVehicle() instanceof PodEntity pod && pod.getControllingPassenger() == person) {
            int clipped = 0;
            int reeled = 0;
            for (ServerPlayer other : person.level().getServer().getPlayerList().getPlayers()) {
                if (Optional.of(pod.getUUID()).equals(podId(other))) {
                    clipped++;
                    if (setWinching(other, true)) {
                        other.sendOverlayMessage(Component.translatable("message.deepcharter.tether.reeled_in"));
                        reeled++;
                    }
                }
            }
            if (reeled > 0) {
                return InteractionResult.SUCCESS;
            }
            if (clipped > 0) {
                // setWinching told the person on the line; the pilot is who pressed the button.
                person.sendOverlayMessage(Component.translatable("message.deepcharter.tether.no_power"));
                return InteractionResult.FAIL;
            }
        }
        person.sendOverlayMessage(Component.translatable("message.deepcharter.tether.not_clipped"));
        return InteractionResult.FAIL;
    }

    private static InteractionResult onUse(
            Player player, Level level, InteractionHand hand, Entity entity, EntityHitResult hit) {
        if (!(entity instanceof PodEntity target)
                || !(player.getItemInHand(hand).getItem() instanceof TetherItem)
                || player.isSpectator()) {
            return InteractionResult.PASS;
        }
        if (!(player instanceof ServerPlayer person)) {
            // The client guesses a hit and the server settles it.
            return InteractionResult.SUCCESS;
        }
        if (isClipped(person)) {
            return useOnSecondPod(person, target);
        }
        Optional<Refusal> refusal = clipRefusal(person, target);
        if (refusal.isPresent()) {
            person.sendOverlayMessage(refusal.get().message());
            return InteractionResult.FAIL;
        }
        clip(person, target);
        person.sendOverlayMessage(Component.translatable("message.deepcharter.tether.clipped"));
        return InteractionResult.SUCCESS;
    }

    /** The tether used on a pod while clipped: on its own pod it unhooks, on another it hooks or frees a tow from the pod the person is clipped to. */
    private static InteractionResult useOnSecondPod(ServerPlayer person, PodEntity target) {
        if (Optional.of(target.getUUID()).equals(podId(person))) {
            unclip(person, Unclipped.UNHOOKED);
            return InteractionResult.SUCCESS;
        }
        Optional<PodEntity> mine = pod(person);
        if (mine.isEmpty()) {
            unclip(person, Unclipped.POD_GONE);
            return InteractionResult.FAIL;
        }
        PodEntity tower = mine.get();
        if (PodTowing.towerId(target).filter(tower.getUUID()::equals).isPresent()) {
            PodTowing.detach(target);
            person.sendOverlayMessage(Component.translatable("message.deepcharter.towing.detached"));
            return InteractionResult.SUCCESS;
        }
        Optional<Charter> charter = Charters.readableCharterOf(person.level().getServer(), person.getUUID());
        if (!PodComponents.mayAccess(target, charter)) {
            person.sendOverlayMessage(PodTowing.Refusal.NOT_ALLOWED_TO_TOW.message());
            return InteractionResult.FAIL;
        }
        Optional<PodTowing.Refusal> refusal = PodTowing.refusal(tower, target);
        if (refusal.isPresent()) {
            person.sendOverlayMessage(refusal.get().message());
            return InteractionResult.FAIL;
        }
        PodTowing.attach(tower, target);
        person.sendOverlayMessage(Component.translatable("message.deepcharter.towing.attached"));
        for (Entity rider : target.getPassengers()) {
            if (rider instanceof ServerPlayer crew) {
                crew.sendOverlayMessage(Component.translatable("message.deepcharter.towing.being_towed"));
            }
        }
        return InteractionResult.SUCCESS;
    }
}
