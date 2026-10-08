package io.github.pkeppeler.deepcharter.test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.CharterData;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.hangar.HangarEvents;
import io.github.pkeppeler.deepcharter.hangar.HangarTerminal;
import io.github.pkeppeler.deepcharter.handbook.Directives;
import io.github.pkeppeler.deepcharter.handbook.HandbookChapter;
import io.github.pkeppeler.deepcharter.handbook.HandbookProgressData;
import io.github.pkeppeler.deepcharter.handbook.HandbookTriggers;
import io.github.pkeppeler.deepcharter.handbook.HandbookTuning;
import io.github.pkeppeler.deepcharter.layer.BreachEvents;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.Zones;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodTowing;
import io.github.pkeppeler.deepcharter.repair.RepairStation;
import io.github.pkeppeler.deepcharter.scanner.LoadedBlocks;
import io.github.pkeppeler.deepcharter.scanner.ScanSlice;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalEvents;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.Terminals;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.ScannerPods;
import io.github.pkeppeler.deepcharter.test.support.WorldData;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeEvents;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeTerminal;
import io.github.pkeppeler.deepcharter.wreck.Wrecks;

/**
 * Server GameTests for #84: chapters 6 to 9 and their directives are pinned, their text is the lore canon word for word, and a
 * scripted run by a two-player charter completes the four chapters in order, the directives fired from the events of the game.
 * Each trigger has its negatives: the wrong actor, the wrong charter, no charter, and the wrong thing done.
 */
public class HandbookChaptersSixToNineTest {
	private static final BlockPos TERMINAL = new BlockPos(0, 1, 0);

	/** The permanent ids of chapters 6 to 9: a world's saved progress names these directives. */
	private static final Map<String, List<String>> PINNED = Map.of(
			"deepcharter:seeing_below", List.of("deepcharter:handbook/seeing_below/install_scanner", "deepcharter:handbook/seeing_below/find_ore"),
			"deepcharter:staying_safe", List.of("deepcharter:handbook/staying_safe/repair_hull", "deepcharter:handbook/staying_safe/reach_deep_claim"),
			"deepcharter:first_breach", List.of("deepcharter:handbook/first_breach/breach_workings"),
			"deepcharter:company_property", List.of("deepcharter:handbook/company_property/find_prospector",
					"deepcharter:handbook/company_property/tow_prospector", "deepcharter:handbook/company_property/restore_prospector",
					"deepcharter:handbook/company_property/reach_workings_floor"));
	private static final List<String> ORDER = List.of("deepcharter:seeing_below", "deepcharter:staying_safe", "deepcharter:first_breach",
			"deepcharter:company_property");

	/** The canon (docs/lore/handbook.md on PR 34), markdown stripped. */
	private static final Map<String, String> CANON = new LinkedHashMap<>();

	static {
		String base = "deepcharter.handbook.chapter.deepcharter.";
		CANON.put(base + "seeing_below.title", "Seeing Below");
		CANON.put(base + "seeing_below.text.1", "Your scanner shows a slice of the ground around your pod, so you can find ore before you dig for it. Better scanners see farther and show more. Hazards that can't be seen with the eye will show on higher-tier scanners.");
		CANON.put(base + "seeing_below.margin", "If a shape on the scanner moves, it isn't ore. —R.");
		CANON.put("deepcharter.handbook.directive.seeing_below.install_scanner", "Install a scanner.");
		CANON.put("deepcharter.handbook.directive.seeing_below.find_ore", "Find ore with the scanner.");
		CANON.put(base + "staying_safe.title", "Staying Safe on the Claim");
		CANON.put(base + "staying_safe.text.1", "The Claim is the friendliest ground we have, but it isn't harmless. Watch for undiggable rock, lava you can see, gas pockets you can't, and hard landings. Your hull takes the damage, so repair it at the colony. Accidents happen! The Continuity Plan returns you to work at no cost to you.");
		CANON.put(base + "staying_safe.margin", "Not listed: what's in the Workings. —R.");
		CANON.put("deepcharter.handbook.directive.staying_safe.repair_hull", "Repair your hull at the colony.");
		CANON.put("deepcharter.handbook.directive.staying_safe.reach_deep_claim", "Reach the Deep Claim.");
		CANON.put(base + "first_breach.title", "Your First Breach");
		CANON.put(base + "first_breach.text.1", "At the floor of the Claim you'll meet a breach: a hard crust between layers. Any drill can get through, slowly, and it's hard on the hull and the heat. Top up your fuel and repair your hull first. On the other side are the Old Workings, Prosperity's original mine levels and a proud part of our heritage.");
		CANON.put(base + "first_breach.margin", "Every breach is a door. We didn't put the doors there. —R.");
		CANON.put("deepcharter.handbook.directive.first_breach.breach_workings", "Breach into the Old Workings.");
		CANON.put(base + "company_property.title", "Recovering Company Property");
		CANON.put(base + "company_property.text.1", "Somewhere in the Old Workings lies the Prospector, a two-seat pod that went missing some years ago. Find her, tow her home and restore her, and she's yours to fly. She has a navigator's seat, so bring a friend! Then take her down to the floor of the Workings. You've earned it.");
		CANON.put(base + "company_property.margin", "Ines's pod. Read her log, then decide if you still want the job. —R.");
		CANON.put("deepcharter.handbook.directive.company_property.find_prospector", "Find the Prospector.");
		CANON.put("deepcharter.handbook.directive.company_property.tow_prospector", "Tow the Prospector to the colony.");
		CANON.put("deepcharter.handbook.directive.company_property.restore_prospector", "Restore the Prospector.");
		CANON.put("deepcharter.handbook.directive.company_property.reach_workings_floor", "Reach the floor of the Old Workings.");
	}

