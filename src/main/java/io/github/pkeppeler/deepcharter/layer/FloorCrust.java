package io.github.pkeppeler.deepcharter.layer;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/**
 * What a layer's layer data says about the crust at its floor, the boundary to the band below (#216): the world's seed decides it
 * ({@link #DRAW}), or the data does, as a story layer may.
 */
public enum FloorCrust implements StringRepresentable {
    /** The seed decides: grained for about 1 in 3 boundaries ({@link CrustBoundaries}). */
    DRAW("draw"),
    /** The breach crust, whatever the draw says. */
    NORMAL("normal"),
    /** The grained crust, whatever the draw says: the crust of a seam. */
    GRAINED("grained");

    public static final Codec<FloorCrust> CODEC = StringRepresentable.fromEnum(FloorCrust::values);

    private final String name;

    FloorCrust(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
