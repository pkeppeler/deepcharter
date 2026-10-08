package io.github.pkeppeler.deepcharter.test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.charter.terminal.ContractActions;
import io.github.pkeppeler.deepcharter.charter.terminal.ContractState;
import io.github.pkeppeler.deepcharter.charter.terminal.ContractState.Role;
import io.github.pkeppeler.deepcharter.charter.terminal.ContractTerminal;
import io.github.pkeppeler.deepcharter.charter.terminal.ContractTerminalTuning;
import io.github.pkeppeler.deepcharter.terminal.TerminalRefusal;
import io.github.pkeppeler.deepcharter.terminal.Terminals;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;

/**
 * Server GameTests for #72: the contract terminal is always online and open to a player with no charter, each of its five
 * actions does what {@code Charters} does and shows the player where they stand, and a request it cannot trust changes nothing.
 */
public class ContractTerminalTest {
	private static String uniqueName(String base) {
		return base + " " + UUID.randomUUID().toString().substring(0, 8);
	}

	private static BlockPos place(GameTestHelper helper) {
		BlockPos relative = new BlockPos(0, 1, 0);
		helper.setBlock(relative, ContractTerminal.TYPE.block().defaultBlockState());
		return helper.absolutePos(relative);
	}

	/** A mock player in survival, on no charter, standing two blocks from {@code pos}. */
	private static MockPlayer visitor(GameTestHelper helper, String name, BlockPos pos) {
		MockPlayer mock = MockPlayers.join(helper, name);
		mock.player().setGameMode(GameType.SURVIVAL);
		Vec3 centre = Vec3.atCenterOf(pos);
		mock.teleportTo(helper.getLevel(), new Vec3(centre.x + 2, centre.y - mock.player().getEyeHeight(), centre.z), 0, 0);
		return mock;
	}

	private static CompoundTag name(String name) {
		CompoundTag args = new CompoundTag();
		args.putString(ContractActions.NAME_KEY, name);
		return args;
	}

	private static void expectDone(GameTestHelper helper, Optional<TerminalRefusal> refusal, String what) {
		if (refusal.isPresent()) {
			throw helper.assertionException("%s should succeed, was refused: %s", what, refusal.get());
		}
	}

	private static void expectRefused(GameTestHelper helper, Optional<TerminalRefusal> refusal, String what) {
		if (!refusal.equals(Optional.of(TerminalRefusal.ACTION_REFUSED))) {
			throw helper.assertionException("%s should be refused by the action, got %s", what, refusal);
		}
	}

	private static ContractState state(MinecraftServer server, ServerPlayer player) {
		return ContractTerminal.stateOf(server, player.getUUID());
	}

	private static void expectRole(GameTestHelper helper, MinecraftServer server, ServerPlayer player, Role role, String what) {
		Role actual = state(server, player).role();
		if (actual != role) {
			throw helper.assertionException("%s: the state should be %s, was %s", what, role, actual);
		}
	}

	@GameTest
	public void aPlayerWithNoCharterOpensItAndFoundsOne(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		BlockPos pos = place(helper);
		ServerPlayer player = visitor(helper, "Founder", pos).player();
		expectDone(helper, Terminals.open(player, pos), "opening the contract terminal with no charter");
		expectRole(helper, server, player, Role.NONE, "before founding");

		String charter = uniqueName("Founders");
		expectDone(helper, Terminals.act(player, pos, ContractActions.FOUND, name(charter)), "founding a charter");
		Optional<Charter> found = Charters.charterOfOrThrow(server, player.getUUID());
		if (found.isEmpty() || !found.get().name().equals(charter) || !found.get().isDirector(player.getUUID())) {
			throw helper.assertionException("the founder should direct the charter %s, got %s", charter, found);
		}
		ContractState after = state(server, player);
		if (after.role() != Role.DIRECTOR || !after.charter().equals(charter)) {
			throw helper.assertionException("the state after founding should be Director of %s, got %s", charter, after);
		}
		helper.succeed();
	}

