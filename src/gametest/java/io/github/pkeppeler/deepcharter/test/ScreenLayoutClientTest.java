package io.github.pkeppeler.deepcharter.test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntFunction;
import java.util.function.Supplier;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.charter.CharterTuning;
import io.github.pkeppeler.deepcharter.charter.terminal.ContractState;
import io.github.pkeppeler.deepcharter.charter.terminal.ContractTerminal;
import io.github.pkeppeler.deepcharter.charter.terminal.ContractTerminalTuning;
import io.github.pkeppeler.deepcharter.client.charter.terminal.ContractScreen;
import io.github.pkeppeler.deepcharter.client.handbook.ClientHandbook;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookNote;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookPages;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookScreen;
import io.github.pkeppeler.deepcharter.client.terminal.TerminalScreens;
import io.github.pkeppeler.deepcharter.client.ui.CrtDemoScreen;
import io.github.pkeppeler.deepcharter.client.upgrade.UpgradeScreen;
import io.github.pkeppeler.deepcharter.handbook.HandbookChapter;
import io.github.pkeppeler.deepcharter.handbook.HandbookChapters;
import io.github.pkeppeler.deepcharter.hangar.HangarTerminal;
import io.github.pkeppeler.deepcharter.hangar.HangarView;
import io.github.pkeppeler.deepcharter.market.WorkOrder;
import io.github.pkeppeler.deepcharter.market.WorkOrdersView;
import io.github.pkeppeler.deepcharter.ore.OreCargoMenu;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.pod.PodTuning;
import io.github.pkeppeler.deepcharter.terminal.TerminalFeature;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.TerminalView;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks.Box;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeTuning;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeView;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

/**
 * Client GameTest for #381: every mod screen fits the default window (854 x 480 at GUI scale 2, so 427 x 240), with the largest realistic
 * content. Each widget lies inside the screen and no two widgets overlap; a failure names the screen, the widget and the overflow in pixels.
 *
 * <p>The screens are found, not listed. The terminal screens come from {@link TerminalTypes#all()} through {@link TerminalScreens#create},
 * so a new terminal is built the way the game builds it (the generic {@code TerminalScreen} is the offline screen of every type, and the online one of
 * a type that registers none); the container screens come from the menu types the mod registered and the screen
 * each registered with {@code MenuScreens}. A terminal type that adds a feature, or a menu type, with no fixture below fails the test and
 * says so, so a new one cannot be skipped. Only the screens nothing registers are named by hand ({@link #standalone}), and
 * {@code tools/tests/test_screen_layout_gate.py} fails when a screen class is neither registered nor named in this file.
 *
 * <p>No screen needs a scroll region declared: the repair station builds buttons for the rows that fit only, so its widgets are checked as they are.
 * The handbook is paged and the work order list pages, and each page is a variant here.
 */
public class ScreenLayoutClientTest implements FabricClientGameTest {
	private static final int WIDTH = 427;
	private static final int HEIGHT = 240;
	private static final BlockPos POS = new BlockPos(0, 64, 0);
	/** The longest a charter or player name can be. */
	private static final int NAME_LENGTH = CharterTuning.DEFAULT.maxNameLength();

	/** One thing to check after a screen is open: a track chosen, a page turned. */
	private record Variant(String name, VariantStep step) {
	}

	/** Puts the open screen in a state, and returns the layout problems the step itself found in the states it passed through (usually none). */
	@FunctionalInterface
	private interface VariantStep {
		List<String> apply(ClientGameTestContext context, Screen screen);
	}

	/**
	 * A screen to open and the states to check it in. {@code screen} runs on the client thread.
	 *
	 * @param scrollRegions rectangles the screen scrolls its content in, if it scrolls by design. A widget that lies inside a region is not
	 *                      held to the screen edge: the region is what the screen vouches for. A widget outside every region still is.
	 */
	private record Case(String name, Supplier<Screen> screen, List<Variant> variants, List<Box> scrollRegions) {
		Case(String name, Supplier<Screen> screen) {
			this(name, screen, List.of(), List.of());
		}

		Case(String name, Supplier<Screen> screen, List<Variant> variants) {
			this(name, screen, variants, List.of());
		}

		/** This case for a screen that scrolls its content in {@code regions}. */
		Case scrolls(List<Box> regions) {
			return new Case(name, screen, variants, List.copyOf(regions));
		}
	}

