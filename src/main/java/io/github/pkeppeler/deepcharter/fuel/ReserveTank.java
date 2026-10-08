package io.github.pkeppeler.deepcharter.fuel;

import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;

import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.pod.PodTuning;

/**
 * A second tank on a pod (SPEC: stranded pods wait for "rescue or a reserve tank"). It is a separate attachment, not a part on the
 * fuel tank track: a part replaces the one on its track, and a reserve is fitted besides the tank part and is not charter-stamped
 * (ADR 0019).
 *
 * <p>A pod with a reserve has {@link FuelTuning#reserveLitres()} more litres of capacity. The listener adds them after the tank part
 * scales the tank, so the reserve is always exactly that many litres whatever tier the tank is. Fitting one keeps the litres in the
 * tank (the stored percent is rescaled, ADR 0010), and a stranded pod gets the reserve's litres as well, so Stranded clears and
 * stays cleared. A pod takes one reserve.
 *
 * <p>The stats listener runs on every tick and on the client, so it never throws on an unreadable state: it logs once for each
 * pod and reads it as no reserve. {@link #install} is an explicit change and does throw.
 */
public final class ReserveTank {
	public static final int VERSION = 1;

	/** Whether the pod has a reserve tank fitted. */
	public record State(boolean installed) {
		public static final State EMPTY = new State(false);
		public static final MapCodec<State> BODY = RecordCodecBuilder.mapCodec(instance -> instance.group(
				Codec.BOOL.fieldOf("installed").forGetter(State::installed)).apply(instance, State::new));
		public static final StreamCodec<ByteBuf, State> STREAM = ByteBufCodecs.BOOL.map(State::new, State::installed);
	}

	public static final AttachmentType<Versioned<State>> STATE = AttachmentRegistry.create(
			Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "reserve_tank"),
			builder -> builder
					.persistent(Versioned.codec(VERSION, State.BODY))
					.initializer(() -> Versioned.of(State.EMPTY))
					.syncWith(Versioned.streamCodec(VERSION, State.STREAM), AttachmentSyncPredicate.all()));

	private ReserveTank() {
	}

	static void init() {
		// The default phase runs after BASE, where the tank part scales the tank, so the reserve adds its litres on top of that.
		PodStats.MODIFY.register((pod, stats) -> isInstalled(pod) ? stats.withTankLitres(stats.tankLitres() + FuelTuning.DEFAULT.reserveLitres()) : stats);
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
				entity instanceof PodEntity pod ? fit(player, hand, pod) : InteractionResult.PASS);
	}

	/** True when the pod has a reserve tank. An unreadable state reads as none (logged once for the pod). Never throws. */
	public static boolean isInstalled(PodEntity pod) {
		return Versioned.readable(pod, STATE).map(State::installed).orElse(false);
	}

	/**
	 * Server only: fits a reserve tank. Returns false, and changes nothing, when the pod has one already. Throws on an unreadable
	 * state, naming the attachment.
	 */
	public static boolean install(PodEntity pod) {
		if (pod.level().isClientSide()) {
			throw new IllegalStateException("a reserve tank is fitted on the server only");
		}
		if (Versioned.orThrow(pod, STATE).installed()) {
			return false;
		}
		PodStats before = PodStats.of(pod);
		Versioned.modifyOrThrow(pod, STATE, state -> new State(true));
		PodStats after = PodStats.of(pod);
		PodComponents.rescaleFuel(pod, before, after);
		if (pod.stranded()) {
			// Stranded means dry. The reserve is full, so the pod leaves with its litres.
			float full = PodTuning.DEFAULT.shell().fullFuel();
			pod.setFuel(Math.min(full, pod.fuel() + FuelTuning.DEFAULT.reserveLitres() / after.tankLitres() * full));
			pod.setStranded(false);
		}
		return true;
	}

	private static InteractionResult fit(Player player, InteractionHand hand, PodEntity pod) {
		ItemStack stack = player.getItemInHand(hand);
		if (!stack.is(FuelRegistry.RESERVE_TANK)) {
			return InteractionResult.PASS;
		}
		if (!(player instanceof ServerPlayer serverPlayer)) {
			// The attachment is synced, so a client can tell a pod that has a reserve already.
			return isInstalled(pod) ? InteractionResult.PASS : InteractionResult.SUCCESS;
		}
		Optional<Component> refusal = refusal(serverPlayer, pod);
		if (refusal.isPresent()) {
			serverPlayer.sendOverlayMessage(refusal.get());
			return InteractionResult.FAIL;
		}
		install(pod);
		stack.consume(1, player);
		return InteractionResult.SUCCESS;
	}

	/** Why the player cannot fit a reserve tank to the pod, or empty. Never throws. */
	private static Optional<Component> refusal(ServerPlayer player, PodEntity pod) {
		MinecraftServer server = player.level().getServer();
		if (Versioned.readable(pod, STATE).isEmpty()) {
			return Optional.of(Component.translatable("message.deepcharter.fuel.reserve_unreadable"));
		}
		if (isInstalled(pod)) {
			return Optional.of(Component.translatable("message.deepcharter.fuel.reserve_fitted"));
		}
		if (!Charters.isReadable(server)) {
			return Optional.of(Component.translatable("message.deepcharter.fuel.reserve_unreadable"));
		}
		boolean allowed = PodComponents.mayAccess(pod, Charters.readableCharterOf(server, player.getUUID()));
		return allowed ? Optional.empty() : Optional.of(Component.translatable("message.deepcharter.fuel.reserve_not_yours"));
	}
}
