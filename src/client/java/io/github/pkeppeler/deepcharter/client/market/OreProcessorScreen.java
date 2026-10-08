package io.github.pkeppeler.deepcharter.client.market;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
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
import io.github.pkeppeler.deepcharter.terminal.TerminalActionPayload;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.TerminalView;

/**
 * The online screen of the ore processor: the price of each ore, the charter's account, and the two "sell all" buttons. A button
 * only asks the server; the server decides, and the account shown is the one it last synced, so it changes when a sale goes through.
 */
public final class OreProcessorScreen extends CrtScreen implements TerminalViewScreen {
	private static final int MARGIN = 24;
	private static final int BUTTON_WIDTH = 200;
	private static final int BUTTON_HEIGHT = 20;
	private static final int GAP = 6;
	private static final int CLOSE_WIDTH = 90;

	private TerminalView view;
	private final Typewriter typewriter;

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
		int closeY = height - MARGIN - BUTTON_HEIGHT;
		addRenderableWidget(new CrtButton(MARGIN, closeY, CLOSE_WIDTH, BUTTON_HEIGHT,
				Component.translatable("screen.deepcharter.processor.close"), button -> onClose()));
		int inventoryY = closeY - GAP - BUTTON_HEIGHT - GAP;
		addRenderableWidget(new CrtButton(MARGIN, inventoryY, BUTTON_WIDTH, BUTTON_HEIGHT,
				Component.translatable("screen.deepcharter.processor.sell_inventory"), button -> sell(OreProcessor.SELL_INVENTORY)));
		int cargoY = inventoryY - GAP - BUTTON_HEIGHT;
		addRenderableWidget(new CrtButton(MARGIN, cargoY, BUTTON_WIDTH, BUTTON_HEIGHT,
				Component.translatable("screen.deepcharter.processor.sell_cargo"), button -> sell(OreProcessor.SELL_CARGO)));
		int deliverY = cargoY - GAP - BUTTON_HEIGHT - GAP;
		for (WorkOrdersView.Entry entry : openOrders()) {
			addRenderableWidget(new CrtButton(MARGIN, deliverY, BUTTON_WIDTH, BUTTON_HEIGHT,
					Component.translatable("screen.deepcharter.processor.deliver", oreName(entry.order()).getString().toUpperCase(Locale.ROOT)),
					button -> deliver(entry.order())));
			deliverY -= GAP + BUTTON_HEIGHT;
		}
	}

	private Optional<WorkOrdersView> orders() {
		return view.feature(WorkOrdersView.class);
	}

	/** The orders the charter can still hand ore in for. */
	private List<WorkOrdersView.Entry> openOrders() {
		return orders().map(orders -> orders.orders().stream().filter(entry -> !entry.done()).toList()).orElse(List.of());
	}

	private static Component oreName(WorkOrder order) {
		return Component.translatable(OreRegistry.item(order.ore()).getDescriptionId());
	}

	private void sell(Identifier action) {
		ClientPlayNetworking.send(new TerminalActionPayload(view.pos(), action, new CompoundTag()));
	}

	private void deliver(WorkOrder order) {
		CompoundTag args = new CompoundTag();
		args.putString(WorkOrders.ORDER_KEY, order.id().toString());
		ClientPlayNetworking.send(new TerminalActionPayload(view.pos(), WorkOrders.DELIVER, args));
	}

	/** The lines of the work order block: its heading, then a title line and a progress line for each order, or one line saying they are unavailable. */
	public List<String> orderLines() {
		Optional<WorkOrdersView> orders = orders();
		if (orders.isEmpty()) {
			return List.of();
		}
		List<String> lines = new ArrayList<>();
		if (!orders.get().readable()) {
			lines.add(Component.translatable("screen.deepcharter.processor.orders_unreadable").getString());
			return lines;
		}
		lines.add(Component.translatable("screen.deepcharter.processor.orders").getString());
		for (WorkOrdersView.Entry entry : orders.get().orders()) {
			lines.add(Component.translatable(entry.order().titleKey()).getString().toUpperCase(Locale.ROOT));
			lines.add(entry.done() ? Component.translatable("screen.deepcharter.processor.order_done").getString()
					: Component.translatable("screen.deepcharter.processor.order", entry.delivered(), entry.order().quantity(),
							oreName(entry.order()).getString().toUpperCase(Locale.ROOT)).getString());
		}
		return lines;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		CrtTuning tuning = CrtTuning.DEFAULT;
		CrtDraw.glowText(graphics, font, title.getString().toUpperCase(Locale.ROOT), MARGIN, MARGIN, tuning.phosphorColor());
		CrtDraw.border(graphics, MARGIN - 6, MARGIN + font.lineHeight + 4, width - MARGIN + 6, MARGIN + font.lineHeight + 5, tuning.dimColor());
		int top = drawTypewriter(graphics, typewriter, MARGIN, MARGIN + font.lineHeight + 14, width - 2 * MARGIN) + GAP * 2;
		CrtDraw.glowText(graphics, font, accountLine(), MARGIN, top, tuning.phosphorColor());
		if (typewriter.done()) {
			int y = top;
			int x = MARGIN + BUTTON_WIDTH + 2 * GAP;
			for (OreType ore : OreType.values()) {
				String price = Component.translatable("screen.deepcharter.processor.price",
						Component.translatable(OreRegistry.item(ore).getDescriptionId()).getString().toUpperCase(Locale.ROOT), ore.value()).getString();
				CrtDraw.glowText(graphics, font, price, x, y, tuning.dimColor());
				y += font.lineHeight + 2;
			}
			y += GAP * 2;
			for (String line : orderLines()) {
				CrtDraw.glowText(graphics, font, line, x, y, tuning.phosphorColor());
				y += font.lineHeight + 2;
			}
		}
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}
}
