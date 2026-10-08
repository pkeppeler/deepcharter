package io.github.pkeppeler.deepcharter.upgrade;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.CharterRefusal;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodTuning;
import io.github.pkeppeler.deepcharter.pod.Serials;
import io.github.pkeppeler.deepcharter.terminal.TerminalAction;
import io.github.pkeppeler.deepcharter.terminal.TerminalActions;
import io.github.pkeppeler.deepcharter.terminal.TerminalFeatures;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.Terminals;

/**
 * The upgrade terminal (SPEC section 7): a charter buys a part for the pod of its own that is parked at the terminal. The part
 * is paid from the charter's account, stamped for the charter, and installed at once; the part it replaces drops from the pod.
 * As in the original, a new hull refills the hull and a new tank refills the tank. {@code PodComponents.install} alone does not:
 * it keeps the litres and the damage, because other callers need that.
 *
 * <p>A pod is parked at the terminal as {@link Terminals#parkedPods} says, and is the player's to use as
 * {@link PodComponents#mayAccess} says. A pod nearer to the terminal wins over one farther away.
 *
 * <p>Terminals have already checked distance, membership and repair when a handler here runs. The args are untrusted: the track
 * and tier are read defensively, and a part above the chassis' cap is allowed (it is shown and applied as capped).
 */