	@GameTest
	public void applyApproveAndLeaveGoThroughTheCharter(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		BlockPos pos = place(helper);
		ServerPlayer director = visitor(helper, "Directress", pos).player();
		ServerPlayer applicant = visitor(helper, "Applicant", pos).player();
		String charter = uniqueName("Applicants");
		expectDone(helper, Terminals.act(director, pos, ContractActions.FOUND, name(charter)), "founding");

		expectDone(helper, Terminals.act(applicant, pos, ContractActions.APPLY, name(charter)), "applying");
		ContractState waiting = state(server, applicant);
		if (waiting.role() != Role.APPLICANT || !waiting.charter().equals(charter)) {
			throw helper.assertionException("the applicant should be waiting on %s, got %s", charter, waiting);
		}
		List<String> applicants = state(server, director).applicants();
		if (!applicants.equals(List.of("Applicant"))) {
			throw helper.assertionException("the Director should see exactly Applicant, got %s", applicants);
		}

		expectRefused(helper, Terminals.act(applicant, pos, ContractActions.APPROVE, name("Applicant")), "an applicant approving themselves");
		expectRole(helper, server, applicant, Role.APPLICANT, "after approving themselves");
		expectDone(helper, Terminals.act(director, pos, ContractActions.APPROVE, name("Applicant")), "approving");
		expectRole(helper, server, applicant, Role.CREW, "after approval");
		if (!state(server, director).applicants().isEmpty()) {
			throw helper.assertionException("an approved application leaves the Director's list");
		}

		expectDone(helper, Terminals.act(applicant, pos, ContractActions.LEAVE, new CompoundTag()), "a member leaving");
		expectRole(helper, server, applicant, Role.NONE, "after leaving");
		helper.succeed();
	}

	@GameTest
	public void denyAndWithdrawEndAnApplication(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		BlockPos pos = place(helper);
		ServerPlayer director = visitor(helper, "Judge", pos).player();
		ServerPlayer first = visitor(helper, "Hopeful", pos).player();
		String charter = uniqueName("Judged");
		expectDone(helper, Terminals.act(director, pos, ContractActions.FOUND, name(charter)), "founding");
		expectDone(helper, Terminals.act(first, pos, ContractActions.APPLY, name(charter)), "applying");
		expectDone(helper, Terminals.act(director, pos, ContractActions.DENY, name("Hopeful")), "denying");
		expectRole(helper, server, first, Role.NONE, "after a denial");

		expectDone(helper, Terminals.act(first, pos, ContractActions.APPLY, name(charter)), "applying again");
		expectDone(helper, Terminals.act(first, pos, ContractActions.LEAVE, new CompoundTag()), "withdrawing");
		expectRole(helper, server, first, Role.NONE, "after withdrawing");
		if (!state(server, director).applicants().isEmpty()) {
			throw helper.assertionException("a withdrawn application leaves the Director's list");
		}
		helper.succeed();
	}

