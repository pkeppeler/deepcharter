package io.github.pkeppeler.deepcharter.wreck;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.pod.PodCargo;
import io.github.pkeppeler.deepcharter.pod.PodEntity;

/**
 * A pod whose hull reached 0 is a wreck (#67): unpowered (so dark, with no light for #75 to give it), not mountable,
 * never removed, and its cargo stays in the bay for anyone to {@link #salvage}. The state is the pod's
 * {@link WreckRegistry#STATE} attachment; {@link #restore} is the hook the hangar (#77) uses to repair one.
 *
 * <p>Saved wreck state this build cannot read is never guessed at and never overwritten: {@link #isWreck} says no, the
 * event and load paths log an error and carry on, and only {@link #restore} throws.
 */
public final class Wrecks {
	private Wrecks() {
	}

	/** True when the pod is a wreck. Safe on either side; the client sees the synced state. Unreadable state is not a wreck. */
	public static boolean isWreck(PodEntity pod) {
		return pod.getAttached(WreckRegistry.STATE) instanceof Versioned.Readable<WreckState> readable && readable.value().wrecked();
	}

	/**
	 * Turns a wreck back into a working pod with {@code hull} points of hull, which must be above 0.
	 *
	 * @throws IllegalArgumentException if {@code hull} is not above 0
	 * @throws IllegalStateException    if the pod is not a wreck, or its saved wreck state is unreadable
	 */
	public static void restore(PodEntity pod, float hull) {
		if (!(hull > 0f)) {
			throw new IllegalArgumentException("a restored pod needs hull above 0, got " + hull);
		}
		if (!Versioned.require(pod, WreckRegistry.STATE).wrecked()) {
			throw new IllegalStateException("pod " + pod.getUUID() + " is not a wreck");
		}
		Versioned.modify(pod, WreckRegistry.STATE, state -> WreckState.INTACT);
		pod.setHull(hull);
	}

	/**
	 * Server only: moves every ore in the wreck's bay to {@code player}, dropping what does not fit. Anyone may do this.
	 * Tells the player what happened, and returns how many ore moved. Unreadable cargo is left alone and reported, not thrown.
	 */
	public static int salvage(ServerPlayer player, PodEntity pod) {
		if (!isWreck(pod)) {
			throw new IllegalStateException("pod " + pod.getUUID() + " is not a wreck");
		}
		PodCargo cargo = pod.cargo();
		if (!cargo.isReadable()) {
			player.sendSystemMessage(Component.translatable("deepcharter.wreck.salvage.unreadable"));
			return 0;
		}
		List<PodCargo.Entry> ore = cargo.entries();
		if (ore.isEmpty()) {
			player.sendSystemMessage(Component.translatable("deepcharter.wreck.salvage.empty"));
			return 0;
		}
		cargo.dump(pod);
		for (PodCargo.Entry entry : ore) {
			ItemStack stack = entry.stack().copy();
			if (!player.getInventory().add(stack)) {
				player.spawnAtLocation(player.level(), stack);
			}
		}
		player.sendSystemMessage(Component.translatable("deepcharter.wreck.salvage.success", ore.size()));
		return ore.size();
	}

	static void onHullDepleted(PodEntity pod) {
		if (!isReadable(pod)) {
			DeepCharter.LOGGER.error("Pod {} ran out of hull but its saved wreck state is unreadable: it is not made a wreck, and the state is kept",
					pod.getUUID());
			return;
		}
		Versioned.modify(pod, WreckRegistry.STATE, state -> WreckState.WRECKED);
		ServerLevel level = (ServerLevel) pod.level();
		List<Entity> crew = List.copyOf(pod.getPassengers());
		Map<CharterId, Charter> charters = chartersOf(level.getServer(), crew);
		pod.ejectPassengers();
		crew.forEach(member -> CrewFate.die(member, level));
		for (Charter charter : charters.values()) {
			try {
				report(level, charter, pod);
			} catch (RuntimeException e) {
				DeepCharter.LOGGER.error("Could not report the wreck of pod {} to charter {}: the other charters are still told", pod.getUUID(), charter.id(), e);
			}
		}
	}

	/** A pod that comes into the world at hull 0 (saved before wrecks, or by {@code setHull} elsewhere) is a wreck too. */
	static void onLoad(Entity entity, ServerLevel level) {
		if (!(entity instanceof PodEntity pod) || pod.hull() > 0f || isWreck(pod)) {
			return;
		}
		if (!isReadable(pod)) {
			DeepCharter.LOGGER.error("Pod {} has no hull but its saved wreck state is unreadable: it is not made a wreck, and the state is kept",
					pod.getUUID());
			return;
		}
		DeepCharter.LOGGER.warn("Pod {} loaded with hull {} and is now a wreck (a corrupt saved hull loads as 0)", pod.getUUID(), pod.hull());
		Versioned.modify(pod, WreckRegistry.STATE, state -> WreckState.WRECKED);
	}

	/** Using a wreck, without sneaking, salvages its cargo. Sneaking still looks into the bay, and a working pod is not touched. */
	static InteractionResult onUse(Player player, Level level, InteractionHand hand, Entity entity, EntityHitResult hit) {
		if (!(entity instanceof PodEntity pod) || !isWreck(pod) || hand != InteractionHand.MAIN_HAND
				|| player.isSecondaryUseActive() || player.isSpectator()) {
			return InteractionResult.PASS;
		}
		if (player instanceof ServerPlayer serverPlayer) {
			salvage(serverPlayer, pod);
		}
		return InteractionResult.SUCCESS;
	}

	/** False only for saved state of a version this build cannot read. A pod that never had the state is readable. */
	private static boolean isReadable(PodEntity pod) {
		return !(pod.getAttached(WreckRegistry.STATE) instanceof Versioned.Unreadable<WreckState>);
	}

	/** The charter of each player in the crew, once each. */
	private static Map<CharterId, Charter> chartersOf(MinecraftServer server, List<Entity> crew) {
		Map<CharterId, Charter> charters = new LinkedHashMap<>();
		for (Entity member : crew) {
			Charters.charterOf(server, member.getUUID()).ifPresent(charter -> charters.putIfAbsent(charter.id(), charter));
		}
		return charters;
	}

	/** Tells the charter's online members where the pod went down, then fires {@link WreckEvents#REPORTED}. */
	private static void report(ServerLevel level, Charter charter, PodEntity pod) {
		MinecraftServer server = level.getServer();
		BlockPos pos = pod.blockPosition();
		OptionalInt layer = LayerChain.layerOf(level.dimensionTypeRegistration().unwrapKey().orElseThrow().identifier());
		Component message = layer.isPresent()
				? Component.translatable("deepcharter.wreck.report.layer", layer.getAsInt(), pos.getX(), pos.getY(), pos.getZ())
				: Component.translatable("deepcharter.wreck.report.surface", pos.getX(), pos.getY(), pos.getZ());
		List<ServerPlayer> online = new ArrayList<>();
		for (var member : charter.roster()) {
			ServerPlayer player = server.getPlayerList().getPlayer(member);
			if (player != null) {
				online.add(player);
			}
		}
		online.forEach(player -> player.sendSystemMessage(message));
		WreckEvents.REPORTED.invoker().onReported(server, charter, pod, layer, pos);
	}
}
