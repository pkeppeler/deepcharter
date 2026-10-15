package io.github.pkeppeler.deepcharter.client.layer;

import io.github.pkeppeler.deepcharter.layer.Depth;
import java.util.Locale;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** The depth readout, for example "-1,234 ft.". Below sea level is negative. */
public final class Altimeter {
    private Altimeter() {}

    /** The reading for the client's player. */
    public static Component reading(Minecraft client) {
        var level = Objects.requireNonNull(client.level, () -> "no client level: the altimeter reads only in a world");
        var player =
                Objects.requireNonNull(client.player, () -> "no client player: the altimeter reads only in a world");
        int feet = Depth.feet(Depth.of(level, player.getBlockY()));
        // Locale.US so the thousands separator is the same on every machine.
        return Component.translatable("deepcharter.breach.altimeter", String.format(Locale.US, "%,d", -feet));
    }
}
