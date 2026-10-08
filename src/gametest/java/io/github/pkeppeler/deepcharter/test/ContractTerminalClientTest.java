package io.github.pkeppeler.deepcharter.test;

import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.CharterRefusal;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.charter.terminal.ContractState;
import io.github.pkeppeler.deepcharter.charter.terminal.ContractState.Role;
import io.github.pkeppeler.deepcharter.charter.terminal.ContractTerminal;
import io.github.pkeppeler.deepcharter.client.charter.terminal.ContractScreen;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;

/**
 * Client GameTest for #72: the real client uses the contract terminal on a dedicated server with a mock player as the other
 * person. The client founds a charter by typing a name, the mock applies and is turned down, applies again and is approved,
 * the client (as Director) leaves, applies to the charter the mock now directs, is approved, and leaves as crew. Refusals show
 * their message on the screen.
 */
public class ContractTerminalClientTest implements FabricClientGameTest {
	private static final String CHARTER = "Riggs and Sons";
	private static final String MOCK_NAME = "MockPilot";
	private static final int WAIT_TICKS = 200;

	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);
		try (TwoPlayerServer two = TwoPlayerServer.start(context)) {
			UUID mock = two.mock().player().getUUID();
			BlockPos terminal = two.server().computeOnServer(server -> {
				ServerPlayer real = server.getPlayerList().getPlayers().stream().filter(player -> !player.getUUID().equals(mock)).findFirst().orElseThrow();
				BlockPos at = real.blockPosition().relative(Direction.EAST, 2);
				real.level().setBlock(at, ContractTerminal.TYPE.block().defaultBlockState(), 3);
				return at;
			});
			UUID real = two.server().computeOnServer(server -> server.getPlayerList().getPlayers().stream()
					.map(ServerPlayer::getUUID).filter(uuid -> !uuid.equals(mock)).findFirst().orElseThrow());

			ContractScreen screen = open(context, terminal);
			awaitState(context, state -> state.role() == Role.NONE);
			check(context.computeOnClient(client -> screen.statusLine().getString()).contains("NO CHARTER"), "a player with no charter is told so");

			refusalsShowTheirMessage(context, screen);
			foundByTypingAName(context);
			Charter charter = two.server().computeOnServer(server -> Charters.charterOf(server, real).orElseThrow());
			check(charter.name().equals(CHARTER) && charter.isDirector(real), "the server has the typed charter, directed by the client, got " + charter);

			anotherPlayerAppliesAndIsDenied(context, two, mock, charter);
			anotherPlayerAppliesAndIsApproved(context, two, mock, real, charter);
			aDirectorLeavesAndAMemberLeaves(context, two, mock, real);
		}
	}

	private static ContractScreen open(ClientGameTestContext context, BlockPos terminal) {
		context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(terminal)));
		context.waitForScreen(ContractScreen.class);
		return context.computeOnClient(client -> (ContractScreen) client.gui.screen());
	}

	private static void awaitState(ClientGameTestContext context, Predicate<ContractState> wanted) {
		context.waitFor(client -> client.gui.screen() instanceof ContractScreen open && open.state().filter(wanted).isPresent(), WAIT_TICKS);
	}

	private static ContractScreen screen(ClientGameTestContext context) {
		return context.computeOnClient(client -> (ContractScreen) client.gui.screen());
	}

	private static void type(ClientGameTestContext context, String name) {
		context.runOnClient(client -> ((ContractScreen) client.gui.screen()).nameField().setValue(name));
	}

	private static String refusalShown(ClientGameTestContext context) {
		return context.computeOnClient(client -> ((ContractScreen) client.gui.screen()).refusal().map(message -> message.getString()).orElse(""));
	}

	/** An empty name is refused by the server, and the screen shows why. */
	private static void refusalsShowTheirMessage(ClientGameTestContext context, ContractScreen screen) {
		type(context, "");
		context.clickScreenButton("FOUND CHARTER");
		awaitState(context, state -> state.notice().isPresent());
		String expected = context.computeOnClient(client -> Component.translatable(CharterRefusal.INVALID_NAME.translationKey()).getString());
		check(refusalShown(context).contains(expected), "the blank name's refusal reads '" + expected + "', the screen shows '" + refusalShown(context) + "'");
		check(screen(context).state().orElseThrow().role() == Role.NONE, "a refused founding leaves the player on no charter");
	}

	private static void foundByTypingAName(ClientGameTestContext context) {
		type(context, CHARTER);
		context.clickScreenButton("FOUND CHARTER");
		awaitState(context, state -> state.role() == Role.DIRECTOR);
		ContractState state = screen(context).state().orElseThrow();
		check(state.charter().equals(CHARTER) && state.notice().isEmpty(), "the Director screen names the charter and shows no refusal, got " + state);
		check(context.computeOnClient(client -> screen(context).statusLine().getString()).contains("DIRECTOR OF " + CHARTER), "the status says who directs what");
	}

	private static void anotherPlayerAppliesAndIsDenied(ClientGameTestContext context, TwoPlayerServer two, UUID mock, Charter charter) {
		two.server().runOnServer(server -> {
			if (Charters.apply(server, mock, charter.id()).isPresent()) {
				throw new AssertionError("the mock's application should succeed");
			}
		});
		awaitState(context, state -> state.applicants().equals(List.of(MOCK_NAME)));
		context.clickScreenButton("DENY " + MOCK_NAME.toUpperCase());
		awaitState(context, state -> state.applicants().isEmpty());
		boolean turnedDown = two.server().computeOnServer(server -> Charters.charterOf(server, mock).isEmpty()
				&& Charters.find(server, charter.id()).orElseThrow().applications().isEmpty());
		check(turnedDown, "a denied applicant is on no charter and has no application");
	}

	private static void anotherPlayerAppliesAndIsApproved(ClientGameTestContext context, TwoPlayerServer two, UUID mock, UUID real, Charter charter) {
		two.server().runOnServer(server -> Charters.apply(server, mock, charter.id()));
		awaitState(context, state -> state.applicants().equals(List.of(MOCK_NAME)));
		context.clickScreenButton("APPROVE " + MOCK_NAME.toUpperCase());
		awaitState(context, state -> state.applicants().isEmpty());
		Charter after = two.server().computeOnServer(server -> Charters.charterOf(server, mock).orElseThrow());
		check(after.id().equals(charter.id()) && !after.isDirector(mock) && after.isDirector(real), "the mock is crew of the client's charter");
	}

	/** The Director leaves, so the mock takes over; the client applies to the mock's charter, is approved, and leaves as crew. */
	private static void aDirectorLeavesAndAMemberLeaves(ClientGameTestContext context, TwoPlayerServer two, UUID mock, UUID real) {
		context.clickScreenButton("LEAVE CHARTER");
		awaitState(context, state -> state.role() == Role.NONE && state.charters().contains(CHARTER));

		// Founding a name that is taken is refused with its message.
		type(context, CHARTER.toUpperCase());
		context.clickScreenButton("FOUND CHARTER");
		awaitState(context, state -> state.notice().isPresent());
		String taken = context.computeOnClient(client -> Component.translatable(CharterRefusal.NAME_TAKEN.translationKey()).getString());
		check(refusalShown(context).contains(taken), "a taken name's refusal reads '" + taken + "', the screen shows '" + refusalShown(context) + "'");

		context.clickScreenButton("APPLY: " + CHARTER.toUpperCase());
		awaitState(context, state -> state.role() == Role.APPLICANT && state.charter().equals(CHARTER));
		two.server().runOnServer(server -> {
			if (Charters.approve(server, mock, real).isPresent()) {
				throw new AssertionError("the mock Director's approval should succeed");
			}
		});
		awaitState(context, state -> state.role() == Role.CREW);

		context.clickScreenButton("LEAVE CHARTER");
		awaitState(context, state -> state.role() == Role.NONE);
		boolean left = two.server().computeOnServer(server -> Charters.charterOf(server, real).isEmpty());
		check(left, "the member left the charter on the server");
		context.setScreen(() -> null);
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
