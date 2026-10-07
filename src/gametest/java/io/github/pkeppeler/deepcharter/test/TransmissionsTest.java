package io.github.pkeppeler.deepcharter.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
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
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.Zones;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.transmission.Transmission;
import io.github.pkeppeler.deepcharter.transmission.TransmissionCatalog;
import io.github.pkeppeler.deepcharter.transmission.TransmissionData;
import io.github.pkeppeler.deepcharter.transmission.TransmissionEvents;
import io.github.pkeppeler.deepcharter.transmission.Transmissions;

/**
 * Server GameTests for #62: each transmission fires once and in order, repair transmissions replay for a charter founded later,
 * bonuses are credited exactly once (also across a restart and a replay), and a charter is sent its queue when a member is online.
 *
 * <p>Every test of the run shares one world, and the world's transmission data remembers the repair transmissions of all of them. A
 * test that needs exact state therefore passes a {@link TransmissionData} of its own, and makes its charters with
 * {@link #directCharter} so no founding or joining hook touches the world's data. The tests of the hooks use the world's data and
 * assert only on what must be there.
 */
public class TransmissionsTest {
	/** What each player has been sent, in order. A Fabric event cannot be unregistered, so this filters by player UUID. */
	private static final Map<UUID, List<Identifier>> DELIVERED = new ConcurrentHashMap<>();
	/** Real ticks for a zone or breach trigger to fire: the zone poll every 20 ticks, or a fall and a crossing. */
	private static final int TRIGGER_TICKS = 400;

