package io.github.pkeppeler.deepcharter.pod;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.DeepCharter;

/**
 * The pattern for new pod state: a Fabric data attachment, never a {@link PodData} field. Copy this
 * file's shape into your own feature package (a feature's attachments live in that feature, not
 * here) and register it from your {@code XRegistry}.
 *
 * <ul>
 *   <li>The value is an immutable record. Change it with {@code pod.modifyAttached(TYPE, v -> v.withX(...))}.</li>
 *   <li>It has an {@code int version} field, written to disk and to the wire. The codec refuses a
 *       version it does not know, so a world from a newer build fails loudly instead of loading wrong.
 *       To change the shape, bump the version and make the codec read the old one too.</li>
 *   <li>{@code persistent} saves it with the pod. That is also what carries it across a breach
 *       crossing: the arriving pod is a new entity that vanilla fills from the old one's saved data,
 *       so no copy code is needed ({@code copyOnDeath} is for players respawning and is not used).</li>
 *   <li>{@code syncWith} sends it to the clients that track the pod. Use
 *       {@link AttachmentSyncPredicate#targetOnly()} for state only a rider's own client needs.</li>
 * </ul>
 */
public final class PodAttachments {
	/** A throwaway attachment, there to show the pattern and to be tested. It is not read by any feature. */
	public record Example(int version, int counter) {
		public static final int CURRENT_VERSION = 1;
		public static final Example DEFAULT = new Example(CURRENT_VERSION, 0);

		public static final Codec<Example> CODEC = RecordCodecBuilder.<Example>create(instance -> instance.group(
				Codec.INT.fieldOf("version").forGetter(Example::version),
				Codec.INT.fieldOf("counter").forGetter(Example::counter)).apply(instance, Example::new))
				.validate(Example::checkVersion);

		public static final StreamCodec<ByteBuf, Example> STREAM_CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, Example::version,
				ByteBufCodecs.VAR_INT, Example::counter,
				Example::new).map(Example::requireVersion, example -> example);

		public Example withCounter(int counter) {
			return new Example(version, counter);
		}

		private DataResult<Example> checkVersion() {
			return version == CURRENT_VERSION
					? DataResult.success(this)
					: DataResult.error(() -> "unsupported pod example attachment version " + version + ", this build reads " + CURRENT_VERSION);
		}

		private static Example requireVersion(Example example) {
			return example.checkVersion().getOrThrow();
		}
	}

	public static final AttachmentType<Example> EXAMPLE = AttachmentRegistry.create(
			Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "pod_example"),
			builder -> builder
					.persistent(Example.CODEC)
					.initializer(() -> Example.DEFAULT)
					.syncWith(Example.STREAM_CODEC, AttachmentSyncPredicate.all()));

	private PodAttachments() {
	}

	/** Loads the class, which registers the attachment type at startup. */
	static void init() {
	}
}
