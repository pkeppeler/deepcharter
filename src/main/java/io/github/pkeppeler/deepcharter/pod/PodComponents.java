package io.github.pkeppeler.deepcharter.pod;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.UnaryOperator;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;

import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.github.pkeppeler.deepcharter.upgrade.PartLabel;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeTuning;

/**
 * What a pod is made of and whose it is: the owner charter and serial (such as {@code MOLE-0001}), and the parts installed in
 * it. Both live in one versioned attachment that is saved, synced to every client that tracks the pod, and carried across a
 * breach with the pod.
 *
 * <p>Parts change the pod through {@link PodStats#MODIFY} and nothing else: a part's effect is added in
 * {@link PodStats#BASE}, at the part's tier or the chassis' tier cap if that is lower, so the cap limits the part and never
 * what other features add to the same stat. A part stamped with another charter than the pod's owner is
 * void (SPEC section 6): it stays installed and does nothing. A pod with no owner has no charter whose parts count, so all
 * of its parts are void.
 *
 * <p>Only the owner charter's members can pilot a pod ({@link PodEvents#CAN_MOUNT}). Anyone can refuel it ({@link PodFuel}
 * asks nobody) and, from #76, tow it. A pod with no owner, such as one spawned by a command, is anyone's.
 *
 * <p>The listeners run on every tick and on the client, so they never throw on an unreadable state: they log once, read it
 * as a pod with no parts, and refuse every pilot, because the owner is unknown. {@link #register} and {@link #install} are explicit changes and do throw.
 */
public final class PodComponents {
	public static final int VERSION = 1;

	/** Pods whose unreadable state has been logged, so a tick path logs once for each pod and not once for each tick. */
	private static final Set<PodEntity> UNREADABLE_LOGGED = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
	private static volatile boolean chartersUnreadableLogged;

