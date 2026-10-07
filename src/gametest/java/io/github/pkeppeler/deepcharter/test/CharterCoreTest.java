package io.github.pkeppeler.deepcharter.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.level.storage.SavedDataStorage;

import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.CharterData;
import io.github.pkeppeler.deepcharter.charter.CharterEvents;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.CharterRefusal;
import io.github.pkeppeler.deepcharter.charter.CharterTuning;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;

/**
 * Server GameTests for #52: two mock players found, join and leave a charter, the charter survives a restart of the saved data,
 * an overdraft is refused, and the succession, dormancy and one-charter-per-player rules hold.
 */
public class CharterCoreTest {
	private static String uniqueName() {
		return "Test " + UUID.randomUUID().toString().substring(0, 8);
	}

	private static void expectDone(GameTestHelper helper, Optional<CharterRefusal> refusal, String what) {
		if (refusal.isPresent()) {
			throw helper.assertionException("%s should succeed, was refused: %s", what, refusal.get());
		}
	}

	private static void expectRefused(GameTestHelper helper, CharterRefusal expected, Optional<CharterRefusal> actual, String what) {
		if (!actual.equals(Optional.of(expected))) {
			throw helper.assertionException("%s should be refused with %s, got %s", what, expected, actual);
		}
	}

	/** A charter with the Director and the given crew, joined in that order. */
	private static CharterId crewed(GameTestHelper helper, CharterData data, UUID director, UUID... crew) {
		CharterId id = CharterId.random();
		expectDone(helper, data.found(director, uniqueName(), id), "found");
		for (UUID member : crew) {
			expectDone(helper, data.apply(member, id), "apply");
			expectDone(helper, data.approve(director, member), "approve");
		}
		return id;
	}

	@GameTest
	public void twoPlayersFoundJoinAndLeave(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		MockPlayer director = MockPlayers.join(helper, "Director");
		MockPlayer crew = MockPlayers.join(helper, "Crew");
		UUID directorId = director.player().getUUID();
		UUID crewId = crew.player().getUUID();

		expectDone(helper, Charters.found(server, directorId, uniqueName()), "founding");
		Charter founded = Charters.charterOf(server, directorId).orElseThrow();
		if (!founded.director().equals(Optional.of(directorId)) || !founded.crew().isEmpty() || founded.account() != 0) {
			throw helper.assertionException("a new charter has its founder as Director, no crew and an empty account: %s", founded);
		}

		expectDone(helper, Charters.apply(server, crewId, founded.id()), "applying");
		if (Charters.charterOf(server, crewId).isPresent()
				|| !Charters.find(server, founded.id()).orElseThrow().applications().equals(List.of(crewId))) {
			throw helper.assertionException("an application is pending, not a membership");
		}
		expectDone(helper, Charters.approve(server, directorId, crewId), "approving");
		Charter joined = Charters.charterOf(server, crewId).orElseThrow();
		if (!joined.id().equals(founded.id()) || !joined.crew().equals(List.of(crewId)) || !joined.applications().isEmpty()) {
			throw helper.assertionException("the applicant should now be crew: %s", joined);
		}

		expectDone(helper, Charters.leave(server, crewId), "the crew leaving");
		if (Charters.charterOf(server, crewId).isPresent()
				|| !Charters.find(server, founded.id()).orElseThrow().crew().isEmpty()) {
			throw helper.assertionException("the crew member should be gone");
		}
		helper.succeed();
	}

