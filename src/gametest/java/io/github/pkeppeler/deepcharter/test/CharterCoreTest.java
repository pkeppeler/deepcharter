package io.github.pkeppeler.deepcharter.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.level.storage.SavedDataStorage;

import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.CharterData;
import io.github.pkeppeler.deepcharter.charter.CharterCommands;
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
		Charter founded = Charters.charterOfOrThrow(server, directorId).orElseThrow();
		if (!founded.director().equals(Optional.of(directorId)) || !founded.crew().isEmpty() || founded.account() != 0) {
			throw helper.assertionException("a new charter has its founder as Director, no crew and an empty account: %s", founded);
		}

		expectDone(helper, Charters.apply(server, crewId, founded.id()), "applying");
		if (Charters.charterOfOrThrow(server, crewId).isPresent()
				|| !Charters.findOrThrow(server, founded.id()).orElseThrow().applications().equals(List.of(crewId))) {
			throw helper.assertionException("an application is pending, not a membership");
		}
		expectDone(helper, Charters.approve(server, directorId, crewId), "approving");
		Charter joined = Charters.charterOfOrThrow(server, crewId).orElseThrow();
		if (!joined.id().equals(founded.id()) || !joined.crew().equals(List.of(crewId)) || !joined.applications().isEmpty()) {
			throw helper.assertionException("the applicant should now be crew: %s", joined);
		}

		expectDone(helper, Charters.leave(server, crewId), "the crew leaving");
		if (Charters.charterOfOrThrow(server, crewId).isPresent()
				|| !Charters.findOrThrow(server, founded.id()).orElseThrow().crew().isEmpty()) {
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
		Path dir = tempDir();
		try {
			try (SavedDataStorage first = storage(server, dir)) {
				CharterData data = first.computeIfAbsent(CharterData.TYPE);
				id = crewed(helper, data, director, crew);
				expectDone(helper, data.deposit(id, 750), "depositing");
				expectDone(helper, data.recordDeepestPoint(id, 312), "recording depth");
				first.saveAndJoin();
			}
			try (SavedDataStorage second = storage(server, dir)) {
				Charter loaded = second.computeIfAbsent(CharterData.TYPE).find(id)
						.orElseThrow(() -> helper.assertionException("the charter should have been saved"));
				if (!loaded.director().equals(Optional.of(director)) || !loaded.crew().equals(List.of(crew))
						|| loaded.account() != 750 || loaded.deepestPoint() != 312) {
					throw helper.assertionException("the charter should load as it was saved: %s", loaded);
				}
			}
		} finally {
			deleteTree(dir);
		}
		helper.succeed();
	}

	/**
	 * Guards the datafixer type of {@link CharterData#TYPE}: a file saved by an older Minecraft goes through the real fixer on
	 * load and must come out unchanged. Keep it green on every Minecraft bump.
	 */
	@GameTest
	public void aFileFromAnOlderMinecraftLoadsUnchanged(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		int olderDataVersion = 4000;
		Path dir = tempDir();
		try {
			CharterId id;
			try (SavedDataStorage first = storage(server, dir)) {
				CharterData data = first.computeIfAbsent(CharterData.TYPE);
				id = crewed(helper, data, UUID.randomUUID(), UUID.randomUUID());
				expectDone(helper, data.deposit(id, 99), "depositing");
				first.saveAndJoin();
			}
			Path file = savedFile(dir);
			CompoundTag stamped = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
			if (NbtUtils.getDataVersion(stamped) <= olderDataVersion) {
				throw helper.assertionException("the test needs a saved DataVersion above %s, got %s", olderDataVersion, NbtUtils.getDataVersion(stamped));
			}
			Tag savedBody = stamped.get("data");
			NbtIo.writeCompressed(NbtUtils.addDataVersion(stamped, olderDataVersion), file);

			try (SavedDataStorage second = storage(server, dir)) {
				CharterData loaded = second.computeIfAbsent(CharterData.TYPE);
				Tag reloaded = CharterData.CODEC.encodeStart(NbtOps.INSTANCE, loaded).getOrThrow();
				if (!reloaded.equals(savedBody) || loaded.find(id).isEmpty()) {
					throw helper.assertionException("the fixer changed the charter data: saved %s, loaded %s", savedBody, reloaded);
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} finally {
			deleteTree(dir);
		}
		helper.succeed();
	}

	private static SavedDataStorage storage(MinecraftServer server, Path dir) {
		return new SavedDataStorage(dir, server.getFixerUpper(), server.registryAccess());
	}

	private static Path tempDir() {
		try {
			return Files.createTempDirectory("charter-saved-data");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static Path savedFile(Path dir) throws IOException {
		try (Stream<Path> files = Files.walk(dir)) {
			return files.filter(path -> path.toString().endsWith(".dat")).findFirst().orElseThrow();
		}
	}

	private static void deleteTree(Path dir) {
		try (Stream<Path> files = Files.walk(dir)) {
			for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(path);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
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
	public void aDepositThatOverflowsTheAccountIsRefused(GameTestHelper helper) {
		CharterData data = new CharterData();
		CharterId id = crewed(helper, data, UUID.randomUUID());
		expectDone(helper, data.deposit(id, Long.MAX_VALUE), "filling the account");

		expectRefused(helper, CharterRefusal.ACCOUNT_FULL, data.deposit(id, 1), "depositing into a full account");
		if (data.find(id).orElseThrow().account() != Long.MAX_VALUE) {
			throw helper.assertionException("a refused deposit must not change the balance");
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
	public void aPlayerRevivesADormantCharterAndBecomesItsDirector(GameTestHelper helper) {
		CharterData data = new CharterData();
		UUID director = UUID.randomUUID();
		UUID reviver = UUID.randomUUID();
		UUID crew = UUID.randomUUID();
		CharterId id = crewed(helper, data, director);
		expectDone(helper, data.deposit(id, 40), "depositing");
		expectDone(helper, data.recordDeepestPoint(id, 120), "recording depth");
		String name = data.find(id).orElseThrow().name();

		expectRefused(helper, CharterRefusal.NOT_DORMANT, data.revive(reviver, id), "reviving a charter that has a Director");
		expectDone(helper, data.leave(director), "the last person leaving");
		expectRefused(helper, CharterRefusal.NO_SUCH_CHARTER, data.revive(reviver, CharterId.random()), "reviving nothing");
		crewed(helper, data, reviver);
		expectRefused(helper, CharterRefusal.ALREADY_ON_A_CHARTER, data.revive(reviver, id), "reviving as the Director of another charter");

		UUID second = UUID.randomUUID();
		expectDone(helper, data.revive(second, id), "reviving");
		Charter revived = data.find(id).orElseThrow();
		if (!revived.director().equals(Optional.of(second)) || !revived.crew().isEmpty() || !revived.applications().isEmpty()
				|| !revived.name().equals(name) || revived.account() != 40 || revived.deepestPoint() != 120) {
			throw helper.assertionException("the reviver should be Director of the same charter with its account and depth: %s", revived);
		}
		expectDone(helper, data.apply(crew, id), "applying to the revived charter");
		expectDone(helper, data.approve(second, crew), "the new Director approving");
		helper.succeed();
	}

	@GameTest
	public void aRevivingPlayerWithAnApplicationOpenIsRefused(GameTestHelper helper) {
		CharterData data = new CharterData();
		UUID director = UUID.randomUUID();
		UUID applicant = UUID.randomUUID();
		CharterId dormant = crewed(helper, data, director);
		CharterId open = crewed(helper, data, UUID.randomUUID());
		expectDone(helper, data.leave(director), "the last person leaving");
		expectDone(helper, data.apply(applicant, open), "applying");

		expectRefused(helper, CharterRefusal.ALREADY_ON_A_CHARTER, data.revive(applicant, dormant), "reviving with an application open");
		if (!data.find(dormant).orElseThrow().dormant()) {
			throw helper.assertionException("a refused revival changes nothing");
		}
		helper.succeed();
	}

	@GameTest
	public void revivingTellsListenersAndTheNewDirector(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		MockPlayer first = MockPlayers.join(helper, "ReviveFirst");
		MockPlayer second = MockPlayers.join(helper, "ReviveSecond");
		String name = uniqueName();
		List<String> seen = new ArrayList<>();
		CharterId[] mine = new CharterId[1];
		CharterEvents.REVIVED.register((s, charter, director) -> {
			if (charter.id().equals(mine[0])) {
				seen.add("revived " + director.equals(second.player().getUUID()) + " " + charter.director().equals(Optional.of(director)));
			}
		});
		try {
			expectDone(helper, Charters.found(server, first.player().getUUID(), name), "found");
			mine[0] = Charters.findByName(server, name).orElseThrow().id();
			expectRefused(helper, CharterRefusal.NOT_DORMANT, Charters.revive(server, second.player().getUUID(), mine[0]), "reviving a live charter");
			expectDone(helper, Charters.leave(server, first.player().getUUID()), "leave");
			expectDone(helper, Charters.revive(server, second.player().getUUID(), mine[0]), "revive");
			if (!seen.equals(List.of("revived true true"))) {
				throw helper.assertionException("exactly one revived event, after the change: %s", seen);
			}
			if (!Charters.charterOfOrThrow(server, second.player().getUUID()).orElseThrow().id().equals(mine[0])) {
				throw helper.assertionException("the reviver should be on the charter");
			}
			helper.succeed();
		} finally {
			first.leave();
			second.leave();
		}
	}

	@GameTest
	public void theReviveCommandAnswersInChat(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		MockPlayer director = MockPlayers.join(helper, "CmdReviveOld");
		MockPlayer reviver = MockPlayers.join(helper, "CmdReviveNew");
		String name = uniqueName();
		try {
			run(server, director, "found \"" + name + "\"");
			List<Component> live = run(server, reviver, LevelBasedPermissionSet.GAMEMASTER, "revive \"" + name + "\"");
			if (live.stream().noneMatch(message -> message.contains(CharterRefusal.NOT_DORMANT.message()))) {
				throw helper.assertionException("reviving a live charter should say %s, got %s", CharterRefusal.NOT_DORMANT, live);
			}
			List<Component> missing = run(server, reviver, LevelBasedPermissionSet.GAMEMASTER, "revive \"" + name + " nowhere\"");
			if (missing.stream().noneMatch(message -> message.contains(CharterRefusal.NO_SUCH_CHARTER.message()))) {
				throw helper.assertionException("reviving nothing should say %s, got %s", CharterRefusal.NO_SUCH_CHARTER, missing);
			}
			run(server, director, "leave");
			List<Component> done = run(server, reviver, LevelBasedPermissionSet.GAMEMASTER, "revive \"" + name.toUpperCase() + "\"");
			if (done.stream().noneMatch(message -> message.contains(Component.translatable("deepcharter.charter.revive.success", name)))) {
				throw helper.assertionException("the success reply should name the charter, got %s", done);
			}
			Charter revived = Charters.charterOfOrThrow(server, reviver.player().getUUID()).orElseThrow();
			if (!revived.name().equals(name) || !revived.isDirector(reviver.player().getUUID())) {
				throw helper.assertionException("the command should make the player Director of %s: %s", name, revived);
			}
			helper.succeed();
		} finally {
			director.leave();
			reviver.leave();
		}
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
		mine[0] = Charters.charterOfOrThrow(server, director).orElseThrow().id();
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

		Charter charter = Charters.charterOfOrThrow(server, crew.player().getUUID())
				.orElseThrow(() -> helper.assertionException("the commands should have made the crew member join"));
		if (!charter.name().equals(name) || charter.account() != 90) {
			throw helper.assertionException("the deposit works and the overdraft is refused: %s", charter);
		}
		run(server, crew, "leave");
		if (Charters.charterOfOrThrow(server, crew.player().getUUID()).isPresent()) {
			throw helper.assertionException("/leave should remove the crew member");
		}
		helper.succeed();
	}

	@GameTest
	public void countsReadSingularForOneAndPluralOtherwise(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		MockPlayer director = MockPlayers.join(helper, "CountDirector");
		MockPlayer crew = MockPlayers.join(helper, "CountCrew");
		String name = uniqueName();

		expectText(helper, "1 charter", CharterCommands.count(CharterCommands.Noun.CHARTER, 1));
		expectText(helper, "0 charters", CharterCommands.count(CharterCommands.Noun.CHARTER, 0));
		expectText(helper, "2 charters", CharterCommands.count(CharterCommands.Noun.CHARTER, 2));

		run(server, director, "found \"" + name + "\"");
		run(server, crew, "apply \"" + name + "\"");
		expectLine(helper, name + ": 1 person, 1 application, account 0, deepest point",
				run(server, director, LevelBasedPermissionSet.GAMEMASTER, "info"), true);
		expectLine(helper, name + ": 1 person, account 0", run(server, director, LevelBasedPermissionSet.GAMEMASTER, "list"), false);

		run(server, director, "approve CountCrew");
		expectLine(helper, name + ": 2 people, 0 applications, account 0, deepest point",
				run(server, director, LevelBasedPermissionSet.GAMEMASTER, "info"), true);
		expectLine(helper, name + ": 2 people, account 0", run(server, director, LevelBasedPermissionSet.GAMEMASTER, "list"), false);
		helper.succeed();
	}

	@GameTest
	public void everyCountNounHasSingularAndPluralText(GameTestHelper helper) {
		for (CharterCommands.Noun noun : CharterCommands.Noun.values()) {
			for (int amount : new int[] {1, 2}) {
				String text = CharterCommands.count(noun, amount).getString();
				if (text.contains("deepcharter.") || !text.startsWith(amount + " ")) {
					throw helper.assertionException("%s x%d has no lang text, got \"%s\"", noun, amount, text);
				}
			}
		}
		helper.succeed();
	}

	private static void expectText(GameTestHelper helper, String expected, Component actual) {
		if (!actual.getString().equals(expected)) {
			throw helper.assertionException("expected \"%s\", got \"%s\"", expected, actual.getString());
		}
	}

	private static void expectLine(GameTestHelper helper, String expected, List<Component> messages, boolean prefix) {
		boolean found = messages.stream().map(Component::getString)
				.anyMatch(line -> prefix ? line.startsWith(expected) : line.equals(expected));
		if (!found) {
			throw helper.assertionException("expected a line \"%s\", got %s", expected, messages.stream().map(Component::getString).toList());
		}
	}

	@GameTest
	public void theCommandsNeedGamemasterPermission(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		MockPlayer player = MockPlayers.join(helper, "CmdNoRights");

		run(server, player, LevelBasedPermissionSet.ALL, "found \"" + uniqueName() + "\"");
		if (Charters.charterOfOrThrow(server, player.player().getUUID()).isPresent()) {
			throw helper.assertionException("a player without permission must not found a charter");
		}
		helper.succeed();
	}

	@GameTest
	public void aCrewMemberCannotApproveByCommand(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		MockPlayer director = MockPlayers.join(helper, "CmdBoss");
		MockPlayer crew = MockPlayers.join(helper, "CmdHand");
		MockPlayer applicant = MockPlayers.join(helper, "CmdHopeful");
		UUID directorId = director.player().getUUID();
		UUID crewId = crew.player().getUUID();
		UUID applicantId = applicant.player().getUUID();
		expectDone(helper, Charters.found(server, directorId, uniqueName()), "founding");
		CharterId id = Charters.charterOfOrThrow(server, directorId).orElseThrow().id();
		expectDone(helper, Charters.apply(server, crewId, id), "the crew member applying");
		expectDone(helper, Charters.approve(server, directorId, crewId), "approving the crew member");
		expectDone(helper, Charters.apply(server, applicantId, id), "the applicant applying");

		List<Component> messages = run(server, crew, LevelBasedPermissionSet.GAMEMASTER, "approve CmdHopeful");
		if (messages.stream().noneMatch(message -> message.contains(CharterRefusal.NOT_THE_DIRECTOR.message()))) {
			throw helper.assertionException("the command should report %s, got %s", CharterRefusal.NOT_THE_DIRECTOR, messages);
		}
		if (Charters.charterOfOrThrow(server, applicantId).isPresent()) {
			throw helper.assertionException("the applicant must not join on a crew member's say");
		}
		helper.succeed();
	}

	/** Runs {@code /deepcharter charter <arguments>} as {@code player} with {@code permission}, and returns what the command said. */
	private static List<Component> run(MinecraftServer server, MockPlayer player, PermissionSet permission, String arguments) {
		List<Component> messages = new ArrayList<>();
		CommandSource recorder = new CommandSource() {
			@Override
			public void sendSystemMessage(Component message) {
				messages.add(message);
			}

			@Override
			public boolean acceptsSuccess() {
				return true;
			}

			@Override
			public boolean acceptsFailure() {
				return true;
			}

			@Override
			public boolean shouldInformAdmins() {
				return false;
			}
		};
		CommandSourceStack source = player.player().createCommandSourceStack().withPermission(permission).withSource(recorder);
		server.getCommands().performPrefixedCommand(source, "deepcharter charter " + arguments);
		return messages;
	}

	private static void run(MinecraftServer server, MockPlayer player, String arguments) {
		run(server, player, LevelBasedPermissionSet.GAMEMASTER, arguments);
	}
}
