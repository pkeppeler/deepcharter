package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pods that make the drill's noise without boring: the flag {@code drilling} is held on after the pod's own tick, so a test or a scene can have
 * a drilling pod that stays where it is. Ids only, because a Fabric event cannot be unregistered. Test code only.
 */
public final class LoudPods {
    private static final Set<UUID> LOUD = ConcurrentHashMap.newKeySet();

    static {
        PodEvents.AFTER_TICK.register(pod -> {
            if (LOUD.contains(pod.getUUID())) {
                pod.setDrilling(true);
            }
        });
    }

    private LoudPods() {}

    public static void make(PodEntity pod) {
        LOUD.add(pod.getUUID());
    }

    public static void quiet(PodEntity pod) {
        LOUD.remove(pod.getUUID());
    }
}
