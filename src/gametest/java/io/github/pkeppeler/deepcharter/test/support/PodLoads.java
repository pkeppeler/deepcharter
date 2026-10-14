package io.github.pkeppeler.deepcharter.test.support;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.client.pod.PodGeoRenderer;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/** The look and geometry the game loads for a pod's chassis, read the way the renderer reads them. */
public final class PodLoads {
    private PodLoads() {}

    /**
     * What {@link PodGeoRenderer#load} gives the chassis of {@code pod}. {@code renderer} is the pod's own renderer: it draws one of
     * the loaded look's textures, so the load is for the chassis and the look it draws.
     */
    public static PodGeoRenderer.Loaded of(Minecraft client, PodGeoRenderer renderer, PodEntity pod) {
        PodGeoRenderer.Loaded loaded = PodGeoRenderer.load(client.getResourceManager(), pod.chassis());
        Identifier drawn = renderer.appearanceOf(pod).variant().texture();
        require(
                drawn.equals(loaded.look().intact().texture())
                        || drawn.equals(loaded.look().wreck().texture()),
                "the renderer of the " + pod.chassis().id() + " draws " + drawn
                        + ", not a texture of the look it loads from "
                        + loaded.look().source());
        return loaded;
    }
}
