package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.charter.terminal.ContractActions;
import io.github.pkeppeler.deepcharter.charter.terminal.ContractTerminal;
import io.github.pkeppeler.deepcharter.client.charter.terminal.ContractScreen;
import io.github.pkeppeler.deepcharter.terminal.TerminalActionPayload;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.ContractShown;
import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;
import java.util.UUID;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

/**
 * Evidence scenario "m2-charter-revive" for #168: two charters are dormant, and the real player was a member of only one of them.
 * The contract terminal offers REVIVE for that one and nothing for the other; a request to revive the other by hand is refused with
 * its reason; REVIVE on the first makes the player its Director, with its account intact.
 */
public class CharterReviveScenario extends EvidenceScenario {
    private static final String OURS = "Lost Crew";
    private static final String THEIRS = "Strangers Hold";
    private static final long ACCOUNT = 750;
    private static final int TICKS_PER_FRAME = 3;
    private static final int TYPING_FRAMES = 30;
    private static final int HOLD_FRAMES = 8;

    @Override
    protected String name() {
        return "m2-charter-revive";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TwoPlayerServer two = TwoPlayerServer.start(context)) {
            UUID mock = two.mock().player().getUUID();
            BlockPos terminal = two.server().computeOnServer(server -> {
                ServerPlayer real = server.getPlayerList().getPlayers().stream()
                        .filter(player -> !player.getUUID().equals(mock))
                        .findFirst()
                        .orElseThrow();
                BlockPos at = real.blockPosition().relative(Direction.EAST, 2);
                real.level().setBlock(at, ContractTerminal.TYPE.block().defaultBlockState(), 3);
                // The real player founds and leaves OURS; the stranger founds and leaves THEIRS. Both go dormant.
                Charters.found(server, real.getUUID(), OURS);
                Charters.deposit(
                        server, Charters.findByName(server, OURS).orElseThrow().id(), ACCOUNT);
                Charters.leave(server, real.getUUID());
                UUID stranger = UUID.randomUUID();
                Charters.found(server, stranger, THEIRS);
                Charters.leave(server, stranger);
                return at;
            });

            context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(terminal)));
            ClientWait.screen(context, ContractScreen.class);
            awaitState(context, open -> ContractShown.hasNoCharter(open) && ContractShown.offersRevive(open, OURS));
            ContractScreen screen = context.computeOnClient(client -> (ContractScreen) client.gui.screen());
            for (int i = 0; i < TYPING_FRAMES && !screen.typewriter().done(); i++) {
                context.waitTicks(TICKS_PER_FRAME);
                clearToastsAndFrame(context);
            }
            hold(context);
            clearToasts(context);
            screenshot(context, "dormant-offered");

            // A request for a charter the player was never in, as a hand-made packet would send it.
            CompoundTag args = new CompoundTag();
            args.putString(ContractActions.NAME_KEY, THEIRS);
            context.runOnClient(client ->
                    ClientPlayNetworking.send(new TerminalActionPayload(terminal, ContractActions.REVIVE, args)));
            awaitState(context, open -> open.refusal().isPresent());
            hold(context);
            clearToasts(context);
            screenshot(context, "stranger-refused");

            context.clickScreenButton("REVIVE: " + OURS.toUpperCase());
            awaitState(context, open -> ContractShown.directs(open, OURS));
            hold(context);
            clearToasts(context);
            screenshot(context, "revived");
            context.setScreen(() -> null);
        }
    }

    private static void awaitState(ClientGameTestContext context, Predicate<ContractScreen> wanted) {
        ClientWait.until(
                context,
                "the contract screen in the wanted state",
                client -> client.gui.screen() instanceof ContractScreen open && wanted.test(open));
    }

    /** The dedicated server's join toasts ("chat messages can't be verified") would cover the screen. */
    private static void clearToasts(ClientGameTestContext context) {
        context.runOnClient(client -> client.gui.toastManager().clear());
    }

    private void clearToastsAndFrame(ClientGameTestContext context) {
        clearToasts(context);
        frame(context);
    }

    private void hold(ClientGameTestContext context) {
        for (int i = 0; i < HOLD_FRAMES; i++) {
            context.waitTicks(TICKS_PER_FRAME);
            clearToastsAndFrame(context);
        }
    }
}
