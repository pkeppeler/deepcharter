package io.github.pkeppeler.deepcharter.pod;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InterpolationHandler;

/** Gives the tests in other packages a {@link PodGlide} on any pod, a server's too, so a test can drive the client's glide move by move. */
public final class PodGlideProbe {
    private PodGlideProbe() {}

    public static InterpolationHandler glide(Entity pod) {
        return new PodGlide(pod);
    }
}
