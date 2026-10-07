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
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.layer.BreachEvents;

/**
 * The tow cable between two pods. A player riding a pod uses a {@link PodRegistry#TOW_CABLE} on another pod within
 * {@link TowTuning#reach()} to make it the towed pod of theirs, and uses it on a towed pod to take the cable off. Anyone can tow
 * any pod, whoever owns it: the cable touches neither cargo nor parts, so it does not ask {@link PodComponents#mayAccess}.
 *
 * <p>The link is one versioned attachment on the towed pod, {@link #STATE}, holding the tower's UUID. It is saved and synced, and a
 * pod keeps its UUID when a breach recreates it, so the link outlives unloading and crossing. While the tower is in the towed pod's
 * level the towed pod passes through blocks ({@link PodEvents#IGNORES_BLOCK_COLLISION}), is held still or pulled in to
 * {@link TowTuning#trailDistance()} from the tower at the end of each tick ({@link PodEvents#AFTER_TICK}), and its mass and its
 * cargo's cut the tower's lift ({@link PodEvents#EXTRA_MASS}). With no tower in the level it is an ordinary pod that still remembers
 * the cable. A tower with a player crossing a breach carries the pod it tows across with it.
 *
 * <p>A tower tows one pod, a towed pod tows none, and a pod is on one cable. The listeners run every tick and on a crossing, so they
 * never throw on an unreadable state: they log once for each pod and read it as no cable. {@link #attach} and {@link #detach} are
 * explicit changes and do throw.
 */
public final class PodTowing {
	public static final int VERSION = 1;

	/** Pods whose unreadable cable has been logged, so a tick path logs once for each pod and not once for each tick. */
	private static final Set<PodEntity> UNREADABLE_LOGGED = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

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
		NOT_RIDING, SAME_POD, TOO_FAR, ALREADY_TOWED, TOWER_IS_TOWED, TOWED_TOWS, ALREADY_TOWING, UNREADABLE;

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
		Versioned.modify(towed, STATE, state -> State.EMPTY);
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
			return;
		}
		Vec3 anchor = tower.get().position();
		Vec3 held = new Vec3(towed.xo, towed.yo, towed.zo);
		Vec3 away = held.subtract(anchor);
		double trail = TowTuning.DEFAULT.trailDistance();
		towed.setPos(away.lengthSqr() > trail * trail ? anchor.add(away.normalize().scale(trail)) : held);
		towed.setDeltaMovement(Vec3.ZERO);
		towed.resetFallDistance();
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
			Entity arrived = pod.teleport(new TeleportTransition(to, tower.position(), Vec3.ZERO, pod.getYRot(), pod.getXRot(), TeleportTransition.DO_NOTHING));
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

	private static InteractionResult onUse(Player player, Level level, InteractionHand hand, Entity entity, EntityHitResult hit) {
		if (!(entity instanceof PodEntity target) || !player.getItemInHand(hand).is(PodRegistry.TOW_CABLE) || player.isSpectator()) {
			return InteractionResult.PASS;
		}
		if (!(player instanceof ServerPlayer serverPlayer)) {
			// The client guesses a hit and the server settles it.
			return InteractionResult.SUCCESS;
		}
		if (isTowed(target)) {
			detach(target);
			serverPlayer.sendOverlayMessage(Component.translatable("message.deepcharter.towing.detached"));
			return InteractionResult.SUCCESS;
		}
		if (!(player.getVehicle() instanceof PodEntity tower)) {
			serverPlayer.sendOverlayMessage(Refusal.NOT_RIDING.message());
			return InteractionResult.FAIL;
		}
		Optional<Refusal> refusal = refusal(tower, target);
		if (refusal.isPresent()) {
			serverPlayer.sendOverlayMessage(refusal.get().message());
			return InteractionResult.FAIL;
		}
		attach(tower, target);
		serverPlayer.sendOverlayMessage(Component.translatable("message.deepcharter.towing.attached"));
		return InteractionResult.SUCCESS;
	}

	private static void requireServer(PodEntity pod) {
		if (pod.level().isClientSide()) {
			throw new IllegalStateException("a tow cable is fitted on the server only");
		}
	}
}
