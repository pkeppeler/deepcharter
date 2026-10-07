package io.github.pkeppeler.deepcharter.handbook;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;

/** Registers the handbook item, the {@code deepcharter:directive} criterion, the read-marks attachment, the chapters and the sync. */
public final class HandbookRegistry {
	public static final Identifier HANDBOOK_ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "handbook");

	public static final Item HANDBOOK = registerHandbook();

	public static final DirectiveTrigger DIRECTIVE_TRIGGER = Registry.register(BuiltInRegistries.TRIGGER_TYPES,
			Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "directive"), new DirectiveTrigger());

	/** What one player has read: persistent, kept on death, sent to the player alone. */
	public static final AttachmentType<Versioned<ReadMarks>> READ_MARKS = AttachmentRegistry.create(
			Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "read_marks"),
			builder -> builder
					.persistent(ReadMarks.CODEC)
					.copyOnDeath()
					.initializer(() -> Versioned.of(ReadMarks.DEFAULT))
					.syncWith(Versioned.streamCodec(ReadMarks.VERSION, ReadMarks.STREAM), AttachmentSyncPredicate.targetOnly()));

	private HandbookRegistry() {
	}

	public static void register() {
		HandbookChapters.register();
		ServerLifecycleEvents.SERVER_STARTED.register(HandbookChapters::validate);
		HandbookProgress.register();
		HandbookReadPayload.register();
		HandbookItems.register();
	}

	private static Item registerHandbook() {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, HANDBOOK_ID);
		return Registry.register(BuiltInRegistries.ITEM, key, new HandbookItem(new Item.Properties().setId(key).stacksTo(1)));
	}
}
