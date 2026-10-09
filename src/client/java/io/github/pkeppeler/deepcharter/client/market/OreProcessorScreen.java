package io.github.pkeppeler.deepcharter.client.market;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.client.charter.ClientCharter;
import io.github.pkeppeler.deepcharter.client.sound.TypewriterSound;
import io.github.pkeppeler.deepcharter.client.ui.CrtButton;
import io.github.pkeppeler.deepcharter.client.ui.CrtDraw;
import io.github.pkeppeler.deepcharter.client.ui.CrtScreen;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;
import io.github.pkeppeler.deepcharter.client.ui.Typewriter;
import io.github.pkeppeler.deepcharter.client.terminal.TerminalViewScreen;
import io.github.pkeppeler.deepcharter.market.OreProcessor;
import io.github.pkeppeler.deepcharter.market.WorkOrder;
import io.github.pkeppeler.deepcharter.market.WorkOrders;
import io.github.pkeppeler.deepcharter.market.WorkOrdersView;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodLiningTuning;
import io.github.pkeppeler.deepcharter.terminal.TerminalActionPayload;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.TerminalView;

/**
 * The online screen of the ore processor: the price of each ore, the charter's account, the two "sell all" buttons and the one that makes slag brick. A button
 * only asks the server; the server decides, and the account shown is the one it last synced, so it changes when a sale goes through.
 */
