package io.github.pkeppeler.deepcharter.test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.charter.CharterData;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.CharterRefusal;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.layer.BreachEvents;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.Zones;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.UnreadableChecks;
import io.github.pkeppeler.deepcharter.test.support.WorldData;
import io.github.pkeppeler.deepcharter.transmission.Transmission;
import io.github.pkeppeler.deepcharter.transmission.TransmissionCatalog;
import io.github.pkeppeler.deepcharter.transmission.TransmissionData;
import io.github.pkeppeler.deepcharter.transmission.TransmissionEvents;
import io.github.pkeppeler.deepcharter.transmission.TransmissionTriggers;
import io.github.pkeppeler.deepcharter.transmission.Transmissions;

/**
 * Server GameTests for #62: each transmission fires once and in order, repair transmissions replay for a charter founded later,
 * bonuses are credited exactly once (also across a restart, a replay and a full account), every member of a charter is sent what they
 * missed on their own login, and unreadable or broken data never fails a tick, a login or a crossing.
 *
 * <p>Every test of the run shares one world. A test that needs exact state passes a {@link TransmissionData} of its own and makes its
 * charters with {@link #directCharter}, so no hook touches the world's data. A test that goes through the hooks swaps a fresh
 * {@link TransmissionData} into the world's storage for the length of one server tick, in {@link #withFreshWorldData}, and puts the old
 * one back: nothing a test writes is seen by another, and no production reset exists. The two real-trigger tests span many ticks, so they
 * use the world's data as it is and only look at their own player.
 */
public class TransmissionsTest {
	/** What each player has been sent, in order. A Fabric event cannot be unregistered, so this filters by player UUID. */
	private static final Map<UUID, List<Identifier>> DELIVERED = new ConcurrentHashMap<>();
	/** Players for whom the test's second listener throws, to see that a throwing listener loses nothing. */
	private static final Set<UUID> THROWING = ConcurrentHashMap.newKeySet();
	/** Real ticks for a zone or breach trigger to fire: the zone poll every 20 ticks, or a fall and a crossing. */
	private static final int TRIGGER_TICKS = 400;
	/** Columns of the two real-trigger tests. No other test class uses X or Z from 6000 to 6999. */
	private static final double BREACH_COLUMN = 6400.5;
	private static final double ZONE_COLUMN = 6600.5;
	private static final String LANG_RESOURCE = "/assets/deepcharter/lang/en_us.json";

	static {
		TransmissionEvents.DELIVERED.register((server, charter, player, transmission) ->
				DELIVERED.computeIfAbsent(player.getUUID(), uuid -> new CopyOnWriteArrayList<>()).add(transmission.id()));
		TransmissionEvents.DELIVERED.register((server, charter, player, transmission) -> {
			if (THROWING.contains(player.getUUID())) {
				throw new IllegalStateException("a listener of the test throws");
			}
		});
	}

