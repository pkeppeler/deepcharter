package io.github.pkeppeler.deepcharter.upgrade;

import java.util.Objects;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import io.github.pkeppeler.deepcharter.charter.CharterId;

/**
 * What a part is stamped with: its tier, the charter it belongs to and its serial number. Parts are company property
 * (SPEC section 6): a pod ignores a part stamped with another charter's id.
 */
public record PartLabel(int tier, CharterId charter, String serial) {
	/** Invalid saved data decodes to an error, never to an exception: a versioned attachment's decode must not throw. */
	public static final Codec<PartLabel> CODEC = RecordCodecBuilder.<Fields>create(instance -> instance.group(
			Codec.INT.fieldOf("tier").forGetter(Fields::tier),
			CharterId.CODEC.fieldOf("charter").forGetter(Fields::charter),
			Codec.STRING.fieldOf("serial").forGetter(Fields::serial)).apply(instance, Fields::new))
			.flatXmap(PartLabel::fromFields, label -> DataResult.success(new Fields(label.tier, label.charter, label.serial)));
	public static final StreamCodec<ByteBuf, PartLabel> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, PartLabel::tier,
			CharterId.STREAM_CODEC, PartLabel::charter,
			ByteBufCodecs.STRING_UTF8, PartLabel::serial,
			PartLabel::new);

	public PartLabel {
		Objects.requireNonNull(charter, "charter");
		String problem = problem(tier, serial);
		if (problem != null) {
			throw new IllegalArgumentException(problem);
		}
	}

	private static String problem(int tier, String serial) {
		if (tier < 1) {
			return "a part label has tier 1 or more, got " + tier;
		}
		return serial.isBlank() ? "a part label needs a serial" : null;
	}

	private static DataResult<PartLabel> fromFields(Fields fields) {
		String problem = problem(fields.tier, fields.serial);
		return problem == null ? DataResult.success(new PartLabel(fields.tier, fields.charter, fields.serial)) : DataResult.error(() -> problem);
	}

	private record Fields(int tier, CharterId charter, String serial) {
	}
}
