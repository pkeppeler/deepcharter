package io.github.pkeppeler.deepcharter.test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.CharterData;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.fuel.FuelPump;
import io.github.pkeppeler.deepcharter.hangar.HangarData;
import io.github.pkeppeler.deepcharter.hangar.HangarTerminal;
import io.github.pkeppeler.deepcharter.hangar.HangarTuning;
import io.github.pkeppeler.deepcharter.handbook.HandbookChapter;
import io.github.pkeppeler.deepcharter.handbook.HandbookChapters;
import io.github.pkeppeler.deepcharter.handbook.HandbookProgress;
import io.github.pkeppeler.deepcharter.handbook.HandbookProgressData;
import io.github.pkeppeler.deepcharter.handbook.HandbookTriggers;
import io.github.pkeppeler.deepcharter.handbook.HandbookTuning;
import io.github.pkeppeler.deepcharter.handbook.HandbookVisibility;
import io.github.pkeppeler.deepcharter.market.OreProcessor;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.Serials;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalEvents;
import io.github.pkeppeler.deepcharter.terminal.TerminalRefusal;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.Terminals;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeTerminal;


/**
 * Server GameTests for #81: chapters 1 to 5 and their directives are pinned, their lang text is real, and a scripted run by a
 * two-player charter completes the five chapters in order, the directives fired from the real events of the game.
 */
public class HandbookChaptersOneToFiveTest {
	private static final String SAMPLE = "sample";
	private static final BlockPos TERMINAL = new BlockPos(0, 1, 0);

	/** The permanent ids: a world's saved progress names these directives. */
	static final Map<String, List<String>> PINNED = Map.of(
			"deepcharter:welcome", List.of("deepcharter:handbook/welcome/gather_wood", "deepcharter:handbook/welcome/craft_crafting_table",
					"deepcharter:handbook/welcome/craft_stone_tools", "deepcharter:handbook/welcome/mine_iron_ore",
					"deepcharter:handbook/welcome/smelt_iron"),
			"deepcharter:back_online", List.of("deepcharter:handbook/back_online/repair_fuel_pump",
					"deepcharter:handbook/back_online/repair_ore_processor", "deepcharter:handbook/back_online/repair_upgrade_terminal"),
			"deepcharter:meet_the_mole", List.of("deepcharter:handbook/meet_the_mole/repair_mole", "deepcharter:handbook/meet_the_mole/board_mole"),
			"deepcharter:fuel_is_life", List.of("deepcharter:handbook/fuel_is_life/refuel_mole", "deepcharter:handbook/fuel_is_life/fly_mole",
					"deepcharter:handbook/fuel_is_life/drill_down", "deepcharter:handbook/fuel_is_life/return_to_colony"),
			"deepcharter:every_sale_counts", List.of("deepcharter:handbook/every_sale_counts/sell_ore",
					"deepcharter:handbook/every_sale_counts/buy_component", "deepcharter:handbook/every_sale_counts/install_component"));
	static final List<String> ORDER = List.of("deepcharter:welcome", "deepcharter:back_online", "deepcharter:meet_the_mole",
			"deepcharter:fuel_is_life", "deepcharter:every_sale_counts");

	/** The chapters of this issue by id in handbook order, leaving out the sample chapter that the test mod adds in front. */
	static Map<String, HandbookChapter> shipped(MinecraftServer server) {
		Map<String, HandbookChapter> chapters = new LinkedHashMap<>();
		for (Holder.Reference<HandbookChapter> chapter : HandbookChapters.all(server)) {
			if (!chapter.key().identifier().getPath().equals(SAMPLE)) {
				chapters.put(chapter.key().identifier().toString(), chapter.value());
			}
		}
		return chapters;
	}

	static List<String> directivesOf(HandbookChapter chapter) {
		return chapter.directives().stream().map(entry -> entry.id().toString()).toList();
	}

	@GameTest
	public void chaptersOneToFiveAndTheirDirectivesArePinnedInOrder(GameTestHelper helper) {
		Map<String, HandbookChapter> chapters = shipped(helper.getLevel().getServer());
		// Chapters 6 to 9 follow these five (#84).
		List<String> firstFive = new ArrayList<>(chapters.keySet()).subList(0, ORDER.size());
		if (!firstFive.equals(ORDER)) {
			throw helper.assertionException("the first chapters should be %s in order, got %s", ORDER, chapters.keySet());
		}
		int order = 1;
		for (String id : ORDER) {
			HandbookChapter chapter = chapters.get(id);
			if (chapter.order() != order++ || !directivesOf(chapter).equals(PINNED.get(id))) {
				throw helper.assertionException("chapter %s should be number %s with directives %s, got %s with %s", id, order - 1,
						PINNED.get(id), chapter.order(), directivesOf(chapter));
			}
		}
		helper.succeed();
	}

