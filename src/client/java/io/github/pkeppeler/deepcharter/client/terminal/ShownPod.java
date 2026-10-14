package io.github.pkeppeler.deepcharter.client.terminal;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.terminal.ParkedPodView;
import io.github.pkeppeler.deepcharter.terminal.TerminalView;
import io.github.pkeppeler.deepcharter.terminal.Terminals;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.world.level.Level;

/** The pod a pod terminal's screen shows: the one the server named in the view, so the screen and the action agree. */
public final class ShownPod {
    private ShownPod() {}

    /**
     * The pod the server named, if this client sees it parked. When the server named none, the nearest pod parked here, so a pod
     * that arrives while the screen is open shows at once; an action on it is refused if the server may not use it.
     */
    public static Optional<PodEntity> of(Level level, TerminalView view) {
        Optional<UUID> named = view.feature(ParkedPodView.class).flatMap(ParkedPodView::pod);
        var parked = Terminals.parkedPods(level, view.pos()).stream();
        return named.isPresent()
                ? parked.filter(pod -> pod.getUUID().equals(named.get())).findFirst()
                : parked.findFirst();
    }
}