	/** What a terminal type's online view carries, at its largest. A type that registers a feature must have one. */
	private static Map<TerminalType, TerminalFeature> features() {
		Map<TerminalType, TerminalFeature> features = new LinkedHashMap<>();
		features.put(HangarTerminal.TYPE, new HangarView(999));
		features.put(TerminalTypes.UPGRADE_TERMINAL, upgradeView());
		List<WorkOrdersView.Entry> orders = new ArrayList<>();
		for (WorkOrder order : WorkOrder.values()) {
			orders.add(new WorkOrdersView.Entry(order, Math.max(0, order.quantity() - 1), 99));
		}
		features.put(TerminalTypes.ORE_PROCESSOR, new WorkOrdersView(true, orders));
		return features;
	}

	/** A pod with every track at its best tier and a cap below it, so each row carries the longest label. */
	private static UpgradeView upgradeView() {
		List<UpgradeView.Slot> slots = new ArrayList<>();
		for (ComponentTrack track : ComponentTrack.values()) {
			slots.add(new UpgradeView.Slot(track, track.maxTier(), Math.min(track.maxTier(), 3)));
		}
		return new UpgradeView(Optional.of(new UpgradeView.Pod("PROSPECTOR-9999", 3, slots)), false);
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);
		checkTheGateSeesAnOverflow(context);
		List<String> problems = new ArrayList<>();
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			List<Case> cases = new ArrayList<>();
			cases.addAll(terminalCases());
			cases.addAll(menuCases());
			cases.addAll(standalone());
			for (Case screenCase : cases) {
				check(context, screenCase, problems);
			}
			context.setScreen(() -> null);
			System.out.println("[screen-layout] checked " + cases.size() + " screens at " + WIDTH + " x " + HEIGHT + ": " + cases.stream().map(Case::name).toList());
		}
		require(problems.isEmpty(), problems.size() + " layout problem(s) at the default window:\n" + String.join("\n", problems));
	}

	/** The gate must fail on a screen that overflows, or a green run says nothing. */
	private static void checkTheGateSeesAnOverflow(ClientGameTestContext context) {
		List<String> problems = new ArrayList<>();
		List<String> scrolled = new ArrayList<>();
		context.runOnClient(client -> {
			Screen screen = new OverflowFixtureScreen();
			screen.init(WIDTH, HEIGHT);
			problems.addAll(problemsOf("fixture", screen, List.of()));
			Screen scrolling = new ScrollFixtureScreen();
			scrolling.init(WIDTH, HEIGHT);
			scrolled.addAll(problemsOf("scroll fixture", scrolling, new Case("scroll fixture", ScrollFixtureScreen::new).scrolls(List.of(SCROLL_REGION)).scrollRegions()));
		});
		require(problems.size() == 2, "the overflowing fixture should give 2 problems (one leaves the screen, one overlap), got " + problems);
		require(problems.get(0).contains("fixture") && problems.get(0).contains("'WIDE'") && problems.get(0).contains("10 px past the right edge"),
				"a problem names the screen, the widget and the overflow in pixels, got " + problems.get(0));
		require(problems.get(1).contains("'WIDE' overlaps 'UNDER' by 20 x 10 px"), "an overlap names both widgets and the pixels, got " + problems.get(1));
		require(scrolled.size() == 1 && scrolled.getFirst().contains("'OUT'") && scrolled.getFirst().contains("20 px past the right edge"),
				"past the edge inside the scroll region passes, outside it fails: expected one problem, about 'OUT', got " + scrolled);
	}

	/** A button 10 pixels past the right edge, and two that overlap by 20 x 10 pixels. */
	private static final class OverflowFixtureScreen extends Screen {
		OverflowFixtureScreen() {
			super(Component.literal("fixture"));
		}

		@Override
		protected void init() {
			addRenderableWidget(Button.builder(Component.literal("WIDE"), button -> { }).bounds(WIDTH - 90, 10, 100, 20).build());
			addRenderableWidget(Button.builder(Component.literal("UNDER"), button -> { }).bounds(WIDTH - 110, 20, 40, 20).build());
		}
	}

	/** Where {@link ScrollFixtureScreen} scrolls: it reaches 50 pixels past the right edge. */
	private static final Box SCROLL_REGION = new Box(WIDTH - 30, 100, 80, 60);

	/** Two buttons 20 pixels past the right edge: one inside the declared scroll region, one below it. */
	private static final class ScrollFixtureScreen extends Screen {
		ScrollFixtureScreen() {
			super(Component.literal("scroll fixture"));
		}

		@Override
		protected void init() {
			addRenderableWidget(Button.builder(Component.literal("IN"), button -> { }).bounds(WIDTH - 20, 110, 40, 20).build());
			addRenderableWidget(Button.builder(Component.literal("OUT"), button -> { }).bounds(WIDTH - 20, 200, 40, 20).build());
		}
	}

	private static void check(ClientGameTestContext context, Case screenCase, List<String> problems) {
		Screen screen = context.computeOnClient(client -> screenCase.screen().get());
		context.setScreen(() -> screen);
		ClientWait.until(context, "the " + screenCase.name() + " screen", client -> client.gui.screen() == screen);
		problems.addAll(context.computeOnClient(client -> sizeProblem(screenCase.name(), screen)));
		problems.addAll(context.computeOnClient(client -> problemsOf(screenCase.name(), screen, screenCase.scrollRegions())));
		System.out.println("[screen-layout] " + screenCase.name() + ": " + screen.getClass().getSimpleName() + ", "
				+ context.computeOnClient(client -> ClientChecks.visibleWidgets(screen).size()) + " widgets");
		for (Variant variant : screenCase.variants()) {
			problems.addAll(variant.step().apply(context, screen));
			String name = screenCase.name() + " / " + variant.name();
			problems.addAll(context.computeOnClient(client -> problemsOf(name, screen, screenCase.scrollRegions())));
		}
	}

	private static List<String> sizeProblem(String name, Screen screen) {
		return screen.width == WIDTH && screen.height == HEIGHT ? List.of()
				: List.of(name + ": the test window is 854 x 480 at GUI scale 2, a screen of " + WIDTH + " x " + HEIGHT + ", got " + screen.width + " x " + screen.height);
	}

	/**
	 * Every way the screen's widgets (and, for a container screen, its panel and slots) leave the window or overlap. A widget inside one of
	 * {@code scrollRegions} may reach past the screen edge. Client thread.
	 */
	private static List<String> problemsOf(String name, Screen screen, List<Box> scrollRegions) {
		List<String> problems = new ArrayList<>();
		for (AbstractWidget widget : ClientChecks.visibleWidgets(screen)) {
			if (scrollRegions.stream().noneMatch(region -> contains(region, Box.of(widget)))) {
				ClientChecks.widgetLeavesScreen(screen, widget).ifPresent(problem -> problems.add(name + ": " + problem));
			}
			if (widget instanceof Button button) {
				ClientChecks.labelClipped(button).ifPresent(problem -> problems.add(name + ": " + problem));
			}
		}
		List<AbstractWidget> interactive = ClientChecks.interactiveWidgets(screen);
		for (int i = 0; i < interactive.size(); i++) {
			ClientChecks.widgetOverlaps(interactive.get(i), interactive.subList(i + 1, interactive.size())).ifPresent(problem -> problems.add(name + ": " + problem));
		}
		if (screen instanceof AbstractContainerScreen<?> container) {
			int left = intField(container, "leftPos");
			int top = intField(container, "topPos");
			ClientChecks.boxLeavesScreen(screen, "the panel", new Box(left, top, intField(container, "imageWidth"), intField(container, "imageHeight")))
					.ifPresent(problem -> problems.add(name + ": " + problem));
			for (Slot slot : container.getMenu().slots) {
				ClientChecks.boxLeavesScreen(screen, "slot " + slot.index, new Box(left + slot.x, top + slot.y, 16, 16))
						.ifPresent(problem -> problems.add(name + ": " + problem));
			}
		}
		return problems;
	}

	private static boolean contains(Box region, Box box) {
		return box.x() >= region.x() && box.y() >= region.y() && box.right() <= region.right() && box.bottom() <= region.bottom();
	}

	private static int intField(Object target, String name) {
		try {
			Field field = AbstractContainerScreen.class.getDeclaredField(name);
			field.setAccessible(true);
			return field.getInt(target);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("a container screen has no field " + name + "; the gate reads it by name", e);
		}
	}

	private static List<Case> terminalCases() {
		Map<TerminalType, TerminalFeature> features = features();
		List<Case> cases = new ArrayList<>();
		for (TerminalType type : TerminalTypes.all()) {
			if (!type.id().getNamespace().equals(DeepCharter.MOD_ID)) {
				continue; // the gametest mod registers terminals of its own to test the framework
			}
			boolean registers = registersFeature(type);
			require(registers == features.containsKey(type), "terminal " + type.id()
					+ (registers ? " adds a view feature but has no fixture in ScreenLayoutClientTest.features()" : " has a fixture there but registers no feature"));
			List<TerminalView.PartStatus> missing = type.parts().stream().map(part -> new TerminalView.PartStatus(BuiltInRegistries.ITEM.getKey(part), false)).toList();
			List<TerminalView.PartStatus> inserted = type.parts().stream().map(part -> new TerminalView.PartStatus(BuiltInRegistries.ITEM.getKey(part), true)).toList();
			String id = type.id().getPath();
			if (type.needsRepair()) {
				TerminalView locked = new TerminalView(POS, type.id(), false, false, missing, Optional.empty());
				TerminalView open = new TerminalView(POS, type.id(), false, true, missing, Optional.empty());
				if (type.prerequisite().isPresent()) {
					cases.add(new Case(id + " offline, locked", () -> TerminalScreens.create(locked)));
				}
				cases.add(new Case(id + " offline, parts missing", () -> TerminalScreens.create(open)));
			}
			TerminalView online = new TerminalView(POS, type.id(), true, true, type.needsRepair() ? inserted : List.of(), Optional.ofNullable(features.get(type)));
			if (type == ContractTerminal.TYPE) {
				// The server's state arrives after the screen is open, so each role is a variant of the open screen.
				List<Variant> roles = new ArrayList<>();
				for (ContractState state : contractStates()) {
					roles.add(new Variant("role " + state.role(), (context, screen) -> {
						context.runOnClient(client -> ((ContractScreen) screen).show(state));
						return List.of();
					}));
				}
				cases.add(new Case(id + " online", () -> TerminalScreens.create(online), roles));
			} else if (type == TerminalTypes.UPGRADE_TERMINAL) {
				cases.add(new Case(id + " online", () -> TerminalScreens.create(online), upgradeTrackVariants()));
			} else {
				cases.add(new Case(id + " online", () -> TerminalScreens.create(online)));
			}
		}
		return cases;
	}

	/**
	 * True when the terminal type registered a view feature. {@code TerminalFeatures} offers no query, so this decodes a view that claims a feature:
	 * a type with none refuses it with "registered none", and a type with one fails reading the feature that is not there.
	 */
	private static boolean registersFeature(TerminalType type) {
		ByteBuf buffer = Unpooled.buffer();
		TerminalView.STREAM_CODEC.encode(buffer, new TerminalView(POS, type.id(), true, true, List.of(), Optional.empty()));
		buffer.setByte(buffer.writerIndex() - 1, 1);
		try {
			TerminalView.STREAM_CODEC.decode(buffer);
		} catch (RuntimeException e) {
			return e.getMessage() == null || !e.getMessage().contains("registered none");
		}
		throw new AssertionError("a view that claims a feature it does not carry decoded for " + type.id());
	}

	/** The contract terminal at each role, with the longest names and as many rows as it lists. */
	private static List<ContractState> contractStates() {
		String name = "W".repeat(NAME_LENGTH);
		List<String> names = Collections.nCopies(ContractTerminalTuning.DEFAULT.listedRows(), name);
		int total = names.size() + 5;
		return List.of(
				new ContractState(ContractState.Role.NONE, "", names, total, List.of(), 0, Optional.of("screen.deepcharter.contract.refused")),
				new ContractState(ContractState.Role.APPLICANT, name, List.of(), 0, List.of(), 0, Optional.empty()),
				new ContractState(ContractState.Role.CREW, name, List.of(), 0, List.of(), 0, Optional.empty()),
				new ContractState(ContractState.Role.DIRECTOR, name, List.of(), 0, names, total, Optional.empty()));
	}

	/** Chooses each track in turn: the right column lists that track's tiers, so its length changes. */
	private static List<Variant> upgradeTrackVariants() {
		List<Variant> variants = new ArrayList<>();
		for (ComponentTrack track : ComponentTrack.values()) {
			variants.add(new Variant("track " + track.id() + " chosen", (context, screen) -> {
				// The screen offers no way to choose a track but its button, whose label is this lang key with the track's name and installed tier.
				String label = context.computeOnClient(client -> Component.translatable("screen.deepcharter.upgrade.track",
						Component.translatable("deepcharter.upgrade.track." + track.id()).getString(), track.maxTier()).getString());
				UpgradeScreen upgrade = (UpgradeScreen) screen;
				if (upgrade.selected() != track) {
					context.clickScreenButton(label);
				}
				ClientWait.until(context, "the track " + track.id() + " selected", client -> upgrade.selected() == track);
				return List.of();
			}));
		}
		return variants;
	}

	/** One screen for each menu type the mod registered, built with the screen constructor it registered with {@code MenuScreens}. */
	private static List<Case> menuCases() {
		Map<MenuType<?>, MenuFixture> fixtures = new LinkedHashMap<>();
		int bay = Math.round(PodTuning.Cargo.DEFAULT.slots() * UpgradeTuning.DEFAULT.ratio(ComponentTrack.CARGO_BAY, ComponentTrack.CARGO_BAY.maxTier()));
		fixtures.put(OreRegistry.CARGO_MENU, new MenuFixture(id -> new OreCargoMenu(id, bay), "cargo bay of " + bay, Component.translatable("container.deepcharter.cargo")));
		Map<?, ?> constructors = menuConstructors();
		List<Case> cases = new ArrayList<>();
		for (MenuType<?> type : BuiltInRegistries.MENU) {
			Identifier id = BuiltInRegistries.MENU.getKey(type);
			if (!id.getNamespace().equals(DeepCharter.MOD_ID)) {
				continue;
			}
			MenuFixture fixture = fixtures.get(type);
			require(fixture != null, "menu " + id + " has no fixture in ScreenLayoutClientTest.menuCases(): add the menu at its largest");
			require(constructors.containsKey(type), "menu " + id + " registered no screen with MenuScreens");
			cases.add(new Case("menu " + id.getPath() + ", " + fixture.what(), () -> menuScreen(constructors.get(type), fixture.menu().apply(1), fixture.title())));
		}
		require(fixtures.size() == cases.size(), "a fixture names a menu the mod did not register: " + fixtures.keySet());
		return cases;
	}

	/** A menu at its largest, and the title the server opens it with. */
	private record MenuFixture(IntFunction<AbstractContainerMenu> menu, String what, Component title) {
	}

	/** Vanilla keeps the registered screen constructors in a private map and exposes no way to read it. */
	private static Map<?, ?> menuConstructors() {
		try {
			Field field = MenuScreens.class.getDeclaredField("SCREENS");
			field.setAccessible(true);
			return (Map<?, ?>) field.get(null);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("MenuScreens has no field SCREENS; the gate reads the registered screens from it", e);
		}
	}

	private static Screen menuScreen(Object constructor, AbstractContainerMenu menu, Component title) {
		try {
			Class<?> type = Class.forName("net.minecraft.client.gui.screens.MenuScreens$ScreenConstructor");
			Method create = type.getMethod("create", AbstractContainerMenu.class, Inventory.class, Component.class);
			create.setAccessible(true);
			Minecraft client = Minecraft.getInstance();
			return (Screen) create.invoke(constructor, menu, client.player.getInventory(), title);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("could not build a menu screen", e);
		}
	}

	/** The screens nothing registers: the handbook, opened from a key, and the UI kit's demo. */
	private static List<Case> standalone() {
		List<Case> cases = new ArrayList<>();
		cases.add(new Case("ui demo", CrtDemoScreen::new));
		List<HandbookNote> notes = new ArrayList<>();
		for (int number = 1; number <= 40; number++) {
			notes.add(new HandbookNote(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "note_" + number), number,
					Component.literal("A NOTE WITH A LONG TITLE " + number), Component.literal("A note of some length. ".repeat(40))));
		}
		cases.add(new Case("handbook", () -> {
			Minecraft client = Minecraft.getInstance();
			Map<Identifier, HandbookChapter> chapters = new LinkedHashMap<>();
			HandbookChapters.all(client.getConnection().registryAccess()).forEach(chapter -> chapters.put(chapter.key().identifier(), chapter.value()));
			return new HandbookScreen(HandbookPages.of(chapters, ClientHandbook.completed()), id -> true, id -> { }, notes);
		}, handbookVariants(notes.size())));
		return cases;
	}

	/** Every page of the handbook, then the Notes tab with its list and with a Note open. */
	private static List<Variant> handbookVariants(int notes) {
		List<Variant> variants = new ArrayList<>();
		variants.add(new Variant("every page", (context, screen) -> {
			HandbookScreen handbook = (HandbookScreen) screen;
			List<String> problems = new ArrayList<>();
			for (int page = 0; page < handbook.pages().size(); page++) {
				int turned = page;
				context.runOnClient(client -> handbook.goTo(turned));
				problems.addAll(context.computeOnClient(client -> problemsOf("handbook / page " + turned, handbook, List.of())));
			}
			return problems;
		}));
		variants.add(new Variant("notes tab", (context, screen) -> {
			context.runOnClient(client -> ((HandbookScreen) screen).showNotes());
			return List.of();
		}));
		variants.add(new Variant("a note open", (context, screen) -> {
			context.runOnClient(client -> ((HandbookScreen) screen).openNote(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "note_" + notes)));
			return List.of();
		}));
		return variants;
	}
}