	@GameTest
	public void requestsItCannotTrustChangeNothing(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		BlockPos pos = place(helper);
		ServerPlayer director = visitor(helper, "Careful", pos).player();
		ServerPlayer stranger = visitor(helper, "Stranger", pos).player();
		String charter = uniqueName("Careful");
		expectDone(helper, Terminals.act(director, pos, ContractActions.FOUND, name(charter)), "founding");
		int charters = Charters.allOrThrow(server).size();

		CompoundTag wrongType = new CompoundTag();
		wrongType.put(ContractActions.NAME_KEY, IntTag.valueOf(7));
		expectRefused(helper, Terminals.act(stranger, pos, ContractActions.FOUND, new CompoundTag()), "founding with no name");
		expectRefused(helper, Terminals.act(stranger, pos, ContractActions.FOUND, wrongType), "founding with a name that is not text");
		expectRefused(helper, Terminals.act(stranger, pos, ContractActions.FOUND, name("   ")), "founding with a blank name");
		expectRefused(helper, Terminals.act(stranger, pos, ContractActions.FOUND, name("x".repeat(500))), "founding with an oversized name");
		expectRefused(helper, Terminals.act(stranger, pos, ContractActions.FOUND, name(charter.toUpperCase())), "founding a name another charter has");
		expectRefused(helper, Terminals.act(stranger, pos, ContractActions.APPLY, name(uniqueName("Nowhere"))), "applying to a charter that does not exist");
		expectRefused(helper, Terminals.act(stranger, pos, ContractActions.APPROVE, name("Stranger")), "approving with no charter");
		expectRefused(helper, Terminals.act(stranger, pos, ContractActions.DENY, new CompoundTag()), "denying with no name");
		expectRefused(helper, Terminals.act(stranger, pos, ContractActions.LEAVE, new CompoundTag()), "leaving with no charter");
		if (Charters.allOrThrow(server).size() != charters || Charters.charterOfOrThrow(server, stranger.getUUID()).isPresent()) {
			throw helper.assertionException("a refused request changes nothing");
		}

		expectDone(helper, Terminals.act(stranger, pos, ContractActions.APPLY, name(charter)), "applying");
		expectRefused(helper, Terminals.act(stranger, pos, ContractActions.APPLY, name(charter)), "applying twice");
		expectRefused(helper, Terminals.act(stranger, pos, ContractActions.FOUND, name(uniqueName("Rival"))), "founding while an application is open");
		expectRefused(helper, Terminals.act(director, pos, ContractActions.APPROVE, name("Nobody")), "approving a player who did not apply");
		expectRefused(helper, Terminals.act(director, pos, ContractActions.APPROVE, new CompoundTag()), "approving with no name");
		expectRole(helper, server, stranger, Role.APPLICANT, "after the refusals");
		helper.succeed();
	}

	@GameTest
	public void theListsAreCappedAndSayHowManyExist(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		BlockPos pos = place(helper);
		ServerPlayer viewer = visitor(helper, "Browser", pos).player();
		ServerPlayer director = visitor(helper, "Crowded", pos).player();
		int cap = ContractTerminalTuning.DEFAULT.listedRows();
		int before = state(server, viewer).charterCount();
		for (int i = 0; i < cap + 2; i++) {
			if (Charters.found(server, UUID.randomUUID(), uniqueName("Listed")).isPresent()) {
				throw helper.assertionException("founding a charter for a list test should succeed");
			}
		}
		ContractState seen = state(server, viewer);
		if (seen.charters().size() != cap || seen.charterCount() != before + cap + 2) {
			throw helper.assertionException("the charters offered are capped at %s and counted in full, got %s of %s (was %s)", cap, seen.charters().size(), seen.charterCount(), before);
		}

		expectDone(helper, Terminals.act(director, pos, ContractActions.FOUND, name(uniqueName("Crowded"))), "founding");
		Charter crowded = Charters.charterOfOrThrow(server, director.getUUID()).orElseThrow();
		for (int i = 0; i < cap + 2; i++) {
			if (Charters.apply(server, UUID.randomUUID(), crowded.id()).isPresent()) {
				throw helper.assertionException("an application for a list test should succeed");
			}
		}
		ContractState waiting = state(server, director);
		if (waiting.applicants().size() != cap || waiting.applicantCount() != cap + 2) {
			throw helper.assertionException("the applicants listed are capped at %s and counted in full, got %s of %s", cap, waiting.applicants().size(), waiting.applicantCount());
		}
		helper.succeed();
	}

	@GameTest
	public void anApplicationMadeAnyWayReachesTheDirector(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		BlockPos pos = place(helper);
		ServerPlayer director = visitor(helper, "Watcher", pos).player();
		ServerPlayer applicant = visitor(helper, "Caller", pos).player();
		expectDone(helper, Terminals.act(director, pos, ContractActions.FOUND, name(uniqueName("Watched"))), "founding");
		Charter charter = Charters.charterOfOrThrow(server, director.getUUID()).orElseThrow();
		PUSHED.clear();

		// Not through the terminal: the charter event alone must tell the Director.
		if (Charters.apply(server, applicant.getUUID(), charter.id()).isPresent()) {
			throw helper.assertionException("the application should succeed");
		}
		expectPushed(helper, director, state -> state.applicants().equals(List.of("Caller")) && state.applicantCount() == 1, "the Director sees the application at once");
		expectPushed(helper, applicant, state -> state.role() == Role.APPLICANT, "the applicant sees they are waiting");
		helper.succeed();
	}