public final class UpgradeTerminal {
	/** The action: buy the part named by {@link #TRACK_KEY} and {@link #TIER_KEY}. */
	public static final Identifier BUY = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "upgrade_buy");
	/** The id of a {@link ComponentTrack}, such as {@code fuel_tank}. */
	public static final String TRACK_KEY = "track";
	/** An int, 1 up to the track's best tier. */
	public static final String TIER_KEY = "tier";

	private static boolean loggedSerialsUnreadable;

	private UpgradeTerminal() {
	}

	static void register() {
		TerminalActions.register(TerminalTypes.UPGRADE_TERMINAL, BUY, UpgradeTerminal::runBuy);
		TerminalFeatures.register(TerminalTypes.UPGRADE_TERMINAL, UpgradeView.STREAM_CODEC, UpgradeTerminal::view);
	}

	private sealed interface Parked {
		record Ours(PodEntity pod) implements Parked {
		}

		record Foreign() implements Parked {
		}

		record None() implements Parked {
		}
	}

	private static Optional<Component> runBuy(TerminalAction.Context context) {
		Optional<ComponentTrack> track = context.args().getString(TRACK_KEY).flatMap(UpgradeTerminal::trackNamed);
		Optional<Integer> tier = context.args().getInt(TIER_KEY);
		if (track.isEmpty() || tier.isEmpty()) {
			return Optional.of(UpgradeRefusal.BAD_REQUEST.message());
		}
		return buy(context.server(), context.player(), context.pos(), track.get(), tier.get());
	}

	private static Optional<ComponentTrack> trackNamed(String id) {
		return Arrays.stream(ComponentTrack.values()).filter(track -> track.id().equals(id)).findFirst();
	}

	/** Empty when done, or why it was refused, with nothing changed. The player must be on a charter. */
	public static Optional<Component> buy(MinecraftServer server, ServerPlayer player, BlockPos terminal, ComponentTrack track, int tier) {
		if (tier < 1 || tier > track.maxTier()) {
			return Optional.of(UpgradeRefusal.BAD_REQUEST.message());
		}
		Charter charter = Charters.charterOf(server, player.getUUID()).orElseThrow();
		PodEntity pod;
		switch (parked(player, charter, terminal)) {
			case Parked.None _ -> {
				return Optional.of(UpgradeRefusal.NO_POD.message());
			}
			case Parked.Foreign _ -> {
				return Optional.of(UpgradeRefusal.NOT_YOUR_POD.message());
			}
			case Parked.Ours ours -> pod = ours.pod();
		}
		if (PodComponents.registration(pod).isEmpty()) {
			// A part there would be void, and the refill would make the purchase a repair.
			return Optional.of(UpgradeRefusal.NOT_REGISTERED.message());
		}
		Optional<PartLabel> held = PodComponents.partOf(pod, track);
		if (held.isPresent() && held.get().charter().equals(charter.id()) && held.get().tier() >= tier) {
			return Optional.of(UpgradeRefusal.NOT_AN_UPGRADE.message());
		}
		// Everything that can throw or refuse comes before the spend, so a charge always buys an install. A serial burnt by a later refusal is harmless.
		if (!Serials.get(server).isReadable()) {
			if (!loggedSerialsUnreadable) {
				loggedSerialsUnreadable = true;
				DeepCharter.LOGGER.error("The saved serials are of a version this build cannot read: the upgrade terminal sells nothing until the world is opened by a build that reads them");
			}
			return Optional.of(UpgradeRefusal.SERIALS_UNREADABLE.message());
		}
		ItemStack part = ComponentItems.mint(server, track, tier, charter.id());
		Optional<CharterRefusal> unpaid = Charters.spend(server, charter.id(), UpgradeTuning.DEFAULT.price(track, tier));
		if (unpaid.isPresent()) {
			return Optional.of(unpaid.get().message());
		}
		Optional<PartLabel> replaced = PodComponents.install(pod, part);
		refill(pod, track);
		replaced.ifPresent(label -> drop(pod, ComponentItems.stackOf(track, label)));
		return Optional.empty();
	}

	/** Why: the original refills on a purchase, while an install alone keeps the litres and the damage. */
	private static void refill(PodEntity pod, ComponentTrack track) {
		switch (track) {
			case HULL -> {
				// A wreck (no hull left) is a wreck for #67 to handle, and a part must not repair it.
				if (pod.hull() > 0f) {
					pod.setHull(pod.maxHull());
				}
			}
			case FUEL_TANK -> pod.setFuel(PodTuning.DEFAULT.shell().fullFuel());
			case DRILL, ENGINE, RADIATOR, CARGO_BAY, SCANNER, LIGHTS -> {
			}
		}
	}

	private static void drop(PodEntity pod, ItemStack stack) {
		pod.level().addFreshEntity(new ItemEntity(pod.level(), pod.getX(), pod.getY() + pod.getBbHeight(), pod.getZ(), stack));
	}

	private static Parked parked(ServerPlayer player, Charter charter, BlockPos terminal) {
		ServerLevel level = player.level();
		List<PodEntity> parked = Terminals.parkedPods(level, terminal);
		Optional<PodEntity> ours = parked.stream().filter(pod -> PodComponents.mayAccess(pod, Optional.of(charter))).findFirst();
		if (ours.isPresent()) {
			return new Parked.Ours(ours.get());
		}
		return parked.isEmpty() ? new Parked.None() : new Parked.Foreign();
	}

	/** The view of the terminal at {@code terminal} for {@code player}, who must be on a charter. Never throws on unreadable pod data. */
	public static UpgradeView view(MinecraftServer server, ServerPlayer player, Optional<Charter> onCharter, BlockPos terminal) {
		Charter charter = onCharter.orElseThrow();
		return switch (parked(player, charter, terminal)) {
			case Parked.None _ -> new UpgradeView(Optional.empty(), false);
			case Parked.Foreign _ -> new UpgradeView(Optional.empty(), true);
			case Parked.Ours ours -> {
				PodEntity pod = ours.pod();
				List<UpgradeView.Slot> slots = new ArrayList<>();
				for (ComponentTrack track : ComponentTrack.values()) {
					slots.add(new UpgradeView.Slot(track, PodComponents.partOf(pod, track).map(PartLabel::tier).orElse(0),
							PodComponents.effectiveTier(pod, track)));
				}
				// An unowned pod is anyone's and has no serial: the screen words that.
				String serial = PodComponents.registration(pod).map(PodComponents.Registration::serial).orElse("");
				yield new UpgradeView(Optional.of(new UpgradeView.Pod(serial, UpgradeTuning.DEFAULT.tierCap(pod.chassis().id()), slots)), false);
			}
		};
	}

}
