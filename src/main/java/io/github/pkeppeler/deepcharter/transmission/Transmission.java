package io.github.pkeppeler.deepcharter.transmission;

import java.util.Objects;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;

import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.Zones;

/**
 * One transmission, as the data file describes it: who sends it, how the signal is framed, what makes it fire, and what
 * else comes with it. The text is not here: it lives in the lang fragment, under {@link #bodyKey()}.
 *
 * @param sender the sender's name, a key under {@code deepcharter.transmission.sender.}
 * @param replay true for a repair transmission: a charter founded after it first fired is sent it too
 * @param bonus  the employer bonus credited once to the charter that fires it live, never on a replay
 */
public record Transmission(Identifier id, String sender, Framing framing, Trigger trigger, Optional<Bonus> bonus, boolean replay) {
	public static final Codec<Transmission> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Identifier.CODEC.fieldOf("id").forGetter(Transmission::id),
			Codec.STRING.fieldOf("sender").forGetter(Transmission::sender),
			Framing.CODEC.fieldOf("framing").forGetter(Transmission::framing),
			Trigger.CODEC.fieldOf("trigger").forGetter(Transmission::trigger),
			Bonus.CODEC.optionalFieldOf("bonus").forGetter(Transmission::bonus),
			Codec.BOOL.optionalFieldOf("replay", false).forGetter(Transmission::replay)).apply(instance, Transmission::new));

	public Transmission {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(sender, "sender");
		Objects.requireNonNull(framing, "framing");
		Objects.requireNonNull(trigger, "trigger");
		Objects.requireNonNull(bonus, "bonus");
		if (sender.isBlank()) {
			throw new IllegalArgumentException("transmission " + id + " has no sender");
		}
	}

	/** The lang key of the sender's display name. */
	public String senderKey() {
		return "deepcharter.transmission.sender." + sender;
	}

	/** The lang key of the text. It may hold the fields {@code [CHARTER]} and {@code [DIRECTOR]}. */
	public String bodyKey() {
		return "deepcharter.transmission." + id.getPath() + ".body";
	}

	/** How the signal reaches the charter. This sets the header's wording and colour, and the sound. */
	public enum Framing implements StringRepresentable {
		/** The sender is speaking now. */
		LIVE("live"),
		/** A recording or message passed on by something else. */
		RELAY("relay"),
		/** Nobody can say where it comes from. */
		UNKNOWN("unknown");

		public static final Codec<Framing> CODEC = StringRepresentable.fromEnum(Framing::values);

		private final String serializedName;

		Framing(String serializedName) {
			this.serializedName = serializedName;
		}

		@Override
		public String getSerializedName() {
			return serializedName;
		}

		/** The lang key of the header line's wording; it takes the sender's name. */
		public String headerKey() {
			return "deepcharter.transmission.framing." + serializedName;
		}
	}

	/** The employer bonuses. A bonus is credited once, when its transmission first fires for a charter. */
	public enum Bonus implements StringRepresentable {
		B1("b1"),
		B2("b2"),
		B3("b3");

		public static final Codec<Bonus> CODEC = StringRepresentable.fromEnum(Bonus::values);

		private final String serializedName;

		Bonus(String serializedName) {
			this.serializedName = serializedName;
		}

		@Override
		public String getSerializedName() {
			return serializedName;
		}

		/** The amount, in dollars, from {@link TransmissionTuning}. */
		public long amount() {
			TransmissionTuning tuning = TransmissionTuning.DEFAULT;
			return switch (this) {
				case B1 -> tuning.bonusB1();
				case B2 -> tuning.bonusB2();
				case B3 -> tuning.bonusB3();
			};
		}
	}

	/** What makes a transmission fire. */
	public sealed interface Trigger {
		Codec<Trigger> CODEC = Kind.CODEC.dispatch("type", Trigger::kind, Kind::codec);

		Kind kind();

		/** Another feature calls {@link Transmissions#fire}. */
		record Event() implements Trigger {
			@Override
			public Kind kind() {
				return Kind.EVENT;
			}
		}

		/** A charter member stands in the zone: {@code zone} is 0 for the top third of the layer, 2 for the bottom. */
		record Zone(int layer, int zone) implements Trigger {
			public Zone {
				if (layer < 1 || zone < 0 || zone >= Zones.COUNT) {
					throw new IllegalArgumentException("no such zone: layer " + layer + ", zone " + zone);
				}
			}

			@Override
			public Kind kind() {
				return Kind.ZONE;
			}
		}

		/**
		 * A charter member crosses a breach into {@code toLayer}: a descent into layer 2 or deeper, or the climb out into the surface,
		 * which is layer 0. Any other ascent fires nothing.
		 */
		record Breach(int toLayer) implements Trigger {
			public Breach {
				if (toLayer != LayerChain.SURFACE && toLayer < 2) {
					throw new IllegalArgumentException("a breach leads into the surface or into layer 2 or deeper, got " + toLayer);
				}
			}

			@Override
			public Kind kind() {
				return Kind.BREACH;
			}
		}

		enum Kind implements StringRepresentable {
			EVENT("event", MapCodec.unit(new Event())),
			ZONE("zone", RecordCodecBuilder.<Zone>mapCodec(instance -> instance.group(
					Codec.INT.fieldOf("layer").forGetter(Zone::layer),
					Codec.INT.fieldOf("zone").forGetter(Zone::zone)).apply(instance, Zone::new))),
			BREACH("breach", RecordCodecBuilder.<Breach>mapCodec(instance -> instance.group(
					Codec.INT.fieldOf("to_layer").forGetter(Breach::toLayer)).apply(instance, Breach::new)));

			static final Codec<Kind> CODEC = StringRepresentable.fromEnum(Kind::values);

			private final String serializedName;
			private final MapCodec<? extends Trigger> codec;

			Kind(String serializedName, MapCodec<? extends Trigger> codec) {
				this.serializedName = serializedName;
				this.codec = codec;
			}

			MapCodec<? extends Trigger> codec() {
				return codec;
			}

			@Override
			public String getSerializedName() {
				return serializedName;
			}
		}
	}
}
