package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;

/**
 * Pods that keep a second seat across a teleport (#1229). {@link SecondSeat} gives one pod object a second seat, but
 * the teleport of a crossing recreates the pod from its data, which has none, and would leave the second rider behind.
 * A pod added here gets the seat again each time it loads, so a test of the fallback keeps both riders on it, as a
 * chassis with two seats would.
 */
public final class TwoSeatPods {
    private static final Set<UUID> PODS = ConcurrentHashMap.newKeySet();

    static {
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof PodEntity pod && PODS.contains(pod.getUUID())) {
                SecondSeat.add(pod);
            }
        });
    }

    private static volatile boolean clientArmed;
    private static boolean clientInstalled;

    private TwoSeatPods() {}

    /**
     * Gives every pod the client loads a second seat while armed, for a client test with a rider on each seat: the
     * client's pod is its own object and has the chassis' one seat, so it would refuse the second rider. A pod the
     * server recreates is loaded again here, so it gets the seat again. Call it with false at the end of the test.
     */
    public static synchronized void armClient(boolean armed) {
        clientArmed = armed;
        if (!clientInstalled) {
            clientInstalled = true;
            ClientEntityEvents.ENTITY_LOAD.register((entity, level) -> {
                if (clientArmed && entity instanceof PodEntity pod) {
                    SecondSeat.add(pod);
                }
            });
        }
    }

    /** Gives {@code pod} a second seat now, and again whenever it loads. */
    public static void add(PodEntity pod) {
        SecondSeat.add(pod);
        PODS.add(pod.getUUID());
    }

    /** Stops giving {@code pod} the seat at a load. */
    public static void forget(PodEntity pod) {
        PODS.remove(pod.getUUID());
    }
}
