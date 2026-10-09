package io.github.pkeppeler.deepcharter.pod;

import java.util.List;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.sound.DeepSound;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;

/**
 * The liner (#339): a part that lines the stretch of bore it is about to drill with slag brick from the rack, then the seated pilot's pack. The ring is as many slabs
 * tall as its tier's interval, so the next ring starts where this one ends. A ring is due each time the pod has sunk that many slabs,
 * and when the bore turns into another column (a sidestep). The cells and the brick accounting are hand lining's
 * ({@link PodLining#cellsToLine}, the rack in {@link PodLining.State}). A tier that does not line in a fall waits until the pod rests,
 * then lines the ring that fell due. The liner works only in a layer: above ground a ring would wall the sky.
 */
public final class PodLiner {
	public static final int VERSION = 1;

	/**
	 * Where the liner counts the next ring from: the column of the pod's footprint, and the highest feet Y the pod has had in that
	 * column since the last ring.
	 *
	 * @param feetY the Y of the pod's feet, as {@code PodFootprint} says
	 * @param lowX  the low X of its footprint
	 * @param lowZ  the low Z of its footprint
	 */
	public record Anchor(int feetY, int lowX, int lowZ) {
		public static final Codec<Anchor> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.INT.fieldOf("feetY").forGetter(Anchor::feetY),
				Codec.INT.fieldOf("lowX").forGetter(Anchor::lowX),
				Codec.INT.fieldOf("lowZ").forGetter(Anchor::lowZ)).apply(instance, Anchor::new));
		public static final StreamCodec<ByteBuf, Anchor> STREAM = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, Anchor::feetY,
				ByteBufCodecs.VAR_INT, Anchor::lowX,
				ByteBufCodecs.VAR_INT, Anchor::lowZ,
				Anchor::new);

		static Anchor of(PodEntity pod) {
			PodFootprint foot = PodFootprint.of(pod);
			return new Anchor(foot.feetY(), foot.lowX(), foot.lowZ());
		}

		boolean sameColumn(Anchor other) {
			return lowX == other.lowX && lowZ == other.lowZ;
		}
	}

	/**
	 * @param anchor where the next ring is counted from, or empty before the first tick with a liner
	 */
	public record State(Optional<Anchor> anchor) {
		public static final State EMPTY = new State(Optional.empty());
		public static final MapCodec<State> BODY = RecordCodecBuilder.mapCodec(instance -> instance.group(
				Anchor.CODEC.optionalFieldOf("anchor").forGetter(State::anchor)).apply(instance, State::new));
		public static final StreamCodec<ByteBuf, State> STREAM = StreamCodec.composite(
				ByteBufCodecs.optional(Anchor.STREAM), State::anchor,
				State::new);

		/** Slabs of sinking left before the ring is due, 0 when it is due, for a pod now at {@code here}. */
		public int slabsToRing(Anchor here, int ringEverySlabs) {
			return anchor
					.map(last -> last.sameColumn(here) ? Math.max(0, ringEverySlabs - Math.max(0, last.feetY() - here.feetY())) : 0)
					.orElse(ringEverySlabs);
		}

		/** A pod that climbs in its column moves the anchor up with it, and the first tick sets it. A pod in another column leaves it, so the ring stays due. */
		State seen(Anchor here) {
			Anchor kept = anchor.map(last -> last.sameColumn(here) && here.feetY() > last.feetY() ? here : last).orElse(here);
			return new State(Optional.of(kept));
		}

		State ringedAt(Anchor here) {
			return new State(Optional.of(here));
		}
	}

	public static final AttachmentType<Versioned<State>> STATE = AttachmentRegistry.create(
			Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "pod_liner"),
			builder -> builder
					.persistent(Versioned.codec(VERSION, State.BODY))
					.initializer(() -> Versioned.of(State.EMPTY))
					.syncWith(Versioned.streamCodec(VERSION, State.STREAM), AttachmentSyncPredicate.all()));

	private PodLiner() {
	}

	public static void init() {
		PodEvents.AFTER_TICK.register(PodLiner::afterTick);
		PodStats.MODIFY.register(PodStats.BASE, (pod, stats) -> {
			int tier = tier(pod);
			return tier == 0 ? stats : stats.withTicksPerHardness(PodLinerTuning.DEFAULT.tier(tier).slowedDrill(stats.ticksPerHardness()));
		});
	}

	/** The tier the pod's liner works at: 0 for none, a void part or an unreadable one. */
	public static int tier(PodEntity pod) {
		return PodComponents.effectiveTier(pod, ComponentTrack.LINER);
	}

	/** Slabs the pod can still sink before its liner rings (0 when the ring is due), or empty for a pod with no liner. Works on both sides, from the synced state. */
	public static Optional<Integer> slabsToNextRing(PodEntity pod) {
		int tier = tier(pod);
		if (tier == 0) {
			return Optional.empty();
		}
		State state = Versioned.readable(pod, STATE).orElse(State.EMPTY);
		return Optional.of(state.slabsToRing(Anchor.of(pod), PodLinerTuning.DEFAULT.tier(tier).ringEverySlabs()));
	}

	private static void afterTick(PodEntity pod) {
		int tier = tier(pod);
		Optional<State> read = Versioned.readable(pod, STATE);
		if (tier == 0 || read.isEmpty()) {
			return;
		}
		PodLinerTuning.Tier spec = PodLinerTuning.DEFAULT.tier(tier);
		Anchor here = Anchor.of(pod);
		State state = read.get().seen(here);
		if (state.slabsToRing(here, spec.ringEverySlabs()) == 0 && mayLine(pod, spec)) {
			ring(pod, spec);
			state = state.ringedAt(here);
		}
		if (!state.equals(read.get())) {
			pod.setAttached(STATE, Versioned.of(state));
		}
	}

	/**
	 * The liner works in a layer while the pod has power and the pilot is not lining by hand, and in a fall only for a tier that lines in one.
	 * A pod above ground keeps its bricks.
	 */
	private static boolean mayLine(PodEntity pod, PodLinerTuning.Tier spec) {
		return inLayer(pod) && PodEvents.isPowered(pod) && !PodLining.working(pod) && (pod.onGround() || spec.linesWhileFalling());
	}

	private static boolean inLayer(PodEntity pod) {
		return LayerChain.layerOf(pod.level().dimensionTypeRegistration().unwrapKey().orElseThrow().identifier()).isPresent();
	}

	/**
	 * Lines as many of the cells hand lining would line as the bricks pay for, and marks the rack dry when they paid for fewer than all. The
	 * bricks are the rack's first, then the seated pilot's pack, as by hand ({@link PodLining.State}).
	 */
	private static void ring(PodEntity pod, PodLinerTuning.Tier spec) {
		List<BlockPos> cells = PodLining.cellsToLine(pod, spec.ringEverySlabs());
		if (cells.isEmpty()) {
			return;
		}
		int rack = PodLining.of(pod).bricks();
		Optional<ServerPlayer> pilot = pod.getControllingPassenger() instanceof ServerPlayer player && PodLining.mayUseStores(pod, player)
				? Optional.of(player) : Optional.empty();
		int pack = pilot.map(player -> PodLining.carried(player.getInventory())).orElse(0);
		int placed = Math.min(cells.size(), (rack + pack) * spec.cellsPerBrick());
		ServerLevel level = (ServerLevel) pod.level();
		for (BlockPos cell : cells.subList(0, placed)) {
			PodLining.setBrick(level, cell);
		}
		if (placed > 0) {
			level.playSound(null, pod.getX(), pod.getY(), pod.getZ(), DeepSound.POD_LINING_PLACE.event(), SoundSource.BLOCKS);
		}
		int bricks = Math.ceilDiv(placed, spec.cellsPerBrick());
		int fromRack = Math.min(bricks, rack);
		for (int fromPack = bricks - fromRack; fromPack > 0; fromPack--) {
			PodLining.takeCarried(pilot.orElseThrow().getInventory());
		}
		PodLining.modify(pod, state -> state.linedByLiner(fromRack, placed < cells.size()));
	}
}