	static final Pattern PROSE_KEY = Pattern.compile(
			"deepcharter\\.handbook\\.(chapter\\..*\\.(text\\.\\d+(\\.margin)?|margin)|appendix\\..*)");

	/**
	 * Every title, directive line, text page, margin note and appendix line is a real line of the language file, none a placeholder.
	 * The text keys are found by counting up from 1, and every prose key the file holds is checked, so a page added later is covered.
	 */
	@GameTest
	public void everyChapterTextIsInTheLanguageFileAndIsNotAPlaceholder(GameTestHelper helper) {
		JsonObject lang = lang();
		List<String> keys = new ArrayList<>();
		for (Map.Entry<String, HandbookChapter> chapter : shipped(helper.getLevel().getServer()).entrySet()) {
			String base = "deepcharter.handbook.chapter.deepcharter." + chapter.getKey().substring(DeepCharter.MOD_ID.length() + 1);
			keys.add(base + ".title");
			keys.add(base + ".margin");
			chapter.getValue().directives().forEach(entry -> keys.add("deepcharter.handbook.directive."
					+ entry.id().getPath().substring("handbook/".length()).replace('/', '.')));
			keys.addAll(pagesFrom(lang, base + ".text."));
		}
		keys.addAll(pagesFrom(lang, "deepcharter.handbook.appendix.page."));
		keys.addAll(List.of("deepcharter.handbook.cover.company", "deepcharter.handbook.cover.subtitle", "deepcharter.handbook.slip.note",
				"deepcharter.handbook.slip.margin", "deepcharter.handbook.letter.body.1", "deepcharter.handbook.letter.body.2",
				"deepcharter.handbook.letter.margin", "deepcharter.handbook.appendix.margin"));
		lang.keySet().stream().filter(key -> PROSE_KEY.matcher(key).matches()).forEach(keys::add);
		for (String page : List.of("deepcharter.handbook.chapter.deepcharter.back_online.text.2", "deepcharter.handbook.appendix.page.3")) {
			if (!keys.contains(page)) {
				throw helper.assertionException("the pages should include %s", page);
			}
		}
		for (String key : keys) {
			if (!lang.has(key)) {
				throw helper.assertionException("the language file should have %s", key);
			}
			if (lang.get(key).getAsString().isBlank() || lang.get(key).getAsString().contains("PLACEHOLDER")) {
				throw helper.assertionException("%s should be real text, not a placeholder", key);
			}
		}
		helper.succeed();
	}

	/** {@code prefix + 1}, {@code prefix + 2}, ... while the language file has them, each with its margin note key when it has one. */
	static List<String> pagesFrom(JsonObject lang, String prefix) {
		List<String> keys = new ArrayList<>();
		for (int page = 1; lang.has(prefix + page); page++) {
			keys.add(prefix + page);
			if (lang.has(prefix + page + ".margin")) {
				keys.add(prefix + page + ".margin");
			}
		}
		return keys;
	}

