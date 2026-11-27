package io.github.pkeppeler.deepcharter.colony;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import java.util.Map;

/**
 * What the Company has handed one player in rations: whether the starting tins are given. It is the player attachment
 * {@link ColonyRations#LEDGER}: persistent, kept on death, never sent to the client.
 *
 * <p>Version 1 also held {@code canteenDay}, the day of the canteen's last ration, from when the canteen gave one a day. The
 * canteen now gives one on every use (#1439), so version 2 drops the day. Version 1's body is the current one plus that field,
 * which the body codec does not read, so a v1 save loads with its {@code starterGiven} and is written back as v2 without the day.
 *
 * @param starterGiven true once the player has been given the starting rations, so a later join gives none
 */
public record RationLedger(boolean starterGiven) {
    public static final int VERSION = 2;

    public static final RationLedger DEFAULT = new RationLedger(false);
    public static final MapCodec<RationLedger> BODY = RecordCodecBuilder.mapCodec(
            instance -> instance.group(Codec.BOOL.fieldOf("starterGiven").forGetter(RationLedger::starterGiven))
                    .apply(instance, RationLedger::new));
    /** The disk codec of the attachment, which never fails to decode. */
    public static final Codec<Versioned<RationLedger>> CODEC = Versioned.codec(VERSION, BODY, Map.of(1, BODY));
}