	private static String directive(String chapter, String name) {
		return HandbookChaptersOneToFiveTest.directive(chapter, name);
	}

	private static Identifier id(String id) {
		return Identifier.parse(id);
	}

	/** Every directive of the chapters before {@code chapters + 1}, and any {@code extra}. */
	private static Set<String> through(int chapters, String... extra) {
		Set<String> directives = new LinkedHashSet<>();
		List<String> order = new ArrayList<>(HandbookChaptersOneToFiveTest.ORDER);
		order.addAll(ORDER);
		for (int index = 0; index < chapters; index++) {
			directives.addAll(HandbookChaptersOneToFiveTest.PINNED.getOrDefault(order.get(index), PINNED.get(order.get(index))));
		}
		directives.addAll(List.of(extra));
		return directives;
	}

	private static void expect(GameTestHelper helper, boolean condition, String format, Object... args) {
		if (!condition) {
			throw helper.assertionException(format, args);
		}
	}

	private static ServerLevel layer(MinecraftServer server, int layer) {
		ServerLevel level = server.getLevel(LayerChain.dimension(layer));
		if (level == null) {
			throw new IllegalStateException("dimension " + LayerChain.dimension(layer) + " did not load");
		}
		return level;
	}

	/** A Y inside zone {@code index} of the layer (0 is the top third, 2 the bottom). */
	private static double zoneY(ServerLevel level, int index) {
		Zones.Span span = Zones.span(level.getMinY(), level.getHeight(), index);
		return span.low() + span.size() / 2.0;
	}

	private static PodEntity podIn(GameTestHelper helper, ServerLevel level, EntityType<PodEntity> type, Vec3 at) {
		PodEntity pod = type.create(level, EntitySpawnReason.TRIGGERED);
		expect(helper, pod != null, "the pod should be made");
		pod.setPos(at);
		expect(helper, level.addFreshEntity(pod), "the world should accept the pod at %s", at);
		return pod;
	}

	private static void poll(PodEntity pod) {
		pod.tickCount = 0;
		PodEvents.AFTER_TICK.invoker().afterTick(pod);
	}

	private static Set<Identifier> completed(MinecraftServer server, MockPlayer mock) {
		return HandbookChaptersOneToFiveTest.completed(server, mock.player());
	}

	private static void expectNothingNew(GameTestHelper helper, MinecraftServer server, String step, MockPlayer mock, Set<Identifier> before) {
		expect(helper, before.equals(completed(server, mock)), "after %s nothing should complete for %s, it has %s from %s", step,
				mock.player().getGameProfile().name(), completed(server, mock), before);
	}

	private static Set<String> with(Set<String> base, String... more) {
		Set<String> directives = new LinkedHashSet<>(base);
		directives.addAll(List.of(more));
		return directives;
	}

	private static MockPlayer charterMember(GameTestHelper helper, String name) {
		MockPlayer mock = MockPlayers.join(helper, name);
		HandbookChaptersOneToFiveTest.foundCharter(helper, helper.getLevel().getServer(), mock);
		return mock;
	}

	@GameTest
	public void chaptersSixToNineAndTheirDirectivesArePinnedAfterTheFirstFive(GameTestHelper helper) {
		Map<String, HandbookChapter> chapters = HandbookChaptersOneToFiveTest.shipped(helper.getLevel().getServer());
		List<String> keys = new ArrayList<>(chapters.keySet());
		expect(helper, keys.size() == 9 && keys.subList(5, 9).equals(ORDER),
				"chapters 6 to 9 should be %s, after the first five; the handbook has %s", ORDER, keys);
		int order = 6;
		for (String chapter : ORDER) {
			List<String> directives = HandbookChaptersOneToFiveTest.directivesOf(chapters.get(chapter));
			expect(helper, chapters.get(chapter).order() == order && directives.equals(PINNED.get(chapter)),
					"chapter %s should be number %s with directives %s, got %s with %s", chapter, order, PINNED.get(chapter),
					chapters.get(chapter).order(), directives);
			order++;
		}
		helper.succeed();
	}

	/** The chapter text, its margin notes and its directive lines are the canon, word for word, and the end page follows unchanged. */
	@GameTest
	public void theTextOfChaptersSixToNineIsTheCanonWordForWord(GameTestHelper helper) {
		JsonObject lang = HandbookChaptersOneToFiveTest.lang();
		CANON.forEach((key, text) -> {
			expect(helper, lang.has(key), "the language file should have %s", key);
			expect(helper, lang.get(key).getAsString().equals(text), "%s should be the canon %s, got %s", key, text, lang.get(key).getAsString());
		});
		// The end page and its margin note shipped with chapters 1 to 5 and stay as they were.
		expect(helper, lang.get("deepcharter.handbook.appendix.restricted").getAsString().equals("Further documentation is restricted to Senior Personnel."),
				"the end page should still read as the canon");
		expect(helper, lang.get("deepcharter.handbook.appendix.margin").getAsString().equals("Nobody's ever been promoted. —R."),
				"the end page's margin note should still read as the canon");
		expect(helper, lang.get("deepcharter.handbook.appendix.heading").getAsString().equals("END OF CHAPTERS"),
				"the end page's heading should be unchanged");
		helper.succeed();
	}

