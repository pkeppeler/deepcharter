package io.github.pkeppeler.deepcharter.wreck;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import java.util.OptionalInt;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

/** Server-side events of the wreck feature. */
public final class WreckEvents {
    /**
     * A charter was told that a pod became a wreck: the chat message to its online members has been sent. Fires once, for the
     * pod's owner charter, even if nobody was riding. A pod with no live owner (unowned, or its owner gone or dormant) fires
     * once for each charter on the crew when it went down, so a pod with no such crew fires nothing.
     * {@code layer} is empty on the surface. A listener that throws is logged and does not stop the others.
     */
    public static final Event<Reported> REPORTED =
            EventFactory.createArrayBacked(Reported.class, listeners -> (server, charter, pod, layer, pos) -> {
                for (Reported listener : listeners) {
                    try {
                        listener.onReported(server, charter, pod, layer, pos);
                    } catch (RuntimeException e) {
                        DeepCharter.LOGGER.error(
                                "A wreck report listener failed for pod {}: the other listeners still run",
                                pod.getUUID(),
                                e);
                    }
                }
            });

    /**
     * A pod became a wreck: its hull ran out, or it loaded at hull 0. Fires after the state is set and before the crew
     * die, once per change. A listener that throws is logged and does not stop the others.
     */
    public static final Event<Wrecked> WRECKED = EventFactory.createArrayBacked(Wrecked.class, listeners -> pod -> {
        for (Wrecked listener : listeners) {
            try {
                listener.onWrecked(pod);
            } catch (RuntimeException e) {
                DeepCharter.LOGGER.error(
                        "A wreck listener failed for pod {}: the other listeners still run", pod.getUUID(), e);
            }
        }
    });

    /**
     * A wreck was made a working pod again, by whatever repairs it: the hangar console today, the field kit later.
     * Fires from {@link Wrecks#restore} after the state is set. A listener that throws is logged and does not stop the others.
     */
    public static final Event<Restored> RESTORED = EventFactory.createArrayBacked(Restored.class, listeners -> pod -> {
        for (Restored listener : listeners) {
            try {
                listener.onRestored(pod);
            } catch (RuntimeException e) {
                DeepCharter.LOGGER.error(
                        "A wreck restore listener failed for pod {}: the other listeners still run", pod.getUUID(), e);
            }
        }
    });

    private WreckEvents() {}

    @FunctionalInterface
    public interface Wrecked {
        void onWrecked(PodEntity pod);
    }

    @FunctionalInterface
    public interface Restored {
        void onRestored(PodEntity pod);
    }

    @FunctionalInterface
    public interface Reported {
        void onReported(MinecraftServer server, Charter charter, PodEntity pod, OptionalInt layer, BlockPos pos);
    }
}
