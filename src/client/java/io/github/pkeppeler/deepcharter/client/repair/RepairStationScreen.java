package io.github.pkeppeler.deepcharter.client.repair;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import io.github.pkeppeler.deepcharter.client.charter.ClientCharter;
import io.github.pkeppeler.deepcharter.client.sound.TypewriterSound;
import io.github.pkeppeler.deepcharter.client.terminal.TerminalViewScreen;
import io.github.pkeppeler.deepcharter.client.ui.CrtButton;
import io.github.pkeppeler.deepcharter.client.ui.CrtDraw;
import io.github.pkeppeler.deepcharter.client.ui.CrtScreen;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;
import io.github.pkeppeler.deepcharter.client.ui.Typewriter;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.repair.RepairRegistry;
import io.github.pkeppeler.deepcharter.repair.RepairStation;
import io.github.pkeppeler.deepcharter.repair.RepairTuning;
import io.github.pkeppeler.deepcharter.terminal.TerminalActionPayload;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.TerminalView;
import io.github.pkeppeler.deepcharter.terminal.Terminals;

/**
 * The online repair station: the hull repair buttons, then the shop, in one list under the header. The list is longer than a
 * small screen, so it scrolls: only the rows that fit are buttons, and the mouse wheel or Page Up and Page Down move the
 * list. The screen never decides anything: it sends the press and the server checks it, charges the account and answers with
 * a new {@link TerminalView}.
 */
public final class RepairStationScreen extends CrtScreen implements TerminalViewScreen {
	private static final int MARGIN = 24;
	private static final int COLUMN_WIDTH = 190;
	private static final int BUTTON_HEIGHT = 20;
	private static final int GAP = 6;
	private static final int CLOSE_WIDTH = 90;
	private static final int[] REPAIR_STEPS = {10, 25, 50};

	/** One button of the list: what it says, and the action it sends. */
	private record Row(Component label, Identifier action, CompoundTag args) {
	}

	/** One line of text beside the list, where it is drawn: in the theme's phosphor colour, or its dim colour when {@code dim}. */
	public record TextLine(String text, int x, int y, boolean dim) {
	}

	private TerminalView view;
	private int firstRow;
	/** The buttons of the rows shown, top to bottom, made by the last layout. */
	private final List<CrtButton> rowButtons = new ArrayList<>();
	private final Typewriter typewriter;

	public RepairStationScreen(TerminalView view) {
		super(Component.translatable(TerminalTypes.REPAIR_STATION.block().getDescriptionId()));
		this.view = view;
		this.typewriter = typewriter(Component.translatable("screen.deepcharter.repair.intro"), new TypewriterSound());
	}

	@Override
	public boolean accepts(TerminalView other) {
		return view.pos().equals(other.pos()) && view.type().equals(other.type());
	}

	@Override
	public void update(TerminalView newer) {
		view = newer;
		rebuildWidgets();
	}

	private static List<Row> rows() {
		List<Row> rows = new ArrayList<>();
		for (int hp : REPAIR_STEPS) {
			long cost = hp * RepairTuning.DEFAULT.repairCostPerHp();
			CompoundTag args = new CompoundTag();
			args.putInt(RepairStation.HP_KEY, hp);
			rows.add(new Row(Component.translatable("screen.deepcharter.repair.repair", hp, cost), RepairStation.REPAIR, args));
		}
		rows.add(new Row(Component.translatable("screen.deepcharter.repair.total"), RepairStation.REPAIR_TOTAL, new CompoundTag()));
		for (Consumable consumable : Consumable.values()) {
			CompoundTag args = new CompoundTag();
			args.putString(RepairStation.ITEM_KEY, consumable.itemId().toString());
			String name = new ItemStack(RepairRegistry.item(consumable)).getHoverName().getString().toUpperCase(Locale.ROOT);
			rows.add(new Row(Component.translatable("screen.deepcharter.repair.buy", name, consumable.price()), RepairStation.BUY, args));
		}
		return rows;
	}

	/** The y where the intro starts, below the header. */
	private int introTop() {
		return MARGIN + font.lineHeight + 14;
	}

	private int lineStep() {
		return font.lineHeight + CrtTuning.current().lineSpacing();
	}

	/** The intro as wrapped once it has typed out in full, one entry per line, where each is drawn. */
	public List<TextLine> introLines() {
		List<String> wrapped = font.getSplitter().splitLines(FormattedText.of(typewriter.text()), width - 2 * MARGIN, Style.EMPTY)
				.stream().map(FormattedText::getString).toList();
		List<TextLine> lines = new ArrayList<>();
		for (int i = 0; i < wrapped.size(); i++) {
			lines.add(new TextLine(wrapped.get(i), MARGIN, introTop() + i * lineStep(), false));
		}
		return lines;
	}

	/** The y below the hull line once the intro has typed out in full, so the list does not move while it types. */
	private int headerBottom() {
		int introBottom = introTop() + Math.max(introLines().size(), 1) * lineStep();
		return introBottom + 2 * (GAP + font.lineHeight);
	}

	/** The y where the first row starts. */
	public int listTop() {
		return headerBottom() + GAP;
	}

	/** CLOSE sits near the bottom edge, so that the list gets the space above it. */
	private int closeY() {
		return height - GAP - BUTTON_HEIGHT;
	}

	/** How many rows fit between the header and CLOSE, at least one. */
	public int visibleRows() {
		int space = closeY() - GAP - listTop();
		return Math.max(1, (space + GAP) / (BUTTON_HEIGHT + GAP));
	}