	@GameTest
	public void theCharterSurvivesARestart(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		UUID director = UUID.randomUUID();
		UUID crew = UUID.randomUUID();
		CharterId id;
		Path dir;
		try {
			dir = Files.createTempDirectory("charter-restart");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		try (SavedDataStorage first = new SavedDataStorage(dir, server.getFixerUpper(), server.registryAccess())) {
			CharterData data = first.computeIfAbsent(CharterData.TYPE);
			id = crewed(helper, data, director, crew);
			expectDone(helper, data.deposit(id, 750), "depositing");
			expectDone(helper, data.recordDeepestPoint(id, 312), "recording depth");
			first.saveAndJoin();
		}
		try (SavedDataStorage second = new SavedDataStorage(dir, server.getFixerUpper(), server.registryAccess())) {
			Charter loaded = second.computeIfAbsent(CharterData.TYPE).find(id)
					.orElseThrow(() -> helper.assertionException("the charter should have been saved"));
			if (!loaded.director().equals(Optional.of(director)) || !loaded.crew().equals(List.of(crew))
					|| loaded.account() != 750 || loaded.deepestPoint() != 312) {
				throw helper.assertionException("the charter should load as it was saved: %s", loaded);
			}
		}
		helper.succeed();
	}

	@GameTest
	public void anOverdraftIsRefusedAndLeavesTheAccountAlone(GameTestHelper helper) {
		CharterData data = new CharterData();
		CharterId id = crewed(helper, data, UUID.randomUUID());
		expectDone(helper, data.deposit(id, 100), "depositing");

		expectRefused(helper, CharterRefusal.INSUFFICIENT_FUNDS, data.spend(id, 101), "spending more than the balance");
		if (data.find(id).orElseThrow().account() != 100) {
			throw helper.assertionException("a refused overdraft must not change the balance");
		}
		expectDone(helper, data.spend(id, 100), "spending the whole balance");
		expectRefused(helper, CharterRefusal.INSUFFICIENT_FUNDS, data.spend(id, 1), "spending from an empty account");
		expectRefused(helper, CharterRefusal.INVALID_AMOUNT, data.spend(id, 0), "spending nothing");
		expectRefused(helper, CharterRefusal.INVALID_AMOUNT, data.spend(id, -5), "spending a negative amount");
		expectRefused(helper, CharterRefusal.INVALID_AMOUNT, data.deposit(id, -5), "depositing a negative amount");
		if (data.find(id).orElseThrow().account() != 0) {
			throw helper.assertionException("the account should be empty, not negative");
		}
		helper.succeed();
	}

	@GameTest
	public void theLongestServingCrewTakesOverWhenTheDirectorLeaves(GameTestHelper helper) {
		CharterData data = new CharterData();
		UUID director = UUID.randomUUID();
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		CharterId id = crewed(helper, data, director, first, second);

		expectDone(helper, data.leave(director), "the Director leaving");
		Charter after = data.find(id).orElseThrow();
		if (!after.director().equals(Optional.of(first)) || !after.crew().equals(List.of(second)) || after.dormant()) {
			throw helper.assertionException("the longest-serving crew member should be Director: %s", after);
		}
		if (data.charterOf(director).isPresent()) {
			throw helper.assertionException("the old Director should be off the charter");
		}
		helper.succeed();
	}

	@GameTest
	public void anEmptyCharterGoesDormantAndKeepsItsAccount(GameTestHelper helper) {
		CharterData data = new CharterData();
		UUID director = UUID.randomUUID();
		UUID applicant = UUID.randomUUID();
		CharterId id = crewed(helper, data, director);
		expectDone(helper, data.deposit(id, 40), "depositing");
		expectDone(helper, data.apply(applicant, id), "applying");

		expectDone(helper, data.leave(director), "the last person leaving");
		Charter dormant = data.find(id).orElseThrow();
		if (!dormant.dormant() || dormant.account() != 40 || !dormant.applications().isEmpty()) {
			throw helper.assertionException("an empty charter is dormant, keeps its account and drops its applications: %s", dormant);
		}
		expectRefused(helper, CharterRefusal.CHARTER_DORMANT, data.apply(applicant, id), "applying to a dormant charter");
		helper.succeed();
	}

	@GameTest
	public void thePeopleRulesHold(GameTestHelper helper) {
		CharterData data = new CharterData();
		UUID director = UUID.randomUUID();
		UUID crew = UUID.randomUUID();
		UUID stranger = UUID.randomUUID();
		CharterId id = CharterId.random();
		String name = uniqueName();
		expectDone(helper, data.found(director, name, id), "founding");

		expectRefused(helper, CharterRefusal.ALREADY_ON_A_CHARTER, data.found(director, uniqueName(), CharterId.random()), "founding twice");
		expectRefused(helper, CharterRefusal.NAME_TAKEN, data.found(stranger, name.toUpperCase(), CharterId.random()), "reusing a name");
		expectRefused(helper, CharterRefusal.INVALID_NAME, data.found(stranger, "   ", CharterId.random()), "a blank name");
		expectRefused(helper, CharterRefusal.INVALID_NAME,
				data.found(stranger, "x".repeat(CharterTuning.DEFAULT.maxNameLength() + 1), CharterId.random()), "a long name");
		expectRefused(helper, CharterRefusal.NO_SUCH_CHARTER, data.apply(stranger, CharterId.random()), "applying to nothing");
		expectRefused(helper, CharterRefusal.ALREADY_ON_A_CHARTER, data.apply(director, id), "the Director applying");

		expectDone(helper, data.apply(crew, id), "applying");
		expectRefused(helper, CharterRefusal.ALREADY_APPLIED, data.apply(crew, id), "applying twice");
		expectRefused(helper, CharterRefusal.NOT_THE_DIRECTOR, data.approve(stranger, crew), "a stranger approving");
		expectRefused(helper, CharterRefusal.NO_APPLICATION, data.approve(director, stranger), "approving nobody");
		expectDone(helper, data.deny(director, crew), "denying");
		expectRefused(helper, CharterRefusal.NO_APPLICATION, data.approve(director, crew), "approving a denied application");

		expectDone(helper, data.apply(crew, id), "applying again");
		expectDone(helper, data.leave(crew), "withdrawing an application");
		if (!data.find(id).orElseThrow().applications().isEmpty()) {
			throw helper.assertionException("a withdrawn application should be gone");
		}
		expectRefused(helper, CharterRefusal.NOT_ON_A_CHARTER, data.leave(stranger), "leaving without a charter");
		helper.succeed();
	}

	@GameTest
	public void dataOfAnotherVersionIsKeptAndFailsLoud(GameTestHelper helper) {
		CompoundTag future = new CompoundTag();
		future.putInt("version", CharterData.VERSION + 1);
		future.putString("shape", "from a newer build");

		CharterData data = CharterData.CODEC.parse(NbtOps.INSTANCE, future).getOrThrow();
		boolean threw = false;
		try {
			data.find(CharterId.random());
		} catch (IllegalStateException e) {
			threw = e.getMessage().contains(Integer.toString(CharterData.VERSION + 1));
		}
		if (!threw) {
			throw helper.assertionException("using unreadable charter data should throw and name its version");
		}
		Tag written = CharterData.CODEC.encodeStart(NbtOps.INSTANCE, data).getOrThrow();
		if (!future.equals(written)) {
			throw helper.assertionException("unreadable data must be written back unchanged, got %s", written);
		}
		helper.succeed();
	}

	@GameTest
	public void eventsFireForEachChange(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		UUID director = UUID.randomUUID();
		UUID crew = UUID.randomUUID();
		// Fabric events cannot be unregistered, so the listeners record only this test's charter.
		CharterId[] mine = new CharterId[1];
		List<String> seen = new ArrayList<>();
		CharterEvents.FOUNDED.register((s, charter) -> {
			if (charter.director().equals(Optional.of(director))) {
				seen.add("founded");
			}
		});
		CharterEvents.APPLIED.register((s, charter, player) -> record(seen, mine, charter, "applied"));
		CharterEvents.JOINED.register((s, charter, player) -> record(seen, mine, charter, "joined"));
		CharterEvents.LEFT.register((s, charter, player) -> record(seen, mine, charter, "left"));
		CharterEvents.DIRECTOR_CHANGED.register((s, charter, previous, next) -> record(seen, mine, charter, "director"));
		CharterEvents.WENT_DORMANT.register((s, charter) -> record(seen, mine, charter, "dormant"));
		CharterEvents.ACCOUNT_CHANGED.register((s, charter, delta) -> record(seen, mine, charter, "account " + delta));

		expectDone(helper, Charters.found(server, director, uniqueName()), "founding");
		mine[0] = Charters.charterOf(server, director).orElseThrow().id();
		expectDone(helper, Charters.apply(server, crew, mine[0]), "applying");
		expectDone(helper, Charters.approve(server, director, crew), "approving");
		expectDone(helper, Charters.deposit(server, mine[0], 25), "depositing");
		expectDone(helper, Charters.spend(server, mine[0], 5), "spending");
		expectDone(helper, Charters.leave(server, director), "the Director leaving");
		expectDone(helper, Charters.leave(server, crew), "the last person leaving");

		List<String> expected = List.of("founded", "applied", "joined", "account 25", "account -5", "left", "director", "left", "dormant");
		if (!seen.equals(expected)) {
			throw helper.assertionException("events: expected %s, got %s", expected, seen);
		}
		helper.succeed();
	}

	private static void record(List<String> seen, CharterId[] mine, Charter charter, String what) {
		if (charter.id().equals(mine[0])) {
			seen.add(what);
		}
	}

	@GameTest
	public void theCommandsDriveTheSameOperations(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		MockPlayer director = MockPlayers.join(helper, "CmdDirector");
		MockPlayer crew = MockPlayers.join(helper, "CmdCrew");
		String name = uniqueName();

		run(server, director, "found \"" + name + "\"");
		run(server, crew, "apply \"" + name + "\"");
		run(server, director, "approve CmdCrew");
		run(server, director, "account deposit \"" + name + "\" 90");
		run(server, director, "account spend \"" + name + "\" 200");

		Charter charter = Charters.charterOf(server, crew.player().getUUID())
				.orElseThrow(() -> helper.assertionException("the commands should have made the crew member join"));
		if (!charter.name().equals(name) || charter.account() != 90) {
			throw helper.assertionException("the deposit works and the overdraft is refused: %s", charter);
		}
		run(server, crew, "leave");
		if (Charters.charterOf(server, crew.player().getUUID()).isPresent()) {
			throw helper.assertionException("/leave should remove the crew member");
		}
		helper.succeed();
	}

	private static void run(MinecraftServer server, MockPlayer player, String arguments) {
		var source = player.player().createCommandSourceStack().withPermission(LevelBasedPermissionSet.GAMEMASTER);
		server.getCommands().performPrefixedCommand(source, "deepcharter charter " + arguments);
	}
}
