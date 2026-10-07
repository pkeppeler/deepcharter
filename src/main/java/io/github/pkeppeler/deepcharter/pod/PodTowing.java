package io.github.pkeppeler.deepcharter.pod;

import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;

/** The tow cable between pods. Filled by #76. */
public final class PodTowing {
	public static final int VERSION = 1;

	/** Whose cable is on this pod. */
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

	/** Why a cable cannot be fitted. */
	public enum Refusal {
		NOT_RIDING, SAME_POD, TOO_FAR, ALREADY_TOWED, TOWER_IS_TOWED, TOWED_TOWS, ALREADY_TOWING, UNREADABLE;

		public Component message() {
			throw new UnsupportedOperationException("not implemented");
		}
	}

	private PodTowing() {
	}

	public static void init() {
	}

	public static Optional<UUID> towerId(PodEntity pod) {
		throw new UnsupportedOperationException("not implemented");
	}

	public static boolean isTowed(PodEntity pod) {
		throw new UnsupportedOperationException("not implemented");
	}

	public static Optional<Refusal> refusal(PodEntity tower, PodEntity towed) {
		throw new UnsupportedOperationException("not implemented");
	}

	public static void attach(PodEntity tower, PodEntity towed) {
		throw new UnsupportedOperationException("not implemented");
	}

	public static boolean detach(PodEntity towed) {
		throw new UnsupportedOperationException("not implemented");
	}
}