	static {
		TransmissionEvents.DELIVERED.register((server, charter, player, transmission) ->
				DELIVERED.computeIfAbsent(player.getUUID(), uuid -> new CopyOnWriteArrayList<>()).add(transmission.id()));
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

	private static void expectDone(GameTestHelper helper, Optional<CharterRefusal> refusal, String what) {
		if (refusal.isPresent()) {
			throw helper.assertionException("%s should succeed, was refused: %s", what, refusal.get());
		}
	}

	private static List<Identifier> deliveredTo(MockPlayer player) {
		return DELIVERED.getOrDefault(player.player().getUUID(), List.of());
	}

	private static long account(GameTestHelper helper, CharterId charter) {
		return Charters.find(server(helper), charter).orElseThrow().account();
	}

	@GameTest
	public void theCatalogListsT01ToT18WithTheThreeBonuses(GameTestHelper helper) {
		List<Identifier> expected = Stream.iterate(1, number -> number + 1).limit(18).map(number -> id(String.format("t%02d", number))).toList();
		List<Identifier> actual = TransmissionCatalog.all().stream().map(Transmission::id).toList();
		if (!actual.equals(expected)) {
			throw helper.assertionException("the catalog should list T01 to T18 in order, lists %s", actual);
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
		if (!progress.fired().equals(ids("t03", "t01", "t04")) || !progress.queue().isEmpty()) {
			throw helper.assertionException("the fired set keeps all three and the sent queue is empty: %s", progress);
		}
		helper.succeed();
	}

	@GameTest
	public void aQueueWaitsForAMemberAndIsDeliveredInOrder(GameTestHelper helper) {
		MinecraftServer server = server(helper);
		TransmissionData data = new TransmissionData();
		UUID absent = UUID.randomUUID();
		CharterId charter = directCharter(helper, absent);

		Transmissions.fire(server, data, charter, id("t06"));
		Transmissions.fire(server, data, charter, id("t01"));
		Transmissions.fire(server, data, charter, id("t06"));
		Transmissions.fire(server, data, charter, id("t07"));
		TransmissionData.Progress waiting = data.progress(charter);
		if (!waiting.fired().equals(ids("t06", "t01", "t07")) || !waiting.queue().equals(ids("t06", "t01", "t07"))) {
			throw helper.assertionException("with nobody online the queue holds each once, in order: %s", waiting);
		}

		// A member comes online: the queue is sent in order, once each, and is then empty.
		MockPlayer crew = MockPlayers.join(helper, "tx-queue");
		UUID crewId = crew.player().getUUID();
		CharterData charters = CharterData.get(server);
		expectDone(helper, charters.apply(crewId, charter), "applying");
		expectDone(helper, charters.approve(absent, crewId), "approving");
		Transmissions.deliver(server, data, charter);
		if (!deliveredTo(crew).equals(ids("t06", "t01", "t07"))) {
			throw helper.assertionException("the queue should arrive in order, once each: %s", deliveredTo(crew));
		}
		Transmissions.deliver(server, data, charter);
		TransmissionData.Progress after = data.progress(charter);
		if (deliveredTo(crew).size() != 3 || !after.queue().isEmpty() || !after.fired().equals(ids("t06", "t01", "t07"))) {
			throw helper.assertionException("a second delivery sends nothing, and the fired set stays: %s, %s", deliveredTo(crew), after);
		}
		helper.succeed();
	}

	@GameTest
	public void aMemberWhoJoinsIsSentTheQueue(GameTestHelper helper) {
		MinecraftServer server = server(helper);
		UUID director = UUID.randomUUID();
		CharterId charter = directCharter(helper, director);
		Transmissions.fire(server, charter, id("t17"));
		Transmissions.fire(server, charter, id("t18"));
		Transmissions.fire(server, charter, id("t17"));
		if (!TransmissionData.get(server).progress(charter).queue().equals(ids("t17", "t18"))) {
			throw helper.assertionException("nobody is online, so t17 and t18 wait: %s", TransmissionData.get(server).progress(charter));
		}

		MockPlayer crew = MockPlayers.join(helper, "tx-join");
		UUID crewId = crew.player().getUUID();
		expectDone(helper, Charters.apply(server, crewId, charter), "applying");
		expectDone(helper, Charters.approve(server, director, crewId), "approving");
		if (!deliveredTo(crew).equals(ids("t17", "t18"))) {
			throw helper.assertionException("joining sends the queue, in order, once each: %s", deliveredTo(crew));
		}
		if (!TransmissionData.get(server).progress(charter).queue().isEmpty()) {
			throw helper.assertionException("the queue should be empty after delivery");
		}
		helper.succeed();
	}

	@GameTest
	public void theFrozenSignatureFiresOnTheRunningServer(GameTestHelper helper) {
		CharterId charter = directCharter(helper, UUID.randomUUID());
		Transmissions.fire(charter, id("t17"));
		Transmissions.fire(charter, id("t17"));
		if (!TransmissionData.get(server(helper)).progress(charter).fired().equals(ids("t17"))) {
			throw helper.assertionException("fire(CharterId, Identifier) should fire once on the running server");
		}
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

	/** The whole path with the world's data and its hooks: a charter founded through {@code Charters} is sent what the world fired. */
	@GameTest
	public void foundingACharterSendsItTheRepairsTheWorldHasFired(GameTestHelper helper) {
		MinecraftServer server = server(helper);
		CharterId first = directCharter(helper, UUID.randomUUID());
		Transmissions.fire(server, first, id("t12"));
		Transmissions.fire(server, first, id("t18"));

		MockPlayer founder = MockPlayers.join(helper, "tx-found");
		CharterId later = foundedCharter(helper, founder.player().getUUID());
		if (!deliveredTo(founder).contains(id("t12")) || deliveredTo(founder).contains(id("t18"))) {
			throw helper.assertionException("founding should send t12, a repair, and not t18: %s", deliveredTo(founder));
		}
		Transmissions.fire(server, later, id("t12"));
		if (deliveredTo(founder).stream().filter(id("t12")::equals).count() != 1) {
			throw helper.assertionException("t12 should not be sent twice: %s", deliveredTo(founder));
		}
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

	@GameTest
	public void theFiredSetAndTheQueueSurviveARestart(GameTestHelper helper) {
		MinecraftServer server = server(helper);
		CharterId charter = CharterId.random();
		Path dir = tempDir();
		try {
			try (SavedDataStorage first = storage(server, dir)) {
				TransmissionData data = first.computeIfAbsent(TransmissionData.TYPE);
				requireFired(helper, data, charter, "t01", true);
				requireFired(helper, data, charter, "t02", true);
				data.takeQueue(charter);
				requireFired(helper, data, charter, "t03", true);
				first.saveAndJoin();
			}
			try (SavedDataStorage second = storage(server, dir)) {
				TransmissionData data = second.computeIfAbsent(TransmissionData.TYPE);
				TransmissionData.Progress progress = data.progress(charter);
				if (!progress.fired().equals(ids("t01", "t02", "t03")) || !progress.queue().equals(ids("t03"))) {
					throw helper.assertionException("the fired set and the queue should load as saved: %s", progress);
				}
				if (!data.replays().equals(ids("t02"))) {
					throw helper.assertionException("the replay list should load as saved: %s", data.replays());
				}
				requireFired(helper, data, charter, "t02", false);
				requireFired(helper, data, charter, "t03", false);
				requireFired(helper, data, charter, "t04", true);
				if (!data.takeQueue(charter).equals(ids("t03", "t04"))) {
					throw helper.assertionException("the queue keeps its order across a restart");
				}
			}
		} finally {
			deleteTree(dir);
		}
		helper.succeed();
	}

	@GameTest
	public void dataOfAnotherVersionIsKeptAndFailsLoud(GameTestHelper helper) {
		for (CompoundTag saved : List.of(versioned(TransmissionData.VERSION + 1), versioned(0), new CompoundTag())) {
			saved.putString("shape", "from another build");
			TransmissionData data = TransmissionData.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
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
		if (!brokenBody.equals(TransmissionData.CODEC.encodeStart(NbtOps.INSTANCE, data).getOrThrow())) {
			throw helper.assertionException("a body that does not parse must be written back unchanged");
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
		double x = 3000.5;
		double z = 3000.5;
		ServerLevel one = server.getLevel(LayerChain.dimension(1));
		ServerLevel two = server.getLevel(LayerChain.dimension(2));
		// Generate the arrival area in layer 2 now so the crossing does not wait on it.
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				two.getChunk((int) x / 16 + dx, (int) z / 16 + dz);
			}
		}
		MockPlayer crew = MockPlayers.join(helper, "tx-breach");
		CharterId charter = foundedCharter(helper, crew.player().getUUID());
		crew.player().setPermanentlyInvulnerable(true);
		crew.teleportTo(one, new Vec3(x, one.getMinY() - 1, z), 0, 0);
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

	@GameTest(maxTicks = TRIGGER_TICKS)
	public void standingInAZoneFiresItsTransmissionForTheCharter(GameTestHelper helper) {
		MinecraftServer server = server(helper);
		ServerLevel two = server.getLevel(LayerChain.dimension(2));
		MockPlayer crew = MockPlayers.join(helper, "tx-zone");
		foundedCharter(helper, crew.player().getUUID());
		crew.player().setPermanentlyInvulnerable(true);
		// Held in place, so the player stays in the zone it was put in.
		crew.player().setNoGravity(true);
		double x = 3200.5;
		double z = 3200.5;
		// The middle third of layer 2 is zone 1 and the bottom third is zone 2.
		int middle = two.getMinY() + two.getHeight() / 2;
		int bottom = two.getMinY() + 4;
		if (Zones.of(two, middle).orElseThrow().index() != 1 || Zones.of(two, bottom).orElseThrow().index() != 2) {
			throw helper.assertionException("the test's heights should be in zones 1 and 2");
		}
		crew.teleportTo(two, new Vec3(x, middle, z), 0, 0);
		boolean[] moved = {false};
		helper.succeedWhen(() -> {
			List<Identifier> delivered = deliveredTo(crew);
			if (!delivered.contains(id("t07"))) {
				throw helper.assertionException("standing in zone 1 of layer 2 should send t07, sent %s", delivered);
			}
			if (!moved[0]) {
				moved[0] = true;
				crew.teleportTo(two, new Vec3(x, bottom, z), 0, 0);
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
