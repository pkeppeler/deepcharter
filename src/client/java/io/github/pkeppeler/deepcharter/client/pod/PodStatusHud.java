package io.github.pkeppeler.deepcharter.client.pod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;

import net.minecraft.client.DeltaTracker;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.theme.HudLook;
import io.github.pkeppeler.deepcharter.pod.HardLanding;
import io.github.pkeppeler.deepcharter.pod.PodBrace;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodLavaWatch;
import io.github.pkeppeler.deepcharter.pod.PodLiner;
import io.github.pkeppeler.deepcharter.pod.PodLining;
import io.github.pkeppeler.deepcharter.pod.PodSeat;
import io.github.pkeppeler.deepcharter.pod.PodSounder;
import io.github.pkeppeler.deepcharter.pod.PodStats;

/** Plain text readout of the ridden pod; its margin, spacing and colour are the HUD theme's. */
public final class PodStatusHud {
	private static final Identifier ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "pod_status");

	private PodStatusHud() {
	}

	public static void init() {
		HudElementRegistry.addLast(ID, PodStatusHud::extract);
	}

	/**
	 * Public so tests can check the text without reading pixels. The hull is shown as points out of the most the pod's parts allow:
	 * the client works the maximum out itself, from the synced component state ({@link io.github.pkeppeler.deepcharter.pod.PodComponents#STATE}).
	 */
	public static List<Component> lines(PodEntity pod) {
		return statusLines(pod).stream().map(Line::text).toList();
	}

	/** The pod's own lines: the four of {@link Kind#BASE}, then the slag the lining has and whether the pod is stranded. */
	private static List<Line> statusLines(PodEntity pod) {
		HudLook look = HudLook.current();
		List<Line> lines = new ArrayList<>();
		lines.add(new Line(Component.translatable("hud.deepcharter.pod.hull", Math.round(pod.hull()), Math.round(pod.maxHull())), look.podStatusColor()));
		lines.add(new Line(Component.translatable("hud.deepcharter.pod.fuel", Math.round(pod.fuel())), look.podStatusColor()));
		lines.add(new Line(Component.translatable("hud.deepcharter.pod.cargo", pod.cargoUsed()), look.podStatusColor()));
		lines.add(new Line(Component.translatable("hud.deepcharter.pod.depth", Math.round(pod.getY())), look.podStatusColor()));
		PodLining.State lining = PodLining.of(pod);
		if (PodLining.hasHopper(pod)) {
			lines.add(new Line(Component.translatable("hud.deepcharter.pod.slag", lining.bricks(), lining.spoil()), look.podStatusColor()));
		} else if (lining.bricks() > 0 || PodLiner.tier(pod) > 0) {
			lines.add(new Line(Component.translatable("hud.deepcharter.pod.slag_only", lining.bricks()), look.podStatusColor()));
		}
		if (pod.stranded()) {
			lines.add(new Line(Component.translatable("hud.deepcharter.pod.stranded"), look.podStatusColor()));
		}
		return lines;
	}

	/** The warning line shown, in its own colour, while lava burns the hull. */
	public static Optional<Component> burningLine(PodEntity pod) {
		return pod.hullBurning() ? Optional.of(Component.translatable("hud.deepcharter.pod.burning")) : Optional.empty();
	}

	/**
	 * The warning line shown, in the burning colour, while flowing lava comes toward the pod through open cells ({@link PodLavaWatch}): how many blocks off it is, and
	 * whether it comes down the shaft from above, where a cap of slag brick stops it.
	 */
	public static Optional<Component> lavaNearLine(PodEntity pod) {
		return PodLavaWatch.warning(pod).map(state -> Component.translatable(state.above() ? "hud.deepcharter.pod.lava_above" : "hud.deepcharter.pod.lava_near", state.blocksOff()));
	}

	/**
	 * The lines shown, in their own colour, about the breach crust (#378), from one look at the crust ahead: when the hull would not outlast it, the slabs to it (or that
	 * the pod is in it), the hull, and what the crust rows left would take; then what a breach brace does about that, if the pod has one. {@code resting} is whether the
	 * pod is on the ground with its pilot giving no input.
	 */
	public static List<Component> crustLines(PodEntity pod, boolean resting) {
		PodBrace.Status status = PodBrace.status(pod, resting);
		List<Component> lines = new ArrayList<>();
		status.warning().ifPresent(warning -> lines.add(warning.slabsAway() > 0
				? Component.translatable("hud.deepcharter.pod.crust_ahead", warning.slabsAway(), Math.round(warning.hull()), Math.round(warning.cost()))
				: Component.translatable("hud.deepcharter.pod.crust_in", Math.round(warning.hull()), Math.round(warning.cost()))));
		switch (status.patching()) {
			case IDLE -> {
			}
			case WAITS_FOR_REST -> lines.add(Component.translatable("hud.deepcharter.pod.brace_waits"));
			case NO_ORE -> lines.add(Component.translatable("hud.deepcharter.pod.brace_dry"));
			case NO_CHEAP_ORE -> lines.add(Component.translatable("hud.deepcharter.pod.brace_dear"));
			case WORKING -> lines.add(Component.translatable("hud.deepcharter.pod.brace_working"));
		}
		return lines;
	}

	/** Whether the ridden pod is at rest as the local pilot sees it: on the ground, with no drill, drive or rotor key held. */
	private static boolean resting(Minecraft client, PodEntity pod) {
		return pod.onGround() && PodBrace.idle(client.player.input.keyPresses);
	}

	/** The line shown, in its own colour, while the pilot lines the slab: the bricks placed so far. */
	public static Optional<Component> liningLine(PodEntity pod) {
		PodLining.State lining = PodLining.of(pod);
		return lining.working() ? Optional.of(Component.translatable("hud.deepcharter.pod.lining", lining.used())) : Optional.empty();
	}

	/** The line shown, in its own colour, for a pod with a liner: the slabs it can sink before the next ring. The rack count is in the stock line. */
	public static Optional<Component> linerLine(PodEntity pod) {
		return PodLiner.slabsToNextRing(pod).map(slabs -> Component.translatable("hud.deepcharter.pod.liner", slabs));
	}

	/**
	 * The lines shown, in their own colour, for a pod whose sounder hears a pocket: the slabs down to the nearest pocket of the footprint (or that the
	 * drill is bleeding it), and the sides a sidestep would meet one on.
	 */
	public static List<Component> sounderLines(PodEntity pod) {
		PodSounder.State state = PodSounder.reading(pod).orElse(PodSounder.State.EMPTY);
		List<Component> lines = new ArrayList<>();
		// The drill waits on a pocket in the slab it bores next: the one below, or the one beside in the direction it bores.
		Direction bore = pod.drillDirection();
		boolean bleeding = pod.drilling() && PodSounder.bleedPauseTicks(pod) > 0 && (bore == Direction.DOWN ? state.down() == 1 : state.marks(bore));
		if (bleeding) {
			lines.add(Component.translatable("hud.deepcharter.pod.seepage_bleeding"));
		} else if (state.down() > 0) {
			lines.add(Component.translatable("hud.deepcharter.pod.seepage", state.down()));
		}
		if (state.beside() != 0) {
			MutableComponent sides = Component.empty();
			for (Direction side : Direction.Plane.HORIZONTAL) {
				if (state.marks(side)) {
					sides.append(sides.getSiblings().isEmpty() ? CommonComponents.EMPTY : CommonComponents.SPACE);
					sides.append(Component.translatable("hud.deepcharter.pod.seepage_side." + side.getName()));
				}
			}
			lines.add(Component.translatable("hud.deepcharter.pod.seepage_beside", sides));
		}
		return lines;
	}

	/** The warning line shown, in its own colour, after a lining stopped for want of slag brick. */
	public static Optional<Component> outOfBrickLine(PodEntity pod) {
		return PodLining.of(pod).dry() ? Optional.of(Component.translatable("hud.deepcharter.pod.lining_dry")) : Optional.empty();
	}

	/** The warning line shown, in its own colour, while the pod sinks faster than it can land without damage. */
	public static Optional<Component> hardLandingLine(PodEntity pod) {
		// The pod is moved by the server: the client reads its speed from where it was last tick.
		double sinkSpeed = HardLanding.sinkSpeed(pod.getY() - pod.yo);
		return HardLanding.isHard(PodStats.of(pod), sinkSpeed) ? Optional.of(Component.translatable("hud.deepcharter.pod.hard_landing")) : Optional.empty();
	}

	/** What a line of the readout is: one of the pod's four values, a note, or a warning (a cockpit lights a lamp for a warning). */
	public enum Kind {
		BASE,
		INFO,
		ALERT
	}

	/** The kind of each line of the readout, by its lang key. A line with a key not here fails the readout, so a new line must say what it is. */
	public static final Map<String, Kind> KINDS = Map.ofEntries(
			Map.entry("hud.deepcharter.pod.hull", Kind.BASE), Map.entry("hud.deepcharter.pod.fuel", Kind.BASE), Map.entry("hud.deepcharter.pod.cargo", Kind.BASE),
			Map.entry("hud.deepcharter.pod.depth", Kind.BASE), Map.entry("hud.deepcharter.pod.tally_hull", Kind.BASE), Map.entry("hud.deepcharter.pod.tally_fuel", Kind.BASE),
			Map.entry("hud.deepcharter.pod.tally_cargo", Kind.BASE), Map.entry("hud.deepcharter.pod.tally_depth", Kind.BASE),
			Map.entry("hud.deepcharter.pod.slag", Kind.INFO), Map.entry("hud.deepcharter.pod.slag_only", Kind.INFO), Map.entry("hud.deepcharter.pod.liner", Kind.INFO),
			Map.entry("hud.deepcharter.pod.lining", Kind.INFO), Map.entry("hud.deepcharter.pod.brace_working", Kind.INFO),
			Map.entry("hud.deepcharter.pod.burning", Kind.ALERT), Map.entry("hud.deepcharter.pod.stranded", Kind.ALERT), Map.entry("hud.deepcharter.pod.crust_ahead", Kind.ALERT),
			Map.entry("hud.deepcharter.pod.crust_in", Kind.ALERT), Map.entry("hud.deepcharter.pod.brace_waits", Kind.ALERT), Map.entry("hud.deepcharter.pod.brace_dry", Kind.ALERT),
			Map.entry("hud.deepcharter.pod.brace_dear", Kind.ALERT), Map.entry("hud.deepcharter.pod.seepage", Kind.ALERT), Map.entry("hud.deepcharter.pod.seepage_beside", Kind.ALERT),
			Map.entry("hud.deepcharter.pod.seepage_bleeding", Kind.ALERT), Map.entry("hud.deepcharter.pod.lining_dry", Kind.ALERT),
			Map.entry("hud.deepcharter.pod.hard_landing", Kind.ALERT), Map.entry("hud.deepcharter.pod.lava_near", Kind.ALERT),
			Map.entry("hud.deepcharter.pod.lava_above", Kind.ALERT));

	/** A line of the readout and the colour it is drawn in. */
	public record Line(Component text, int color) {
		/** The kind of a line from its key in {@link #KINDS}; a line of plain text (the account line) is a note. */
		public Kind kind() {
			if (!(text.getContents() instanceof TranslatableContents contents)) {
				return Kind.INFO;
			}
			Kind kind = KINDS.get(contents.getKey());
			if (kind == null) {
				throw new IllegalStateException("The readout line " + contents.getKey() + " has no kind in PodStatusHud.KINDS");
			}
			return kind;
		}
	}

	/** Every line the pilot's readout shows now, in the order it is drawn: the plain HUD's text and the cockpit's plate show the same. */
	public static List<Line> readout(Minecraft client, PodEntity pod) {
		HudLook look = HudLook.current();
		List<Line> readout = new ArrayList<>(statusLines(pod));
		burningLine(pod).ifPresent(line -> readout.add(new Line(line, look.podBurningColor())));
		lavaNearLine(pod).ifPresent(line -> readout.add(new Line(line, look.podBurningColor())));
		linerLine(pod).ifPresent(line -> readout.add(new Line(line, look.podLinerColor())));
		sounderLines(pod).forEach(line -> readout.add(new Line(line, look.podSounderColor())));
		crustLines(pod, resting(client, pod)).forEach(line -> readout.add(new Line(line, look.podHardLandingColor())));
		liningLine(pod).ifPresent(line -> readout.add(new Line(line, look.podLiningColor())));
		outOfBrickLine(pod).ifPresent(line -> readout.add(new Line(line, look.podLiningDryColor())));
		hardLandingLine(pod).ifPresent(line -> readout.add(new Line(line, look.podHardLandingColor())));
		return readout;
	}

	private static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || !(client.player.getVehicle() instanceof PodEntity pod) || !PodSeat.showsPodStatus(pod, client.player)
				|| CockpitHud.seated(client).isPresent()) {
			return;
		}
		Font font = client.font;
		HudLook look = HudLook.current();
		int y = look.podStatusMargin();
		for (Line line : readout(client, pod)) {
			graphics.text(font, line.text(), look.podStatusMargin(), y, line.color());
			y += font.lineHeight + look.podStatusLineGap();
		}
	}
}
