package io.github.pkeppeler.deepcharter.wreck;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;

import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.pod.PodEvents;

/**
 * Registers the wreck state and wires the wreck into the pod through {@link PodEvents}: it never edits the pod. The
 * state is synced to every client that tracks the pod, so the client can ask {@code PodEvents.isPowered} too.
 */
public final class WreckRegistry {
	/** Persistent and synced. Read it through {@link Wrecks#isWreck}, never raw: it may be {@code Unreadable}. */
	public static final AttachmentType<Versioned<WreckState>> STATE = AttachmentRegistry.create(
			Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "wreck"),
			builder -> builder
					.persistent(Versioned.codec(WreckState.VERSION, WreckState.BODY))
					.initializer(() -> Versioned.of(WreckState.INTACT))
					.syncWith(Versioned.streamCodec(WreckState.VERSION, WreckState.STREAM), AttachmentSyncPredicate.all()));

	private WreckRegistry() {
	}

	public static void register() {
		CrewFate.init();
		PodEvents.HULL_DEPLETED.register(Wrecks::onHullDepleted);
		PodEvents.CAN_MOUNT.register((pod, passenger) -> !Wrecks.isWreck(pod));
		PodEvents.IS_POWERED.register(pod -> !Wrecks.isWreck(pod));
		ServerEntityEvents.ENTITY_LOAD.register(Wrecks::onLoad);
		UseEntityCallback.EVENT.register(Wrecks::onUse);
	}
}