	/** All the rows of the list, shown or not. */
	public int rowCount() {
		return rows().size();
	}

	/** The index of the first row shown. */
	public int firstRow() {
		return firstRow;
	}

	/** Shows the list from row {@code first}, kept inside the list, and lays the buttons out again. */
	public void scrollTo(int first) {
		int clamped = Mth.clamp(first, 0, Math.max(0, rowCount() - visibleRows()));
		if (clamped != firstRow) {
			firstRow = clamped;
			rebuildWidgets();
		}
	}

	@Override
	protected void layout() {
		List<Row> rows = rows();
		int visible = visibleRows();
		firstRow = Mth.clamp(firstRow, 0, Math.max(0, rows.size() - visible));
		rowButtons.clear();
		for (int i = firstRow; i < Math.min(rows.size(), firstRow + visible); i++) {
			Row row = rows.get(i);
			CrtButton button = new CrtButton(MARGIN, listTop() + (i - firstRow) * (BUTTON_HEIGHT + GAP), COLUMN_WIDTH, BUTTON_HEIGHT, row.label(),
					pressed -> ClientPlayNetworking.send(new TerminalActionPayload(view.pos(), row.action(), row.args())));
			rowButtons.add(button);
			addRenderableWidget(button);
		}
		addRenderableWidget(new CrtButton(MARGIN, closeY(), CLOSE_WIDTH, BUTTON_HEIGHT,
				Component.translatable("screen.deepcharter.terminal.close"), button -> onClose()));
	}

	/** The account line, the hull line and, when the list is longer than the screen, which rows are shown, as drawn. */
	public List<TextLine> textLines() {
		String account = ClientCharter.view()
				.map(charter -> Component.translatable("screen.deepcharter.terminal.account", charter.balance()).getString()).orElse("");
		Component hull = nearbyPod()
				.map(pod -> Component.translatable("screen.deepcharter.repair.hull", Math.round(pod.hull()), Math.round(pod.maxHull())))
				.orElse(Component.translatable("screen.deepcharter.repair.no_pod"));
		int hullY = headerBottom() - font.lineHeight;
		List<TextLine> lines = new ArrayList<>();
		lines.add(new TextLine(account, MARGIN, hullY - GAP - font.lineHeight, false));
		lines.add(new TextLine(hull.getString().toUpperCase(Locale.ROOT), MARGIN, hullY, false));
		scrollHint().ifPresent(hint -> lines.add(new TextLine(hint, MARGIN + CLOSE_WIDTH + GAP, closeY() + (BUTTON_HEIGHT - font.lineHeight) / 2, true)));
		return lines;
	}

	/** Which rows are shown, when the list is longer than the screen. */
	private Optional<String> scrollHint() {
		int visible = visibleRows();
		if (visible >= rowCount()) {
			return Optional.empty();
		}
		return Optional.of(Component.translatable("screen.deepcharter.repair.scroll", firstRow + 1, firstRow + visible, rowCount()).getString());
	}

	/** The narrator also tells which rows are shown, so that a player who cannot see the screen knows that there are more. */
	@Override
	public Component getNarrationMessage() {
		Component narration = super.getNarrationMessage();
		return scrollHint().<Component>map(hint -> Component.literal(narration.getString() + ". " + hint)).orElse(narration);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (scrollY != 0) {
			scrollTo(firstRow - (int) Math.signum(scrollY));
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	/**
	 * A button off the screen cannot take focus, so keys scroll the list: Page Up and Page Down move it a page, and Down or Tab
	 * from the last row (Up or Shift+Tab from the first) moves it one row and focuses the row that came into view.
	 */
	@Override
	public boolean keyPressed(KeyEvent event) {
		int focused = rowButtons.indexOf(getFocused());
		boolean forward = event.isDown() || (event.isCycleFocus() && !event.hasShiftDown());
		boolean backward = event.isUp() || (event.isCycleFocus() && event.hasShiftDown());
		if (focused >= 0 && forward && focused == rowButtons.size() - 1 && firstRow + rowButtons.size() < rowCount()) {
			scrollTo(firstRow + 1);
			setFocused(rowButtons.getLast());
			return true;
		}
		if (focused == 0 && backward && firstRow > 0) {
			scrollTo(firstRow - 1);
			setFocused(rowButtons.getFirst());
			return true;
		}
		if (event.key() == InputConstants.KEY_PAGEDOWN) {
			scrollTo(firstRow + visibleRows());
			return true;
		}
		if (event.key() == InputConstants.KEY_PAGEUP) {
			scrollTo(firstRow - visibleRows());
			return true;
		}
		return super.keyPressed(event);
	}

	/** The pod the server would repair, as far as the client can tell: the nearest parked at the station. The server decides. */
	private Optional<PodEntity> nearbyPod() {
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) {
			return Optional.empty();
		}
		return Terminals.parkedPods(client.level, view.pos()).stream().findFirst();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		CrtTuning tuning = CrtTuning.current();
		CrtDraw.header(graphics, font, title.getString().toUpperCase(Locale.ROOT), MARGIN, width);
		drawTypewriter(graphics, typewriter, MARGIN, introTop(), width - 2 * MARGIN);
		for (TextLine line : textLines()) {
			CrtDraw.glowText(graphics, font, line.text(), line.x(), line.y(), line.dim() ? tuning.dimColor() : tuning.phosphorColor());
		}
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}
}
