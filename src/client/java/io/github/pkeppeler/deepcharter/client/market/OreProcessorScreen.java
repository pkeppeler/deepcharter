package io.github.pkeppeler.deepcharter.client.market;

import java.util.Locale;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.client.charter.ClientCharter;
import io.github.pkeppeler.deepcharter.client.ui.CrtButton;
import io.github.pkeppeler.deepcharter.client.ui.CrtDraw;
import io.github.pkeppeler.deepcharter.client.ui.CrtScreen;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;
import io.github.pkeppeler.deepcharter.client.ui.Typewriter;
import io.github.pkeppeler.deepcharter.client.terminal.TerminalViewScreen;
import io.github.pkeppeler.deepcharter.market.OreProcessor;
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
		this.typewriter = typewriter(Component.translatable("screen.deepcharter.processor.intro"), (index, letter) -> { });
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
		addRenderableWidget(new CrtButton(MARGIN, inventoryY - GAP - BUTTON_HEIGHT, BUTTON_WIDTH, BUTTON_HEIGHT,
				Component.translatable("screen.deepcharter.processor.sell_cargo"), button -> sell(OreProcessor.SELL_CARGO)));
	}

	private void sell(Identifier action) {
		ClientPlayNetworking.send(new TerminalActionPayload(view.pos(), action, new CompoundTag()));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		CrtTuning tuning = CrtTuning.DEFAULT;
		CrtDraw.glowText(graphics, font, title.getString().toUpperCase(Locale.ROOT), MARGIN, MARGIN, tuning.phosphorColor());
		CrtDraw.border(graphics, MARGIN - 6, MARGIN + font.lineHeight + 4, width - MARGIN + 6, MARGIN + font.lineHeight + 5, tuning.dimColor());
		int y = drawTypewriter(graphics, typewriter, MARGIN, MARGIN + font.lineHeight + 14, width - 2 * MARGIN) + GAP;
		if (typewriter.done()) {
			for (OreType ore : OreType.values()) {
				String price = Component.translatable("screen.deepcharter.processor.price",
						Component.translatable(OreRegistry.item(ore).getDescriptionId()).getString().toUpperCase(Locale.ROOT), ore.value()).getString();
				CrtDraw.glowText(graphics, font, price, MARGIN, y, tuning.dimColor());
				y += font.lineHeight + 2;
			}
		}
		CrtDraw.glowText(graphics, font, accountLine(), width - MARGIN - font.width(accountLine()), MARGIN, tuning.phosphorColor());
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}
}
