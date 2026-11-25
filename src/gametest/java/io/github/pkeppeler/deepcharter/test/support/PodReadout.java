package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.client.pod.CockpitHud;
import io.github.pkeppeler.deepcharter.client.pod.CockpitReading;
import io.github.pkeppeler.deepcharter.client.pod.PodStatusHud;
import io.github.pkeppeler.deepcharter.client.theme.CockpitLook;
import io.github.pkeppeler.deepcharter.client.theme.HudLook;
import io.github.pkeppeler.deepcharter.client.ui.HudKeepClear;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** The text of the pilot's readout, the lines the pod HUD and the cockpit plate both draw. */
public final class PodReadout {
    /** The two smallest GUIs a readout must fit, width by height. */
    private static final int[][] GUIS = {{320, 240}, {427, 240}};

    private PodReadout() {}

    public static List<Component> texts(Minecraft client, PodEntity pod) {
        return PodStatusHud.readout(ClientSession.player(client), pod).stream()
                .map(PodStatusHud.Line::text)
                .toList();
    }

    /**
     * The ridden pod's plain readout box, as {@code PodStatusHud} draws it, inside the two smallest GUIs and clear of
     * vanilla's rows ({@link VanillaHudRows}); and the plate inside the cockpit's smallest GUI.
     */
    public static void expectClear(ClientGameTestContext context) {
        context.runOnClient(client -> {
            PodEntity pod = TestEntities.vehicle(ClientSession.player(client), PodEntity.class);
            HudLook look = HudLook.current();
            List<PodStatusHud.Line> readout = PodStatusHud.readout(ClientSession.player(client), pod);
            int widest = readout.stream()
                    .mapToInt(line -> client.font.width(line.text()))
                    .max()
                    .orElse(0);
            int pitch = client.font.lineHeight + look.podStatusLineGap();
            HudKeepClear.Rect box = new HudKeepClear.Rect(
                    "pod readout",
                    look.podStatusMargin(),
                    look.podStatusMargin(),
                    widest,
                    readout.size() * pitch - look.podStatusLineGap());
            for (int[] gui : GUIS) {
                if (box.right() > gui[0] || box.bottom() > gui[1]) {
                    throw new AssertionError("the readout " + box + " leaves the GUI " + gui[0] + " x " + gui[1]);
                }
                for (HudKeepClear.Rect row : VanillaHudRows.rects(gui[0], gui[1])) {
                    if (box.overlaps(row)) {
                        throw new AssertionError("the readout " + box + " with " + readout.size()
                                + " lines runs into vanilla's " + row.what() + " " + row + " on " + gui[0] + " x "
                                + gui[1]);
                    }
                }
            }
            CockpitLook cockpit = CockpitLook.current();
            CockpitHud.Box plate = CockpitHud.plateBox(
                    client.font,
                    cockpit.ribbon(),
                    cockpit,
                    CockpitHud.rows(pod, CockpitReading.of(pod, 0), cockpit, readout),
                    cockpit.minGuiWidth(),
                    240);
            if (plate.left() < 0 || plate.left() + plate.width() > cockpit.minGuiWidth()) {
                throw new AssertionError("the plate spans " + plate.left() + " to " + (plate.left() + plate.width())
                        + " of a " + cockpit.minGuiWidth() + " px GUI");
            }
        });
    }
}