	/** A pod's owner charter and its serial number. */
	public record Registration(CharterId owner, String serial) {
		public static final Codec<Registration> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				CharterId.CODEC.fieldOf("owner").forGetter(Registration::owner),
				Codec.STRING.fieldOf("serial").forGetter(Registration::serial)).apply(instance, Registration::new));
		public static final StreamCodec<ByteBuf, Registration> STREAM_CODEC = StreamCodec.composite(
				CharterId.STREAM_CODEC, Registration::owner,
				ByteBufCodecs.STRING_UTF8, Registration::serial,
				Registration::new);
	}

	/** The attachment's value: who owns the pod (unowned until {@link #register}) and the part installed on each track. */
	public record State(Optional<Registration> registration, Map<ComponentTrack, PartLabel> parts) {
		public static final State EMPTY = new State(Optional.empty(), Map.of());
		/** A saved tier beyond its track's best decodes to an error, so it reads as unreadable and never reaches a stats listener. */
		public static final MapCodec<State> BODY = RecordCodecBuilder.<State>mapCodec(instance -> instance.group(
				Registration.CODEC.optionalFieldOf("registration").forGetter(State::registration),
				Codec.unboundedMap(ComponentTrack.CODEC, PartLabel.CODEC).fieldOf("parts").forGetter(State::parts)).apply(instance, State::new))
				.flatXmap(State::validated, DataResult::success);
		public static final StreamCodec<ByteBuf, State> STREAM = StreamCodec.composite(
				ByteBufCodecs.optional(Registration.STREAM_CODEC), State::registration,
				ByteBufCodecs.map(ignored -> new EnumMap<>(ComponentTrack.class), ComponentTrack.STREAM_CODEC, PartLabel.STREAM_CODEC), State::parts,
				State::new);

		public State {
			parts = Map.copyOf(parts);
		}

		private DataResult<State> validated() {
			for (Map.Entry<ComponentTrack, PartLabel> part : parts.entrySet()) {
				if (part.getValue().tier() > part.getKey().maxTier()) {
					return DataResult.error(() -> "a " + part.getKey().id() + " part has tier " + part.getValue().tier()
							+ ", the best is " + part.getKey().maxTier());
				}
			}
			return DataResult.success(this);
		}

		private State with(ComponentTrack track, PartLabel label) {
			Map<ComponentTrack, PartLabel> changed = new EnumMap<>(ComponentTrack.class);
			changed.putAll(parts);
			changed.put(track, label);
			return new State(registration, changed);
		}
	}

	public static final AttachmentType<Versioned<State>> STATE = AttachmentRegistry.create(
			Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "pod_components"),
			builder -> builder
					.persistent(Versioned.codec(VERSION, State.BODY))
					.initializer(() -> Versioned.of(State.EMPTY))
					.syncWith(Versioned.streamCodec(VERSION, State.STREAM), AttachmentSyncPredicate.all()));

	/**
	 * One stat a track moves, and which way is better. A part scales the stat by the tier's ratio against the stock part, so it
	 * composes with other listeners. A drill is better when its ticks for each hardness are fewer, so it divides.
	 */
	private record Axis(Function<PodStats, Float> read, BiFunction<PodStats, Float, PodStats> write, boolean lowerIsBetter) {
		PodStats scale(PodStats stats, float ratio) {
			float value = read.apply(stats);
			return write.apply(stats, lowerIsBetter ? value / ratio : value * ratio);
		}
	}

	/** The stats of each track. The radiator, scanner and lights move none: they are read through {@link #effectiveTier}. */
	private static final Map<ComponentTrack, List<Axis>> AXES = new EnumMap<>(ComponentTrack.class);

	static {
		AXES.put(ComponentTrack.DRILL, List.of(new Axis(PodStats::ticksPerHardness, PodStats::withTicksPerHardness, true)));
		AXES.put(ComponentTrack.HULL, List.of(new Axis(PodStats::maxHull, PodStats::withMaxHull, false)));
		AXES.put(ComponentTrack.ENGINE, List.of(
				new Axis(PodStats::enginePower, PodStats::withEnginePower, false),
				new Axis(PodStats::horizontalSpeed, PodStats::withHorizontalSpeed, false)));
		AXES.put(ComponentTrack.FUEL_TANK, List.of(new Axis(PodStats::tankLitres, PodStats::withTankLitres, false)));
		AXES.put(ComponentTrack.CARGO_BAY, List.of(new Axis(stats -> (float) stats.cargoSlots(),
				(stats, slots) -> stats.withCargoSlots(Math.round(slots)), false)));
	}

	private PodComponents() {
	}

	public static void init() {
		PodStats.MODIFY.register(PodStats.BASE, PodComponents::applyParts);
		PodEvents.CAN_MOUNT.register(PodComponents::canMount);
	}

	/** The pod's owner and serial, or empty for a pod nobody owns. */
	public static Optional<Registration> registration(PodEntity pod) {
		return read(pod).registration();
	}

	/** The part installed on {@code track}, void or not. */
	public static Optional<PartLabel> partOf(PodEntity pod, ComponentTrack track) {
		return Optional.ofNullable(read(pod).parts().get(track));
	}

	/**
	 * The tier the pod gets from {@code track}: 0 for no part or a void one, and never above the chassis' cap. The scanner,
	 * lights and radiator read this to know what they do.
	 */
	public static int effectiveTier(PodEntity pod, ComponentTrack track) {
		State state = read(pod);
		PartLabel label = state.parts().get(track);
		if (label == null || !counts(state, label)) {
			return 0;
		}
		return cappedTier(pod, label);
	}

	/** The one place a chassis' tier cap applies: the tier a counted part works at. */
	private static int cappedTier(PodEntity pod, PartLabel label) {
		return Math.min(label.tier(), UpgradeTuning.DEFAULT.tierCap(pod.chassis().id()));
	}

	/**
	 * Server only: makes {@code owner} the pod's owner and gives the pod the next serial of its chassis. A pod is registered
	 * once, so a second call throws.
	 */
	public static void register(PodEntity pod, CharterId owner) {
		MinecraftServer server = requireServer(pod);
		if (Versioned.require(pod, STATE).registration().isPresent()) {
			throw new IllegalStateException("pod " + pod.getUUID() + " is already registered");
		}
		String serial = Serials.get(server).next(pod.chassis().id().toUpperCase(Locale.ROOT));
		change(pod, state -> new State(Optional.of(new Registration(owner, serial)), state.parts()));
	}

	/**
	 * Server only: installs the part in the stack, over the part of its track that is there, and returns that one. The stack is
	 * left alone: whoever installs it takes it. A stack that is not a labelled part throws.
	 *
	 * <p>A part of another charter installs and then does nothing. Fuel and hull follow the change, see {@link #change}.
	 */
	public static Optional<PartLabel> install(PodEntity pod, ItemStack stack) {
		requireServer(pod);
		ComponentTrack track = ComponentItems.trackOf(stack)
				.orElseThrow(() -> new IllegalArgumentException("not a pod part: " + stack));
		PartLabel label = ComponentItems.labelOf(stack)
				.orElseThrow(() -> new IllegalArgumentException("pod part has no label: " + stack));
		track.requirePartTier(label.tier());
		Optional<PartLabel> replaced = Optional.ofNullable(Versioned.require(pod, STATE).parts().get(track));
		change(pod, state -> state.with(track, label));
		return replaced;
	}

	/**
	 * Changes the state, and keeps what the pod holds while its stats move: a new tank keeps the litres (the stored percent is
	 * rescaled, ADR 0010), and a bigger hull keeps the damage taken. Registering an owner can change the stats too, because
	 * it makes the parts installed before it count.
	 */
	private static void change(PodEntity pod, UnaryOperator<State> change) {
		PodStats before = PodStats.of(pod);
		Versioned.modify(pod, STATE, change);
		PodStats after = PodStats.of(pod);
		rescaleFuel(pod, before, after);
		if (pod.hull() > 0f) {
			// A pod with no hull left is a wreck (#67), and a part must not repair it. setHull holds the result to the new maximum.
			pod.setHull(pod.hull() + Math.max(0f, after.maxHull() - before.maxHull()));
		}
	}

	/**
	 * Keeps the litres in the tank when the tank size moves from {@code before} to {@code after}: the stored percent is of the new
	 * size (ADR 0010). Does nothing when the size is the same. Server only, like every change to a pod.
	 *
	 * <p>{@code before} and {@code after} must both be this pod's {@code PodStats.of(pod)}, taken before and after the change.
	 */
	public static void rescaleFuel(PodEntity pod, PodStats before, PodStats after) {
		Objects.requireNonNull(before, "before");
		Objects.requireNonNull(after, "after");
		if (after.tankLitres() != before.tankLitres()) {
			float full = PodTuning.DEFAULT.shell().fullFuel();
			pod.setFuel(Math.min(full, pod.fuel() * before.tankLitres() / after.tankLitres()));
		}
	}

	private static PodStats applyParts(PodEntity pod, PodStats stats) {
		PodStats result = stats;
		for (Map.Entry<ComponentTrack, List<Axis>> track : AXES.entrySet()) {
			int tier = effectiveTier(pod, track.getKey());
			if (tier == 0) {
				continue;
			}
			float ratio = UpgradeTuning.DEFAULT.ratio(track.getKey(), tier);
			for (Axis axis : track.getValue()) {
				result = axis.scale(result, ratio);
			}
		}
		return result;
	}

	private static boolean canMount(PodEntity pod, Entity passenger) {
		if (!(passenger instanceof ServerPlayer player)) {
			return !unreadable(pod);
		}
		MinecraftServer server = player.level().getServer();
		Optional<Charter> charter;
		try {
			charter = Charters.charterOf(server, player.getUUID());
		} catch (IllegalStateException unreadable) {
			logChartersUnreadable(unreadable);
			return !unreadable(pod);
		}
		if (mayAccess(pod, charter)) {
			return true;
		}
		// Denied: say whose pod it is, unless the pod's own state is what cannot be read.
		if (!unreadable(pod)) {
			Registration registration = read(pod).registration().orElseThrow();
			player.sendSystemMessage(Component.translatable("message.deepcharter.pod.not_crew", registration.serial(),
					Charters.find(server, registration.owner()).orElseThrow().name()), true);
		}
		return false;
	}

	/**
	 * The one rule of who may act on a pod (mount it, sell its cargo, fit its parts) for {@code charter}, the acting player's
	 * charter if they are on one. Features ask this and never reimplement it.
	 * <ul>
	 *   <li>Components the build cannot read: no. Who owns the pod is unknown, and unknown is not everyone.</li>
	 *   <li>An unowned pod: anyone.</li>
	 *   <li>An owner that is gone or dormant cannot crew its pods, so they must not stay locked: anyone.</li>
	 *   <li>Otherwise only the owner charter.</li>
	 * </ul>
	 * Saved charters this build cannot read leave no owner to check against: the owner check is skipped (logged once).
	 */
	public static boolean mayAccess(PodEntity pod, Optional<Charter> charter) {
		if (unreadable(pod)) {
			return false;
		}
		Optional<Registration> registration = read(pod).registration();
		if (registration.isEmpty()) {
			return true;
		}
		try {
			Optional<Charter> owner = Charters.find(pod.level().getServer(), registration.get().owner());
			if (owner.isEmpty() || owner.get().dormant()) {
				return true;
			}
			return charter.isPresent() && charter.get().id().equals(registration.get().owner());
		} catch (IllegalStateException unreadable) {
			logChartersUnreadable(unreadable);
			return true;
		}
	}

	/**
	 * The charter that owns the pod and can act as its owner: empty for a pod nobody owns, and for an owner that is gone or dormant
	 * (as in {@link #mayAccess}, such a pod is anyone's). Also empty when the pod's components or the saved charters cannot be
	 * read; never throws.
	 */
	public static Optional<Charter> ownerCharter(PodEntity pod) {
		Optional<Registration> registration = read(pod).registration();
		if (registration.isEmpty()) {
			return Optional.empty();
		}
		try {
			return Charters.find(pod.level().getServer(), registration.get().owner()).filter(owner -> !owner.dormant());
		} catch (IllegalStateException unreadable) {
			logChartersUnreadable(unreadable);
			return Optional.empty();
		}
	}

	/** True when the pod's components are unreadable; logs once, through {@link #read}. */
	private static boolean unreadable(PodEntity pod) {
		read(pod);
		return pod.getAttached(STATE) instanceof Versioned.Unreadable<State>;
	}

	private static void logChartersUnreadable(IllegalStateException unreadable) {
		if (!chartersUnreadableLogged) {
			chartersUnreadableLogged = true;
			DeepCharter.LOGGER.error("Pod ownership is not checked, because the saved charters cannot be read: {}", unreadable.getMessage());
		}
	}

	private static boolean counts(State state, PartLabel label) {
		return state.registration().map(registration -> registration.owner().equals(label.charter())).orElse(false);
	}

	/** The pod's state, or the empty one when it was never set or is unreadable (logged once). Never throws: listeners call it. */
	private static State read(PodEntity pod) {
		Versioned<State> versioned = pod.getAttached(STATE);
		return switch (versioned) {
			case null -> State.EMPTY;
			case Versioned.Readable<State> readable -> readable.value();
			case Versioned.Unreadable<State> unreadable -> {
				if (UNREADABLE_LOGGED.add(pod)) {
					DeepCharter.LOGGER.error("Pod {} has components saved as version {}, which this build cannot read: it runs with stock parts, nobody can pilot it, and the saved data is kept",
							pod.getUUID(), unreadable.version());
				}
				yield State.EMPTY;
			}
		};
	}

	private static MinecraftServer requireServer(PodEntity pod) {
		MinecraftServer server = pod.level().getServer();
		if (pod.level().isClientSide() || server == null) {
			throw new IllegalStateException("pod components change on the server only");
		}
		return server;
	}
}
