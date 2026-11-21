package io.github.pkeppeler.deepcharter.colony;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.pkeppeler.deepcharter.attachment.Versioned;

/**
 * What the Company has handed one player in rations: whether the starting tins are given, and the day the canteen last served them.
 * It is the player attachment {@link ColonyRations#LEDGER}: persistent, kept on death, never sent to the client.
 *
 * @param starterGiven true once the player has been given the starting rations, so a later join gives none
 * @param canteenDay   the in-game day number of the canteen's last ration for this player, or {@link #NEVER}
 */
public record RationLedger(boolean starterGiven, long canteenDay) {
    public static final int VERSION = 1;
    /** The day of a player the canteen has not served. */
    public static final long NEVER = -1;

    public static final RationLedger DEFAULT = new RationLedger(false, NEVER);
    public static final MapCodec<RationLedger> BODY = RecordCodecBuilder.mapCodec(instance -> instance.group(
                    Codec.BOOL.fieldOf("starterGiven").forGetter(RationLedger::starterGiven),
                    Codec.LONG.fieldOf("canteenDay").forGetter(RationLedger::canteenDay))
            .apply(instance, RationLedger::new));
    /** The disk codec of the attachment, which never fails to decode. */
    public static final Codec<Versioned<RationLedger>> CODEC = Versioned.codec(VERSION, BODY);
}
