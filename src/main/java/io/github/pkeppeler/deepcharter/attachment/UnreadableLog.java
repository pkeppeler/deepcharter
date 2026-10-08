package io.github.pkeppeler.deepcharter.attachment;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;

import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;

import io.github.pkeppeler.deepcharter.DeepCharter;

/** The log-once of {@link Versioned#readable}: one error for each owner and attachment, not one for each tick. */
final class UnreadableLog {
	/** Held weakly by owner, so a pod that unloads takes its entry with it. */
	private static final Map<AttachmentTarget, Set<Identifier>> LOGGED = Collections.synchronizedMap(new WeakHashMap<>());

	private UnreadableLog() {
	}

	static <T> void once(AttachmentTarget owner, AttachmentType<Versioned<T>> type, Versioned.Unreadable<T> unreadable) {
		if (!LOGGED.computeIfAbsent(owner, ignored -> ConcurrentHashMap.newKeySet()).add(type.identifier())) {
			return;
		}
		Object name = owner instanceof Entity entity ? entity.getUUID() : owner;
		DeepCharter.LOGGER.error("{} has its {} saved as version {}, which this build cannot read: it is skipped and the saved data is kept",
				name, type.identifier(), unreadable.version());
	}
}