public final class OreProcessorScreen extends CrtScreen implements TerminalViewScreen {
	private static final int MARGIN = 24;
	private static final int BUTTON_WIDTH = 200;
	private static final int BUTTON_HEIGHT = 20;
	private static final int GAP = 6;
	private static final int CLOSE_WIDTH = 90;
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
		int closeY = height - MARGIN - BUTTON_HEIGHT;
		addRenderableWidget(new CrtButton(MARGIN, closeY, CLOSE_WIDTH, BUTTON_HEIGHT,
				Component.translatable("screen.deepcharter.processor.close"), button -> onClose()));
		int inventoryY = closeY - GAP - BUTTON_HEIGHT;
		addRenderableWidget(new CrtButton(MARGIN, inventoryY, BUTTON_WIDTH, BUTTON_HEIGHT,
				Component.translatable("screen.deepcharter.processor.sell_inventory"), button -> sell(OreProcessor.SELL_INVENTORY)));
		int cargoY = inventoryY - GAP - BUTTON_HEIGHT;
		addRenderableWidget(new CrtButton(MARGIN, cargoY, BUTTON_WIDTH, BUTTON_HEIGHT,
				Component.translatable("screen.deepcharter.processor.sell_cargo"), button -> sell(OreProcessor.SELL_CARGO)));
		int fuseY = cargoY - GAP - BUTTON_HEIGHT;
		addRenderableWidget(new CrtButton(MARGIN, fuseY, BUTTON_WIDTH, BUTTON_HEIGHT,
				Component.translatable("screen.deepcharter.processor.fuse", PodLiningTuning.DEFAULT.fusePrice()), button -> sell(OreProcessor.FUSE_SPOIL)));
		layoutOrders(fuseY);
	}

	/**
	 * The order rows sit above the topmost button, in the room between it and the account line, so their number is bounded by the
	 * screen: as many rows as fit, at least one. The orders beyond a page are reached with the pager, under the prices.
	 */
	private void layoutOrders(int buttonsTop) {
		List<WorkOrdersView.Entry> listed = listedOrders();
		int perPage = ordersPerPage(buttonsTop);
		pages = Math.max(1, (listed.size() + perPage - 1) / perPage);
		page = Math.clamp(page, 0, pages - 1);
		int first = page * perPage;
		int rowY = buttonsTop - ORDER_GAP - perPage * ORDER_ROW_PITCH + ORDER_ROW_GAP;
		for (WorkOrdersView.Entry entry : listed.subList(first, Math.min(listed.size(), first + perPage))) {
			Component deliver = Component.translatable("screen.deepcharter.processor.deliver", entry.order().oreName().getString().toUpperCase(Locale.ROOT));
			OrderRowButton row = new OrderRowButton(MARGIN, rowY, BUTTON_WIDTH, deliver,
					Component.translatable(entry.order().titleKey()).getString().toUpperCase(Locale.ROOT), progressLine(entry), !entry.done(),
					button -> deliver(entry.order()));
			orderRows.add(row);
			addRenderableWidget(row);
			rowY += ORDER_ROW_PITCH;
		}
		if (pages > 1) {
			int pagerY = pricesBottom() + GAP * 2 + font.lineHeight + ORDER_GAP;
			CrtButton previous = addRenderableWidget(new CrtButton(rightColumn(), pagerY, PAGER_WIDTH, BUTTON_HEIGHT,
					Component.literal("<"), button -> turnPage(-1)));
			previous.active = page > 0;
			CrtButton next = addRenderableWidget(new CrtButton(rightColumn() + PAGER_WIDTH + GAP, pagerY, PAGER_WIDTH, BUTTON_HEIGHT,
					Component.literal(">"), button -> turnPage(1)));
			next.active = page < pages - 1;
		}
	}

	private void turnPage(int by) {
		page += by;
		rebuildWidgets();
	}

	/** How many order rows fit between the account line and the topmost button. */
	private int ordersPerPage(int buttonsTop) {
		int room = buttonsTop - ORDER_GAP - (accountTop() + font.lineHeight + ORDER_GAP);
		return Math.max(1, (room + ORDER_ROW_GAP) / ORDER_ROW_PITCH);
	}

	/** The y of the account line, which is below the intro in full. Fixed, so that the rows do not move while the intro types. */
	private int accountTop() {
		int introLines = font.getSplitter().splitLines(FormattedText.of(typewriter.text()), width - 2 * MARGIN, Style.EMPTY).size();
		return MARGIN + font.lineHeight + 14 + introLines * (font.lineHeight + CrtTuning.current().lineSpacing()) + GAP * 2;
	}

	/** The y below the account line: the order rows start under it. */
	public int accountBottom() {
		return accountTop() + font.lineHeight;
	}

	private int rightColumn() {
		return MARGIN + BUTTON_WIDTH + 2 * GAP;
	}

	private int pricesBottom() {
		return accountTop() + OreType.values().length * (font.lineHeight + 2);
	}

	private Optional<WorkOrdersView> orders() {
		return view.feature(WorkOrdersView.class);
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

	/** The order rows on screen, top to bottom. */
	public List<OrderRowButton> orderRows() {
		return List.copyOf(orderRows);
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

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		CrtTuning tuning = CrtTuning.current();
		CrtDraw.header(graphics, font, title.getString().toUpperCase(Locale.ROOT), MARGIN, width);
		drawTypewriter(graphics, typewriter, MARGIN, MARGIN + font.lineHeight + 14, width - 2 * MARGIN);
		int top = accountTop();
		CrtDraw.glowText(graphics, font, accountLine(), MARGIN, top, tuning.phosphorColor());
		if (typewriter.done()) {
			int y = top;
			int x = rightColumn();
			for (OreType ore : OreType.values()) {
				String price = Component.translatable("screen.deepcharter.processor.price",
						Component.translatable(OreRegistry.item(ore).getDescriptionId()).getString().toUpperCase(Locale.ROOT), ore.value()).getString();
				CrtDraw.glowText(graphics, font, price, x, y, tuning.dimColor());
				y += font.lineHeight + 2;
			}
			// The rows are buttons; only the heading (or the reason there are none) is text.
			List<String> lines = orderLines();
			if (!lines.isEmpty()) {
				CrtDraw.glowText(graphics, font, lines.getFirst(), x, y + GAP * 2, tuning.phosphorColor());
			}
		}
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}
}
