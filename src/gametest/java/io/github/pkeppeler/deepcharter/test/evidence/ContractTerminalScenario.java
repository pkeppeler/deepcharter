package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;

import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.charter.terminal.ContractState;
import io.github.pkeppeler.deepcharter.charter.terminal.ContractState.Role;
import io.github.pkeppeler.deepcharter.charter.terminal.ContractTerminal;
import io.github.pkeppeler.deepcharter.client.charter.terminal.ContractScreen;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;

/**
 * Evidence scenario "m2-contract-terminal" for #72: on a dedicated server a player walks up to the contract terminal, types a
 * charter name and founds the charter, a second player applies, and the Director approves. Stills of the empty file, the
 * Director's list with one application, and the charter with two people.
 */
public class ContractTerminalScenario extends EvidenceScenario {
	private static final String CHARTER = "Riggs and Sons";
	private static final String MOCK_NAME = "MockPilot";
	private static final int TICKS_PER_FRAME = 3;
	private static final int TYPING_FRAMES = 30;
	private static final int HOLD_FRAMES = 8;
	private static final int WAIT_TICKS = 200;

	@Override
	protected String name() {
		return "m2-contract-terminal";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TwoPlayerServer two = TwoPlayerServer.start(context)) {
			UUID mock = two.mock().player().getUUID();
			BlockPos terminal = two.server().computeOnServer(server -> {
				ServerPlayer real = server.getPlayerList().getPlayers().stream().filter(player -> !player.getUUID().equals(mock)).findFirst().orElseThrow();
				BlockPos at = real.blockPosition().relative(Direction.EAST, 2);
				real.level().setBlock(at, ContractTerminal.TYPE.block().defaultBlockState(), 3);
				return at;
			});

			context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(terminal)));
			context.waitForScreen(ContractScreen.class);
			awaitState(context, state -> state.role() == Role.NONE);
			ContractScreen screen = context.computeOnClient(client -> (ContractScreen) client.gui.screen());
			for (int i = 0; i < TYPING_FRAMES && !screen.typewriter().done(); i++) {
				context.waitTicks(TICKS_PER_FRAME);
				clearToastsAndFrame(context);
			}
			hold(context);
			clearToasts(context);
			screenshot(context, "contract-no-charter");

			for (int length = 1; length <= CHARTER.length(); length++) {
				String typed = CHARTER.substring(0, length);
				context.runOnClient(client -> ((ContractScreen) client.gui.screen()).nameField().setValue(typed));
				context.waitTicks(TICKS_PER_FRAME);
				clearToastsAndFrame(context);
			}
			hold(context);
			context.clickScreenButton("FOUND CHARTER");
			awaitState(context, state -> state.role() == Role.DIRECTOR);
			hold(context);

			two.server().runOnServer(server -> {
				UUID director = server.getPlayerList().getPlayers().stream().map(ServerPlayer::getUUID).filter(uuid -> !uuid.equals(mock)).findFirst().orElseThrow();
				Charters.apply(server, mock, Charters.charterOfOrThrow(server, director).orElseThrow().id());
			});
			awaitState(context, state -> state.applicants().equals(List.of(MOCK_NAME)));
			hold(context);
			clearToasts(context);
			screenshot(context, "contract-application");

			context.clickScreenButton("APPROVE " + MOCK_NAME.toUpperCase());
			awaitState(context, state -> state.applicants().isEmpty());
			hold(context);
			clearToasts(context);
			screenshot(context, "contract-approved");
			context.setScreen(() -> null);
		}
	}

	private static void awaitState(ClientGameTestContext context, Predicate<ContractState> wanted) {
		context.waitFor(client -> client.gui.screen() instanceof ContractScreen open && open.state().filter(wanted).isPresent(), WAIT_TICKS);
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
