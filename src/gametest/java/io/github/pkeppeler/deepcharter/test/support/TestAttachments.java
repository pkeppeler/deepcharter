package io.github.pkeppeler.deepcharter.test.support;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.attachment.Versioned;

/**
 * Two throwaway versioned attachments, registered by the test mod only, that show the pattern for
 * M2 features: a record with no version field, a body {@link MapCodec}, and a
 * {@code Versioned<T>} attachment type. The real mod registers none of these ids.
 */
public final class TestAttachments implements ModInitializer {
	public static final int VERSION = 1;

	public record Example(int counter) {
		public static final Example DEFAULT = new Example(0);
		public static final MapCodec<Example> BODY = RecordCodecBuilder.mapCodec(instance -> instance.group(
				com.mojang.serialization.Codec.INT.fieldOf("counter").forGetter(Example::counter)).apply(instance, Example::new));
		public static final StreamCodec<ByteBuf, Example> STREAM = ByteBufCodecs.VAR_INT.map(Example::new, Example::counter);
	}

	public record Other(String text) {
		public static final Other DEFAULT = new Other("");
		public static final MapCodec<Other> BODY = RecordCodecBuilder.mapCodec(instance -> instance.group(
				com.mojang.serialization.Codec.STRING.fieldOf("text").forGetter(Other::text)).apply(instance, Other::new));
	}

	/** Persistent and synced to every client that tracks the pod. */
	public static final AttachmentType<Versioned<Example>> EXAMPLE = AttachmentRegistry.create(
			Identifier.fromNamespaceAndPath("deepcharter_test", "pod_example"),
			builder -> builder
					.persistent(Versioned.codec(VERSION, Example.BODY))
					.initializer(() -> Versioned.of(Example.DEFAULT))
					.syncWith(Versioned.streamCodec(VERSION, Example.STREAM), AttachmentSyncPredicate.all()));

	/** Persistent only: a second feature's attachment, to show one bad entry costs the others nothing. */
	public static final AttachmentType<Versioned<Other>> OTHER = AttachmentRegistry.create(
			Identifier.fromNamespaceAndPath("deepcharter_test", "pod_other"),
			builder -> builder
					.persistent(Versioned.codec(VERSION, Other.BODY))
					.initializer(() -> Versioned.of(Other.DEFAULT)));

	/** Loading this class registers the types; the entrypoint makes sure that happens at startup. */
	@Override
	public void onInitialize() {
	}
}
