package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;
import static io.github.pkeppeler.deepcharter.test.support.ContainerScreenFields.intField;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.charter.CharterTuning;
import io.github.pkeppeler.deepcharter.charter.terminal.ContractState;
import io.github.pkeppeler.deepcharter.charter.terminal.ContractTerminal;
import io.github.pkeppeler.deepcharter.charter.terminal.ContractTerminalTuning;
import io.github.pkeppeler.deepcharter.client.charter.terminal.ContractScreen;
import io.github.pkeppeler.deepcharter.client.handbook.ClientNotes;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookNote;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookScreen;
import io.github.pkeppeler.deepcharter.client.terminal.TerminalScreens;
import io.github.pkeppeler.deepcharter.client.ui.CrtButton;
import io.github.pkeppeler.deepcharter.client.ui.CrtDraw;
import io.github.pkeppeler.deepcharter.client.ui.CrtScreen;
import io.github.pkeppeler.deepcharter.client.ui.Typewriter;
import io.github.pkeppeler.deepcharter.client.upgrade.UpgradeScreen;
import io.github.pkeppeler.deepcharter.hangar.HangarTerminal;
import io.github.pkeppeler.deepcharter.hangar.HangarView;
import io.github.pkeppeler.deepcharter.market.ProcessorView;
import io.github.pkeppeler.deepcharter.market.WorkOrder;
import io.github.pkeppeler.deepcharter.market.WorkOrdersView;
import io.github.pkeppeler.deepcharter.ore.OreCargoMenu;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodTuning;
import io.github.pkeppeler.deepcharter.terminal.ParkedPodView;
import io.github.pkeppeler.deepcharter.terminal.TerminalFeature;
import io.github.pkeppeler.deepcharter.terminal.TerminalFeatureProbe;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.TerminalView;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks.Box;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.CrtDemoScreen;
import io.github.pkeppeler.deepcharter.test.support.DrawnText;
import io.github.pkeppeler.deepcharter.test.support.DrawnText.Drawn;
import io.github.pkeppeler.deepcharter.test.support.ShippedNotes;
import io.github.pkeppeler.deepcharter.test.support.UpgradeShown;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeTuning;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
 * <p>The text a screen draws itself ({@code graphics.text}, {@code CrtDraw.glowText}) is checked too (#411): {@link DrawnText} renders the screen
 * once into a recorder, and each string must lie inside the screen (or inside a declared scroll region) and must not overlap an interactive widget. Text
 * a widget draws on itself is the widget's, and is left out. A failure names the screen, the string and the overflow in pixels. The typewriters are
 * finished first, so the full text is drawn.
 *
 * <p>No screen needs a scroll region declared: the repair station builds buttons for the rows that fit only, so its widgets are checked as they are.
 * The handbook is paged and the work order list pages, and each page is a variant here.
 */
public class ScreenLayoutClientTest implements FabricClientGameTest {
    private static final int WIDTH = 427;
    private static final int HEIGHT = 240;
    private static final BlockPos POS = new BlockPos(0, 64, 0);
    /** A step that types out any intro in one go. */
    private static final double TYPING_SECONDS = 3600;
    /** The longest a charter or player name can be. */
    private static final int NAME_LENGTH = CharterTuning.DEFAULT.maxNameLength();

    /** One thing to check after a screen is open: a track chosen, a page turned. */
    private record Variant(String name, VariantStep step) {}

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
        features.put(TerminalTypes.FUEL_PUMP, new ParkedPodView(Optional.of(UUID.randomUUID())));
        features.put(TerminalTypes.REPAIR_STATION, new ParkedPodView(Optional.of(UUID.randomUUID())));
        List<WorkOrdersView.Entry> orders = new ArrayList<>();
        for (WorkOrder order : WorkOrder.values()) {
            orders.add(new WorkOrdersView.Entry(order, Math.max(0, order.quantity() - 1), 99));
        }
        features.put(
                TerminalTypes.ORE_PROCESSOR,
                new ProcessorView(
                        new WorkOrdersView(true, orders),
                        Arrays.stream(OreType.values())
                                .map(ore -> new ProcessorView.Hold(ore, 99999))
                                .toList()));
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
        try (TestSingleplayerContext _ = context.worldBuilder().create()) {
            List<Case> cases = new ArrayList<>();
            cases.addAll(terminalCases());
            cases.addAll(menuCases());
            cases.addAll(standalone());
            for (Case screenCase : cases) {
                check(context, screenCase, problems);
            }
            context.setScreen(() -> null);
            System.out.println("[screen-layout] checked " + cases.size() + " screens at " + WIDTH + " x " + HEIGHT
                    + ": " + cases.stream().map(Case::name).toList());
        }
        require(
                problems.isEmpty(),
                problems.size() + " layout problem(s) at the default window:\n" + String.join("\n", problems));
    }

    /** The gate must fail on a screen that overflows, or a green run says nothing. */
    private static void checkTheGateSeesAnOverflow(ClientGameTestContext context) {
        List<String> problems = new ArrayList<>();
        List<String> scrolled = new ArrayList<>();
        List<String> drawn = new ArrayList<>();
        context.runOnClient(client -> {
            Screen text = new DrawnTextFixtureScreen();
            text.init(WIDTH, HEIGHT);
            drawn.addAll(problemsOf("text fixture", text, List.of()));
            Screen screen = new OverflowFixtureScreen();
            screen.init(WIDTH, HEIGHT);
            problems.addAll(problemsOf("fixture", screen, List.of()));
            Screen scrolling = new ScrollFixtureScreen();
            scrolling.init(WIDTH, HEIGHT);
            scrolled.addAll(problemsOf(
                    "scroll fixture",
                    scrolling,
                    new Case("scroll fixture", ScrollFixtureScreen::new)
                            .scrolls(List.of(SCROLL_REGION))
                            .scrollRegions()));
        });
        require(
                drawn.size() == 2,
                "the text fixture should give 2 problems (one string leaves the screen, one under a button; the button's own label and the string inside the screen are fine), got "
                        + drawn);
        require(
                drawn.get(0).contains("text fixture")
                        && drawn.get(0).contains("text 'PAST THE EDGE'")
                        && drawn.get(0).contains("px past the right edge"),
                "a problem names the screen, the string and the overflow in pixels, got " + drawn.get(0));
        require(
                drawn.get(1).contains("text 'UNDER'") && drawn.get(1).contains("overlaps 'ON ITS OWN'"),
                "a problem names the string and the widget under it, got " + drawn.get(1));
        List<String> overloads = new ArrayList<>();
        List<String> scissored = new ArrayList<>();
        context.runOnClient(client -> {
            Screen all = new OverloadsFixtureScreen();
            all.init(WIDTH, HEIGHT);
            overloads.addAll(problemsOf("overloads fixture", all, List.of()));
            Screen scissor = new ScissorFixtureScreen();
            scissor.init(WIDTH, HEIGHT);
            DrawnText.of(scissor).forEach(text -> scissored.add(text.text()));
        });
        require(
                overloads.size() == OVERLOAD_STRINGS.size(),
                "every overload of text and centeredText is recorded: expected " + OVERLOAD_STRINGS.size()
                        + " problems, got " + overloads.size() + ": " + overloads);
        for (String string : OVERLOAD_STRINGS) {
            require(
                    overloads.stream()
                            .anyMatch(problem -> problem.contains("text '" + string + "'")
                                    && problem.contains("past the right edge")),
                    "the string '" + string + "' past the edge is reported, got " + overloads);
        }
        require(
                scissored.equals(List.of("IN")),
                "a scissor under a translate is read in screen space: only 'IN' is inside it, got " + scissored);
        require(
                problems.size() == 2,
                "the overflowing fixture should give 2 problems (one leaves the screen, one overlap), got " + problems);
        require(
                problems.get(0).contains("fixture")
                        && problems.get(0).contains("'WIDE'")
                        && problems.get(0).contains("10 px past the right edge"),
                "a problem names the screen, the widget and the overflow in pixels, got " + problems.get(0));
        require(
                problems.get(1).contains("'WIDE' overlaps 'UNDER' by 20 x 10 px"),
                "an overlap names both widgets and the pixels, got " + problems.get(1));
        require(
                scrolled.size() == 1
                        && scrolled.getFirst().contains("'OUT'")
                        && scrolled.getFirst().contains("20 px past the right edge"),
                "past the edge inside the scroll region passes, outside it fails: expected one problem, about 'OUT', got "
                        + scrolled);
    }

    /** Text 20 pixels past the right edge, text on a button of its own, and a string inside the screen: only the first two are problems, and a button's own label is not. */
    private static final class DrawnTextFixtureScreen extends Screen {
        DrawnTextFixtureScreen() {
            super(Component.literal("text fixture"));
        }

        @Override
        protected void init() {
            addRenderableWidget(new CrtButton(100, 100, 80, 20, Component.literal("ON ITS OWN"), button -> {}));
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            graphics.text(font, "FINE", 10, 10, 0xFFFFFFFF);
            CrtDraw.glowText(graphics, font, "PAST THE EDGE", WIDTH - 20, 40, 0xFFFFFFFF);
            graphics.text(font, "UNDER", 110, 105, 0xFFFFFFFF);
            super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        }
    }

    /** The strings {@link OverloadsFixtureScreen} draws, one for each overload of {@code text} and {@code centeredText} the recorder overrides. */
    private static final List<String> OVERLOAD_STRINGS = List.of("S5", "S6", "F5", "F6", "C5", "C6", "K1", "K2", "K3");

    /**
     * One string past the right edge through every overload the recorder overrides. If an update routes one of them around the recorder, the
     * gate goes blind to it, and the count of problems drops below {@link #OVERLOAD_STRINGS}.
     */
    private static final class OverloadsFixtureScreen extends Screen {
        OverloadsFixtureScreen() {
            super(Component.literal("overloads fixture"));
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            int x = WIDTH - 5;
            graphics.text(font, "S5", x, 10, 0xFFFFFFFF);
            graphics.text(font, "S6", x, 30, 0xFFFFFFFF, false);
            graphics.text(font, Component.literal("F5").getVisualOrderText(), x, 50, 0xFFFFFFFF);
            graphics.text(font, Component.literal("F6").getVisualOrderText(), x, 70, 0xFFFFFFFF, false);
            graphics.text(font, Component.literal("C5"), x, 90, 0xFFFFFFFF);
            graphics.text(font, Component.literal("C6"), x, 110, 0xFFFFFFFF, false);
            graphics.centeredText(font, "K1", WIDTH, 130, 0xFFFFFFFF);
            graphics.centeredText(font, Component.literal("K2"), WIDTH, 150, 0xFFFFFFFF);
            graphics.centeredText(font, Component.literal("K3").getVisualOrderText(), WIDTH, 170, 0xFFFFFFFF);
        }
    }

    /**
     * A scissor drawn under a translation: vanilla pushes it transformed by the pose, so it covers x 100 to 140 on the screen. "IN" is drawn at
     * local x 0 (screen x 100, inside it) and stays; "OUT" at local x 100 (screen x 200, outside it) is clipped away. A scissor kept raw (0 to 40)
     * would drop "IN" and keep nothing.
     */
    private static final class ScissorFixtureScreen extends Screen {
        ScissorFixtureScreen() {
            super(Component.literal("scissor fixture"));
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            graphics.pose().pushMatrix();
            graphics.pose().translate(100f, 0f);
            graphics.enableScissor(0, 50, 40, 90);
            graphics.text(font, "IN", 0, 60, 0xFFFFFFFF);
            graphics.text(font, "OUT", 100, 60, 0xFFFFFFFF);
            graphics.disableScissor();
            graphics.pose().popMatrix();
        }
    }

    /** A button 10 pixels past the right edge, and two that overlap by 20 x 10 pixels. */
    private static final class OverflowFixtureScreen extends Screen {
        OverflowFixtureScreen() {
            super(Component.literal("fixture"));
        }

        @Override
        protected void init() {
            addRenderableWidget(Button.builder(Component.literal("WIDE"), button -> {})
                    .bounds(WIDTH - 90, 10, 100, 20)
                    .build());
            addRenderableWidget(Button.builder(Component.literal("UNDER"), button -> {})
                    .bounds(WIDTH - 110, 20, 40, 20)
                    .build());
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
            addRenderableWidget(Button.builder(Component.literal("IN"), button -> {})
                    .bounds(WIDTH - 20, 110, 40, 20)
                    .build());
            addRenderableWidget(Button.builder(Component.literal("OUT"), button -> {})
                    .bounds(WIDTH - 20, 200, 40, 20)
                    .build());
        }
    }

    private static void check(ClientGameTestContext context, Case screenCase, List<String> problems) {
        Screen screen = context.computeOnClient(client -> screenCase.screen().get());
        context.setScreen(() -> screen);
        ClientWait.until(context, "the " + screenCase.name() + " screen", client -> client.gui.screen() == screen);
        problems.addAll(context.computeOnClient(client -> sizeProblem(screenCase.name(), screen)));
        problems.addAll(
                context.computeOnClient(client -> problemsOf(screenCase.name(), screen, screenCase.scrollRegions())));
        System.out.println("[screen-layout] " + screenCase.name() + ": "
                + screen.getClass().getSimpleName() + ", "
                + context.computeOnClient(
                        client -> ClientChecks.visibleWidgets(screen).size()) + " widgets");
        for (Variant variant : screenCase.variants()) {
            problems.addAll(variant.step().apply(context, screen));
            String name = screenCase.name() + " / " + variant.name();
            problems.addAll(context.computeOnClient(client -> problemsOf(name, screen, screenCase.scrollRegions())));
        }
    }

    private static List<String> sizeProblem(String name, Screen screen) {
        return screen.width == WIDTH && screen.height == HEIGHT
                ? List.of()
                : List.of(name + ": the test window is 854 x 480 at GUI scale 2, a screen of " + WIDTH + " x " + HEIGHT
                        + ", got " + screen.width + " x " + screen.height);
    }

    /**
     * Every way the screen's widgets (and, for a container screen, its panel and slots) leave the window or overlap. A widget inside one of
     * {@code scrollRegions} may reach past the screen edge. Client thread.
     */
    private static List<String> problemsOf(String name, Screen screen, List<Box> scrollRegions) {
        List<String> problems = new ArrayList<>();
        for (AbstractWidget widget : ClientChecks.visibleWidgets(screen)) {
            if (scrollRegions.stream().noneMatch(region -> contains(region, Box.of(widget)))) {
                ClientChecks.boxLeavesScreen(screen, ClientChecks.describe(widget), Box.of(widget))
                        .ifPresent(problem -> problems.add(name + ": " + problem));
            }
            if (widget instanceof Button button) {
                ClientChecks.labelClipped(button).ifPresent(problem -> problems.add(name + ": " + problem));
            }
        }
        List<AbstractWidget> interactive = ClientChecks.interactiveWidgets(screen);
        for (int i = 0; i < interactive.size(); i++) {
            ClientChecks.widgetOverlaps(interactive.get(i), interactive.subList(i + 1, interactive.size()))
                    .ifPresent(problem -> problems.add(name + ": " + problem));
        }
        finishTyping(screen);
        for (Drawn drawn : DrawnText.of(screen)) {
            if (scrollRegions.stream().noneMatch(region -> contains(region, drawn.box()))) {
                DrawnText.leavesScreen(screen, drawn).ifPresent(problem -> problems.add(name + ": " + problem));
            }
            DrawnText.underWidget(drawn, interactive).ifPresent(problem -> problems.add(name + ": " + problem));
        }
        if (screen instanceof AbstractContainerScreen<?> container) {
            int left = intField(container, "leftPos");
            int top = intField(container, "topPos");
            ClientChecks.boxLeavesScreen(
                            screen,
                            "the panel",
                            new Box(left, top, intField(container, "imageWidth"), intField(container, "imageHeight")))
                    .ifPresent(problem -> problems.add(name + ": " + problem));
            for (Slot slot : container.getMenu().slots) {
                ClientChecks.boxLeavesScreen(screen, "slot " + slot.index, new Box(left + slot.x, top + slot.y, 16, 16))
                        .ifPresent(problem -> problems.add(name + ": " + problem));
            }
        }
        return problems;
    }

    /** A typewriter draws the text it has revealed, so the layout is checked with all of it revealed: a step long enough to type any intro. The screen keeps its typewriters in a private map. */
    private static void finishTyping(Screen screen) {
        if (!(screen instanceof CrtScreen)) {
            return;
        }
        try {
            Field field = CrtScreen.class.getDeclaredField("typewriters");
            field.setAccessible(true);
            ((Map<?, ?>) field.get(screen)).keySet().forEach(writer -> ((Typewriter) writer).advance(TYPING_SECONDS));
        } catch (ReflectiveOperationException e) {
            throw new LinkageError("CrtScreen has no field typewriters; the gate reads it by name", e);
        }
    }

    private static boolean contains(Box region, Box box) {
        return box.x() >= region.x()
                && box.y() >= region.y()
                && box.right() <= region.right()
                && box.bottom() <= region.bottom();
    }

    private static List<Case> terminalCases() {
        Map<TerminalType, TerminalFeature> features = features();
        List<Case> cases = new ArrayList<>();
        for (TerminalType type : TerminalTypes.all()) {
            if (!type.id().getNamespace().equals(DeepCharter.MOD_ID)) {
                continue; // the gametest mod registers terminals of its own to test the framework
            }
            boolean registers = TerminalFeatureProbe.registersFeature(type);
            require(
                    registers == features.containsKey(type),
                    "terminal " + type.id()
                            + (registers
                                    ? " adds a view feature but has no fixture in ScreenLayoutClientTest.features()"
                                    : " has a fixture there but registers no feature"));
            List<TerminalView.PartStatus> missing = type.parts().stream()
                    .map(part -> new TerminalView.PartStatus(BuiltInRegistries.ITEM.getKey(part), false))
                    .toList();
            List<TerminalView.PartStatus> inserted = type.parts().stream()
                    .map(part -> new TerminalView.PartStatus(BuiltInRegistries.ITEM.getKey(part), true))
                    .toList();
            String id = type.id().getPath();
            if (type.needsRepair()) {
                TerminalView locked = new TerminalView(POS, type.id(), false, false, missing, Optional.empty());
                TerminalView open = new TerminalView(POS, type.id(), false, true, missing, Optional.empty());
                if (type.prerequisite().isPresent()) {
                    cases.add(new Case(id + " offline, locked", () -> TerminalScreens.create(locked)));
                }
                cases.add(new Case(id + " offline, parts missing", () -> TerminalScreens.create(open)));
            }
            TerminalView online = new TerminalView(
                    POS,
                    type.id(),
                    true,
                    true,
                    type.needsRepair() ? inserted : List.of(),
                    Optional.ofNullable(features.get(type)));
            List<Variant> variants = List.of();
            if (type == ContractTerminal.TYPE) {
                // The server's state arrives after the screen is open, so each role is a variant of the open screen.
                variants = contractStates().stream()
                        .<Variant>map(state -> new Variant("role " + state.role(), (context, screen) -> {
                            context.runOnClient(client -> ((ContractScreen) screen).show(state));
                            return List.of();
                        }))
                        .toList();
            } else if (type == TerminalTypes.UPGRADE_TERMINAL) {
                variants = upgradeTrackVariants();
            } else if (type == TerminalTypes.ORE_PROCESSOR) {
                // WITHDRAW ORE swaps the sheet for the list of ores, here all seven at the largest count.
                variants = List.of(new Variant("withdraw list", (context, screen) -> {
                    context.clickScreenButton("WITHDRAW ORE");
                    return List.of();
                }));
            }
            cases.add(new Case(id + " online", () -> TerminalScreens.create(online), variants));
        }
        return cases;
    }

    /** The contract terminal at each role, with the longest names and as many rows as it lists. */
    private static List<ContractState> contractStates() {
        String name = "W".repeat(NAME_LENGTH);
        List<String> names = Collections.nCopies(ContractTerminalTuning.DEFAULT.listedRows(), name);
        int total = names.size() + 5;
        List<String> revivable = Collections.nCopies(ContractTerminalTuning.DEFAULT.revivableRows(), name);
        return List.of(
                new ContractState(
                        ContractState.Role.NONE,
                        "",
                        names,
                        total,
                        revivable,
                        revivable.size() + 2,
                        List.of(),
                        0,
                        false,
                        Optional.of("screen.deepcharter.contract.refused")),
                new ContractState(
                        ContractState.Role.APPLICANT,
                        name,
                        List.of(),
                        0,
                        List.of(),
                        0,
                        List.of(),
                        0,
                        false,
                        Optional.empty()),
                new ContractState(
                        ContractState.Role.CREW,
                        name,
                        List.of(),
                        0,
                        List.of(),
                        0,
                        List.of(),
                        0,
                        false,
                        Optional.empty()),
                new ContractState(
                        ContractState.Role.DIRECTOR,
                        name,
                        List.of(),
                        0,
                        List.of(),
                        0,
                        names,
                        total,
                        false,
                        Optional.empty()));
    }

    /** Chooses each track in turn: the right column lists that track's tiers, so its length changes. */
    private static List<Variant> upgradeTrackVariants() {
        List<Variant> variants = new ArrayList<>();
        for (ComponentTrack track : ComponentTrack.values()) {
            variants.add(new Variant("track " + track.id() + " chosen", (context, screen) -> {
                // The screen offers no way to choose a track but its button, whose label is this lang key with the
                // track's name and installed tier.
                String label = context.computeOnClient(client -> Component.translatable(
                                "screen.deepcharter.upgrade.track",
                                Component.translatable("deepcharter.upgrade.track." + track.id())
                                        .getString(),
                                track.maxTier())
                        .getString());
                UpgradeScreen upgrade = (UpgradeScreen) screen;
                context.runOnClient(client -> UpgradeShown.reveal(upgrade, track));
                context.clickScreenButton(label);
                ClientWait.until(context, "the track " + track.id(), client -> UpgradeShown.isChosen(upgrade, track));
                return List.of();
            }));
        }
        return variants;
    }

    /** One screen for each menu type the mod registered, built with the screen constructor it registered with {@code MenuScreens}. */
    private static List<Case> menuCases() {
        Map<MenuType<?>, MenuFixture> fixtures = new LinkedHashMap<>();
        int bay = Math.round(PodTuning.Cargo.DEFAULT.slots()
                * UpgradeTuning.DEFAULT.ratio(ComponentTrack.CARGO_BAY, ComponentTrack.CARGO_BAY.maxTier()));
        fixtures.put(
                OreRegistry.CARGO_MENU,
                new MenuFixture(
                        id -> new OreCargoMenu(id, bay),
                        "cargo bay of " + bay,
                        Component.translatable("container.deepcharter.cargo")));
        Map<?, ?> constructors = menuConstructors();
        List<Case> cases = new ArrayList<>();
        for (MenuType<?> type : BuiltInRegistries.MENU) {
            Identifier id =
                    Objects.requireNonNull(BuiltInRegistries.MENU.getKey(type), "a registered menu type has a key");
            if (!id.getNamespace().equals(DeepCharter.MOD_ID)) {
                continue;
            }
            MenuFixture fixture = Objects.requireNonNull(
                    fixtures.get(type),
                    () -> "menu " + id
                            + " has no fixture in ScreenLayoutClientTest.menuCases(): add the menu at its largest");
            require(constructors.containsKey(type), "menu " + id + " registered no screen with MenuScreens");
            cases.add(new Case(
                    "menu " + id.getPath() + ", " + fixture.what(),
                    () -> menuScreen(
                            Objects.requireNonNull(constructors.get(type), "constructors.get(type) is absent"),
                            fixture.menu().apply(1),
                            fixture.title())));
        }
        require(
                fixtures.size() == cases.size(),
                "a fixture names a menu the mod did not register: " + fixtures.keySet());
        return cases;
    }

    /** A menu at its largest, and the title the server opens it with. */
    private record MenuFixture(IntFunction<AbstractContainerMenu> menu, String what, Component title) {}

    /** Vanilla keeps the registered screen constructors in a private map and exposes no way to read it. */
    private static Map<?, ?> menuConstructors() {
        try {
            Field field = MenuScreens.class.getDeclaredField("SCREENS");
            field.setAccessible(true);
            return (Map<?, ?>) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new LinkageError(
                    "MenuScreens has no field SCREENS; the gate reads the registered screens from it", e);
        }
    }

    private static Screen menuScreen(Object constructor, AbstractContainerMenu menu, Component title) {
        try {
            Class<?> type = Class.forName("net.minecraft.client.gui.screens.MenuScreens$ScreenConstructor");
            Method create = type.getMethod("create", AbstractContainerMenu.class, Inventory.class, Component.class);
            create.setAccessible(true);
            Minecraft client = Minecraft.getInstance();
            return (Screen) create.invoke(
                    constructor, menu, ClientSession.player(client).getInventory(), title);
        } catch (ReflectiveOperationException e) {
            throw new LinkageError("could not build a menu screen", e);
        }
    }

    /** The screens nothing registers: the handbook, opened from a key, and the UI kit's demo. */
    private static List<Case> standalone() {
        List<Case> cases = new ArrayList<>();
        cases.add(new Case("ui demo", CrtDemoScreen::new));
        // The notes the game ships, all found: the longest real one is what the page must hold.
        List<HandbookNote> notes =
                ShippedNotes.ids().stream().map(ClientNotes::entry).toList();
        cases.add(new Case(
                "handbook",
                () -> {
                    return new HandbookScreen(
                            HandbookScreen.currentPages(Minecraft.getInstance()), id -> true, id -> {}, notes);
                },
                handbookVariants(notes)));
        return cases;
    }

    /** Every page of the handbook, then the Notes tab with its list and with a Note open. */
    private static List<Variant> handbookVariants(List<HandbookNote> notes) {
        List<Variant> variants = new ArrayList<>();
        variants.add(new Variant("every page", (context, screen) -> {
            HandbookScreen handbook = (HandbookScreen) screen;
            List<String> problems = new ArrayList<>();
            int pages = context.computeOnClient(
                    client -> HandbookScreen.currentPages(client).size());
            for (int page = 0; page < pages; page++) {
                int turned = page;
                context.runOnClient(client -> handbook.goTo(turned));
                problems.addAll(context.computeOnClient(
                        client -> problemsOf("handbook / page " + turned, handbook, List.of())));
            }
            return problems;
        }));
        variants.add(new Variant("notes tab", (context, screen) -> {
            context.runOnClient(client -> ((HandbookScreen) screen).showNotes());
            return List.of();
        }));
        variants.add(new Variant("every note open", (context, screen) -> {
            HandbookScreen handbook = (HandbookScreen) screen;
            List<String> problems = new ArrayList<>();
            for (HandbookNote note : notes) {
                context.runOnClient(client -> handbook.openNote(note.id()));
                for (int sheet = 0; sheet < context.computeOnClient(client -> handbook.noteSheets()); sheet++) {
                    int turned = sheet;
                    context.runOnClient(client -> handbook.turnNote(turned));
                    problems.addAll(context.computeOnClient(client -> problemsOf(
                            "handbook / note " + note.number() + ", sheet " + (turned + 1), handbook, List.of())));
                }
            }
            return problems;
        }));
        return variants;
    }
}