	static JsonObject lang() {
		try (InputStream stream = HandbookChaptersOneToFiveTest.class.getResourceAsStream("/assets/deepcharter/lang/en_us.json")) {
			if (stream == null) {
				throw new IllegalStateException("no generated language file");
			}
			return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private static Identifier id(String id) {
		return Identifier.parse(id);
	}

	static void give(ServerPlayer player, Item item) {
		ItemStack stack = new ItemStack(item);
		player.getInventory().add(stack.copy());
		CriteriaTriggers.INVENTORY_CHANGED.trigger(player, player.getInventory(), stack);
		HandbookProgress.sweep(player);
	}

	static void expectCompleted(GameTestHelper helper, MinecraftServer server, String step, List<ServerPlayer> crew, Set<String> expected) {
		for (ServerPlayer member : crew) {
			Set<String> actual = new LinkedHashSet<>();
			HandbookProgress.completedFor(server, member.getUUID()).stream().filter(id -> !id.getPath().startsWith("handbook/sample"))
					.forEach(id -> actual.add(id.toString()));
			if (!actual.equals(expected)) {
				throw helper.assertionException("after %s, %s should have completed %s, got %s", step, member.getGameProfile().name(), expected, actual);
			}
		}
	}

	/** The chapter the crew is on, the first with a directive left: every chapter before it shows in full and the one after it only as a preview. */
	static void expectRoadAt(GameTestHelper helper, MinecraftServer server, ServerPlayer player, int current) {
		Set<Identifier> done = HandbookProgress.completedFor(server, player.getUUID());
		List<List<Identifier>> directives = new ArrayList<>();
		shipped(server).values().forEach(chapter -> directives.add(chapter.directives().stream().map(HandbookChapter.Entry::id).toList()));
		List<HandbookVisibility> visibility = HandbookVisibility.of(directives, done);
		for (int index = 0; index < visibility.size(); index++) {
			HandbookVisibility expected = index <= current ? HandbookVisibility.FULL : index == current + 1 ? HandbookVisibility.PREVIEW : HandbookVisibility.CLASSIFIED;
			if (visibility.get(index) != expected) {
				throw helper.assertionException("with chapter %s current, chapter %s should be %s, was %s", current + 1, index + 1, expected, visibility.get(index));
			}
		}
	}

	private static Set<String> through(int chapters, String... extra) {
		Set<String> directives = new LinkedHashSet<>();
		for (int index = 0; index < chapters; index++) {
			directives.addAll(PINNED.get(ORDER.get(index)));
		}
		directives.addAll(List.of(extra));
		return directives;
	}

	private static Set<String> with(Set<String> base, String... more) {
		Set<String> directives = new LinkedHashSet<>(base);
		directives.addAll(List.of(more));
		return directives;
	}

	static String directive(String chapter, String name) {
		return "deepcharter:handbook/" + chapter + "/" + name;
	}

	static void stand(GameTestHelper helper, MockPlayer mock, BlockPos terminal) {
		Vec3 centre = Vec3.atCenterOf(terminal);
		mock.teleportTo(helper.getLevel(), new Vec3(centre.x + 2, centre.y - mock.player().getEyeHeight(), centre.z), 0, 0);
	}

	static void expectDone(GameTestHelper helper, Optional<TerminalRefusal> refusal, String what) {
		if (refusal.isPresent()) {
			throw helper.assertionException("%s should succeed, was refused: %s", what, refusal.get());
		}
	}

	/** Puts every part of {@code type} into the terminal at {@code terminal}, one by one through the real insert action. */
	private static void repair(GameTestHelper helper, ServerPlayer player, BlockPos terminal, TerminalType type) {
		helper.getLevel().setBlock(terminal, type.block().defaultBlockState(), 3);
		for (Item part : type.parts()) {
			player.getInventory().add(new ItemStack(part));
			expectDone(helper, Terminals.insertPart(player, terminal, part), "putting " + part + " into " + type.id());
		}
	}

	static String uniqueName() {
		return "Chapters " + UUID.randomUUID().toString().substring(0, 8);
	}

	/**
	 * The scripted run: two players on one charter do what each chapter asks, through the real terminals, pod events and
	 * vanilla triggers, and the chapters complete one after the other, for both of them at each step.
	 */
	@GameTest(maxTicks = 200)
	public void aTwoPlayerCharterCompletesChaptersOneToFiveInOrder(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		RepairState repairs = RepairState.get(server);
		HangarData hangar = HangarData.get(server);
		server.getDataStorage().set(RepairState.TYPE, new RepairState());
		server.getDataStorage().set(HangarData.TYPE, new HangarData());
		PodEntity pod = null;
		MockPlayer director = MockPlayers.join(helper, "Director");
		MockPlayer crew = MockPlayers.join(helper, "Crew");
		try {
			ServerPlayer first = director.player();
			ServerPlayer second = crew.player();
			first.setGameMode(GameType.SURVIVAL);
			second.setGameMode(GameType.SURVIVAL);
			if (Charters.found(server, first.getUUID(), uniqueName()).isPresent()) {
				throw helper.assertionException("founding should succeed");
			}
			CharterId charter = Charters.charterOfOrThrow(server, first.getUUID()).orElseThrow().id();
			if (Charters.apply(server, second.getUUID(), charter).isPresent() || Charters.approve(server, first.getUUID(), second.getUUID()).isPresent()
					|| Charters.deposit(server, charter, 10_000).isPresent()) {
				throw helper.assertionException("the second player should join the charter, which is then funded");
			}
			List<ServerPlayer> both = List.of(first, second);
			BlockPos terminal = helper.absolutePos(TERMINAL);
			stand(helper, director, terminal);
			stand(helper, crew, terminal);
			expectCompleted(helper, server, "joining", both, Set.of());
			expectRoadAt(helper, server, first, 0);

			// Chapter 1: bootstrap crafting, shared between the two.
			give(first, Items.OAK_LOG);
			expectCompleted(helper, server, "gathering wood", both, Set.of(directive("welcome", "gather_wood")));
			give(second, Items.CRAFTING_TABLE);
			give(second, Items.STONE_AXE);
			give(first, Items.RAW_IRON);
			expectCompleted(helper, server, "mining iron", both, Set.of(directive("welcome", "gather_wood"), directive("welcome", "craft_crafting_table"),
					directive("welcome", "craft_stone_tools"), directive("welcome", "mine_iron_ore")));
			expectRoadAt(helper, server, second, 0);
			give(second, Items.IRON_INGOT);
			expectCompleted(helper, server, "chapter 1", both, through(1));
			expectRoadAt(helper, server, first, 1);

			// Chapter 2: the three terminals, in the order the world is repaired.
			repair(helper, first, terminal, TerminalTypes.FUEL_PUMP);
			expectCompleted(helper, server, "the fuel pump", both, with(through(1), directive("back_online", "repair_fuel_pump")));
			repair(helper, second, terminal, TerminalTypes.ORE_PROCESSOR);
			expectCompleted(helper, server, "the ore processor", both, with(through(1), directive("back_online", "repair_fuel_pump"),
					directive("back_online", "repair_ore_processor")));
			expectRoadAt(helper, server, first, 1);
			repair(helper, first, terminal, TerminalTypes.UPGRADE_TERMINAL);
			expectCompleted(helper, server, "chapter 2", both, through(2));
			expectRoadAt(helper, server, second, 2);

			// Chapter 3: the Mole is repaired at the hangar console, then boarded.
			repair(helper, second, terminal, HangarTerminal.TYPE);
			expectCompleted(helper, server, "the Mole", both, with(through(2), directive("meet_the_mole", "repair_mole")));
			pod = helper.spawn(PodRegistry.POD, 2, 1, 2);
			PodComponents.register(pod, charter);
			if (!first.startRiding(pod, true, false)) {
				throw helper.assertionException("the player should board the pod");
			}
			pod.tickCount = 0;
			PodEvents.AFTER_TICK.invoker().afterTick(pod);
			expectCompleted(helper, server, "chapter 3", both, through(3));
			expectRoadAt(helper, server, first, 3);

			// Chapter 4: fuel, flight, a ten block bore, and home again. Returning counts only after the bore.
			first.stopRiding();
			stand(helper, director, terminal);
			helper.getLevel().setBlock(terminal, TerminalTypes.FUEL_PUMP.block().defaultBlockState(), 3);
			pod.setPos(Vec3.atBottomCenterOf(terminal).add(2, 0, 2));
			pod.setFuel(0f);
			expectDone(helper, Terminals.act(first, terminal, FuelPump.FILL, new CompoundTag()), "filling the tank");
			expectCompleted(helper, server, "refuelling", both, with(through(3), directive("fuel_is_life", "refuel_mole")));
			first.startRiding(pod, true, false);
			pod.setFlying(true);
			pod.tickCount = 0;
			PodEvents.AFTER_TICK.invoker().afterTick(pod);
			expectCompleted(helper, server, "flying", both, with(through(3), directive("fuel_is_life", "refuel_mole"), directive("fuel_is_life", "fly_mole")));
			ColonySite.Placed colony = Colony.placed(server).orElseThrow();
			pod.setFlying(false);
			pod.setPos(Vec3.atBottomCenterOf(colony.center()));
			pod.tickCount = 0;
			PodEvents.AFTER_TICK.invoker().afterTick(pod);
			expectCompleted(helper, server, "being home before the bore", both, with(through(3), directive("fuel_is_life", "refuel_mole"), directive("fuel_is_life", "fly_mole")));
			pod.setDrilling(true);
			pod.setDrillDirection(Direction.DOWN);
			pod.setPos(pod.getX(), colony.groundY() - HandbookTuning.DEFAULT.drillDownBlocks() + 1, pod.getZ());
			pod.tickCount = 0;
			PodEvents.AFTER_TICK.invoker().afterTick(pod);
			expectCompleted(helper, server, "nine blocks down", both, with(through(3), directive("fuel_is_life", "refuel_mole"), directive("fuel_is_life", "fly_mole")));
			pod.setPos(pod.getX(), colony.groundY() - HandbookTuning.DEFAULT.drillDownBlocks(), pod.getZ());
			PodEvents.AFTER_TICK.invoker().afterTick(pod);
			expectCompleted(helper, server, "ten blocks down", both, with(through(3), directive("fuel_is_life", "refuel_mole"), directive("fuel_is_life", "fly_mole"),
					directive("fuel_is_life", "drill_down")));
			pod.setDrilling(false);
			pod.setPos(Vec3.atBottomCenterOf(colony.center()));
			PodEvents.AFTER_TICK.invoker().afterTick(pod);
			expectCompleted(helper, server, "chapter 4", both, through(4));
			expectRoadAt(helper, server, second, 4);

			// Chapter 5: sell ore, then buy a part, which installs at once.
			first.stopRiding();
			pod.setPos(Vec3.atBottomCenterOf(terminal).add(2, 0, 2));
			stand(helper, director, terminal);
			helper.getLevel().setBlock(terminal, TerminalTypes.ORE_PROCESSOR.block().defaultBlockState(), 3);
			first.getInventory().add(OreRegistry.stack(OreType.IRONIUM));
			expectDone(helper, Terminals.act(first, terminal, OreProcessor.SELL_INVENTORY, new CompoundTag()), "selling the ore");
			expectCompleted(helper, server, "selling", both, with(through(4), directive("every_sale_counts", "sell_ore")));
			helper.getLevel().setBlock(terminal, TerminalTypes.UPGRADE_TERMINAL.block().defaultBlockState(), 3);
			CompoundTag args = new CompoundTag();
			args.putString(UpgradeTerminal.TRACK_KEY, ComponentTrack.HULL.id());
			args.putInt(UpgradeTerminal.TIER_KEY, 1);
			expectDone(helper, Terminals.act(first, terminal, UpgradeTerminal.BUY, args), "buying a hull");
			expectCompleted(helper, server, "chapter 5", both, through(5));
			expectRoadAt(helper, server, second, 5);
			helper.succeed();
		} finally {
			if (pod != null) {
				pod.discard();
			}
			director.leave();
			crew.leave();
			server.getDataStorage().set(RepairState.TYPE, repairs);
			server.getDataStorage().set(HangarData.TYPE, hangar);
		}
	}

	/** A fresh repair state with {@code types} repaired, in the order given: a terminal's prerequisite comes first. The caller puts the world's back. */
	static RepairState repairedFor(TerminalType... types) {
		RepairState fresh = new RepairState();
		for (TerminalType type : types) {
			type.parts().forEach(part -> fresh.insert(type, part));
		}
		return fresh;
	}

	/**
	 * Runs {@code body} with the three terminals repaired in the world's repair state, and puts the world's own back before it
	 * returns. Everything runs in the one tick, so no test running beside this one ever sees the swapped state.
	 */
	private static void withTheTerminalsRepaired(MinecraftServer server, Runnable body) {
		RepairState original = RepairState.get(server);
		server.getDataStorage().set(RepairState.TYPE, repairedFor(TerminalTypes.FUEL_PUMP, TerminalTypes.ORE_PROCESSOR, TerminalTypes.UPGRADE_TERMINAL));
		try {
			body.run();
		} finally {
			server.getDataStorage().set(RepairState.TYPE, original);
		}
	}

	static final Set<String> THREE_REPAIRS = Set.of(directive("back_online", "repair_fuel_pump"),
			directive("back_online", "repair_ore_processor"), directive("back_online", "repair_upgrade_terminal"));

	static Set<Identifier> completed(MinecraftServer server, ServerPlayer player) {
		return HandbookProgress.completedFor(server, player.getUUID());
	}

	/** A charter founded after the terminals were repaired is credited with the three repairs: they work for everyone, so there is nothing left to repair. */
	@GameTest
	public void aCharterFoundedAfterTheRepairsIsCreditedWithThem(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		RepairState world = RepairState.get(server);
		MockPlayer late = MockPlayers.join(helper, "Latecomer");
		try {
			foundCharter(helper, server, late);
			expectCompleted(helper, server, "founding before any credit", List.of(late.player()), Set.of());
			withTheTerminalsRepaired(server, () -> HandbookTriggers.creditRepairs(server, late.player()));
			expectCompleted(helper, server, "founding after the repairs", List.of(late.player()), THREE_REPAIRS);
			if (RepairState.get(server) != world) {
				throw helper.assertionException("the world's own repair state should be back in place after the credit");
			}
			helper.succeed();
		} finally {
			late.leave();
		}
	}

	static void foundCharter(GameTestHelper helper, MinecraftServer server, MockPlayer mock) {
		mock.player().setGameMode(GameType.SURVIVAL);
		if (Charters.found(server, mock.player().getUUID(), uniqueName()).isPresent()) {
			throw helper.assertionException("founding should succeed");
		}
	}

	/** The credit is idempotent: a second poll changes nothing, and it reaches only the charter of the player it is given. */
	@GameTest
	public void creditingTheRepairsTwiceCompletesThemOnceAndLeavesOtherChartersAlone(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		MockPlayer late = MockPlayers.join(helper, "Latecomer");
		MockPlayer bystander = MockPlayers.join(helper, "Bystander");
		try {
			foundCharter(helper, server, late);
			foundCharter(helper, server, bystander);
			Set<Identifier> bystanderBefore = Set.copyOf(completed(server, bystander.player()));
			withTheTerminalsRepaired(server, () -> {
				HandbookTriggers.creditRepairs(server, late.player());
				Set<Identifier> once = Set.copyOf(completed(server, late.player()));
				HandbookTriggers.creditRepairs(server, late.player());
				if (!once.equals(completed(server, late.player()))) {
					throw helper.assertionException("a second credit should change nothing, got %s after %s", completed(server, late.player()), once);
				}
			});
			expectCompleted(helper, server, "two credits", List.of(late.player()), THREE_REPAIRS);
			if (!bystanderBefore.equals(completed(server, bystander.player()))) {
				throw helper.assertionException("another charter should be untouched, got %s from %s", completed(server, bystander.player()), bystanderBefore);
			}
			helper.succeed();
		} finally {
			late.leave();
			bystander.leave();
		}
	}

	/** A pilot and a terminal user on no charter complete nothing for anyone, and nothing throws. */
	@GameTest
	public void aPilotAndATerminalUserOnNoCharterCompleteNothing(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		MockPlayer member = MockPlayers.join(helper, "Member");
		MockPlayer drifter = MockPlayers.join(helper, "Drifter");
		PodEntity pod = null;
		try {
			foundCharter(helper, server, member);
			drifter.player().setGameMode(GameType.SURVIVAL);
			Set<Identifier> memberBefore = Set.copyOf(completed(server, member.player()));
			Charter charter = Charters.charterOfOrThrow(server, member.player().getUUID()).orElseThrow();
			for (TerminalType type : List.of(TerminalTypes.FUEL_PUMP, TerminalTypes.ORE_PROCESSOR, TerminalTypes.UPGRADE_TERMINAL, HangarTerminal.TYPE)) {
				TerminalEvents.REPAIRED.invoker().onRepaired(server, type, charter, drifter.player());
				for (Identifier action : List.of(FuelPump.BUY, FuelPump.FILL, OreProcessor.SELL_CARGO, OreProcessor.SELL_INVENTORY, UpgradeTerminal.BUY,
						HangarTerminal.BUY_MOLE)) {
					TerminalEvents.ACTED.invoker().onActed(server, type, drifter.player(), action);
				}
			}
			pod = helper.spawn(PodRegistry.POD, 2, 1, 2);
			if (!drifter.player().startRiding(pod, true, false)) {
				throw helper.assertionException("the player should board the pod");
			}
			pod.tickCount = 0;
			PodEvents.AFTER_TICK.invoker().afterTick(pod);
			if (!completed(server, drifter.player()).isEmpty() || !memberBefore.equals(completed(server, member.player()))) {
				throw helper.assertionException("nothing should complete: the drifter has %s, the member %s from %s", completed(server, drifter.player()),
						completed(server, member.player()), memberBefore);
			}
			helper.succeed();
		} finally {
			if (pod != null) {
				pod.discard();
			}
			member.leave();
			drifter.leave();
		}
	}

	/** The pod poll runs on every {@code triggerPollTicks}th tick of the pod: a tick between two of them completes nothing. */
	@GameTest
	public void theBoardingPollSkipsATickThatIsNotAMultipleOfThePollInterval(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		MockPlayer pilot = MockPlayers.join(helper, "Pilot");
		PodEntity pod = null;
		try {
			foundCharter(helper, server, pilot);
			pod = helper.spawn(PodRegistry.POD, 2, 1, 2);
			PodComponents.register(pod, Charters.charterOfOrThrow(server, pilot.player().getUUID()).orElseThrow().id());
			if (!pilot.player().startRiding(pod, true, false)) {
				throw helper.assertionException("the player should board the pod");
			}
			int interval = HandbookTuning.DEFAULT.triggerPollTicks();
			if (interval < 2) {
				throw helper.assertionException("the test needs a poll interval above 1, got %s", interval);
			}
			pod.tickCount = 1;
			PodEvents.AFTER_TICK.invoker().afterTick(pod);
			pod.tickCount = interval + 1;
			PodEvents.AFTER_TICK.invoker().afterTick(pod);
			expectCompleted(helper, server, "ticks between polls", List.of(pilot.player()), Set.of());
			pod.tickCount = interval;
			PodEvents.AFTER_TICK.invoker().afterTick(pod);
			expectCompleted(helper, server, "a poll tick", List.of(pilot.player()), Set.of(directive("meet_the_mole", "board_mole")));
			helper.succeed();
		} finally {
			if (pod != null) {
				pod.discard();
			}
			pilot.leave();
		}
	}

	private static final CompoundTag FUTURE = futureVersion();

	private static CompoundTag futureVersion() {
		CompoundTag future = new CompoundTag();
		future.putInt("version", 99);
		future.putString("shape", "from a later build");
		return future;
	}

	/** Every callback and the credit run once with the saved charters, then once with the saved progress, of a version this build cannot read. */
	@GameTest
	public void theCallbacksDoNotThrowOnUnreadableCharterOrProgressData(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		MockPlayer pilot = MockPlayers.join(helper, "Pilot");
		PodEntity pod = null;
		try {
			foundCharter(helper, server, pilot);
			pod = helper.spawn(PodRegistry.POD, 2, 1, 2);
			Charter charter = Charters.charterOfOrThrow(server, pilot.player().getUUID()).orElseThrow();
			PodComponents.register(pod, charter.id());
			if (!pilot.player().startRiding(pod, true, false)) {
				throw helper.assertionException("the player should board the pod");
			}
			CharterData charters = CharterData.get(server);
			HandbookProgressData progress = HandbookProgressData.get(server);
			// Each swap lasts inside this one tick, so no other test sees it.
			PodEntity riddenPod = pod;
			Runnable callbacks = () -> {
				for (TerminalType type : List.of(TerminalTypes.FUEL_PUMP, TerminalTypes.ORE_PROCESSOR, TerminalTypes.UPGRADE_TERMINAL, HangarTerminal.TYPE)) {
					TerminalEvents.REPAIRED.invoker().onRepaired(server, type, charter, pilot.player());
					for (Identifier action : List.of(FuelPump.BUY, FuelPump.FILL, OreProcessor.SELL_CARGO, OreProcessor.SELL_INVENTORY, UpgradeTerminal.BUY,
							HangarTerminal.BUY_MOLE)) {
						TerminalEvents.ACTED.invoker().onActed(server, type, pilot.player(), action);
					}
				}
				riddenPod.tickCount = 0;
				PodEvents.AFTER_TICK.invoker().afterTick(riddenPod);
				withTheTerminalsRepaired(server, () -> HandbookTriggers.creditRepairs(server, pilot.player()));
			};
			server.getDataStorage().set(CharterData.TYPE, CharterData.CODEC.parse(NbtOps.INSTANCE, FUTURE).getOrThrow());
			try {
				callbacks.run();
			} finally {
				server.getDataStorage().set(CharterData.TYPE, charters);
			}
			server.getDataStorage().set(HandbookProgressData.TYPE, HandbookProgressData.CODEC.parse(NbtOps.INSTANCE, FUTURE).getOrThrow());
			try {
				callbacks.run();
			} finally {
				server.getDataStorage().set(HandbookProgressData.TYPE, progress);
			}
			if (CharterData.get(server) != charters || HandbookProgressData.get(server) != progress) {
				throw helper.assertionException("the saved data should be back in place");
			}
			expectCompleted(helper, server, "unreadable data", List.of(pilot.player()), Set.of());
			helper.succeed();
		} finally {
			if (pod != null) {
				pod.discard();
			}
			pilot.leave();
		}
	}

	private static final Identifier BEFORE_THE_POLL = Identifier.fromNamespaceAndPath("deepcharter-test", "before_the_handbook_poll");
	private static final Identifier AFTER_THE_POLL = Identifier.fromNamespaceAndPath("deepcharter-test", "after_the_handbook_poll");
	/** The last server tick on which the swap below may happen, or -1 when it is not armed. It runs out by itself, so a test that times out leaves nothing armed. */
	private static final AtomicInteger ARMED_UNTIL = new AtomicInteger(-1);
	/** A GameTest environment of its own: the batches run one after another, so nothing else runs while the real listener sees the swapped state. */
	private static final String ALONE = "deepcharter-test:alone";
	private static RepairState worldsRepairs;

	// The handbook's own END_SERVER_TICK listener is in the default phase: the swap goes in just before it and comes out just after it,
	// on the one tick, so no other test sees the repaired state.
	static {
		Event<ServerTickEvents.EndTick> endTick = ServerTickEvents.END_SERVER_TICK;
		endTick.addPhaseOrdering(BEFORE_THE_POLL, Event.DEFAULT_PHASE);
		endTick.addPhaseOrdering(Event.DEFAULT_PHASE, AFTER_THE_POLL);
		endTick.register(BEFORE_THE_POLL, server -> {
			if (server.getTickCount() <= ARMED_UNTIL.get() && server.getTickCount() % HandbookTuning.DEFAULT.triggerPollTicks() == 0) {
				worldsRepairs = RepairState.get(server);
				server.getDataStorage().set(RepairState.TYPE,
						repairedFor(TerminalTypes.FUEL_PUMP, TerminalTypes.ORE_PROCESSOR, TerminalTypes.UPGRADE_TERMINAL));
			}
		});
		endTick.register(AFTER_THE_POLL, server -> {
			if (worldsRepairs != null) {
				server.getDataStorage().set(RepairState.TYPE, worldsRepairs);
				worldsRepairs = null;
				ARMED_UNTIL.set(-1);
			}
		});
	}

	/**
	 * The server tick listener that {@code HandbookTriggers.init()} registers credits a charter founded after the repairs, on a tick
	 * that is a multiple of {@code triggerPollTicks}: the repaired state is in place for that tick only, and nobody is credited before.
	 * The listener credits every online player, so the test runs in an environment of its own, in a batch of its own, with no other
	 * test's player online; and the swap is armed for the next poll tick only, so a timeout leaves it disarmed.
	 */
	@GameTest(maxTicks = 100, environment = ALONE)
	public void theServerTickListenerCreditsTheRepairsOnAPollTick(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		RepairState world = RepairState.get(server);
		MockPlayer late = MockPlayers.join(helper, "Latecomer");
		foundCharter(helper, server, late);
		expectCompleted(helper, server, "founding before the poll", List.of(late.player()), Set.of());
		ARMED_UNTIL.set(server.getTickCount() + HandbookTuning.DEFAULT.triggerPollTicks());
		helper.succeedWhen(() -> {
			expectCompleted(helper, server, "a poll tick", List.of(late.player()), THREE_REPAIRS);
			if (RepairState.get(server) != world || worldsRepairs != null) {
				throw helper.assertionException("the world's own repair state should be back in place after the poll tick");
			}
		});
	}

	private static HangarData foundedHangar() {
		CompoundTag saved = ((CompoundTag) HangarData.CODEC.encodeStart(NbtOps.INSTANCE, new HangarData()).getOrThrow()).copy();
		saved.putBoolean("founded", true);
		return HangarData.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
	}

	/**
	 * A charter that came after the founding charter repairs no Mole: it buys a refurbished one at the hangar, and the purchase
	 * completes "Repair the Mole".
	 */
	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 200)
	public void aLateCharterCompletesRepairTheMoleByBuyingOneAtTheHangar(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		BlockPos anchor = Colony.anchor(server, ColonyAnchor.HANGAR).orElseThrow(() -> helper.assertionException("the colony was not built when the server started"));
		FarChunks.awaitEntityTicking(helper, server.overworld(), anchor, () -> {
			HangarData hangar = HangarData.get(server);
			RepairState repairs = RepairState.get(server);
			Serials serials = Serials.get(server);
			MockPlayer buyer = MockPlayers.join(helper, "Buyer");
			AABB bay = new AABB(anchor).inflate(HangarTuning.DEFAULT.bayRadius() + 4);
			Set<UUID> before = new HashSet<>();
			server.overworld().getEntitiesOfClass(PodEntity.class, bay).forEach(pod -> before.add(pod.getUUID()));
			// Everything runs inside this one tick, so no other test sees the swapped records.
			server.getDataStorage().set(HangarData.TYPE, foundedHangar());
			server.getDataStorage().set(RepairState.TYPE, new RepairState());
			server.getDataStorage().set(Serials.TYPE, new Serials());
			try {
				foundCharter(helper, server, buyer);
				if (Charters.deposit(server, Charters.charterOfOrThrow(server, buyer.player().getUUID()).orElseThrow().id(), 100_000).isPresent()) {
					throw helper.assertionException("depositing should succeed");
				}
				for (Item part : HangarTerminal.TYPE.parts()) {
					RepairState.get(server).insert(HangarTerminal.TYPE, part);
				}
				BlockPos console = helper.absolutePos(TERMINAL);
				helper.getLevel().setBlock(console, HangarTerminal.TYPE.block().defaultBlockState(), 3);
				stand(helper, buyer, console);
				expectCompleted(helper, server, "joining late", List.of(buyer.player()), Set.of());
				expectDone(helper, Terminals.act(buyer.player(), console, HangarTerminal.BUY_MOLE, new CompoundTag()), "buying a Mole");
				expectCompleted(helper, server, "buying a Mole", List.of(buyer.player()), Set.of(directive("meet_the_mole", "repair_mole")));
				helper.succeed();
			} finally {
				server.overworld().getEntitiesOfClass(PodEntity.class, bay).stream().filter(pod -> !before.contains(pod.getUUID())).forEach(PodEntity::discard);
				buyer.leave();
				server.getDataStorage().set(HangarData.TYPE, hangar);
				server.getDataStorage().set(RepairState.TYPE, repairs);
				server.getDataStorage().set(Serials.TYPE, serials);
			}
		});
	}
}
