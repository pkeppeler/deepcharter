package io.github.pkeppeler.deepcharter.transmission;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.Zones;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;

/**
 * One transmission, as the data file describes it: who sends it, how the signal is framed, what makes it fire, and what
 * else comes with it. The text is not here: it lives in the lang fragment, under {@link #bodyKey()}.
 *
 * @param sender the sender's name, a key under {@code deepcharter.transmission.sender.}; for a relay, the serial of the pod whose
 *               beacon repeats it
 * @param loop   for a relay, and only for one, how many times it had looped when the colony was settled again: the header's
 *               loop counter starts there and gains one each day of the world ({@link #loopOn})
 * @param replay true for a repair transmission: a charter founded after it first fired is sent it too
 * @param bonus  the employer bonus credited once to the charter that fires it live, never on a replay
 */
public record Transmission(
        Identifier id,
        String sender,
        Framing framing,
        Trigger trigger,
        Optional<Integer> loop,
        Optional<Bonus> bonus,
        boolean replay) {
    public static final Codec<Transmission> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    Identifier.CODEC.fieldOf("id").forGetter(Transmission::id),
                    Codec.STRING.fieldOf("sender").forGetter(Transmission::sender),
                    Framing.CODEC.fieldOf("framing").forGetter(Transmission::framing),
                    Trigger.CODEC.fieldOf("trigger").forGetter(Transmission::trigger),
                    Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("loop").forGetter(Transmission::loop),
                    Bonus.CODEC.optionalFieldOf("bonus").forGetter(Transmission::bonus),
                    Codec.BOOL.optionalFieldOf("replay", false).forGetter(Transmission::replay))
            .apply(instance, Transmission::new));

    public Transmission {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(framing, "framing");
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(loop, "loop");
        Objects.requireNonNull(bonus, "bonus");
        if (sender.isBlank()) {
            throw new IllegalArgumentException("transmission " + id + " has no sender");
        }
        if (loop.isPresent() != (framing == Framing.RELAY)) {
            throw new IllegalArgumentException("transmission " + id + " is framed " + framing.getSerializedName()
                    + ": a relay has a loop counter, and nothing else has one");
        }
    }

    /** The loop counter of a relay heard on {@code day}, counted from 0, the world's first day: one loop a day. */
    public long loopOn(long day) {
        return loop.orElseThrow(() -> new IllegalStateException(id + " is not a relay")) + day;
    }

    /** The lang key of the sender's display name. */
    public String senderKey() {
        return "deepcharter.transmission.sender." + sender;
    }

    /** The lang key of the text, without the framing's lines. It may hold the fields {@code [CHARTER]} and {@code [DIRECTOR]}. */
    public String bodyKey() {
        return "deepcharter.transmission." + id.getPath() + ".body";
    }

    /**
     * The lang key of when the transmission came, as the comms log shows it (#1615): the zone or breach of its trigger, which takes the
     * layer as its one argument ({@link #whenLayer}), or for an event its own line, since only the transmission knows what the event was.
     */
    public String whenKey() {
        return switch (trigger) {
            case Trigger.Event event -> "deepcharter.transmission." + id.getPath() + ".when";
            case Trigger.Zone zone ->
                "deepcharter.transmission.when.zone." + zone.zone() + (zone.lowerHalf() ? ".lower_half" : "");
            case Trigger.Breach breach ->
                breach.toLayer() == LayerChain.SURFACE
                        ? "deepcharter.transmission.when.surface"
                        : "deepcharter.transmission.when.breach";
        };
    }

    /** The layer {@link #whenKey} names, 0 for a trigger that names none. */
    public int whenLayer() {
        return switch (trigger) {
            case Trigger.Event event -> 0;
            case Trigger.Zone zone -> zone.layer();
            case Trigger.Breach breach -> breach.toLayer();
        };
    }

    /** How the signal reaches the charter. This sets the header's and the closing line's wording, the header's colour, and the sound. */
    public enum Framing implements StringRepresentable {
        /** The sender is speaking now: {@code ▌INCOMING — <sender>} … {@code ▌END OF MESSAGE}. */
        LIVE("live"),
        /**
         * A wreck's beacon repeating a dead crew's last words, once a day since they were recorded:
         * {@code ▌AUTOMATIC RELAY — POD <serial> — LOOP <n>} … {@code ▌RELAY RESTARTS}.
         */
        RELAY("relay"),
        /** A fragment nobody can place: {@code ▌INCOMING — [SOURCE UNKNOWN]} … {@code ▌SIGNAL LOST}. */
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

        /** The lang key of the header line's wording; it takes the sender's name and, for a relay, the loop counter. */
        public String headerKey() {
            return "deepcharter.transmission.framing." + serializedName;
        }

        /** The lang key of the line that closes the text. */
        public String endKey() {
            return headerKey() + ".end";
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

        /**
         * A charter member stands in the zone: {@code zone} is 0 for the top third of the layer, 2 for the bottom. With
         * {@code lowerHalf}, only the lower half of the zone counts.
         */
        record Zone(int layer, int zone, boolean lowerHalf) implements Trigger {
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
                    throw new IllegalArgumentException(
                            "a breach leads into the surface or into layer 2 or deeper, got " + toLayer);
                }
            }

            @Override
            public Kind kind() {
                return Kind.BREACH;
            }
        }

        enum Kind implements StringRepresentable {
            EVENT("event", MapCodec.unit(new Event())),
            ZONE(
                    "zone",
                    RecordCodecBuilder.<Zone>mapCodec(instance -> instance.group(
                                    Codec.INT.fieldOf("layer").forGetter(Zone::layer),
                                    Codec.INT.fieldOf("zone").forGetter(Zone::zone),
                                    Codec.BOOL
                                            .optionalFieldOf("lower_half", false)
                                            .forGetter(Zone::lowerHalf))
                            .apply(instance, Zone::new))),
            BREACH(
                    "breach",
                    RecordCodecBuilder.<Breach>mapCodec(instance -> instance.group(
                                    Codec.INT.fieldOf("to_layer").forGetter(Breach::toLayer))
                            .apply(instance, Breach::new)));

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
