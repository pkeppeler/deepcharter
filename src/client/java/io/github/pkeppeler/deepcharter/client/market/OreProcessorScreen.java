package io.github.pkeppeler.deepcharter.client.market;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.client.charter.ClientCharter;
import io.github.pkeppeler.deepcharter.client.sound.TypewriterSound;
import io.github.pkeppeler.deepcharter.client.theme.PanelLook;
import io.github.pkeppeler.deepcharter.client.ui.CrtButton;
import io.github.pkeppeler.deepcharter.client.ui.CrtDraw;
import io.github.pkeppeler.deepcharter.client.ui.CrtScreen;
import io.github.pkeppeler.deepcharter.client.ui.CrtText;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;
import io.github.pkeppeler.deepcharter.client.ui.ScrollList;
import io.github.pkeppeler.deepcharter.client.ui.Typewriter;
import io.github.pkeppeler.deepcharter.client.terminal.TerminalViewScreen;
import io.github.pkeppeler.deepcharter.market.OreProcessor;
import io.github.pkeppeler.deepcharter.market.ProcessorView;
import io.github.pkeppeler.deepcharter.market.WorkOrder;
import io.github.pkeppeler.deepcharter.market.WorkOrders;
import io.github.pkeppeler.deepcharter.market.WorkOrdersView;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.terminal.TerminalActionPayload;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.TerminalView;

/**
 * The online screen of the ore processor: the price of each ore, the charter's account, the two "sell all" buttons and, at the foot of the price column, the two buttons that use a pod's spoil: slag brick and salvage crates. A button
 * only asks the server; the server decides, and the account shown is the one it last synced, so it changes when a sale goes through.
 *
 * <p>WITHDRAW ORE swaps the sheet for a list of the ores the parked pods hold, each with buttons that move 1, 10 or all of it into the pack (#536). BACK returns to the sheet.
 */
public final class OreProcessorScreen extends CrtScreen implements TerminalViewScreen {
	private static final int MARGIN = 24;
	private static final int BUTTON_WIDTH = 200;
	private static final int BUTTON_HEIGHT = 20;
	private static final int GAP = 6;
	private static final int CLOSE_WIDTH = 90;
	/** The width of each of the three amount buttons of an ore row, which sit at the right edge with the ore's name and count to their left. */
	private static final int AMOUNT_WIDTH = 56;
	private static final int PAGER_WIDTH = 24;
	/** The space kept between the order rows and what is above and below them. */
	private static final int ORDER_GAP = 3;
	private static final int ORDER_ROW_GAP = 2;
	private static final int ORDER_ROW_PITCH = OrderRowButton.HEIGHT + ORDER_ROW_GAP;

	private TerminalView view;
	private final Typewriter typewriter;
	/** The order rows of the page on screen, remade by {@link #layout()}. */
	private final List<OrderRowButton> orderRows = new ArrayList<>();
	private int page;
	private int pages = 1;
	/** True while the sheet is the list of ores to withdraw. */
	private boolean withdrawing;
	/** The ores listed by the last layout of the withdraw list and the y of each, for the text beside its buttons. */
	private final List<ProcessorView.Hold> listedHolds = new ArrayList<>();
	private final List<Integer> holdRows = new ArrayList<>();
	private final ScrollList holds = new ScrollList("screen.deepcharter.processor.withdraw_scroll", () -> cargo().size(), this::holdsPerPage, this::rebuildWidgets);

