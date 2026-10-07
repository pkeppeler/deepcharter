package io.github.pkeppeler.deepcharter.test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
		args.putString(ContractTerminal.NAME_KEY, name);
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
		expectDone(helper, Terminals.act(player, pos, ContractTerminal.FOUND, name(charter)), "founding a charter");
		Optional<Charter> found = Charters.charterOf(server, player.getUUID());
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
		expectDone(helper, Terminals.act(director, pos, ContractTerminal.FOUND, name(charter)), "founding");

		expectDone(helper, Terminals.act(applicant, pos, ContractTerminal.APPLY, name(charter)), "applying");
		ContractState waiting = state(server, applicant);
		if (waiting.role() != Role.APPLICANT || !waiting.charter().equals(charter)) {
			throw helper.assertionException("the applicant should be waiting on %s, got %s", charter, waiting);
		}
		List<String> applicants = state(server, director).applicants();
		if (!applicants.equals(List.of("Applicant"))) {
			throw helper.assertionException("the Director should see exactly Applicant, got %s", applicants);
		}

		expectRefused(helper, Terminals.act(applicant, pos, ContractTerminal.APPROVE, name("Applicant")), "an applicant approving themselves");
		expectRole(helper, server, applicant, Role.APPLICANT, "after approving themselves");
		expectDone(helper, Terminals.act(director, pos, ContractTerminal.APPROVE, name("Applicant")), "approving");
		expectRole(helper, server, applicant, Role.CREW, "after approval");
		if (!state(server, director).applicants().isEmpty()) {
			throw helper.assertionException("an approved application leaves the Director's list");
		}

		expectDone(helper, Terminals.act(applicant, pos, ContractTerminal.LEAVE, new CompoundTag()), "a member leaving");
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
		expectDone(helper, Terminals.act(director, pos, ContractTerminal.FOUND, name(charter)), "founding");
		expectDone(helper, Terminals.act(first, pos, ContractTerminal.APPLY, name(charter)), "applying");
		expectDone(helper, Terminals.act(director, pos, ContractTerminal.DENY, name("Hopeful")), "denying");
		expectRole(helper, server, first, Role.NONE, "after a denial");

		expectDone(helper, Terminals.act(first, pos, ContractTerminal.APPLY, name(charter)), "applying again");
		expectDone(helper, Terminals.act(first, pos, ContractTerminal.LEAVE, new CompoundTag()), "withdrawing");
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
		expectDone(helper, Terminals.act(director, pos, ContractTerminal.FOUND, name(charter)), "founding");
		int charters = Charters.all(server).size();

		CompoundTag wrongType = new CompoundTag();
		wrongType.put(ContractTerminal.NAME_KEY, IntTag.valueOf(7));
		expectRefused(helper, Terminals.act(stranger, pos, ContractTerminal.FOUND, new CompoundTag()), "founding with no name");
		expectRefused(helper, Terminals.act(stranger, pos, ContractTerminal.FOUND, wrongType), "founding with a name that is not text");
		expectRefused(helper, Terminals.act(stranger, pos, ContractTerminal.FOUND, name("   ")), "founding with a blank name");
		expectRefused(helper, Terminals.act(stranger, pos, ContractTerminal.FOUND, name("x".repeat(500))), "founding with an oversized name");
		expectRefused(helper, Terminals.act(stranger, pos, ContractTerminal.FOUND, name(charter.toUpperCase())), "founding a name another charter has");
		expectRefused(helper, Terminals.act(stranger, pos, ContractTerminal.APPLY, name(uniqueName("Nowhere"))), "applying to a charter that does not exist");
		expectRefused(helper, Terminals.act(stranger, pos, ContractTerminal.APPROVE, name("Stranger")), "approving with no charter");
		expectRefused(helper, Terminals.act(stranger, pos, ContractTerminal.DENY, new CompoundTag()), "denying with no name");
		expectRefused(helper, Terminals.act(stranger, pos, ContractTerminal.LEAVE, new CompoundTag()), "leaving with no charter");
		if (Charters.all(server).size() != charters || Charters.charterOf(server, stranger.getUUID()).isPresent()) {
			throw helper.assertionException("a refused request changes nothing");
		}

		expectDone(helper, Terminals.act(stranger, pos, ContractTerminal.APPLY, name(charter)), "applying");
		expectRefused(helper, Terminals.act(stranger, pos, ContractTerminal.APPLY, name(charter)), "applying twice");
		expectRefused(helper, Terminals.act(stranger, pos, ContractTerminal.FOUND, name(uniqueName("Rival"))), "founding while an application is open");
		expectRefused(helper, Terminals.act(director, pos, ContractTerminal.APPROVE, name("Nobody")), "approving a player who did not apply");
		expectRefused(helper, Terminals.act(director, pos, ContractTerminal.APPROVE, new CompoundTag()), "approving with no name");
		expectRole(helper, server, stranger, Role.APPLICANT, "after the refusals");
		helper.succeed();
	}

	@GameTest
	public void theListsAreCapped(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		BlockPos pos = place(helper);
		ServerPlayer viewer = visitor(helper, "Browser", pos).player();
		int cap = ContractTerminalTuning.DEFAULT.listedRows();
		for (int i = 0; i < cap + 2; i++) {
			if (Charters.found(server, UUID.randomUUID(), uniqueName("Listed")).isPresent()) {
				throw helper.assertionException("founding a charter for a list test should succeed");
			}
		}
		int listed = state(server, viewer).charters().size();
		if (listed != cap) {
			throw helper.assertionException("the charters offered are capped at %s, got %s", cap, listed);
		}
		helper.succeed();
	}
}
