package io.github.pkeppeler.deepcharter.outpost;

import java.util.Locale;
import net.minecraft.network.chat.Component;

/** What a depth asks of a block (SPEC section 11): heat, pressure, flooding and quake bracing. The id names its block tags and its words. */
public enum RatingAxis {
    HEAT,
    PRESSURE,
    FLOODING,
    QUAKE;

    /** The lower-case id: the folder of the axis's block tags ({@code rating/<id>/<level>}) and the end of its lang key. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public Component label() {
        return Component.translatable("rating.deepcharter.axis." + id());
    }
}
