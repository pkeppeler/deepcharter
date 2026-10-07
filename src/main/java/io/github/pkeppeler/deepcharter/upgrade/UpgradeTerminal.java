package io.github.pkeppeler.deepcharter.upgrade;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.CharterRefusal;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodTuning;
import io.github.pkeppeler.deepcharter.terminal.TerminalAction;
import io.github.pkeppeler.deepcharter.terminal.TerminalActions;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;

/**
 * The upgrade terminal (SPEC section 7): a charter buys a part for the pod of its own that is parked at the terminal. The part
 * is paid from the charter's account, stamped for the charter, and installed at once; the part it replaces drops from the pod.
 * As in the original, a new hull refills the hull and a new tank refills the tank. {@code PodComponents.install} alone does not:
 * it keeps the litres and the damage, because other callers need that.
 *
 * <p>A pod is parked at the terminal when it is within {@link UpgradeTuning#parkedRadius()} blocks of its centre and its owner
 * is the player's charter. A charter-owned pod nearer to the terminal wins over one farther away.
 *
 * <p>Terminals have already checked distance, membership and repair when a handler here runs. The args are untrusted: the track
 * and tier are read defensively, and a part above the chassis' cap is allowed (it is shown and applied as capped).
 */
public final class UpgradeTerminal {
	/** The action: buy the part named by {@link #TRACK_KEY} and {@link #TIER_KEY}. */
	public static final Identifier BUY = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "upgrade_buy");
	/** The action: send the player the {@link UpgradeView} of the terminal. The screen asks for it when it opens. */
	public static final Identifier VIEW = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "upgrade_view");
	/** The id of a {@link ComponentTrack}, such as {@code fuel_tank}. */
	public static final String TRACK_KEY = "track";
	/** An int, 1 up to the track's best tier. */
	public static final String TIER_KEY = "tier";

	private UpgradeTerminal() {
	}

	static void register() {
		TerminalActions.register(TerminalTypes.UPGRADE_TERMINAL, BUY, UpgradeTerminal::runBuy);
		TerminalActions.register(TerminalTypes.UPGRADE_TERMINAL, VIEW, context -> {
			sendView(context.server(), context.player(), context.pos());
			return Optional.empty();
		});
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
		Optional<Component> refusal;
		if (track.isEmpty() || tier.isEmpty()) {
			refusal = Optional.of(UpgradeRefusal.BAD_REQUEST.message());
		} else {
			refusal = buy(context.server(), context.player(), context.pos(), track.get(), tier.get());
		}
		sendView(context.server(), context.player(), context.pos());
		return refusal;
	}

	private static Optional<ComponentTrack> trackNamed(String id) {
		for (ComponentTrack track : ComponentTrack.values()) {
			if (track.id().equals(id)) {
				return Optional.of(track);
			}
		}
		return Optional.empty();
	}

	/**
	 * Buys a part of {@code track} at {@code tier} for the player's charter and installs it in the charter's pod parked at
	 * {@code terminal}. Empty when done, or why it was refused, with nothing changed. The player must be on a charter.
	 */
	public static Optional<Component> buy(MinecraftServer server, ServerPlayer player, BlockPos terminal, ComponentTrack track, int tier) {
		if (tier < 1 || tier > track.maxTier()) {
			return Optional.of(UpgradeRefusal.BAD_REQUEST.message());
		}
		Charter charter = Charters.charterOf(server, player.getUUID()).orElseThrow();
		PodEntity pod;
		switch (parked(player, charter, terminal)) {
			case Parked.None none -> {
				return Optional.of(UpgradeRefusal.NO_POD.message());
			}
			case Parked.Foreign foreign -> {
				return Optional.of(UpgradeRefusal.NOT_YOUR_POD.message());
			}
			case Parked.Ours ours -> pod = ours.pod();
		}
		Optional<PartLabel> held = PodComponents.partOf(pod, track);
		if (held.isPresent() && held.get().charter().equals(charter.id()) && held.get().tier() >= tier) {
			return Optional.of(UpgradeRefusal.NOT_AN_UPGRADE.message());
		}
		Optional<CharterRefusal> unpaid = Charters.spend(server, charter.id(), UpgradeTuning.DEFAULT.price(track, tier));
		if (unpaid.isPresent()) {
			return Optional.of(unpaid.get().message());
		}
		Optional<PartLabel> replaced = PodComponents.install(pod, ComponentItems.mint(server, track, tier, charter.id()));
		refill(pod, track);
		replaced.ifPresent(label -> drop(pod, ComponentItems.stackOf(track, label)));
		return Optional.empty();
	}

	/** The purchase exception to "an install keeps what the pod holds": a new hull is whole and a new tank is full. */
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
		Vec3 centre = Vec3.atCenterOf(terminal);
		double radius = UpgradeTuning.DEFAULT.parkedRadius();
		List<PodEntity> near = new ArrayList<>(level.getEntitiesOfClass(PodEntity.class, new AABB(centre, centre).inflate(radius),
				pod -> pod.position().distanceTo(centre) <= radius));
		near.sort(Comparator.comparingDouble(pod -> pod.position().distanceToSqr(centre)));
		for (PodEntity pod : near) {
			if (mayAccess(player.level().getServer(), pod, charter)) {
				return new Parked.Ours(pod);
			}
		}
		return near.isEmpty() ? new Parked.None() : new Parked.Foreign();
	}

	/**
	 * Mirrors {@code PodComponents.canMount} exactly: a pod with unreadable components is refused to everyone, an unowned pod
	 * is anyone's, a pod whose owner charter is missing or dormant is anyone's, and any other pod is its owner's members'.
	 * TODO switch to PodComponents.mayAccess (#126)
	 */
	private static boolean mayAccess(MinecraftServer server, PodEntity pod, Charter charter) {
		if (pod.getAttached(PodComponents.STATE) instanceof Versioned.Unreadable<PodComponents.State>) {
			return false;
		}
		Optional<PodComponents.Registration> registration = PodComponents.registration(pod);
		if (registration.isEmpty()) {
			return true;
		}
		Optional<Charter> owner = Charters.find(server, registration.get().owner());
		if (owner.isEmpty() || owner.get().dormant()) {
			return true;
		}
		return registration.get().owner().equals(charter.id());
	}

	/** The view of the terminal at {@code terminal} for {@code player}, who must be on a charter. Never throws on unreadable pod data. */
	public static UpgradeView view(MinecraftServer server, ServerPlayer player, BlockPos terminal) {
		Charter charter = Charters.charterOf(server, player.getUUID()).orElseThrow();
		return switch (parked(player, charter, terminal)) {
			case Parked.None none -> new UpgradeView(terminal, Optional.empty(), false);
			case Parked.Foreign foreign -> new UpgradeView(terminal, Optional.empty(), true);
			case Parked.Ours ours -> {
				PodEntity pod = ours.pod();
				List<UpgradeView.Slot> slots = new ArrayList<>();
				for (ComponentTrack track : ComponentTrack.values()) {
					slots.add(new UpgradeView.Slot(track, PodComponents.partOf(pod, track).map(PartLabel::tier).orElse(0),
							PodComponents.effectiveTier(pod, track)));
				}
				// An unowned pod is anyone's and has no serial: the screen words that.
				String serial = PodComponents.registration(pod).map(PodComponents.Registration::serial).orElse("");
				yield new UpgradeView(terminal, Optional.of(new UpgradeView.Pod(serial, UpgradeTuning.DEFAULT.tierCap(pod.chassis().id()), slots)), false);
			}
		};
	}

	private static void sendView(MinecraftServer server, ServerPlayer player, BlockPos terminal) {
		if (ServerPlayNetworking.canSend(player, UpgradeViewPayload.TYPE)) {
			ServerPlayNetworking.send(player, new UpgradeViewPayload(view(server, player, terminal)));
		}
	}
}