	/**
	 * The scripted run: two players on one charter do what each chapter asks, and the chapters complete one after the other, for both
	 * of them at each step. The scanner is bought at the upgrade terminal and the hull repaired at the repair station, through the
	 * real terminal actions; the breach crossing and the restore are the events those features fire (a real crossing is
	 * {@code BreachCrossingTest}'s, a real restore is the next test's), and the pods and the players are really in the layers.
	 */
	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 200)
	public void aTwoPlayerCharterCompletesChaptersSixToNineInOrder(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerLevel one = layer(server, 1);
		ServerLevel two = layer(server, 2);
		AtomicInteger ready = new AtomicInteger();
		Runnable arrived = () -> {
			if (ready.incrementAndGet() == 2) {
				runTheChapters(helper, server, one, two);
			}
		};
		FarChunks.awaitEntityTicking(helper, one, BlockPos.containing(9100.5, zoneY(one, 2), 9100.5), arrived);
		FarChunks.awaitEntityTicking(helper, two, BlockPos.containing(9100.5, zoneY(two, 2), 9100.5), arrived);
	}

	private static void runTheChapters(GameTestHelper helper, MinecraftServer server, ServerLevel one, ServerLevel two) {
		WorldData.with(server, RepairState.TYPE, HandbookChaptersOneToFiveTest.repairedFor(TerminalTypes.FUEL_PUMP, TerminalTypes.ORE_PROCESSOR, TerminalTypes.UPGRADE_TERMINAL,
				TerminalTypes.REPAIR_STATION), () -> {
			List<PodEntity> pods = new ArrayList<>();
			BlockPos terminal = helper.absolutePos(TERMINAL);
			MockPlayer director = MockPlayers.join(helper, "Director");
			MockPlayer crew = MockPlayers.join(helper, "Crew");
			MockPlayer drifter = MockPlayers.join(helper, "Drifter");
			MockPlayer rival = charterMember(helper, "Rival");
			try {
				ServerPlayer first = director.player();
				ServerPlayer second = crew.player();
				for (MockPlayer mock : List.of(director, crew, drifter)) {
					mock.player().setGameMode(GameType.SURVIVAL);
				}
				HandbookChaptersOneToFiveTest.foundCharter(helper, server, director);
				CharterId charter = Charters.charterOfOrThrow(server, first.getUUID()).orElseThrow().id();
				expect(helper, Charters.apply(server, second.getUUID(), charter).isEmpty() && Charters.approve(server, first.getUUID(), second.getUUID()).isEmpty()
						&& Charters.deposit(server, charter, 20_000).isEmpty(), "the second player should join the charter, which is then funded");
				List<ServerPlayer> both = List.of(first, second);
				// Chapters 1 to 5 are done by their own test; here they are done so that chapter 6 is the one the crew is on.
				for (Item item : List.of(Items.OAK_LOG, Items.CRAFTING_TABLE, Items.STONE_AXE, Items.RAW_IRON, Items.IRON_INGOT)) {
					HandbookChaptersOneToFiveTest.give(first, item);
				}
				for (String directive : through(5)) {
					if (!directive.contains("/welcome/")) {
						Directives.fire(first, id(directive));
					}
				}
				HandbookChaptersOneToFiveTest.expectCompleted(helper, server, "chapters 1 to 5", both, through(5));
				HandbookChaptersOneToFiveTest.expectRoadAt(helper, server, first, 5);
				HandbookChaptersOneToFiveTest.stand(helper, director, terminal);
				HandbookChaptersOneToFiveTest.stand(helper, crew, terminal);

				// Chapter 6: the scanner is bought at the upgrade terminal, then finds ore.
				PodEntity mole = helper.spawn(PodRegistry.POD, 2, 1, 2);
				pods.add(mole);
				PodComponents.register(mole, charter);
				mole.setPos(Vec3.atBottomCenterOf(terminal).add(2, 0, 2));
				helper.getLevel().setBlock(terminal, TerminalTypes.UPGRADE_TERMINAL.block().defaultBlockState(), 3);
				CompoundTag args = new CompoundTag();
				args.putString(UpgradeTerminal.TRACK_KEY, ComponentTrack.HULL.id());
				args.putInt(UpgradeTerminal.TIER_KEY, 1);
				HandbookChaptersOneToFiveTest.expectDone(helper, Terminals.act(first, terminal, UpgradeTerminal.BUY, args), "buying a hull");
				HandbookChaptersOneToFiveTest.expectCompleted(helper, server, "buying a part that is not a scanner", both, through(5));
				args.putString(UpgradeTerminal.TRACK_KEY, ComponentTrack.SCANNER.id());
				HandbookChaptersOneToFiveTest.expectDone(helper, Terminals.act(first, terminal, UpgradeTerminal.BUY, args), "buying a scanner");
				HandbookChaptersOneToFiveTest.expectCompleted(helper, server, "buying a scanner", both, through(5, directive("seeing_below", "install_scanner")));
				BlockPos ore = mole.blockPosition().above(2);
				helper.getLevel().setBlock(ore, Blocks.GOLD_ORE.defaultBlockState(), 3);
				poll(mole);
				HandbookChaptersOneToFiveTest.expectCompleted(helper, server, "a scanner nobody is flying", both, through(5, directive("seeing_below", "install_scanner")));
				expect(helper, first.startRiding(mole, true, false), "the player should board the Mole");
				// room-carver: removes a block this test placed itself in the overworld test structure, not layer rock
				helper.getLevel().setBlock(ore, Blocks.AIR.defaultBlockState(), 3);
				poll(mole);
				HandbookChaptersOneToFiveTest.expectCompleted(helper, server, "flying a scanner with no ore in view", both, through(5, directive("seeing_below", "install_scanner")));
				helper.getLevel().setBlock(ore, Blocks.GOLD_ORE.defaultBlockState(), 3);
				poll(mole);
				HandbookChaptersOneToFiveTest.expectCompleted(helper, server, "chapter 6", both, through(6));
				// room-carver: removes a block this test placed itself in the overworld test structure, not layer rock
				helper.getLevel().setBlock(ore, Blocks.AIR.defaultBlockState(), 3);
				HandbookChaptersOneToFiveTest.expectRoadAt(helper, server, second, 6);

				// Chapter 7: the hull is repaired at the station, and the crew reaches the Deep Claim.
				first.stopRiding();
				HandbookChaptersOneToFiveTest.stand(helper, director, terminal);
				helper.getLevel().setBlock(terminal, TerminalTypes.REPAIR_STATION.block().defaultBlockState(), 3);
				mole.damageHull(mole.maxHull() / 2);
				HandbookChaptersOneToFiveTest.expectDone(helper, Terminals.act(first, terminal, RepairStation.REPAIR_TOTAL, new CompoundTag()), "repairing the hull");
				HandbookChaptersOneToFiveTest.expectCompleted(helper, server, "the hull repair", both, through(6, directive("staying_safe", "repair_hull")));
				HandbookTriggers.pollPlayer(server, first);
				HandbookChaptersOneToFiveTest.expectCompleted(helper, server, "standing on the surface", both, through(6, directive("staying_safe", "repair_hull")));
				director.teleportTo(one, new Vec3(9100.5, zoneY(one, 0), 9100.5), 0, 0);
				HandbookTriggers.pollPlayer(server, first);
				HandbookChaptersOneToFiveTest.expectCompleted(helper, server, "the top of the Claim", both, through(6, directive("staying_safe", "repair_hull")));
				director.teleportTo(one, new Vec3(9100.5, zoneY(one, 2), 9100.5), 0, 0);
				HandbookTriggers.pollPlayer(server, first);
				HandbookChaptersOneToFiveTest.expectCompleted(helper, server, "chapter 7", both, through(7));
				HandbookChaptersOneToFiveTest.expectRoadAt(helper, server, second, 7);

				// Chapter 8: the first breach, from the Claim into the Old Workings.
				BreachEvents.CROSSED.invoker().onCrossed(first, two, one, 2, 1);
				BreachEvents.CROSSED.invoker().onCrossed(first, two, two, 2, 3);
				PodEntity carried = podIn(helper, one, PodRegistry.POD, new Vec3(9100.5, zoneY(one, 2), 9100.5));
				pods.add(carried);
				BreachEvents.CROSSED.invoker().onCrossed(carried, one, two, 1, 2);
				BreachEvents.CROSSED.invoker().onCrossed(drifter.player(), one, two, 1, 2);
				HandbookChaptersOneToFiveTest.expectCompleted(helper, server, "a crossing that is not the first descent by a player of the charter", both, through(7));
				BreachEvents.CROSSED.invoker().onCrossed(first, one, two, 1, 2);
				director.teleportTo(two, new Vec3(9100.5, zoneY(two, 0), 9100.5), 0, 0);
				HandbookChaptersOneToFiveTest.expectCompleted(helper, server, "chapter 8", both, through(8));
				HandbookChaptersOneToFiveTest.expectRoadAt(helper, server, second, 8);

				// Chapter 9: find the Prospector in the Workings, tow it to the colony, restore it, and take it to the floor.
				director.teleportTo(two, new Vec3(9100.5, zoneY(two, 2), 9100.5), 0, 0);
				double near = HandbookTuning.DEFAULT.findProspectorBlocks() / 2.0;
				double far = HandbookTuning.DEFAULT.findProspectorBlocks() + 6.0;
				PodEntity farWreck = podIn(helper, two, PodRegistry.PROSPECTOR, new Vec3(9100.5 + far, zoneY(two, 2), 9100.5));
				PodEntity workingProspector = podIn(helper, two, PodRegistry.PROSPECTOR, new Vec3(9100.5 + near, zoneY(two, 2), 9100.5));
				PodEntity moleWreck = podIn(helper, two, PodRegistry.POD, new Vec3(9100.5, zoneY(two, 2), 9100.5 + near));
				pods.addAll(List.of(farWreck, workingProspector, moleWreck));
				farWreck.damageHull(farWreck.maxHull());
				moleWreck.damageHull(moleWreck.maxHull());
				HandbookTriggers.pollPlayer(server, first);
				HandbookChaptersOneToFiveTest.expectCompleted(helper, server, "a far wreck, a working Prospector and a wrecked Mole", both, through(8));
				PodEntity wreck = podIn(helper, two, PodRegistry.PROSPECTOR, new Vec3(9100.5 - near, zoneY(two, 2), 9100.5));
				pods.add(wreck);
				wreck.damageHull(wreck.maxHull());
				expect(helper, Wrecks.isWreck(wreck), "the Prospector should be a wreck");
				HandbookTriggers.pollPlayer(server, first);
				HandbookChaptersOneToFiveTest.expectCompleted(helper, server, "coming upon the wreck", both, through(8, directive("company_property", "find_prospector")));

				director.teleportTo(server.overworld(), Vec3.atBottomCenterOf(Colony.placed(server).orElseThrow().center()), 0, 0);
				ColonySite.Placed colony = Colony.placed(server).orElseThrow();
				Vec3 pad = Vec3.atBottomCenterOf(colony.center());
				PodEntity tower = helper.spawn(PodRegistry.POD, 2, 1, 2);
				PodEntity wreckHome = helper.spawn(PodRegistry.PROSPECTOR, 4, 1, 4);
				PodEntity otherTower = helper.spawn(PodRegistry.POD, 6, 1, 2);
				PodEntity moleHome = helper.spawn(PodRegistry.POD, 8, 1, 2);
				PodEntity unhitched = helper.spawn(PodRegistry.PROSPECTOR, 6, 1, 6);
				pods.addAll(List.of(tower, wreckHome, otherTower, moleHome, unhitched));
				wreckHome.damageHull(wreckHome.maxHull());
				moleHome.damageHull(moleHome.maxHull());
				Set<String> found = through(8, directive("company_property", "find_prospector"));
				tower.setPos(pad);
				wreckHome.setPos(pad.add(2, 0, 0));
				PodTowing.attach(tower, wreckHome);
				poll(wreckHome);
				HandbookChaptersOneToFiveTest.expectCompleted(helper, server, "a Prospector towed by a pod with nobody at the controls", both, found);
				expect(helper, first.startRiding(tower, true, false), "the player should board the tower");
				otherTower.setPos(pad.add(0, 0, 4));
				moleHome.setPos(pad.add(0, 0, 6));
				PodTowing.attach(otherTower, moleHome);
				expect(helper, second.startRiding(otherTower, true, false), "the crew should board the other tower");
				poll(moleHome);
				unhitched.setPos(pad.add(0, 0, -3));
				poll(unhitched);
				wreckHome.setPos(pad.add(60, 0, 0));
				poll(wreckHome);
				HandbookChaptersOneToFiveTest.expectCompleted(helper, server, "a Mole towed home, a Prospector that is not towed, and one towed far from the colony", both, found);
				wreckHome.setPos(pad.add(2, 0, 0));
				expect(helper, rival.player().startRiding(wreckHome, true, false), "the rival should board the towed Prospector");
				poll(wreckHome);
				Set<String> towed = with(found, directive("company_property", "tow_prospector"));
				HandbookChaptersOneToFiveTest.expectCompleted(helper, server, "towing home", both, towed);
				expect(helper, !completed(server, rival).contains(id(directive("company_property", "tow_prospector"))),
						"a rider of the towed Prospector, of another charter, did not tow it: %s", completed(server, rival));
				rival.player().stopRiding();
				second.stopRiding();

				// The hangar restores it, and it is the charter's to fly.
				HangarEvents.RESTORED.invoker().onRestored(server, first, mole);
				HandbookChaptersOneToFiveTest.expectCompleted(helper, server, "restoring a Mole", both, towed);
				HangarEvents.RESTORED.invoker().onRestored(server, drifter.player(), wreckHome);
				first.stopRiding();
				HandbookChaptersOneToFiveTest.expectCompleted(helper, server, "a restore by a player on no charter", both, towed);
				HangarEvents.RESTORED.invoker().onRestored(server, second, wreckHome);
				Set<String> restored = with(towed, directive("company_property", "restore_prospector"));
				HandbookChaptersOneToFiveTest.expectCompleted(helper, server, "restoring the Prospector", both, restored);
				expect(helper, completed(server, drifter).isEmpty(), "a player on no charter completes nothing, got %s", completed(server, drifter));

				// The floor of the Workings counts only from a Prospector's seat.
				director.teleportTo(two, new Vec3(9100.5, zoneY(two, 2), 9100.5), 0, 0);
				HandbookTriggers.pollPlayer(server, first);
				PodEntity workingMole = podIn(helper, two, PodRegistry.POD, new Vec3(9100.5, zoneY(two, 2), 9100.5));
				pods.add(workingMole);
				expect(helper, first.startRiding(workingMole, true, false), "the player should board the Mole");
				HandbookTriggers.pollPlayer(server, first);
				first.stopRiding();
				workingProspector.setPos(9100.5, zoneY(two, 0), 9100.5);
				director.teleportTo(two, new Vec3(9100.5, zoneY(two, 0), 9100.5), 0, 0);
				expect(helper, first.startRiding(workingProspector, true, false), "the player should board the Prospector");
				HandbookTriggers.pollPlayer(server, first);
				HandbookChaptersOneToFiveTest.expectCompleted(helper, server, "on foot, in a Mole and in a Prospector high in the Workings", both, restored);
				first.stopRiding();
				workingProspector.setPos(9100.5, zoneY(two, 2), 9100.5);
				director.teleportTo(two, new Vec3(9100.5, zoneY(two, 2), 9100.5), 0, 0);
				expect(helper, first.startRiding(workingProspector, true, false), "the player should board the Prospector");
				HandbookTriggers.pollPlayer(server, first);
				HandbookChaptersOneToFiveTest.expectCompleted(helper, server, "chapter 9", both, through(9));
				HandbookChaptersOneToFiveTest.expectRoadAt(helper, server, second, 9);
				helper.succeed();
			} finally {
				pods.forEach(PodEntity::discard);
				director.leave();
				crew.leave();
				drifter.leave();
				rival.leave();
			}
		});
	}

	/**
	 * A player who is on no charter, and one on another charter, complete nothing for the charter whose pod, terminal or crossing it
	 * was not: each event credits the charter of the player who did the deed, and no charter at all if there is none.
	 */
	@GameTest
	public void eachTriggerCreditsOnlyTheCharterOfThePlayerWhoDidTheDeed(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		MockPlayer member = charterMember(helper, "Member");
		MockPlayer rival = charterMember(helper, "Rival");
		MockPlayer drifter = MockPlayers.join(helper, "Drifter");
		PodEntity pod = null;
		PodEntity foreignParts = null;
		try {
			drifter.player().setGameMode(GameType.SURVIVAL);
			Set<Identifier> memberBefore = Set.copyOf(completed(server, member));
			pod = helper.spawn(PodRegistry.POD, 2, 1, 2);
			Charter charter = Charters.charterOfOrThrow(server, member.player().getUUID()).orElseThrow();
			ScannerPods.fit(server, member.player(), pod, 1);
			helper.getLevel().setBlock(pod.blockPosition().above(2), Blocks.GOLD_ORE.defaultBlockState(), 3);
			UpgradeEvents.BOUGHT.invoker().onBought(server, drifter.player(), pod, ComponentTrack.SCANNER, 1);
			TerminalEvents.ACTED.invoker().onActed(server, TerminalTypes.REPAIR_STATION, drifter.player(), RepairStation.REPAIR_TOTAL);
			BreachEvents.CROSSED.invoker().onCrossed(drifter.player(), layer(server, 1), layer(server, 2), 1, 2);
			HangarEvents.RESTORED.invoker().onRestored(server, drifter.player(), pod);
			HandbookTriggers.pollPlayer(server, drifter.player());
			expect(helper, drifter.player().startRiding(pod, true, false), "the drifter should board the pod");
			poll(pod);
			expectNothingNew(helper, server, "a drifter on no charter", member, memberBefore);
			expect(helper, completed(server, drifter).isEmpty(), "a player on no charter completes nothing, got %s", completed(server, drifter));
			drifter.player().stopRiding();

			// A scanner that another charter stamped does nothing on the member's pod, so the member finds no ore with it.
			rival.player().setGameMode(GameType.SURVIVAL);
			PodEntity foreign = helper.spawn(PodRegistry.POD, 4, 1, 4);
			foreignParts = foreign;
			PodComponents.register(foreign, charter.id());
			PodComponents.install(foreign, ComponentItems.mint(server, ComponentTrack.SCANNER, 1, Charters.charterOfOrThrow(server, rival.player().getUUID()).orElseThrow().id()));
			helper.getLevel().setBlock(foreign.blockPosition().above(2), Blocks.GOLD_ORE.defaultBlockState(), 3);
			expect(helper, member.player().startRiding(foreign, true, false), "the member should board the pod");
			poll(foreign);
			// Boarding a Mole is chapter 3's deed, so the check is for ore alone.
			expect(helper, !completed(server, member).contains(id(directive("seeing_below", "find_ore"))),
					"a scanner stamped by another charter should find no ore, the member has %s", completed(server, member));
			member.player().stopRiding();
			Set<Identifier> memberBoarded = Set.copyOf(completed(server, member));

			UpgradeEvents.BOUGHT.invoker().onBought(server, rival.player(), pod, ComponentTrack.SCANNER, 1);
			TerminalEvents.ACTED.invoker().onActed(server, TerminalTypes.REPAIR_STATION, rival.player(), RepairStation.REPAIR);
			TerminalEvents.ACTED.invoker().onActed(server, TerminalTypes.REPAIR_STATION, rival.player(), RepairStation.BUY);
			TerminalEvents.ACTED.invoker().onActed(server, TerminalTypes.UPGRADE_TERMINAL, rival.player(), RepairStation.REPAIR);
			BreachEvents.CROSSED.invoker().onCrossed(rival.player(), layer(server, 1), layer(server, 2), 1, 2);
			expectNothingNew(helper, server, "the rival's deeds", member, memberBoarded);
			Set<Identifier> rivals = completed(server, rival);
			for (String done : List.of(directive("seeing_below", "install_scanner"), directive("staying_safe", "repair_hull"), directive("first_breach", "breach_workings"))) {
				expect(helper, rivals.contains(id(done)), "the rival's own deed should complete %s for the rival's charter, which has %s", done, rivals);
			}
			expect(helper, !rivals.contains(id(directive("company_property", "restore_prospector"))), "nothing restored a Prospector: %s", rivals);
			helper.succeed();
		} finally {
			if (pod != null) {
				pod.discard();
			}
			if (foreignParts != null) {
				foreignParts.discard();
			}
			member.leave();
			rival.leave();
			drifter.leave();
		}
	}

	/**
	 * The ore check reads loaded chunks only: with a gold ore in a chunk that is not loaded, the pod's scanner sees none, the directive
	 * stays open, and the check does not load the chunk. The chunk is forced for one tick to put the ore in it, and then let go.
	 */
	@GameTest(maxTicks = 2 * FarChunks.AWAIT_BUDGET_TICKS + 1200)
	public void theOreCheckNeverLoadsAChunkAndReadsAnUnloadedOneAsAir(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerLevel level = helper.getLevel();
		BlockPos origin = new BlockPos(7_200, 64, 7_200);
		int chunkX = origin.getX() >> 4;
		int chunkZ = origin.getZ() >> 4;
		int[] phase = {0};
		FarChunks.Deadline[] unloadBy = {null};
		helper.onEachTick(() -> {
			switch (phase[0]) {
				case 1 -> {
					level.setChunkForced(chunkX, chunkZ, false);
					unloadBy[0] = FarChunks.deadline();
					phase[0] = 2;
				}
				case 2 -> {
					if (unloadBy[0].awaitUnloaded(helper, level, chunkX, chunkZ)) {
						phase[0] = 3;
						checkTheUnloadedChunk(helper, server, level, origin);
					}
				}
				default -> {
				}
			}
		});
		FarChunks.awaitEntityTicking(helper, level, origin, () -> {
			level.setBlock(origin.relative(Direction.SOUTH, 3), Blocks.GOLD_ORE.defaultBlockState(), 3);
			phase[0] = 1;
		});
	}

	/** In the one tick where the chunk is known to be unloaded: the pod stands in it, the ore is 3 ahead, and nothing may load it. */
	private static void checkTheUnloadedChunk(GameTestHelper helper, MinecraftServer server, ServerLevel level, BlockPos origin) {
		int chunkX = origin.getX() >> 4;
		int chunkZ = origin.getZ() >> 4;
		MockPlayer pilot = charterMember(helper, "Pilot");
		PodEntity pod = null;
		try {
			pod = helper.spawn(PodRegistry.POD, 2, 1, 2);
			ScannerPods.fit(server, pilot.player(), pod, 1);
			expect(helper, pilot.player().startRiding(pod, true, false), "the player should board the pod");
			Vec3 home = pod.position();
			pod.setYRot(0f);
			expect(helper, pod.getDirection() == Direction.SOUTH, "the pod should face south, faces %s", pod.getDirection());
			pod.setPos(Vec3.atBottomCenterOf(origin));
			expect(helper, !level.hasChunkAt(origin), "the pod's chunk should be unloaded");
			expect(helper, !ScanSlice.hasOre(new LoadedBlocks(level), pod), "an ore in an unloaded chunk should not be seen");
			expect(helper, level.getChunkSource().getChunkNow(chunkX, chunkZ) == null, "the ore check should not have loaded the chunk");
			poll(pod);
			expect(helper, level.getChunkSource().getChunkNow(chunkX, chunkZ) == null, "no listener of the pod's tick should have loaded the chunk");
			pod.setPos(home);
			expect(helper, !completed(server, pilot).contains(id(directive("seeing_below", "find_ore"))),
					"find_ore should stay open, the pilot has %s", completed(server, pilot));
			helper.succeed();
		} finally {
			if (pod != null) {
				pod.discard();
			}
			pilot.leave();
		}
	}

	/** The pod poll that watches a scanner runs on every {@code triggerPollTicks}th tick of the pod: a tick between two of them completes nothing. */
	@GameTest
	public void theScannerPollSkipsATickThatIsNotAMultipleOfThePollInterval(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		MockPlayer pilot = charterMember(helper, "Pilot");
		PodEntity pod = null;
		try {
			pod = helper.spawn(PodRegistry.POD, 2, 1, 2);
			ScannerPods.fit(server, pilot.player(), pod, 1);
			helper.getLevel().setBlock(pod.blockPosition().above(2), Blocks.GOLD_ORE.defaultBlockState(), 3);
			expect(helper, pilot.player().startRiding(pod, true, false), "the player should board the pod");
			Set<Identifier> before = Set.copyOf(completed(server, pilot));
			int interval = HandbookTuning.DEFAULT.triggerPollTicks();
			pod.tickCount = interval + 1;
			PodEvents.AFTER_TICK.invoker().afterTick(pod);
			expectNothingNew(helper, server, "a tick between polls", pilot, before);
			pod.tickCount = interval;
			PodEvents.AFTER_TICK.invoker().afterTick(pod);
			expect(helper, completed(server, pilot).contains(id(directive("seeing_below", "find_ore"))), "a poll tick should find the ore, got %s", completed(server, pilot));
			helper.succeed();
		} finally {
			if (pod != null) {
				pod.discard();
			}
			pilot.leave();
		}
	}

	/** The restore at the hangar completes the directive for a Prospector only: a Mole restored completes nothing here. */
	@GameTest(maxTicks = FoundingMoleHangarTest.MAX_TICKS)
	public void restoringAProspectorAtTheHangarCompletesRestoreTheProspectorAndRestoringAMoleDoesNot(GameTestHelper helper) {
		FoundingMoleHangarTest.inTheHangar(helper, before -> FoundingMoleHangarTest.withFreshWorld(helper, () -> {
			FoundingMoleHangarTest.repairTheConsole(helper);
			MinecraftServer server = FoundingMoleHangarTest.server(helper);
			MockPlayer owner = FoundingMoleHangarTest.member(helper, "Restorer");
			BlockPos console = FoundingMoleHangarTest.console(helper, owner);
			CharterId charter = FoundingMoleHangarTest.charterOf(helper, owner).id();
			PodEntity mole = helper.spawn(PodRegistry.POD, new Vec3(5.5, 2, 5.5));
			PodComponents.register(mole, charter);
			mole.damageHull(mole.maxHull());
			PodEntity wreck = helper.spawn(PodRegistry.PROSPECTOR, new Vec3(7.5, 2, 5.5));
			wreck.damageHull(wreck.maxHull());
			FoundingMoleHangarTest.deposit(helper, owner, FoundingMoleHangarTest.RICH);
			FoundingMoleHangarTest.give(owner.player(), FoundingMoleHangarTest.CATALYST, 8);
			Set<Identifier> none = Set.copyOf(completed(server, owner));

			FoundingMoleHangarTest.expectDone(helper, FoundingMoleHangarTest.act(owner.player(), console, HangarTerminal.RESTORE_WRECK), "restoring the nearer wreck");
			boolean moleFirst = !Wrecks.isWreck(mole);
			if (moleFirst) {
				expectNothingNew(helper, server, "restoring a Mole", owner, none);
				FoundingMoleHangarTest.expectDone(helper, FoundingMoleHangarTest.act(owner.player(), console, HangarTerminal.RESTORE_WRECK), "restoring the Prospector");
			}
			expect(helper, !Wrecks.isWreck(wreck), "the Prospector should be restored");
			expect(helper, completed(server, owner).contains(id(directive("company_property", "restore_prospector"))),
					"restoring the Prospector should complete its directive, got %s", completed(server, owner));
			FoundingMoleHangarTest.clearFloor(helper);
			helper.succeed();
		}));
	}

	private static final CompoundTag FUTURE = futureVersion();

	private static CompoundTag futureVersion() {
		CompoundTag future = new CompoundTag();
		future.putInt("version", 99);
		future.putString("shape", "from a later build");
		return future;
	}

	/** Every callback of chapters 6 to 9 runs once with the saved charters, then once with the saved progress, of a version this build cannot read. */
	@GameTest
	public void theCallbacksDoNotThrowOnUnreadableCharterOrProgressData(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		MockPlayer pilot = charterMember(helper, "Pilot");
		PodEntity pod = null;
		PodEntity wreck = null;
		try {
			pod = helper.spawn(PodRegistry.POD, 2, 1, 2);
			ScannerPods.fit(server, pilot.player(), pod, 1);
			helper.getLevel().setBlock(pod.blockPosition().above(2), Blocks.GOLD_ORE.defaultBlockState(), 3);
			wreck = helper.spawn(PodRegistry.PROSPECTOR, 4, 1, 4);
			wreck.damageHull(wreck.maxHull());
			expect(helper, pilot.player().startRiding(pod, true, false), "the player should board the pod");
			CharterData charters = CharterData.get(server);
			HandbookProgressData progress = HandbookProgressData.get(server);
			PodEntity riddenPod = pod;
			PodEntity wrecked = wreck;
			// A crossing also runs the transmissions' listener, which does not read unreadable charters safely: it is left out of that run.
			boolean[] chartersReadable = {true};
			Runnable callbacks = () -> {
				UpgradeEvents.BOUGHT.invoker().onBought(server, pilot.player(), riddenPod, ComponentTrack.SCANNER, 1);
				for (Identifier action : List.of(RepairStation.REPAIR, RepairStation.REPAIR_TOTAL)) {
					TerminalEvents.ACTED.invoker().onActed(server, TerminalTypes.REPAIR_STATION, pilot.player(), action);
				}
				if (chartersReadable[0]) {
					BreachEvents.CROSSED.invoker().onCrossed(pilot.player(), layer(server, 1), layer(server, 2), 1, 2);
				}
				HangarEvents.RESTORED.invoker().onRestored(server, pilot.player(), wrecked);
				HandbookTriggers.pollPlayer(server, pilot.player());
				poll(riddenPod);
				wrecked.setPos(Vec3.atBottomCenterOf(Colony.placed(server).orElseThrow().center()));
				poll(wrecked);
			};
			chartersReadable[0] = false;
			try {
				WorldData.with(server, CharterData.TYPE, CharterData.CODEC.parse(NbtOps.INSTANCE, FUTURE).getOrThrow(), callbacks);
			} finally {
				chartersReadable[0] = true;
			}
			WorldData.with(server, HandbookProgressData.TYPE, HandbookProgressData.CODEC.parse(NbtOps.INSTANCE, FUTURE).getOrThrow(), callbacks);
			expect(helper, CharterData.get(server) == charters && HandbookProgressData.get(server) == progress, "the saved data should be back in place");
			helper.succeed();
		} finally {
			if (pod != null) {
				pod.discard();
			}
			if (wreck != null) {
				wreck.discard();
			}
			pilot.leave();
		}
	}
}
