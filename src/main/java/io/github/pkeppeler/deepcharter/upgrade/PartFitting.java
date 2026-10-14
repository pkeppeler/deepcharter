package io.github.pkeppeler.deepcharter.upgrade;

import java.util.Locale;
import java.util.Optional;

import net.fabricmc.fabric.api.event.player.UseEntityCallback;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;

import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.Serials;

/**
 * Fits a crafted unit (ADR 0048 for the scanner, ADR 0053 for the rest, see {@link PartUnits}): using one on a pod of the player's own charter
 * installs a tier 1 part of its track, stamped for the charter, as a purchase at the upgrade terminal would, and uses the unit up. A new hull
 * refills the hull and a new tank the tank, as a purchase does. It is an entity-use event, like the fuel and the tow cable, so
 * {@code PodEntity.interact} stays the mount's.
 *
 * <p>Every check comes before the change, so a refused use changes nothing and keeps the unit. A pod that already holds a part of the track for
 * the charter, a bought tier 1 of an old world or a better one, refuses the unit and keeps its part.
 */
public final class PartFitting {
	/** Why a unit would not fit. */
	public enum Refusal {
		/** The saved charters are of a version this build cannot read, so nobody's ownership can be checked. */
		CHARTERS_UNREADABLE,
		/** The player is on no charter, so a part would be stamped for nobody. */
		NO_CHARTER,
		/** The pod has no owner: a part in it would be void. */
		NOT_REGISTERED,
		/** The pod belongs to another charter. */
		NOT_YOUR_POD,
		/** The pod has a part of this track that works for the charter; a unit is tier 1 and replaces nothing. */
		ALREADY_FITTED,
		/** The world's saved serials are of a version this build cannot read, so no part can be numbered. */
		SERIALS_UNREADABLE;

		public Component message(ComponentTrack track) {
			return Component.translatable("message.deepcharter.upgrade.fitting.refusal." + name().toLowerCase(Locale.ROOT),
					Component.translatable("item.deepcharter.part_" + track.id()));
		}
	}

	private PartFitting() {
	}

	static void init() {
		UseEntityCallback.EVENT.register(PartFitting::onUse);
	}

	private static InteractionResult onUse(Player player, Level level, InteractionHand hand, Entity entity, EntityHitResult hit) {
		ItemStack stack = player.getItemInHand(hand);
		Optional<ComponentTrack> unit = PartUnits.trackOf(stack);
		if (!(entity instanceof PodEntity pod) || unit.isEmpty() || player.isSpectator()) {
			return InteractionResult.PASS;
		}
		if (!(player instanceof ServerPlayer serverPlayer)) {
			// The client guesses a hit and the server settles it.
			return InteractionResult.SUCCESS;
		}
		ComponentTrack track = unit.get();
		Optional<Refusal> refusal = check(serverPlayer, pod, track);
		if (refusal.isPresent()) {
			serverPlayer.sendOverlayMessage(refusal.get().message(track));
			return InteractionResult.FAIL;
		}
		// The unit goes first: a listener that throws after the fit must not leave a fitted pod and a kept unit.
		stack.consume(1, player);
		install(serverPlayer, pod, track);
		serverPlayer.sendOverlayMessage(Component.translatable("message.deepcharter.upgrade.fitting.fitted",
				Component.translatable("item.deepcharter.part_" + track.id())));
		return InteractionResult.SUCCESS;
	}

	/** Why a unit of {@code track} would not fit {@code pod} for {@code player}, if it would not. Server only; changes nothing. */
	private static Optional<Refusal> check(ServerPlayer player, PodEntity pod, ComponentTrack track) {
		MinecraftServer server = player.level().getServer();
		if (!Charters.isReadable(server)) {
			return Optional.of(Refusal.CHARTERS_UNREADABLE);
		}
		Optional<Charter> charter = Charters.readableCharterOf(server, player.getUUID());
		if (charter.isEmpty()) {
			return Optional.of(Refusal.NO_CHARTER);
		}
		Optional<PodComponents.Registration> registration = PodComponents.registration(pod);
		if (registration.isEmpty()) {
			return Optional.of(Refusal.NOT_REGISTERED);
		}
		// Stricter than PodComponents.mayAccess on purpose: the part is stamped for the fitter's charter and counts only for the pod's owner, so a pod of a dormant or gone owner (anyone's by mayAccess) must not be fitted by an outsider, who would fit a void part.
		if (!registration.get().owner().equals(charter.get().id())) {
			return Optional.of(Refusal.NOT_YOUR_POD);
		}
		if (PodComponents.partOf(pod, track, charter.get().id()).isPresent()) {
			return Optional.of(Refusal.ALREADY_FITTED);
		}
		if (!Serials.get(server).isReadable()) {
			return Optional.of(Refusal.SERIALS_UNREADABLE);
		}
		return Optional.empty();
	}

	/** Only after {@link #check}. Fires {@link UpgradeEvents#FITTED} after the change. A replaced part (a void one of another charter, say) drops as an item. */
	private static void install(ServerPlayer player, PodEntity pod, ComponentTrack track) {
		MinecraftServer server = player.level().getServer();
		CharterId charter = Charters.charterOfOrThrow(server, player.getUUID()).orElseThrow().id();
		PodComponents.installAndDropReplaced(pod, ComponentItems.mint(server, track, 1, charter));
		UpgradeTerminal.refill(pod, track);
		UpgradeEvents.FITTED.invoker().onFitted(server, player, pod, track, 1);
	}
}
