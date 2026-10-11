package io.github.pkeppeler.deepcharter.scanner;

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
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.Serials;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.github.pkeppeler.deepcharter.upgrade.PartLabel;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeEvents;

/**
 * Fits the crafted tier 1 scanner (ADR 0048): using a {@link ScannerRegistry#SCANNER_UNIT} on a pod of the player's own charter installs
 * a tier 1 scanner part stamped for the charter, as a purchase at the upgrade terminal would, and uses the unit up. It is an entity-use
 * event, like the fuel and the tow cable, so {@code PodEntity.interact} stays the mount's.
 *
 * <p>Every check comes before the change, so a refused use changes nothing and keeps the unit.
 */
public final class ScannerFitting {
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
		/** The pod has a scanner that works for the charter; a unit is tier 1 and replaces nothing. */
		ALREADY_FITTED,
		/** The world's saved serials are of a version this build cannot read, so no part can be numbered. */
		SERIALS_UNREADABLE;

		public Component message() {
			return Component.translatable("message.deepcharter.scanner.refusal." + name().toLowerCase(Locale.ROOT));
		}
	}

	private ScannerFitting() {
	}

	public static void init() {
		UseEntityCallback.EVENT.register(ScannerFitting::onUse);
	}

	private static InteractionResult onUse(Player player, Level level, InteractionHand hand, Entity entity, EntityHitResult hit) {
		ItemStack stack = player.getItemInHand(hand);
		if (!(entity instanceof PodEntity pod) || !stack.is(ScannerRegistry.SCANNER_UNIT) || player.isSpectator()) {
			return InteractionResult.PASS;
		}
		if (!(player instanceof ServerPlayer serverPlayer)) {
			// The client guesses a hit and the server settles it.
			return InteractionResult.SUCCESS;
		}
		Optional<Refusal> refusal = fit(serverPlayer, pod);
		if (refusal.isPresent()) {
			serverPlayer.sendOverlayMessage(refusal.get().message());
			return InteractionResult.FAIL;
		}
		stack.consume(1, player);
		serverPlayer.sendOverlayMessage(Component.translatable("message.deepcharter.scanner.fitted"));
		return InteractionResult.SUCCESS;
	}

	/**
	 * Fits a tier 1 scanner to {@code pod} for {@code player}'s charter, and says why it did not. Server only. The unit itself is the
	 * caller's to take. Fires {@link UpgradeEvents#FITTED} after the change.
	 */
	public static Optional<Refusal> fit(ServerPlayer player, PodEntity pod) {
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
		if (!registration.get().owner().equals(charter.get().id())) {
			return Optional.of(Refusal.NOT_YOUR_POD);
		}
		Optional<PartLabel> held = PodComponents.partOf(pod, ComponentTrack.SCANNER);
		if (held.isPresent() && held.get().charter().equals(charter.get().id())) {
			return Optional.of(Refusal.ALREADY_FITTED);
		}
		if (!Serials.get(server).isReadable()) {
			return Optional.of(Refusal.SERIALS_UNREADABLE);
		}
		PodComponents.install(pod, ComponentItems.mint(server, ComponentTrack.SCANNER, 1, charter.get().id()));
		UpgradeEvents.FITTED.invoker().onFitted(server, player, pod, ComponentTrack.SCANNER, 1);
		return Optional.empty();
	}
}
