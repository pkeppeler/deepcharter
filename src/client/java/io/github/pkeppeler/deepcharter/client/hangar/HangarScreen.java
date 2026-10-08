package io.github.pkeppeler.deepcharter.client.hangar;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.client.charter.ClientCharter;
import io.github.pkeppeler.deepcharter.client.sound.TypewriterSound;
import io.github.pkeppeler.deepcharter.client.terminal.TerminalViewScreen;
import io.github.pkeppeler.deepcharter.client.ui.CrtButton;
import io.github.pkeppeler.deepcharter.client.ui.CrtDraw;
import io.github.pkeppeler.deepcharter.client.ui.CrtScreen;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;
import io.github.pkeppeler.deepcharter.client.ui.Typewriter;
import io.github.pkeppeler.deepcharter.hangar.HangarTerminal;
import io.github.pkeppeler.deepcharter.hangar.HangarTuning;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.terminal.TerminalActionPayload;
import io.github.pkeppeler.deepcharter.terminal.TerminalView;

/**
 * The online screen of the hangar console: buy a refurbished Mole, or restore the wreck nearest the console. The prices on the
 * buttons are the tuning's, and the server decides every press, so the screen never needs to know how many pods the charter has.
 * The server's answer to an action is a new view, which this screen takes in place.
 */
public final class HangarScreen extends CrtScreen implements TerminalViewScreen {
	private static final int MARGIN = 24;
	private static final int BUTTON_WIDTH = 260;
	private static final int BUTTON_HEIGHT = 20;
	private static final int GAP = 6;
	private static final int CLOSE_WIDTH = 90;

	/** One wrapped line of the restore prices, where it is drawn. */
	public record PriceLine(String text, int x, int y) {
	}

	private TerminalView view;
	private List<PriceLine> priceLines = List.of();
	private final Typewriter typewriter;

	public HangarScreen(TerminalView view) {
		super(Component.translatable(HangarTerminal.TYPE.block().getDescriptionId()));
		this.view = view;
		this.typewriter = typewriter(Component.translatable("screen.deepcharter.hangar.welcome"), new TypewriterSound());
	}

	@Override
	public boolean accepts(TerminalView other) {
		return view.pos().equals(other.pos()) && view.type().equals(other.type()) && other.repaired();
	}

	@Override
	public void update(TerminalView newer) {
		view = newer;
		rebuildWidgets();
	}

	public Typewriter typewriter() {
		return typewriter;
	}

	@Override
	protected void layout() {
		HangarTuning tuning = HangarTuning.DEFAULT;
		int closeY = height - MARGIN - BUTTON_HEIGHT;
		addRenderableWidget(new CrtButton(MARGIN, closeY, CLOSE_WIDTH, BUTTON_HEIGHT,
				Component.translatable("screen.deepcharter.terminal.close"), button -> onClose()));
		// The restore prices are text under the restore button, wrapped to the screen, so no price can run off its edge.
		String catalyst = OreRegistry.stack(tuning.catalyst()).getHoverName().getString().toUpperCase(Locale.ROOT);
		HangarTuning.RestoreCost mole = tuning.restoreCost(Chassis.MOLE);
		HangarTuning.RestoreCost prospector = tuning.restoreCost(Chassis.PROSPECTOR);
		String prices = Component.translatable("screen.deepcharter.hangar.restore_prices", mole.money(), mole.catalysts(),
				prospector.money(), prospector.catalysts(), catalyst).getString();
		List<String> wrapped = font.getSplitter().splitLines(FormattedText.of(prices), width - 2 * MARGIN, Style.EMPTY)
				.stream().map(FormattedText::getString).toList();
		int lineHeight = font.lineHeight + 1;
		int pricesY = closeY - GAP - wrapped.size() * lineHeight;
		List<PriceLine> lines = new ArrayList<>();
		for (int i = 0; i < wrapped.size(); i++) {
			lines.add(new PriceLine(wrapped.get(i), MARGIN, pricesY + i * lineHeight));
		}
		priceLines = List.copyOf(lines);
		int restoreY = pricesY - GAP - BUTTON_HEIGHT;
		int buyY = restoreY - GAP - BUTTON_HEIGHT;
		addRenderableWidget(new CrtButton(MARGIN, buyY, BUTTON_WIDTH, BUTTON_HEIGHT,
				Component.translatable("screen.deepcharter.hangar.buy", tuning.refurbishedMole(), tuning.registrationFee()),
				button -> send(HangarTerminal.BUY_MOLE)));
		// The wreck the server restores is the nearest one, so the lines under the button name the price of each chassis.
		addRenderableWidget(new CrtButton(MARGIN, restoreY, BUTTON_WIDTH, BUTTON_HEIGHT,
				Component.translatable("screen.deepcharter.hangar.restore"), button -> send(HangarTerminal.RESTORE_WRECK)));
	}

	/** The restore prices as drawn, one entry per wrapped line. */
	public List<PriceLine> priceLines() {
		return priceLines;
	}

	private void send(Identifier action) {
		ClientPlayNetworking.send(new TerminalActionPayload(view.pos(), action, new CompoundTag()));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		CrtTuning tuning = CrtTuning.DEFAULT;
		CrtDraw.glowText(graphics, font, title.getString().toUpperCase(Locale.ROOT), MARGIN, MARGIN, tuning.phosphorColor());
		CrtDraw.border(graphics, MARGIN - 6, MARGIN + font.lineHeight + 4, width - MARGIN + 6, MARGIN + font.lineHeight + 5, tuning.dimColor());
		int below = drawTypewriter(graphics, typewriter, MARGIN, MARGIN + font.lineHeight + 14, width - 2 * MARGIN);
		String account = ClientCharter.view()
				.map(charter -> Component.translatable("screen.deepcharter.terminal.account", charter.balance()).getString())
				.orElse("");
		CrtDraw.glowText(graphics, font, account, MARGIN, below + GAP, tuning.phosphorColor());
		for (PriceLine line : priceLines) {
			CrtDraw.glowText(graphics, font, line.text(), line.x(), line.y(), tuning.phosphorColor());
		}
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}
}