	@GameTest
	public void withdrawingTellsTheApplicantAndTheDirector(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		BlockPos pos = place(helper);
		ServerPlayer director = visitor(helper, "Keeper", pos).player();
		ServerPlayer applicant = visitor(helper, "Wavering", pos).player();
		expectDone(helper, Terminals.act(director, pos, ContractActions.FOUND, name(uniqueName("Kept"))), "founding");
		expectDone(helper, Terminals.act(applicant, pos, ContractActions.APPLY, name(Charters.charterOfOrThrow(server, director.getUUID()).orElseThrow().name())), "applying");
		PUSHED.clear();

		expectDone(helper, Terminals.act(applicant, pos, ContractActions.LEAVE, new CompoundTag()), "withdrawing");
		expectPushed(helper, applicant, state -> state.role() == Role.NONE, "the applicant's screen leaves APPLICANT");
		expectPushed(helper, director, state -> state.applicants().isEmpty() && state.applicantCount() == 0, "the Director's list loses the applicant");
		helper.succeed();
	}

	@GameTest
	public void aViewerWithNoCharterSeesCharterListChanges(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		BlockPos pos = place(helper);
		ServerPlayer viewer = visitor(helper, "Window", pos).player();
		// A name that sorts first, so the five-row cap cannot hide it.
		String charter = "!" + uniqueName("Fresh");
		UUID founder = UUID.randomUUID();
		PUSHED.clear();

		if (Charters.found(server, founder, charter).isPresent()) {
			throw helper.assertionException("founding should succeed");
		}
		expectPushed(helper, viewer, state -> state.role() == Role.NONE && state.charters().contains(charter), "a newly founded charter appears for a player on no charter");

		PUSHED.clear();
		if (Charters.leave(server, founder).isPresent()) {
			throw helper.assertionException("the lone Director leaving should succeed");
		}
		expectPushed(helper, viewer, state -> state.role() == Role.NONE && !state.charters().contains(charter), "a dormant charter leaves the list");
		helper.succeed();
	}

	@GameTest
	public void aDirectorCannotAnswerAnotherCharterApplicant(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		BlockPos pos = place(helper);
		ServerPlayer directorA = visitor(helper, "DirectorA", pos).player();
		ServerPlayer directorB = visitor(helper, "DirectorB", pos).player();
		ServerPlayer applicant = visitor(helper, "Candidate", pos).player();
		expectDone(helper, Terminals.act(directorA, pos, ContractActions.FOUND, name(uniqueName("Alpha"))), "founding A");
		expectDone(helper, Terminals.act(directorB, pos, ContractActions.FOUND, name(uniqueName("Beta"))), "founding B");
		Charter a = Charters.charterOfOrThrow(server, directorA.getUUID()).orElseThrow();
		expectDone(helper, Terminals.act(applicant, pos, ContractActions.APPLY, name(a.name())), "applying to A");

		expectRefused(helper, Terminals.act(directorB, pos, ContractActions.APPROVE, name("Candidate")), "B approving A's applicant");
		expectRefused(helper, Terminals.act(directorB, pos, ContractActions.DENY, name("Candidate")), "B denying A's applicant");
		Charter after = Charters.findOrThrow(server, a.id()).orElseThrow();
		if (!after.applications().equals(List.of(applicant.getUUID())) || Charters.charterOfOrThrow(server, applicant.getUUID()).isPresent()) {
			throw helper.assertionException("A's application must be untouched by B");
		}
		helper.succeed();
	}

	/** The state last pushed to each player since the log was cleared. */
	private static final Map<UUID, ContractState> PUSHED = new HashMap<>();

	static {
		ContractTerminal.PUSHED.register((player, state) -> PUSHED.put(player.getUUID(), state));
	}

	private static void expectPushed(GameTestHelper helper, ServerPlayer player, Predicate<ContractState> wanted, String what) {
		ContractState last = PUSHED.get(player.getUUID());
		if (last == null || !wanted.test(last)) {
			throw helper.assertionException("%s: last state pushed to %s was %s", what, player.getGameProfile().name(), last);
		}
	}
}