	public OreProcessorScreen(TerminalView view) {
		super(Component.translatable(TerminalTypes.ORE_PROCESSOR.block().getDescriptionId()));
		this.view = view;
		this.typewriter = typewriter(Component.translatable("screen.deepcharter.processor.intro"), new TypewriterSound());
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

	public TerminalView view() {
		return view;
	}

	@Override
	public Typewriter typewriter() {
		return typewriter;
	}

	/** The text of the account line, empty when the player is on no charter. */
	public static String accountLine() {
		return ClientCharter.view().map(charter -> Component.translatable("screen.deepcharter.processor.account", charter.balance()).getString())
				.orElse("");
	}

	@Override
	protected void layout() {
		orderRows.clear();
		if (withdrawing) {
			layoutWithdrawal();
			return;
		}
		int left = contentLeft(MARGIN);
		int closeY = contentBottom(MARGIN) - BUTTON_HEIGHT;
		addRenderableWidget(new CrtButton(left, closeY, CLOSE_WIDTH, BUTTON_HEIGHT,
				Component.translatable("screen.deepcharter.processor.close"), button -> onClose()));
		int inventoryY = closeY - GAP - BUTTON_HEIGHT;
		addRenderableWidget(new CrtButton(left, inventoryY, BUTTON_WIDTH, BUTTON_HEIGHT,
				Component.translatable("screen.deepcharter.processor.sell_inventory"), button -> sell(OreProcessor.SELL_INVENTORY)));
		int cargoY = inventoryY - GAP - BUTTON_HEIGHT;
		addRenderableWidget(new CrtButton(left, cargoY, BUTTON_WIDTH, BUTTON_HEIGHT,
				Component.translatable("screen.deepcharter.processor.sell_cargo"), button -> sell(OreProcessor.SELL_CARGO)));
		// Not beside the cargo button: the prices run down the right column to there and past it, and a button on that row hides the last of them.
		addRenderableWidget(new CrtButton(rightColumn(), closeY, Math.min(BUTTON_WIDTH, contentRight(MARGIN) - rightColumn()), BUTTON_HEIGHT,
				Component.translatable("screen.deepcharter.processor.fuse"), button -> sell(OreProcessor.FUSE_SPOIL)));
		// On the close button's row, between the two: the right column above it holds the prices, the heading and the pager.
		int pressLeft = left + CLOSE_WIDTH + GAP;
		addRenderableWidget(new CrtButton(pressLeft, closeY, rightColumn() - GAP - pressLeft, BUTTON_HEIGHT,
				Component.translatable("screen.deepcharter.processor.press"), button -> sell(OreProcessor.PRESS_CRATES)));
		int withdrawY = cargoY - GAP - BUTTON_HEIGHT;
		addRenderableWidget(new CrtButton(left, withdrawY, BUTTON_WIDTH, BUTTON_HEIGHT,
				Component.translatable("screen.deepcharter.processor.withdraw"), button -> startWithdrawing()));
		layoutOrders(withdrawY);
	}

	private void startWithdrawing() {
		withdrawing = true;
		rebuildWidgets();
	}

	private void stopWithdrawing() {
		withdrawing = false;
		rebuildWidgets();
	}

	/** The ore in the cargo of the pods parked here, as the server last sent it. */
	public List<ProcessorView.Hold> cargo() {
		return view.feature(ProcessorView.class).map(ProcessorView::cargo).orElse(List.of());
	}

	/** True while the sheet is the list of ores to withdraw. */
	public boolean withdrawing() {
		return withdrawing;
	}

	/** How many ore rows fit between the heading and the back button. */
	private int holdsPerPage() {
		return Math.max(1, (contentBottom(MARGIN) - BUTTON_HEIGHT - GAP - holdsTop()) / (BUTTON_HEIGHT + GAP));
	}

	/** The y of the first ore row, under the heading and the one line that says what the list does. */
	private int holdsTop() {
		return contentTop(MARGIN) + font.lineHeight + 14 + 2 * (font.lineHeight + CrtTuning.current().lineSpacing()) + GAP;
	}

	/** The withdraw list: a row for each ore the parked pods hold, with the three amounts, and BACK. */
	private void layoutWithdrawal() {
		int left = contentLeft(MARGIN);
		int backY = contentBottom(MARGIN) - BUTTON_HEIGHT;
		addRenderableWidget(new CrtButton(left, backY, CLOSE_WIDTH, BUTTON_HEIGHT,
				Component.translatable("screen.deepcharter.processor.back"), button -> stopWithdrawing()));
		listedHolds.clear();
		holdRows.clear();
		holds.beginLayout();
		List<ProcessorView.Hold> all = cargo();
		int shown = Math.min(holdsPerPage(), all.size() - holds.first());
		int amountWidth = AMOUNT_WIDTH;
		for (int row = 0; row < shown; row++) {
			ProcessorView.Hold hold = all.get(holds.first() + row);
			int y = holdsTop() + row * (BUTTON_HEIGHT + GAP);
			listedHolds.add(hold);
			holdRows.add(y);
			int x = contentRight(MARGIN) - 3 * AMOUNT_WIDTH - 2 * GAP;
			addRenderableWidget(holds.shown(new CrtButton(x, y, amountWidth, BUTTON_HEIGHT,
					Component.translatable("screen.deepcharter.processor.withdraw_one"), button -> withdraw(hold.ore(), 1))));
			addRenderableWidget(new CrtButton(x + amountWidth + GAP, y, amountWidth, BUTTON_HEIGHT,
					Component.translatable("screen.deepcharter.processor.withdraw_ten"), button -> withdraw(hold.ore(), 10)));
			addRenderableWidget(new CrtButton(x + 2 * (amountWidth + GAP), y, amountWidth, BUTTON_HEIGHT,
					Component.translatable("screen.deepcharter.processor.withdraw_all"), button -> withdraw(hold.ore(), hold.count())));
		}
	}

	private void withdraw(OreType ore, int count) {
		CompoundTag args = new CompoundTag();
		args.putString(OreProcessor.ORE_KEY, ore.itemId().getPath());
		args.putInt(OreProcessor.COUNT_KEY, count);
		ClientPlayNetworking.send(new TerminalActionPayload(view.pos(), OreProcessor.WITHDRAW_ORE, args));
	}

	private static String holdLine(ProcessorView.Hold hold) {
		return Component.translatable("screen.deepcharter.processor.withdraw_row",
				Component.translatable(OreRegistry.item(hold.ore()).getDescriptionId()).getString().toUpperCase(Locale.ROOT), hold.count()).getString();
	}

	/** The text of the withdraw list: what it does, then the ore and its count in the cargo of each row shown, or the line that says there is none. */
	public List<String> withdrawLines() {
		List<String> lines = new ArrayList<>();
		lines.add(Component.translatable("screen.deepcharter.processor.withdraw_heading").getString());
		if (cargo().isEmpty()) {
			lines.add(Component.translatable("screen.deepcharter.processor.withdraw_none").getString());
		}
		listedHolds.forEach(hold -> lines.add(holdLine(hold)));
		return lines;
	}

	@Override
	public Component getNarrationMessage() {
		return withdrawing ? holds.narrate(super.getNarrationMessage()) : super.getNarrationMessage();
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		return (withdrawing && holds.mouseScrolled(scrollY)) || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		return (withdrawing && holds.keyPressed(event, this)) || super.keyPressed(event);
	}

	/**
	 * The order rows sit above the cargo button, in the room between it and the account line, so their number is bounded by the
	 * screen: as many rows as fit, at least one. The orders beyond a page are reached with the pager, beside the heading, under the prices.
	 */
	private void layoutOrders(int cargoY) {
		List<WorkOrdersView.Entry> listed = listedOrders();
		int perPage = ordersPerPage(cargoY);
		pages = Math.max(1, (listed.size() + perPage - 1) / perPage);
		page = Math.clamp(page, 0, pages - 1);
		int first = page * perPage;
		int rowY = cargoY - ORDER_GAP - perPage * ORDER_ROW_PITCH + ORDER_ROW_GAP;
		for (WorkOrdersView.Entry entry : listed.subList(first, Math.min(listed.size(), first + perPage))) {
			Component deliver = Component.translatable("screen.deepcharter.processor.deliver", entry.order().oreName().getString().toUpperCase(Locale.ROOT));
			OrderRowButton row = new OrderRowButton(contentLeft(MARGIN), rowY, BUTTON_WIDTH, deliver,
					Component.translatable(entry.order().titleKey()).getString().toUpperCase(Locale.ROOT), progressLine(entry), !entry.done(),
					button -> deliver(entry.order()));
			orderRows.add(row);
			addRenderableWidget(row);
			rowY += ORDER_ROW_PITCH;
		}
		if (pages > 1) {
			// On the heading's row, after its text, so that the pager does not sit in the fuse button's row.
			int pagerY = headingTop() + (font.lineHeight - BUTTON_HEIGHT) / 2;
			int pagerX = Math.min(rightColumn() + CrtText.width(font, orderHeading()) + GAP * 2, contentRight(MARGIN) - 2 * PAGER_WIDTH - GAP);
			CrtButton previous = addRenderableWidget(new CrtButton(pagerX, pagerY, PAGER_WIDTH, BUTTON_HEIGHT,
					Component.literal("<"), button -> turnPage(-1)));
			previous.active = page > 0;
			CrtButton next = addRenderableWidget(new CrtButton(pagerX + PAGER_WIDTH + GAP, pagerY, PAGER_WIDTH, BUTTON_HEIGHT,
					Component.literal(">"), button -> turnPage(1)));
			next.active = page < pages - 1;
		}
	}

	private void turnPage(int by) {
		page += by;
		rebuildWidgets();
	}

	/** How many order rows fit between the account line and the cargo button. */
	private int ordersPerPage(int cargoY) {
		int room = cargoY - ORDER_GAP - (accountTop() + font.lineHeight + ORDER_GAP);
		return Math.max(1, (room + ORDER_ROW_GAP) / ORDER_ROW_PITCH);
	}

	/** The y of the account line, which is below the intro in full. Fixed, so that the rows do not move while the intro types. */
	private int accountTop() {
		int introLines = CrtText.wrap(font, typewriter.text(), contentWidth(MARGIN)).size();
		return contentTop(MARGIN) + font.lineHeight + 14 + introLines * (font.lineHeight + CrtTuning.current().lineSpacing()) + GAP * 2;
	}

	private int rightColumn() {
		return contentLeft(MARGIN) + BUTTON_WIDTH + 2 * GAP;
	}

	private int pricesBottom() {
		return accountTop() + OreType.values().length * (font.lineHeight + 2);
	}

	/** The y of the work order heading, under the prices. */
	private int headingTop() {
		return pricesBottom() + GAP * 2;
	}

	private Optional<WorkOrdersView> orders() {
		return view.feature(ProcessorView.class).map(ProcessorView::orders);
	}

	/** Every order the charter is offered, done or not: a done order keeps its (inactive) row. */
	private List<WorkOrdersView.Entry> listedOrders() {
		return orders().map(WorkOrdersView::orders).orElse(List.of());
	}

	/** The second line of an order's row. */
	private static String progressLine(WorkOrdersView.Entry entry) {
		if (entry.done()) {
			return Component.translatable("screen.deepcharter.processor.order_done").getString();
		}
		String ore = entry.order().oreName().getString().toUpperCase(Locale.ROOT);
		return entry.rounds() == 0 ? Component.translatable("screen.deepcharter.processor.order", entry.delivered(), entry.order().quantity(), ore).getString()
				: Component.translatable("screen.deepcharter.processor.order_repeat", entry.delivered(), entry.order().quantity(), ore, entry.rounds()).getString();
	}

	/** The heading of the work order block, with the page when the orders take more than one. */
	public String orderHeading() {
		return pages > 1 ? Component.translatable("screen.deepcharter.processor.orders_page", page + 1, pages).getString()
				: Component.translatable("screen.deepcharter.processor.orders").getString();
	}

	private void sell(Identifier action) {
		ClientPlayNetworking.send(new TerminalActionPayload(view.pos(), action, new CompoundTag()));
	}

	private void deliver(WorkOrder order) {
		CompoundTag args = new CompoundTag();
		args.putString(WorkOrders.ORDER_KEY, order.id().toString());
		ClientPlayNetworking.send(new TerminalActionPayload(view.pos(), WorkOrders.DELIVER, args));
	}

	/** The lines of the work order block: its heading, then a title line and a progress line for each row on this page, or one line saying they are unavailable. */
	public List<String> orderLines() {
		Optional<WorkOrdersView> orders = orders();
		if (orders.isEmpty()) {
			return List.of();
		}
		if (!orders.get().readable()) {
			return List.of(Component.translatable("screen.deepcharter.processor.orders_unreadable").getString());
		}
		List<String> lines = new ArrayList<>();
		lines.add(orderHeading());
		for (OrderRowButton row : orderRows) {
			lines.add(row.title());
			lines.add(row.progress());
		}
		return lines;
	}

	/** The one line that says what the list does, and the ore of each row at the left of its buttons. */
	private void extractWithdrawal(GuiGraphicsExtractor graphics, CrtTuning tuning) {
		int y = contentTop(MARGIN) + font.lineHeight + 14;
		for (String line : CrtText.wrap(font, Component.translatable("screen.deepcharter.processor.withdraw_heading").getString(), contentWidth(MARGIN))) {
			CrtDraw.glowText(graphics, font, line, contentLeft(MARGIN), y, tuning.phosphorColor());
			y += font.lineHeight + tuning.lineSpacing();
		}
		if (cargo().isEmpty()) {
			CrtDraw.glowText(graphics, font, Component.translatable("screen.deepcharter.processor.withdraw_none").getString(), contentLeft(MARGIN), holdsTop(),
					PanelLook.dimText(tuning.dimColor()));
		}
		for (int row = 0; row < listedHolds.size(); row++) {
			CrtDraw.glowText(graphics, font, holdLine(listedHolds.get(row)), contentLeft(MARGIN),
					holdRows.get(row) + (BUTTON_HEIGHT - font.lineHeight) / 2, tuning.phosphorColor());
		}
		holds.hint().ifPresent(hint -> CrtDraw.glowText(graphics, font, hint, contentLeft(MARGIN) + CLOSE_WIDTH + GAP,
				contentBottom(MARGIN) - BUTTON_HEIGHT + (BUTTON_HEIGHT - font.lineHeight) / 2, tuning.phosphorColor()));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		CrtTuning tuning = CrtTuning.current();
		CrtDraw.header(graphics, font, title.getString().toUpperCase(Locale.ROOT), contentLeft(MARGIN), contentTop(MARGIN), contentRight(MARGIN));
		if (withdrawing) {
			extractWithdrawal(graphics, tuning);
			super.extractRenderState(graphics, mouseX, mouseY, partialTick);
			return;
		}
		drawTypewriter(graphics, typewriter, contentLeft(MARGIN), contentTop(MARGIN) + font.lineHeight + 14, contentWidth(MARGIN));
		int top = accountTop();
		CrtDraw.glowText(graphics, font, accountLine(), contentLeft(MARGIN), top, tuning.phosphorColor());
		if (typewriter.done()) {
			int y = top;
			int x = rightColumn();
			for (OreType ore : OreType.values()) {
				String price = Component.translatable("screen.deepcharter.processor.price",
						Component.translatable(OreRegistry.item(ore).getDescriptionId()).getString().toUpperCase(Locale.ROOT), ore.value()).getString();
				CrtDraw.glowText(graphics, font, price, x, y, PanelLook.dimText(tuning.dimColor()));
				y += font.lineHeight + 2;
			}
			// The rows are buttons; only the heading (or the reason there are none) is text.
			List<String> lines = orderLines();
			if (!lines.isEmpty()) {
				CrtDraw.glowText(graphics, font, lines.getFirst(), x, headingTop(), tuning.phosphorColor());
			}
		}
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}
}
