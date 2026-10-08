package io.github.pkeppeler.deepcharter.test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.fuel.FuelPump;
import io.github.pkeppeler.deepcharter.hangar.HangarData;
import io.github.pkeppeler.deepcharter.hangar.HangarTerminal;
import io.github.pkeppeler.deepcharter.handbook.HandbookChapter;
import io.github.pkeppeler.deepcharter.handbook.HandbookChapters;
import io.github.pkeppeler.deepcharter.handbook.HandbookProgress;
import io.github.pkeppeler.deepcharter.handbook.HandbookTuning;
import io.github.pkeppeler.deepcharter.handbook.HandbookVisibility;
import io.github.pkeppeler.deepcharter.market.OreProcessor;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalRefusal;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.Terminals;
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
	private static final Map<String, List<String>> PINNED = Map.of(
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
	private static final List<String> ORDER = List.of("deepcharter:welcome", "deepcharter:back_online", "deepcharter:meet_the_mole",
			"deepcharter:fuel_is_life", "deepcharter:every_sale_counts");

	/** The chapters of this issue by id in handbook order, leaving out the sample chapter that the test mod adds in front. */
	private static Map<String, HandbookChapter> shipped(MinecraftServer server) {
		Map<String, HandbookChapter> chapters = new LinkedHashMap<>();
		for (Holder.Reference<HandbookChapter> chapter : HandbookChapters.all(server)) {
			if (!chapter.key().identifier().getPath().equals(SAMPLE)) {
				chapters.put(chapter.key().identifier().toString(), chapter.value());
			}
		}
		return chapters;
	}

	private static List<String> directivesOf(HandbookChapter chapter) {
		return chapter.directives().stream().map(entry -> entry.id().toString()).toList();
	}

	@GameTest
	public void chaptersOneToFiveAndTheirDirectivesArePinnedInOrder(GameTestHelper helper) {
		Map<String, HandbookChapter> chapters = shipped(helper.getLevel().getServer());
		if (!new ArrayList<>(chapters.keySet()).equals(ORDER)) {
			throw helper.assertionException("the chapters should be %s in order, got %s", ORDER, chapters.keySet());
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

	/** Every title, directive line and text page is a real line of the language file, none a placeholder. */
	@GameTest
	public void everyChapterTextIsInTheLanguageFileAndIsNotAPlaceholder(GameTestHelper helper) {
		JsonObject lang = lang();
		List<String> keys = new ArrayList<>();
		for (Map.Entry<String, HandbookChapter> chapter : shipped(helper.getLevel().getServer()).entrySet()) {
			String path = chapter.getKey().substring(DeepCharter.MOD_ID.length() + 1);
			keys.add("deepcharter.handbook.chapter.deepcharter." + path + ".title");
			keys.add("deepcharter.handbook.chapter.deepcharter." + path + ".text.1");
			keys.add("deepcharter.handbook.chapter.deepcharter." + path + ".margin");
			chapter.getValue().directives().forEach(entry -> keys.add("deepcharter.handbook.directive."
					+ entry.id().getPath().substring("handbook/".length()).replace('/', '.')));
		}
		keys.addAll(List.of("deepcharter.handbook.cover.company", "deepcharter.handbook.cover.subtitle", "deepcharter.handbook.slip.note",
				"deepcharter.handbook.slip.margin", "deepcharter.handbook.letter.body.1", "deepcharter.handbook.letter.body.2",
				"deepcharter.handbook.letter.margin", "deepcharter.handbook.appendix.page.1", "deepcharter.handbook.appendix.page.2",
				"deepcharter.handbook.appendix.page.3"));
		for (String key : keys) {
			if (!lang.has(key)) {
				throw helper.assertionException("the language file should have %s", key);
			}
			if (lang.get(key).getAsString().contains("PLACEHOLDER")) {
				throw helper.assertionException("%s should be real text, not a placeholder", key);
			}
		}
		helper.succeed();
	}

	private static JsonObject lang() {
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

	private static void give(ServerPlayer player, Item item) {
		ItemStack stack = new ItemStack(item);
		player.getInventory().add(stack.copy());
		CriteriaTriggers.INVENTORY_CHANGED.trigger(player, player.getInventory(), stack);
		HandbookProgress.sweep(player);
	}

	private static void expectCompleted(GameTestHelper helper, MinecraftServer server, String step, List<ServerPlayer> crew, Set<String> expected) {
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
	private static void expectRoadAt(GameTestHelper helper, MinecraftServer server, ServerPlayer player, int current) {
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

	private static String directive(String chapter, String name) {
		return "deepcharter:handbook/" + chapter + "/" + name;
	}

	private static void stand(GameTestHelper helper, MockPlayer mock, BlockPos terminal) {
		Vec3 centre = Vec3.atCenterOf(terminal);
		mock.teleportTo(helper.getLevel(), new Vec3(centre.x + 2, centre.y - mock.player().getEyeHeight(), centre.z), 0, 0);
	}

	private static void expectDone(GameTestHelper helper, Optional<TerminalRefusal> refusal, String what) {
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

	private static String uniqueName() {
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
			CharterId charter = Charters.charterOf(server, first.getUUID()).orElseThrow().id();
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
			expectRoadAt(helper, server, second, 4);
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

	/** A charter founded after the terminals were repaired is credited with the three repairs: they work for everyone, so there is nothing left to repair. */
	@GameTest(maxTicks = 200)
	public void aCharterFoundedAfterTheRepairsIsCreditedWithThem(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		RepairState original = RepairState.get(server);
		RepairState fresh = new RepairState();
		for (TerminalType type : List.of(TerminalTypes.FUEL_PUMP, TerminalTypes.ORE_PROCESSOR, TerminalTypes.UPGRADE_TERMINAL)) {
			type.parts().forEach(part -> fresh.insert(type, part));
		}
		server.getDataStorage().set(RepairState.TYPE, fresh);
		MockPlayer late = MockPlayers.join(helper, "Latecomer");
		try {
			if (Charters.found(server, late.player().getUUID(), uniqueName()).isPresent()) {
				throw helper.assertionException("founding should succeed");
			}
			Set<String> repaired = Set.of(directive("back_online", "repair_fuel_pump"), directive("back_online", "repair_ore_processor"),
					directive("back_online", "repair_upgrade_terminal"));
			helper.runAfterDelay(HandbookTuning.DEFAULT.triggerPollTicks() * 3L, () -> {
				try {
					expectCompleted(helper, server, "founding after the repairs", List.of(late.player()), repaired);
					helper.succeed();
				} finally {
					late.leave();
					server.getDataStorage().set(RepairState.TYPE, original);
				}
			});
		} catch (RuntimeException e) {
			late.leave();
			server.getDataStorage().set(RepairState.TYPE, original);
			throw e;
		}
	}
}
