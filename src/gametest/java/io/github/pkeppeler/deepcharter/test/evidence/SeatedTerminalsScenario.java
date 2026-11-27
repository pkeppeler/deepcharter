package io.github.pkeppeler.deepcharter.test.evidence;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.client.charter.ClientCharter;
import io.github.pkeppeler.deepcharter.client.fuel.FuelPumpScreen;
import io.github.pkeppeler.deepcharter.client.market.OreProcessorScreen;
import io.github.pkeppeler.deepcharter.client.pod.PodChase;
import io.github.pkeppeler.deepcharter.client.transmission.TransmissionOverlay;
import io.github.pkeppeler.deepcharter.test.SeatedTerminalsClientTest;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RealInput;
import java.util.function.BooleanSupplier;
import java.util.function.LongPredicate;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;

/**
 * Evidence scenario "m2-seated-terminals" for #1447: seated in a parked Mole, with the chase camera on, the pilot looks
 * at the pump and right-clicks with an empty hand, fills the tank, closes the screen (still seated), then looks at the
 * ore processor and sells the cargo, all without leaving the seat. Both terminals stand past on-foot reach.
 */
public class SeatedTerminalsScenario extends EvidenceScenario {
    private static final int TICKS_PER_FRAME = 3;
    private static final int TYPING_FRAMES = 45;
    private static final int HOLD_FRAMES = 10;

    @Override
    protected String name() {
        return "m2-seated-terminals";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        PodChase.setAutomatic(true);
        CameraType original = context.computeOnClient(client -> client.options.getCameraType());
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            SeatedTerminalsClientTest.Scene scene =
                    singleplayer.getServer().computeOnServer(SeatedTerminalsClientTest::setUp);
            SeatedTerminalsClientTest.board(context, singleplayer, scene);
            ClientWait.until(
                    context,
                    "the charter's view",
                    client -> ClientCharter.view().isPresent());
            clearOverlays(context);
            still(context, "seated-at-the-pump");

            SeatedTerminalsClientTest.useFacing(context, scene.pump(), FuelPumpScreen.class);
            FuelPumpScreen pump = context.computeOnClient(client -> ClientSession.screen(client, FuelPumpScreen.class));
            typeOut(context, () -> pump.typewriter().done());
            still(context, "pump-from-the-seat");
            long start = SeatedTerminalsClientTest.START_BALANCE;
            context.clickScreenButton("FILL UP");
            awaitBalance(context, "less than " + start, balance -> balance < start);
            clearOverlays(context);
            still(context, "pump-filled");
            long afterFuel = context.computeOnClient(
                    client -> ClientCharter.view().orElseThrow().balance());

            RealInput.key(context, InputConstants.KEY_ESCAPE, 0);
            ClientWait.until(context, "the pump screen closed", client -> client.gui.screen() == null);
            still(context, "still-seated");

            SeatedTerminalsClientTest.useFacing(context, scene.processor(), OreProcessorScreen.class);
            OreProcessorScreen processor =
                    context.computeOnClient(client -> ClientSession.screen(client, OreProcessorScreen.class));
            typeOut(context, () -> processor.typewriter().done());
            still(context, "processor-from-the-seat");
            context.clickScreenButton("SELL ALL POD CARGO");
            awaitBalance(context, "more than " + afterFuel, balance -> balance > afterFuel);
            clearOverlays(context);
            still(context, "cargo-sold");
            context.setScreen(() -> null);
            hold(context);
        } finally {
            context.runOnClient(client -> client.options.setCameraType(original));
        }
    }

    private static void awaitBalance(ClientGameTestContext context, String what, LongPredicate wanted) {
        ClientWait.until(
                context,
                "the charter's balance " + what,
                client -> ClientCharter.view()
                        .map(view -> wanted.test(view.balance()))
                        .orElse(false),
                client -> "balance "
                        + ClientCharter.view()
                                .map(view -> String.valueOf(view.balance()))
                                .orElse("(none)"));
    }

    /** Drops the founding transmission and the "Directive complete" toasts, which cover the seat and the screens. */
    private static void clearOverlays(ClientGameTestContext context) {
        context.runOnClient(client -> {
            TransmissionOverlay.reset();
            client.gui.toastManager().clear();
        });
    }

    /** Records frames while the screen types out, up to {@link #TYPING_FRAMES}. */
    private void typeOut(ClientGameTestContext context, BooleanSupplier done) {
        for (int i = 0; i < TYPING_FRAMES && !done.getAsBoolean(); i++) {
            context.waitTicks(TICKS_PER_FRAME);
            frame(context);
        }
    }

    private void still(ClientGameTestContext context, String screenshotName) {
        hold(context);
        screenshot(context, screenshotName);
    }

    private void hold(ClientGameTestContext context) {
        for (int i = 0; i < HOLD_FRAMES; i++) {
            context.waitTicks(TICKS_PER_FRAME);
            frame(context);
        }
    }
}
