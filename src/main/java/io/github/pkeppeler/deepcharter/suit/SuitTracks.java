package io.github.pkeppeler.deepcharter.suit;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.pkeppeler.deepcharter.outpost.Ratings;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * What one person has bought for their suit (#1064, ADR 0091), the player attachment {@link SuitRegistry#TRACKS}: a tier from 0 (stock) to
 * {@link SuitTrack#maxTier} on each track. Personal and kept across death.
 *
 * @param heat     the heat track's tier, which is the suit's heat rating
 * @param pressure the pressure track's tier, which is the suit's pressure rating
 * @param air      the air track's tier, which is the suit's flooding rating and picks its capacity
 */
public record SuitTracks(int heat, int pressure, int air) {
    public static final int VERSION = 1;
    /** The suit nobody has fitted anything to. */
    public static final SuitTracks STOCK = new SuitTracks(0, 0, 0);

    public SuitTracks {
        for (SuitTrack track : SuitTrack.values()) {
            int tier = switch (track) {
                case HEAT -> heat;
                case PRESSURE -> pressure;
                case AIR -> air;
            };
            if (tier < 0 || tier > track.maxTier()) {
                throw new IllegalArgumentException(track + " has no tier " + tier);
            }
        }
    }

    /** The saved body. A tier out of range reads clamped (and is written back clamped), and one that is missing reads 0. */
    public static final MapCodec<SuitTracks> BODY = RecordCodecBuilder.mapCodec(instance -> instance.group(
                    Codec.INT.optionalFieldOf("heat", 0).forGetter(SuitTracks::heat),
                    Codec.INT.optionalFieldOf("pressure", 0).forGetter(SuitTracks::pressure),
                    Codec.INT.optionalFieldOf("air", 0).forGetter(SuitTracks::air))
            .apply(instance, SuitTracks::clamped));

    /** The wire body: the three tiers. A tier its encoder cannot produce throws, which disconnects, as a version mismatch does. */
    public static final StreamCodec<ByteBuf, SuitTracks> STREAM = StreamCodec.composite(
            ByteBufCodecs.VAR_INT,
            SuitTracks::heat,
            ByteBufCodecs.VAR_INT,
            SuitTracks::pressure,
            ByteBufCodecs.VAR_INT,
            SuitTracks::air,
            SuitTracks::new);

    private static SuitTracks clamped(int heat, int pressure, int air) {
        return new SuitTracks(
                Math.clamp(heat, 0, SuitTrack.HEAT.maxTier()),
                Math.clamp(pressure, 0, SuitTrack.PRESSURE.maxTier()),
                Math.clamp(air, 0, SuitTrack.AIR.maxTier()));
    }

    /** The tier of {@code track}. */
    public int tier(SuitTrack track) {
        return switch (track) {
            case HEAT -> heat;
            case PRESSURE -> pressure;
            case AIR -> air;
        };
    }

    /** These tracks with {@code track} at {@code tier}. */
    public SuitTracks with(SuitTrack track, int tier) {
        return switch (track) {
            case HEAT -> new SuitTracks(tier, pressure, air);
            case PRESSURE -> new SuitTracks(heat, tier, air);
            case AIR -> new SuitTracks(heat, pressure, tier);
        };
    }

    /** What the suit is rated, as a block is: the air tier is the flooding level. Quake is not a thing a suit holds against. */
    public Ratings ratings() {
        return new Ratings(heat, pressure, air, 0);
    }

    /** The full reserve of this suit, in suit-seconds. */
    public float capacity(SuitTuning tuning) {
        return tuning.capacities().get(air);
    }
}
