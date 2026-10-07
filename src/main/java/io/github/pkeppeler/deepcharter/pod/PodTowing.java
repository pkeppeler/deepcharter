package io.github.pkeppeler.deepcharter.pod;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;

import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.layer.BreachEvents;

/**
 * The tow cable between two pods. A player riding a pod uses a {@link PodRegistry#TOW_CABLE} on another pod within
 * {@link TowTuning#reach()} to make it the towed pod of theirs, and uses it on a towed pod to take the cable off. A pod moved
 * where its owner did not choose needs a rule for who may do it: a player may tow a pod only when {@link PodComponents#mayAccess}
 * lets their charter at it, and the tower's charter or the towed pod's owner charter may free it, so an owner can always recover a
 * pod someone else towed.
 *
 * <p>The link is one versioned attachment on the towed pod, {@link #STATE}, holding the tower's UUID. It is saved and synced, and a
 * pod keeps its UUID when a breach recreates it, so the link outlives unloading and crossing. While the tower is in the towed pod's
 * level the towed pod passes through blocks ({@link PodEvents#IGNORES_BLOCK_COLLISION}), is held still or pulled in to
 * {@link TowTuning#trailDistance()} from the tower at the end of each tick ({@link PodEvents#AFTER_TICK}), and its mass and its
 * cargo's cut the tower's lift ({@link PodEvents#EXTRA_MASS}). With no tower in the level it is an ordinary pod that still remembers
 * the cable. A pod freed, or left by its tower, inside blocks is moved to the nearest open space. A tower with a player crossing a
 * breach carries the pod it tows across with it, to the trail distance from the tower. The cable is drawn as particles.
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
	private static final Vec3[] OPEN_DIRECTIONS = {new Vec3(0, 1, 0), new Vec3(1, 0, 0), new Vec3(-1, 0, 0), new Vec3(0, 0, 1), new Vec3(0, 0, -1), new Vec3(0, -1, 0)};

	/** Pods whose unreadable cable has been logged, so a tick path logs once for each pod and not once for each tick. */
	private static final Set<PodEntity> UNREADABLE_LOGGED = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
	/** Pods that found no open space to leave rock for, so a tick path logs once for each pod. */
	private static final Set<PodEntity> STUCK_LOGGED = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

	/** The pod this one is towed by, if any. */
	public record State(Optional<UUID> tower) {
		public static final State EMPTY = new State(Optional.empty());
		public static final MapCodec<State> BODY = RecordCodecBuilder.mapCodec(instance -> instance.group(
				UUIDUtil.CODEC.optionalFieldOf("tower").forGetter(State::tower)).apply(instance, State::new));
		public static final StreamCodec<ByteBuf, State> STREAM = ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC).map(State::new, State::tower);
	}

	public static final AttachmentType<Versioned<State>> STATE = AttachmentRegistry.create(
			Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "pod_towing"),
			builder -> builder
					.persistent(Versioned.codec(VERSION, State.BODY))
					.initializer(() -> Versioned.of(State.EMPTY))
					.syncWith(Versioned.streamCodec(VERSION, State.STREAM), AttachmentSyncPredicate.all()));

	/** Why a cable cannot be fitted from one pod to another. */
	public enum Refusal {
		NOT_RIDING, NOT_ALLOWED_TO_TOW, NOT_ALLOWED_TO_FREE, SAME_POD, TOO_FAR, ALREADY_TOWED, TOWER_IS_TOWED, TOWED_TOWS, ALREADY_TOWING, UNREADABLE;

		public Component message() {
			return Component.translatable("message.deepcharter.towing.refused." + name().toLowerCase(Locale.ROOT));
		}
	}

	private PodTowing() {
	}

	public static void init() {
		PodEvents.EXTRA_MASS.register(PodTowing::towedMass);
		PodEvents.IGNORES_BLOCK_COLLISION.register(pod -> tower(pod).isPresent());
		PodEvents.AFTER_TICK.register(PodTowing::follow);
		BreachEvents.CROSSED.register(PodTowing::carryAcross);
		UseEntityCallback.EVENT.register(PodTowing::onUse);
	}

	/** The UUID of the pod towing this one, whether or not it is in the world. An unreadable cable reads as none (logged once). Never throws. */
	public static Optional<UUID> towerId(PodEntity pod) {
		return switch (pod.getAttached(STATE)) {
			case null -> Optional.empty();
			case Versioned.Readable<State> readable -> readable.value().tower();
			case Versioned.Unreadable<State> unreadable -> {
				if (UNREADABLE_LOGGED.add(pod)) {
					DeepCharter.LOGGER.error("Pod {} has its {} saved as version {}, which this build cannot read: it is not towed and the saved data is kept",
							pod.getUUID(), STATE.identifier(), unreadable.version());
				}
				yield Optional.empty();
			}
		};
	}

	/** True when the pod is on a cable, even if its tower is away. Safe on either side. */
	public static boolean isTowed(PodEntity pod) {
		return towerId(pod).isPresent();
	}

	/** Why {@code tower} cannot tow {@code towed} now, or empty. Never throws. */
	public static Optional<Refusal> refusal(PodEntity tower, PodEntity towed) {
		if (tower == towed) {
			return Optional.of(Refusal.SAME_POD);
		}
		if (tower.getAttached(STATE) instanceof Versioned.Unreadable<State> || towed.getAttached(STATE) instanceof Versioned.Unreadable<State>) {
			// Reading logs them, once for each pod.
			towerId(tower);
			towerId(towed);
			return Optional.of(Refusal.UNREADABLE);
		}
		if (isTowed(towed)) {
			return Optional.of(Refusal.ALREADY_TOWED);
		}
		if (isTowed(tower)) {
			return Optional.of(Refusal.TOWER_IS_TOWED);
		}
		if (!towedBy(towed).isEmpty()) {
			return Optional.of(Refusal.TOWED_TOWS);
		}
		if (!towedBy(tower).isEmpty()) {
			return Optional.of(Refusal.ALREADY_TOWING);
		}
		double reach = TowTuning.DEFAULT.reach();
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
			throw new IllegalStateException("pod " + tower.getUUID() + " cannot tow pod " + towed.getUUID() + ": " + refusal.get());
		}
		Versioned.modify(towed, STATE, state -> new State(Optional.of(tower.getUUID())));
	}

	/**
	 * Server only: takes the cable off {@code towed}. Returns false, and changes nothing, when it had none. Throws on an unreadable
	 * state, naming the attachment, so the saved data is never overwritten.
	 */
	public static boolean detach(PodEntity towed) {
		requireServer(towed);
		if (Versioned.require(towed, STATE).tower().isEmpty()) {
			return false;
		}
		Optional<Vec3> toward = tower(towed).map(PodEntity::position);
		Versioned.modify(towed, STATE, state -> State.EMPTY);
		leaveRock(towed, toward);
		return true;
	}

	/** The tower of a towed pod, when it is in the same level and still there. */
	private static Optional<PodEntity> tower(PodEntity towed) {
		Optional<UUID> id = towerId(towed);
		if (id.isEmpty() || !(towed.level() instanceof ServerLevel level) || !(level.getEntity(id.get()) instanceof PodEntity tower) || tower.isRemoved()) {
			return Optional.empty();
		}
		return Optional.of(tower);
	}

	/** The pods on a cable from {@code tower} that are within its reach. */
	private static List<PodEntity> towedBy(PodEntity tower) {
		return tower.level().getEntitiesOfClass(PodEntity.class, tower.getBoundingBox().inflate(TowTuning.DEFAULT.reach()),
				other -> other != tower && towerId(other).filter(tower.getUUID()::equals).isPresent());
	}

	private static float towedMass(PodEntity tower) {
		if (isTowed(tower)) {
			return 0f;
		}
		float mass = 0f;
		for (PodEntity towed : towedBy(tower)) {
			mass += TowTuning.DEFAULT.baseMass() + towed.cargoMass();
		}
		return mass;
	}

	/**
	 * Holds the towed pod where it started the tick, or pulls it in along the line to its tower until it is
	 * {@link TowTuning#trailDistance()} away. It has no block collision, so nothing else stops it, and its own gravity is undone here.
	 */
	private static void follow(PodEntity towed) {
		Optional<PodEntity> tower = tower(towed);
		if (tower.isEmpty()) {
			if (isTowed(towed)) {
				leaveRock(towed, Optional.empty());
			}
			return;
		}
		Vec3 anchor = tower.get().position();
		Vec3 held = new Vec3(towed.xo, towed.yo, towed.zo);
		Vec3 away = held.subtract(anchor);
		double trail = TowTuning.DEFAULT.trailDistance();
		towed.setPos(away.lengthSqr() > trail * trail ? anchor.add(away.normalize().scale(trail)) : held);
		towed.setDeltaMovement(Vec3.ZERO);
		towed.resetFallDistance();
		drawCable(tower.get(), towed);
	}

	/** Particles along the line between the two pods' middles every {@link TowTuning#cableInterval()} ticks, at most {@link TowTuning#cableMaxParticles()}. */
	private static void drawCable(PodEntity tower, PodEntity towed) {
		TowTuning tuning = TowTuning.DEFAULT;
		if (towed.tickCount % tuning.cableInterval() != 0 || !(towed.level() instanceof ServerLevel level)) {
			return;
		}
		Vec3 from = tower.getBoundingBox().getCenter();
		Vec3 line = towed.getBoundingBox().getCenter().subtract(from);
		int count = Math.min(tuning.cableMaxParticles(), (int) Math.ceil(line.length() / tuning.cableSpacing()));
		for (int i = 1; i <= count; i++) {
			Vec3 at = from.add(line.scale((double) i / (count + 1)));
			level.sendParticles(ParticleTypes.END_ROD, at.x, at.y, at.z, 1, 0, 0, 0, 0);
		}
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
		Optional<Vec3> open = line.lengthSqr() < 1e-6 ? Optional.empty() : firstOpen(pod, box, List.of(line.normalize()), line.length());
		open = open.or(() -> firstOpen(pod, box, List.of(OPEN_DIRECTIONS), OPEN_REACH));
		if (open.isEmpty()) {
			if (STUCK_LOGGED.add(pod)) {
				DeepCharter.LOGGER.error("Pod {} is inside blocks with no open space within {} blocks: it is left where it is", pod.getUUID(), OPEN_REACH);
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
		List<? extends PodEntity> towed = from.getEntities(EntityTypeTest.forClass(PodEntity.class),
				pod -> towerId(pod).filter(tower.getUUID()::equals).isPresent());
		for (PodEntity pod : towed) {
			if (!pod.canTeleport(from, to)) {
				DeepCharter.LOGGER.error("Pod {} cannot cross to {} with its tower {}: it stays behind on its cable", pod.getUUID(), to.dimension(), tower.getUUID());
				continue;
			}
			Entity arrived = pod.teleport(new TeleportTransition(to, trailSpot(tower, pod), Vec3.ZERO, pod.getYRot(), pod.getXRot(), TeleportTransition.DO_NOTHING));
			if (arrived == null) {
				DeepCharter.LOGGER.error("Vanilla refused to take pod {} to {} with its tower {}: it stays behind on its cable", pod.getUUID(), to.dimension(), tower.getUUID());
				continue;
			}
			arrived.getPassengersAndSelf().forEach(crossed -> {
				crossed.resetFallDistance();
				BreachEvents.CROSSED.invoker().onCrossed(crossed, from, to, fromLayer, toLayer);
			});
		}
	}

	/** {@link TowTuning#trailDistance()} from the tower, level with it, on the side the pod was on, or behind the tower if it was straight above or below. */
	private static Vec3 trailSpot(PodEntity tower, PodEntity towed) {
		Vec3 side = new Vec3(towed.getX() - tower.getX(), 0, towed.getZ() - tower.getZ());
		if (side.lengthSqr() < 1e-6) {
			side = Vec3.directionFromRotation(0, tower.getYRot()).scale(-1);
		}
		return tower.position().add(side.normalize().scale(TowTuning.DEFAULT.trailDistance()));
	}

	private static InteractionResult onUse(Player player, Level level, InteractionHand hand, Entity entity, EntityHitResult hit) {
		if (!(entity instanceof PodEntity target) || !player.getItemInHand(hand).is(PodRegistry.TOW_CABLE) || player.isSpectator()) {
			return InteractionResult.PASS;
		}
		if (!(player instanceof ServerPlayer serverPlayer)) {
			// The client guesses a hit and the server settles it.
			return InteractionResult.SUCCESS;
		}
		Optional<Charter> charter;
		try {
			charter = Charters.charterOf(serverPlayer.level().getServer(), serverPlayer.getUUID());
		} catch (IllegalStateException unreadable) {
			DeepCharter.LOGGER.error("Charters cannot be read, so {} cannot use a tow cable", serverPlayer.getGameProfile().name(), unreadable);
			serverPlayer.sendOverlayMessage(Refusal.UNREADABLE.message());
			return InteractionResult.FAIL;
		}
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
		return PodComponents.mayAccess(towed, charter) || tower(towed).filter(tower -> PodComponents.mayAccess(tower, charter)).isPresent();
	}

	private static void requireServer(PodEntity pod) {
		if (pod.level().isClientSide()) {
			throw new IllegalStateException("a tow cable is fitted on the server only");
		}
	}
}