	private static Identifier id(String name) {
		return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, name);
	}

	private static List<Identifier> ids(String... names) {
		return Stream.of(names).map(TransmissionsTest::id).toList();
	}

	private static String uniqueName() {
		return "Tx " + UUID.randomUUID().toString().substring(0, 8);
	}

	private static MinecraftServer server(GameTestHelper helper) {
		return helper.getLevel().getServer();
	}

	/**
	 * A charter made straight in the world's charter data, so no charter event fires and none of this feature's own hooks run. The
	 * Director and the crew are the given players, in that order.
	 */
	private static CharterId directCharter(GameTestHelper helper, UUID director, UUID... crew) {
		CharterData data = CharterData.get(server(helper));
		CharterId id = CharterId.random();
		expectDone(helper, data.found(director, uniqueName(), id), "founding");
		for (UUID member : crew) {
			expectDone(helper, data.apply(member, id), "applying");
			expectDone(helper, data.approve(director, member), "approving");
		}
		return id;
	}

	/** A charter founded through {@code Charters}, with its events, and so with this feature's founding hook. */
	private static CharterId foundedCharter(GameTestHelper helper, UUID founder) {
		String name = uniqueName();
		expectDone(helper, Charters.found(server(helper), founder, name), "founding");
		return Charters.findByName(server(helper), name).orElseThrow().id();
	}

	/**
	 * Runs {@code body} with {@code replacement} as the world's transmission data and puts the world's own data back afterwards, in the
	 * same server tick, so no other test sees either.
	 */
	private static void withWorldData(GameTestHelper helper, TransmissionData replacement, Consumer<TransmissionData> body) {
		WorldData.with(server(helper), TransmissionData.TYPE, replacement, () -> body.accept(replacement));
	}

	private static void withFreshWorldData(GameTestHelper helper, Consumer<TransmissionData> body) {
		withWorldData(helper, new TransmissionData(), body);
	}

	private static void expectDone(GameTestHelper helper, Optional<CharterRefusal> refusal, String what) {
		if (refusal.isPresent()) {
			throw helper.assertionException("%s should succeed, was refused: %s", what, refusal.get());
		}
	}

	private static List<Identifier> deliveredTo(MockPlayer player) {
		return DELIVERED.getOrDefault(player.player().getUUID(), List.of());
	}

	private static long account(GameTestHelper helper, CharterId charter) {
		return Charters.findOrThrow(server(helper), charter).orElseThrow().account();
	}

	@GameTest
	public void theCatalogListsT01ToT18AndTheSurfaceArrivalWithTheThreeBonuses(GameTestHelper helper) {
		List<Identifier> expected = Stream.concat(
				Stream.iterate(1, number -> number + 1).limit(18).map(number -> id(String.format("t%02d", number))),
				Stream.of(id("surface_arrival"))).toList();
		List<Identifier> actual = TransmissionCatalog.all().stream().map(Transmission::id).toList();
		if (!actual.equals(expected)) {
			throw helper.assertionException("the catalog should list T01 to T18 in order, then the surface arrival, lists %s", actual);
		}
		// Written out here, not read from the tuning, so the test checks the amounts rather than repeating them.
		Map<Transmission.Bonus, Long> amounts = Map.of(Transmission.Bonus.B1, 1_000L, Transmission.Bonus.B2, 3_000L, Transmission.Bonus.B3, 10_000L);
		amounts.forEach((bonus, amount) -> {
			if (bonus.amount() != amount) {
				throw helper.assertionException("%s should be worth %s, is %s", bonus, amount, bonus.amount());
			}
			long carriers = TransmissionCatalog.all().stream().filter(transmission -> transmission.bonus().equals(Optional.of(bonus))).count();
			if (carriers != 1) {
				throw helper.assertionException("%s should come with exactly one transmission, comes with %s", bonus, carriers);
			}
		});
		for (Transmission.Framing framing : Transmission.Framing.values()) {
			if (TransmissionCatalog.all().stream().noneMatch(transmission -> transmission.framing() == framing)) {
				throw helper.assertionException("no transmission is framed %s", framing);
			}
		}
		for (Transmission.Trigger.Kind kind : Transmission.Trigger.Kind.values()) {
			if (TransmissionCatalog.all().stream().noneMatch(transmission -> transmission.trigger().kind() == kind)) {
				throw helper.assertionException("no transmission has a %s trigger", kind);
			}
		}
		helper.succeed();
	}

	/** A missing lang key would reach a player as a thrown packet handler, so it fails here, in the tests. */
	@GameTest
	public void everyTransmissionHasItsLangKeys(GameTestHelper helper) {
		JsonObject lang;
		try (InputStream stream = TransmissionsTest.class.getResourceAsStream(LANG_RESOURCE)) {
			if (stream == null) {
				throw helper.assertionException("missing resource %s", LANG_RESOURCE);
			}
			try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
				lang = JsonParser.parseReader(reader).getAsJsonObject();
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		for (Transmission transmission : TransmissionCatalog.all()) {
			for (String key : List.of(transmission.senderKey(), transmission.framing().headerKey(), transmission.bodyKey())) {
				if (!lang.has(key) || lang.get(key).getAsString().isBlank()) {
					throw helper.assertionException("%s needs the lang key %s", transmission.id(), key);
				}
			}
			boolean placeholder = transmission.id().getPath().matches("t\\d\\d");
			if (placeholder && (!lang.get(transmission.bodyKey()).getAsString().contains("[CHARTER]") || !lang.get(transmission.bodyKey()).getAsString().contains("[DIRECTOR]"))) {
				throw helper.assertionException("the placeholder text of %s should show both fields", transmission.id());
			}
		}
		helper.succeed();
	}

	@GameTest
	public void eachTransmissionFiresOnceAndInOrder(GameTestHelper helper) {
		MinecraftServer server = server(helper);
		TransmissionData data = new TransmissionData();
		MockPlayer crew = MockPlayers.join(helper, "tx-order");
		CharterId charter = directCharter(helper, UUID.randomUUID(), crew.player().getUUID());

		Transmissions.fire(server, data, charter, id("t03"));
		Transmissions.fire(server, data, charter, id("t01"));
		Transmissions.fire(server, data, charter, id("t03"));
		Transmissions.fire(server, data, charter, id("t04"));
		Transmissions.fire(server, data, charter, id("t01"));

		if (!deliveredTo(crew).equals(ids("t03", "t01", "t04"))) {
			throw helper.assertionException("with a member online each should be sent at once, once, in order: %s", deliveredTo(crew));
		}
		TransmissionData.Progress progress = data.progress(charter);
		if (!progress.fired().equals(ids("t03", "t01", "t04")) || !progress.unsent(crew.player().getUUID()).isEmpty()) {
			throw helper.assertionException("the fired set keeps all three and the member has been sent them: %s", progress);
		}
		helper.succeed();
	}

	/** A charter of three, one online when a transmission fires: the two offline members are each sent it on their next login, once. */
	@GameTest
	public void everyMemberIsSentWhatTheyMissedOnTheirOwnLogin(GameTestHelper helper) {
		MinecraftServer server = server(helper);
		TransmissionData data = new TransmissionData();
		MockPlayer director = MockPlayers.join(helper, "tx-m1");
		MockPlayer second = MockPlayers.join(helper, "tx-m2");
		MockPlayer third = MockPlayers.join(helper, "tx-m3");
		CharterId charter = directCharter(helper, director.player().getUUID(), second.player().getUUID(), third.player().getUUID());
		// The two crew members are offline when it fires. They keep the player objects they logged in with, which a login passes in.
		second.leave();
		third.leave();

		Transmissions.fire(server, data, charter, id("t17"));
		if (!deliveredTo(director).equals(ids("t17")) || !deliveredTo(second).isEmpty() || !deliveredTo(third).isEmpty()) {
			throw helper.assertionException("only the online member is sent it at once: %s, %s, %s", deliveredTo(director), deliveredTo(second), deliveredTo(third));
		}

		Transmissions.deliverTo(server, data, charter, second.player());
		Transmissions.deliverTo(server, data, charter, second.player());
		if (!deliveredTo(second).equals(ids("t17")) || !deliveredTo(third).isEmpty()) {
			throw helper.assertionException("the second member's login sends it once, and only to them: %s, %s", deliveredTo(second), deliveredTo(third));
		}
		Transmissions.fire(server, data, charter, id("t18"));
		Transmissions.deliverTo(server, data, charter, third.player());
		Transmissions.deliverTo(server, data, charter, second.player());
		if (!deliveredTo(third).equals(ids("t17", "t18")) || !deliveredTo(second).equals(ids("t17", "t18"))
				|| !deliveredTo(director).equals(ids("t17", "t18"))) {
			throw helper.assertionException("each member has been sent each once, in order: %s, %s, %s",
					deliveredTo(director), deliveredTo(second), deliveredTo(third));
		}
		helper.succeed();
	}

	/** A member who joins later starts at the beginning and is sent the charter's story so far. Leaving forgets how far they were. */
	@GameTest
	public void aMemberWhoJoinsLaterIsSentTheStorySoFar(GameTestHelper helper) {
		MinecraftServer server = server(helper);
		withFreshWorldData(helper, data -> {
			UUID director = UUID.randomUUID();
			CharterId charter = directCharter(helper, director);
			Transmissions.fire(server, charter, id("t17"));
			Transmissions.fire(server, charter, id("t18"));

			MockPlayer crew = MockPlayers.join(helper, "tx-join");
			UUID crewId = crew.player().getUUID();
			expectDone(helper, Charters.apply(server, crewId, charter), "applying");
			expectDone(helper, Charters.approve(server, director, crewId), "approving");
			if (!deliveredTo(crew).equals(ids("t17", "t18"))) {
				throw helper.assertionException("joining sends the story so far, in order, once each: %s", deliveredTo(crew));
			}
			if (data.progress(charter).cursors().get(crewId) != 2) {
				throw helper.assertionException("the member has been sent both: %s", data.progress(charter));
			}

			expectDone(helper, Charters.leave(server, crewId), "leaving");
			if (data.progress(charter).cursors().containsKey(crewId)) {
				throw helper.assertionException("leaving should forget the member's place: %s", data.progress(charter));
			}
			expectDone(helper, Charters.apply(server, crewId, charter), "applying again");
			expectDone(helper, Charters.approve(server, director, crewId), "approving again");
			if (!deliveredTo(crew).equals(ids("t17", "t18", "t17", "t18"))) {
				throw helper.assertionException("a member who returns is sent the story again from the start: %s", deliveredTo(crew));
			}
		});
		helper.succeed();
	}

	@GameTest
	public void theFrozenSignatureFiresOnTheRunningServer(GameTestHelper helper) {
		withFreshWorldData(helper, data -> {
			CharterId charter = directCharter(helper, UUID.randomUUID());
			Transmissions.fire(charter, id("t17"));
			Transmissions.fire(charter, id("t17"));
			if (!data.progress(charter).fired().equals(ids("t17"))) {
				throw helper.assertionException("fire(CharterId, Identifier) should fire once on the running server");
			}
		});
		helper.succeed();
	}

	@GameTest
	public void firingForAnUnknownCharterOrTransmissionFailsLoud(GameTestHelper helper) {
		MinecraftServer server = server(helper);
		TransmissionData data = new TransmissionData();
		CharterId charter = directCharter(helper, UUID.randomUUID());
		expectThrows(helper, "an unknown charter", () -> Transmissions.fire(server, data, CharterId.random(), id("t01")));
		expectThrows(helper, "an unknown transmission", () -> Transmissions.fire(server, data, charter, id("t99")));
		if (!data.progress(charter).fired().isEmpty()) {
			throw helper.assertionException("a failed fire must change nothing");
		}
		helper.succeed();
	}

	@GameTest
	public void repairTransmissionsReplayForACharterFoundedLater(GameTestHelper helper) {
		MinecraftServer server = server(helper);
		TransmissionData data = new TransmissionData();
		CharterId first = directCharter(helper, UUID.randomUUID());
		Transmissions.fire(server, data, first, id("t08"));
		Transmissions.fire(server, data, first, id("t03"));
		Transmissions.fire(server, data, first, id("t02"));
		if (!data.replays().equals(ids("t08", "t02"))) {
			throw helper.assertionException("the repair transmissions that fired are replayed in order, and t03 is not one: %s", data.replays());
		}

		MockPlayer founder = MockPlayers.join(helper, "tx-replay");
		CharterId later = directCharter(helper, founder.player().getUUID());
		Transmissions.replayTo(server, data, later);
		if (!deliveredTo(founder).equals(ids("t08", "t02"))) {
			throw helper.assertionException("the new charter is sent the repairs in the order they first fired: %s", deliveredTo(founder));
		}
		if (account(helper, later) != 0) {
			throw helper.assertionException("a replay pays no bonus, the account is %s", account(helper, later));
		}
		// A replayed transmission has fired for the new charter: firing it again changes nothing, and replaying again adds nothing.
		Transmissions.fire(server, data, later, id("t08"));
		Transmissions.replayTo(server, data, later);
		if (deliveredTo(founder).size() != 2 || account(helper, later) != 0) {
			throw helper.assertionException("t08 should not be sent or paid again to the new charter: %s", deliveredTo(founder));
		}
		if (!data.progress(later).fired().equals(ids("t08", "t02"))) {
			throw helper.assertionException("the replays belong to the new charter's fired set: %s", data.progress(later));
		}
		helper.succeed();
	}

	/** The whole path with the hooks: a charter founded through {@code Charters} is sent what the world fired. */
	@GameTest
	public void foundingACharterSendsItTheRepairsTheWorldHasFired(GameTestHelper helper) {
		MinecraftServer server = server(helper);
		withFreshWorldData(helper, data -> {
			CharterId first = directCharter(helper, UUID.randomUUID());
			Transmissions.fire(server, first, id("t12"));
			Transmissions.fire(server, first, id("t18"));

			MockPlayer founder = MockPlayers.join(helper, "tx-found");
			CharterId later = foundedCharter(helper, founder.player().getUUID());
			if (!deliveredTo(founder).equals(ids("t12"))) {
				throw helper.assertionException("founding should send t12, a repair, and not t18: %s", deliveredTo(founder));
			}
			Transmissions.fire(server, later, id("t12"));
			if (deliveredTo(founder).size() != 1) {
				throw helper.assertionException("t12 should not be sent twice: %s", deliveredTo(founder));
			}
		});
		helper.succeed();
	}

	@GameTest
	public void bonusesAreCreditedExactlyOnce(GameTestHelper helper) {
		MinecraftServer server = server(helper);
		TransmissionData data = new TransmissionData();
		CharterId charter = directCharter(helper, UUID.randomUUID());

		for (int repeat = 0; repeat < 3; repeat++) {
			Transmissions.fire(server, data, charter, id("t05"));
		}
		if (account(helper, charter) != 1_000) {
			throw helper.assertionException("B1 is $1,000, credited once: the account is %s", account(helper, charter));
		}
		Transmissions.fire(server, data, charter, id("t08"));
		if (account(helper, charter) != 4_000) {
			throw helper.assertionException("B2 is $3,000: the account is %s", account(helper, charter));
		}
		Transmissions.fire(server, data, charter, id("t14"));
		if (account(helper, charter) != 14_000) {
			throw helper.assertionException("B3 is $10,000: the account is %s", account(helper, charter));
		}
		for (String name : List.of("t05", "t08", "t14", "t05")) {
			Transmissions.fire(server, data, charter, id(name));
		}
		if (account(helper, charter) != 14_000) {
			throw helper.assertionException("nothing is credited twice: the account is %s", account(helper, charter));
		}

		// A charter founded later is replayed t08, which carries B2, and is not paid for it.
		CharterId later = directCharter(helper, UUID.randomUUID());
		Transmissions.replayTo(server, data, later);
		if (!data.progress(later).fired().equals(ids("t08")) || account(helper, later) != 0) {
			throw helper.assertionException("the replay should reach the new charter without paying it: %s, account %s",
					data.progress(later), account(helper, later));
		}
		helper.succeed();
	}

	@GameTest
	public void bonusesAreNotCreditedAgainAfterARestart(GameTestHelper helper) {
		MinecraftServer server = server(helper);
		CharterId charter = directCharter(helper, UUID.randomUUID());
		Path dir = tempDir();
		try {
			try (SavedDataStorage first = storage(server, dir)) {
				Transmissions.fire(server, first.computeIfAbsent(TransmissionData.TYPE), charter, id("t05"));
				first.saveAndJoin();
			}
			if (account(helper, charter) != 1_000) {
				throw helper.assertionException("B1 should be credited once before the restart: %s", account(helper, charter));
			}
			try (SavedDataStorage second = storage(server, dir)) {
				TransmissionData data = second.computeIfAbsent(TransmissionData.TYPE);
				Transmissions.fire(server, data, charter, id("t05"));
				if (account(helper, charter) != 1_000 || !data.progress(charter).fired().equals(ids("t05"))) {
					throw helper.assertionException("B1 must not be credited again after a restart: account %s, %s", account(helper, charter), data.progress(charter));
				}
			}
		} finally {
			deleteTree(dir);
		}
		helper.succeed();
	}

	/**
	 * A bonus the account cannot take is kept, and paid once when there is room: on the next fire, login or zone poll. The account is
	 * filled to the brim, so a deposit of any size is refused.
	 */
	@GameTest
	public void aBonusThatAFullAccountRefusesIsKeptAndPaidOnce(GameTestHelper helper) {
		MinecraftServer server = server(helper);
		TransmissionData data = new TransmissionData();
		CharterId charter = directCharter(helper, UUID.randomUUID());
		expectDone(helper, Charters.deposit(server, charter, Long.MAX_VALUE), "filling the account");

		Transmissions.fire(server, data, charter, id("t05"));
		if (account(helper, charter) != Long.MAX_VALUE || !data.progress(charter).pending().equals(List.of(Transmission.Bonus.B1))
				|| !data.progress(charter).fired().equals(ids("t05"))) {
			throw helper.assertionException("the refused B1 should wait, the transmission still fired: account %s, %s", account(helper, charter), data.progress(charter));
		}
		// Still full: the next fire tries again and keeps it.
		Transmissions.fire(server, data, charter, id("t17"));
		if (!data.progress(charter).pending().equals(List.of(Transmission.Bonus.B1))) {
			throw helper.assertionException("a full account keeps the bonus waiting: %s", data.progress(charter));
		}
		expectDone(helper, Charters.spend(server, charter, 1_000), "making room");
		Transmissions.fire(server, data, charter, id("t18"));
		if (account(helper, charter) != Long.MAX_VALUE || !data.progress(charter).pending().isEmpty()) {
			throw helper.assertionException("B1 should be paid once there is room: account %s, %s", account(helper, charter), data.progress(charter));
		}
		expectDone(helper, Charters.spend(server, charter, 1_000), "making room again");
		Transmissions.fire(server, data, charter, id("t05"));
		Transmissions.fire(server, data, charter, id("t01"));
		if (account(helper, charter) != Long.MAX_VALUE - 1_000) {
			throw helper.assertionException("a paid bonus is not paid again: %s", account(helper, charter));
		}

		// A login and a zone poll retry as well.
		TransmissionData other = new TransmissionData();
		CharterId second = directCharter(helper, UUID.randomUUID());
		expectDone(helper, Charters.deposit(server, second, Long.MAX_VALUE), "filling the second account");
		Transmissions.fire(server, other, second, id("t05"));
		expectDone(helper, Charters.spend(server, second, 1_000), "making room for the second");
		Transmissions.payPending(server, other, second);
		if (account(helper, second) != Long.MAX_VALUE || !other.progress(second).pending().isEmpty()) {
			throw helper.assertionException("paying what is pending should credit B1: %s", account(helper, second));
		}
		helper.succeed();
	}

	@GameTest
	public void theFiredSetTheCursorsAndThePendingBonusesSurviveARestart(GameTestHelper helper) {
		MinecraftServer server = server(helper);
		CharterId charter = CharterId.random();
		UUID member = UUID.randomUUID();
		Path dir = tempDir();
		try {
			try (SavedDataStorage first = storage(server, dir)) {
				TransmissionData data = first.computeIfAbsent(TransmissionData.TYPE);
				requireFired(helper, data, charter, "t01", true);
				requireFired(helper, data, charter, "t02", true);
				data.markSent(charter, member, 2);
				requireFired(helper, data, charter, "t03", true);
				data.addPending(charter, Transmission.Bonus.B2);
				first.saveAndJoin();
			}
			try (SavedDataStorage second = storage(server, dir)) {
				TransmissionData data = second.computeIfAbsent(TransmissionData.TYPE);
				TransmissionData.Progress progress = data.progress(charter);
				if (!progress.fired().equals(ids("t01", "t02", "t03")) || !progress.unsent(member).equals(ids("t03"))
						|| !progress.pending().equals(List.of(Transmission.Bonus.B2))) {
					throw helper.assertionException("the state should load as saved: %s", progress);
				}
				if (!data.replays().equals(ids("t02"))) {
					throw helper.assertionException("the replay list should load as saved: %s", data.replays());
				}
				requireFired(helper, data, charter, "t02", false);
				requireFired(helper, data, charter, "t03", false);
			}
		} finally {
			deleteTree(dir);
		}
		helper.succeed();
	}

	@GameTest
	public void dataOfAnotherVersionIsKeptAndFailsLoudOnExplicitUse(GameTestHelper helper) {
		for (CompoundTag saved : List.of(versioned(TransmissionData.VERSION + 1), versioned(0), new CompoundTag())) {
			saved.putString("shape", "from another build");
			TransmissionData data = TransmissionData.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
			if (data.isReadable()) {
				throw helper.assertionException("data of another version must not be usable: %s", saved);
			}
			expectThrows(helper, "reading unreadable transmission data", () -> data.progress(CharterId.random()));
			expectThrows(helper, "firing into unreadable transmission data", () -> data.fire(CharterId.random(), TransmissionCatalog.require(id("t01"))));
			Tag written = TransmissionData.CODEC.encodeStart(NbtOps.INSTANCE, data).getOrThrow();
			if (!saved.equals(written)) {
				throw helper.assertionException("unreadable data must be written back unchanged: saved %s, written %s", saved, written);
			}
		}
		CompoundTag brokenBody = versioned(TransmissionData.VERSION);
		brokenBody.putString("charters", "not a list");
		TransmissionData data = TransmissionData.CODEC.parse(NbtOps.INSTANCE, brokenBody).getOrThrow();
		expectThrows(helper, "using a body that does not parse", data::replays);
		if (data.isReadable() || !brokenBody.equals(TransmissionData.CODEC.encodeStart(NbtOps.INSTANCE, data).getOrThrow())) {
			throw helper.assertionException("a body that does not parse is unusable and must be written back unchanged");
		}
		helper.succeed();
	}

	/**
	 * With unreadable data in the world, nothing a player does may throw: a zone poll, a login, a crossing, a founding and a direct fire
	 * each do nothing and leave the data as it was. Only an explicit read of the data throws.
	 */
	@GameTest
	public void unreadableDataNeverFailsATickALoginOrACrossing(GameTestHelper helper) {
		MinecraftServer server = server(helper);
		TransmissionData unreadable = TransmissionData.CODEC.parse(NbtOps.INSTANCE, UnreadableChecks.futureData()).getOrThrow();
		MockPlayer crew = MockPlayers.join(helper, "tx-unreadable");
		CharterId charter = directCharter(helper, crew.player().getUUID());
		ServerLevel one = server.getLevel(LayerChain.dimension(1));
		ServerLevel two = server.getLevel(LayerChain.dimension(2));
		crew.player().setNoGravity(true);
		crew.teleportTo(two, new Vec3(BREACH_COLUMN, two.getMinY() + two.getHeight() / 2, BREACH_COLUMN), 0, 0);

		expectThrows(helper, "an explicit read of unreadable data", () -> unreadable.progress(charter));
		TransmissionData world = server.getDataStorage().computeIfAbsent(TransmissionData.TYPE);
		Map<String, Runnable> paths = new LinkedHashMap<>();
		paths.put("zone poll", () -> TransmissionTriggers.pollZones(server));
		paths.put("login", () -> Transmissions.deliverOnLogin(server, crew.player()));
		paths.put("crossing", () -> BreachEvents.CROSSED.invoker().onCrossed(crew.player(), one, two, 1, 2));
		paths.put("fire", () -> Transmissions.fire(charter, id("t01")));
		paths.put("deliver", () -> Transmissions.deliver(server, TransmissionData.get(server), charter));
		paths.put("replay", () -> Transmissions.replayTo(server, TransmissionData.get(server), charter));
		paths.put("pay pending", () -> Transmissions.payPending(server, TransmissionData.get(server), charter));
		paths.put("forget", () -> Transmissions.forget(server, TransmissionData.get(server), charter, crew.player().getUUID()));
		AtomicInteger visits = new AtomicInteger();
		paths.put("charter events", () -> {
			int visit = visits.incrementAndGet();
			MockPlayer founder = MockPlayers.join(helper, "tx-unreadable-founder-" + visit);
			foundedCharter(helper, founder.player().getUUID());
			if (visit == 1) {
				UUID applicant = UUID.randomUUID();
				expectDone(helper, Charters.apply(server, applicant, charter), "applying");
				expectDone(helper, Charters.approve(server, crew.player().getUUID(), applicant), "approving");
				expectDone(helper, Charters.leave(server, crew.player().getUUID()), "leaving");
			}
		});
		UnreadableChecks.assertSavedDataNoThrow(helper, "transmissions", server, TransmissionData.TYPE, paths);
		if (world != server.getDataStorage().computeIfAbsent(TransmissionData.TYPE)) {
			throw helper.assertionException("the world's own data must be put back");
		}
		if (!deliveredTo(crew).isEmpty()) {
			throw helper.assertionException("nothing is sent from unreadable data: %s", deliveredTo(crew));
		}
		helper.succeed();
	}

	/**
	 * A saved id that the data file no longer lists is skipped, and the rest are sent; a listener that throws loses nothing.
	 */
	@GameTest
	public void aMissingIdIsSkippedAndAThrowingListenerLosesNothing(GameTestHelper helper) {
		MinecraftServer server = server(helper);
		MockPlayer crew = MockPlayers.join(helper, "tx-broken");
		UUID crewId = crew.player().getUUID();
		CharterId charter = directCharter(helper, crewId);

		CompoundTag entry = new CompoundTag();
		entry.put("charter", CharterId.CODEC.encodeStart(NbtOps.INSTANCE, charter).getOrThrow());
		ListTag fired = new ListTag();
		fired.add(StringTag.valueOf("deepcharter:t99"));
		fired.add(StringTag.valueOf("deepcharter:t01"));
		entry.put("fired", fired);
		entry.put("cursors", new ListTag());
		entry.put("pending_bonuses", new ListTag());
		ListTag charters = new ListTag();
		charters.add(entry);
		CompoundTag saved = versioned(TransmissionData.VERSION);
		saved.put("charters", charters);
		saved.put("replays", new ListTag());
		TransmissionData data = TransmissionData.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();

		Transmissions.deliver(server, data, charter);
		if (!deliveredTo(crew).equals(ids("t01")) || !data.progress(charter).unsent(crewId).isEmpty()) {
			throw helper.assertionException("t99 should be skipped and t01 sent: %s, %s", deliveredTo(crew), data.progress(charter));
		}

		THROWING.add(crewId);
		try {
			Transmissions.fire(server, data, charter, id("t06"));
			Transmissions.fire(server, data, charter, id("t07"));
		} finally {
			THROWING.remove(crewId);
		}
		if (!deliveredTo(crew).equals(ids("t01", "t06", "t07")) || !data.progress(charter).unsent(crewId).isEmpty()) {
			throw helper.assertionException("a throwing listener should lose nothing: %s, %s", deliveredTo(crew), data.progress(charter));
		}
		helper.succeed();
	}

	/**
	 * Guards the datafixer type of {@link TransmissionData#TYPE}: a file saved by an older Minecraft goes through the real fixer on
	 * load and must come out unchanged. Keep it green on every Minecraft bump.
	 */
	@GameTest
	public void aFileFromAnOlderMinecraftLoadsUnchanged(GameTestHelper helper) {
		MinecraftServer server = server(helper);
		int olderDataVersion = 4000;
		CharterId charter = CharterId.random();
		Path dir = tempDir();
		try {
			try (SavedDataStorage first = storage(server, dir)) {
				TransmissionData data = first.computeIfAbsent(TransmissionData.TYPE);
				requireFired(helper, data, charter, "t02", true);
				requireFired(helper, data, charter, "t05", true);
				data.markSent(charter, UUID.randomUUID(), 1);
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
				TransmissionData loaded = second.computeIfAbsent(TransmissionData.TYPE);
				Tag reloaded = TransmissionData.CODEC.encodeStart(NbtOps.INSTANCE, loaded).getOrThrow();
				if (!reloaded.equals(savedBody) || !loaded.progress(charter).fired().equals(ids("t02", "t05"))) {
					throw helper.assertionException("the fixer changed the transmission data: saved %s, loaded %s", savedBody, reloaded);
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} finally {
			deleteTree(dir);
		}
		helper.succeed();
	}

	@GameTest(maxTicks = TRIGGER_TICKS)
	public void aBreachCrossingFiresItsTransmissionAndItsBonusForTheCharter(GameTestHelper helper) {
		MinecraftServer server = server(helper);
		ServerLevel one = server.getLevel(LayerChain.dimension(1));
		ServerLevel two = server.getLevel(LayerChain.dimension(2));
		// Generate the arrival area in layer 2 now so the crossing does not wait on it.
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				two.getChunk((int) BREACH_COLUMN / 16 + dx, (int) BREACH_COLUMN / 16 + dz);
			}
		}
		MockPlayer crew = MockPlayers.join(helper, "tx-breach");
		CharterId charter = foundedCharter(helper, crew.player().getUUID());
		crew.player().setPermanentlyInvulnerable(true);
		crew.teleportTo(one, new Vec3(BREACH_COLUMN, one.getMinY() - 1, BREACH_COLUMN), 0, 0);
		helper.succeedWhen(() -> {
			List<Identifier> delivered = deliveredTo(crew);
			if (!delivered.contains(id("t05"))) {
				throw helper.assertionException("crossing into layer 2 should send t05, sent %s", delivered);
			}
			if (delivered.stream().filter(id("t05")::equals).count() != 1 || account(helper, charter) != 1_000) {
				throw helper.assertionException("t05 is sent once and pays B1 once: %s, account %s", delivered, account(helper, charter));
			}
		});
	}

	/** The climb out of layer 1 into the surface fires the surface arrival; the climb from layer 3 to 2 fires nothing, and neither does a descent into 1. */
	@GameTest
	public void climbingOutIntoTheSurfaceFiresTheSurfaceArrivalAndNoOtherAscentDoes(GameTestHelper helper) {
		MinecraftServer server = server(helper);
		ServerLevel surface = server.getLevel(LayerChain.dimension(LayerChain.SURFACE));
		ServerLevel one = server.getLevel(LayerChain.dimension(1));
		ServerLevel two = server.getLevel(LayerChain.dimension(2));
		withFreshWorldData(helper, data -> {
			MockPlayer crew = MockPlayers.join(helper, "tx-surface");
			CharterId charter = directCharter(helper, crew.player().getUUID());
			BreachEvents.CROSSED.invoker().onCrossed(crew.player(), two, one, 2, 1);
			BreachEvents.CROSSED.invoker().onCrossed(crew.player(), surface, one, LayerChain.SURFACE, 1);
			BreachEvents.CROSSED.invoker().onCrossed(crew.player(), two, one, 3, 2);
			if (!deliveredTo(crew).isEmpty()) {
				throw helper.assertionException("only the climb into the surface sends something, sent %s", deliveredTo(crew));
			}
			BreachEvents.CROSSED.invoker().onCrossed(crew.player(), one, surface, 1, LayerChain.SURFACE);
			BreachEvents.CROSSED.invoker().onCrossed(crew.player(), one, surface, 1, LayerChain.SURFACE);
			if (!deliveredTo(crew).equals(ids("surface_arrival")) || !data.progress(charter).fired().equals(ids("surface_arrival"))) {
				throw helper.assertionException("climbing out of layer 1 should send the surface arrival once: %s", deliveredTo(crew));
			}
		});
		helper.succeed();
	}

	@GameTest(maxTicks = TRIGGER_TICKS)
	public void standingInAZoneFiresItsTransmissionForTheCharter(GameTestHelper helper) {
		MinecraftServer server = server(helper);
		ServerLevel two = server.getLevel(LayerChain.dimension(2));
		MockPlayer crew = MockPlayers.join(helper, "tx-zone");
		foundedCharter(helper, crew.player().getUUID());
		crew.player().setPermanentlyInvulnerable(true);
		// Held in place, so the player stays in the zone it was put in.
		crew.player().setNoGravity(true);
		// The middle third of layer 2 is zone 1 and the bottom third is zone 2.
		int middle = two.getMinY() + two.getHeight() / 2;
		int bottom = two.getMinY() + 4;
		if (Zones.of(two, middle).orElseThrow().index() != 1 || Zones.of(two, bottom).orElseThrow().index() != 2) {
			throw helper.assertionException("the test's heights should be in zones 1 and 2");
		}
		crew.teleportTo(two, new Vec3(ZONE_COLUMN, middle, ZONE_COLUMN), 0, 0);
		boolean[] moved = {false};
		helper.succeedWhen(() -> {
			List<Identifier> delivered = deliveredTo(crew);
			if (!delivered.contains(id("t07"))) {
				throw helper.assertionException("standing in zone 1 of layer 2 should send t07, sent %s", delivered);
			}
			if (!moved[0]) {
				moved[0] = true;
				crew.teleportTo(two, new Vec3(ZONE_COLUMN, bottom, ZONE_COLUMN), 0, 0);
			}
			if (!delivered.contains(id("t09"))) {
				throw helper.assertionException("standing in zone 2 of layer 2 should send t09, sent %s", delivered);
			}
			if (delivered.contains(id("t06"))) {
				throw helper.assertionException("t06 is for zone 0, which this player never entered: %s", delivered);
			}
		});
	}

	private static void requireFired(GameTestHelper helper, TransmissionData data, CharterId charter, String name, boolean expected) {
		boolean fired = data.fire(charter, TransmissionCatalog.require(id(name)));
		if (fired != expected) {
			throw helper.assertionException("firing %s should return %s, returned %s", name, expected, fired);
		}
	}

	private static CompoundTag versioned(int version) {
		CompoundTag tag = new CompoundTag();
		tag.putInt("version", version);
		return tag;
	}

	private static void expectThrows(GameTestHelper helper, String what, Runnable action) {
		try {
			action.run();
		} catch (IllegalStateException | IllegalArgumentException e) {
			return;
		}
		throw helper.assertionException("%s should throw", what);
	}

	private static SavedDataStorage storage(MinecraftServer server, Path dir) {
		return new SavedDataStorage(dir, server.getFixerUpper(), server.registryAccess());
	}

	private static Path tempDir() {
		try {
			return Files.createTempDirectory("transmission-saved-data");
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
}
