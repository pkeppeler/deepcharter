package io.github.pkeppeler.deepcharter.client.hangar;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.client.charter.ClientCharter;
import io.github.pkeppeler.deepcharter.client.sound.TypewriterSound;
import io.github.pkeppeler.deepcharter.client.terminal.TerminalViewScreen;
import io.github.pkeppeler.deepcharter.client.ui.CrtButton;
import io.github.pkeppeler.deepcharter.client.ui.CrtDraw;
import io.github.pkeppeler.deepcharter.client.ui.CrtScreen;
import io.github.pkeppeler.deepcharter.client.ui.CrtText;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;
import io.github.pkeppeler.deepcharter.client.ui.Typewriter;
import io.github.pkeppeler.deepcharter.hangar.HangarTerminal;
import io.github.pkeppeler.deepcharter.hangar.HangarTuning;
import io.github.pkeppeler.deepcharter.hangar.HangarView;
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
	private List<PriceLine> advanceLines = List.of();
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
		int closeY = contentBottom(MARGIN) - BUTTON_HEIGHT;
		int left = contentLeft(MARGIN);
		addRenderableWidget(new CrtButton(left, closeY, CLOSE_WIDTH, BUTTON_HEIGHT,
				Component.translatable("screen.deepcharter.terminal.close"), button -> onClose()));
		// The restore prices are text under the restore button, wrapped to the screen, so no price can run off its edge.
		String catalyst = OreRegistry.stack(tuning.catalyst()).getHoverName().getString().toUpperCase(Locale.ROOT);
		HangarTuning.RestoreCost mole = tuning.restoreCost(Chassis.MOLE);
		HangarTuning.RestoreCost prospector = tuning.restoreCost(Chassis.PROSPECTOR);
		String prices = Component.translatable("screen.deepcharter.hangar.restore_prices", mole.money(), mole.catalysts(),
				prospector.money(), prospector.catalysts(), catalyst).getString();
		// The advance is a sentence of its own under the prices, and shows only while the charter has some of it left.
		int advanceLeft = view.feature(HangarView.class).map(HangarView::advanceLeft).orElse(0);
		List<String> advance = advanceLeft > 0
				? wrap(Component.translatable("screen.deepcharter.hangar.advance", advanceLeft, catalyst).getString())
				: List.of();
		List<String> wrapped = wrap(prices);
		int lineHeight = font.lineHeight + CrtTuning.current().lineSpacing();
		int pricesY = closeY - GAP - (wrapped.size() + advance.size()) * lineHeight;
		priceLines = placed(wrapped, pricesY, lineHeight);
		advanceLines = placed(advance, pricesY + wrapped.size() * lineHeight, lineHeight);
		int restoreY = pricesY - GAP - BUTTON_HEIGHT;
		int buyY = restoreY - GAP - BUTTON_HEIGHT;
		addRenderableWidget(new CrtButton(left, buyY, BUTTON_WIDTH, BUTTON_HEIGHT,
				Component.translatable("screen.deepcharter.hangar.buy", tuning.refurbishedMole(), tuning.registrationFee()),
				button -> send(HangarTerminal.BUY_MOLE)));
		// The wreck the server restores is the nearest one, so the lines under the button name the price of each chassis.
		addRenderableWidget(new CrtButton(left, restoreY, BUTTON_WIDTH, BUTTON_HEIGHT,
				Component.translatable("screen.deepcharter.hangar.restore"), button -> send(HangarTerminal.RESTORE_WRECK)));
	}

	private List<String> wrap(String text) {
		return CrtText.wrap(font, text, contentWidth(MARGIN));
	}

	private List<PriceLine> placed(List<String> lines, int y, int lineHeight) {
		int left = contentLeft(MARGIN);
		List<PriceLine> placed = new ArrayList<>();
		for (int i = 0; i < lines.size(); i++) {
			placed.add(new PriceLine(lines.get(i), left, y + i * lineHeight));
		}
		return List.copyOf(placed);
	}

	/** The y below the header text once the welcome has typed out in full: the welcome lines, then the account line. */
	public int headerBottom() {
		int welcomeLines = CrtText.wrap(font, typewriter.text(), contentWidth(MARGIN)).size();
		int welcomeBottom = contentTop(MARGIN) + font.lineHeight + 14 + Math.max(welcomeLines, 1) * (font.lineHeight + CrtTuning.current().lineSpacing());
		return welcomeBottom + GAP + font.lineHeight;
	}

	/** The restore prices as drawn, one entry per wrapped line. */
	public List<PriceLine> priceLines() {
		return priceLines;
	}

	/** The line of the Company's advance as drawn, one entry per wrapped line; empty once the charter's advance is used. */
	public List<PriceLine> advanceLines() {
		return advanceLines;
	}

	private void send(Identifier action) {
		ClientPlayNetworking.send(new TerminalActionPayload(view.pos(), action, new CompoundTag()));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		CrtTuning tuning = CrtTuning.current();
		CrtDraw.header(graphics, font, title.getString().toUpperCase(Locale.ROOT), contentLeft(MARGIN), contentTop(MARGIN), contentRight(MARGIN));
		int below = drawTypewriter(graphics, typewriter, contentLeft(MARGIN), contentTop(MARGIN) + font.lineHeight + 14, contentWidth(MARGIN));
		String account = ClientCharter.view()
				.map(charter -> Component.translatable("screen.deepcharter.terminal.account", charter.balance()).getString())
				.orElse("");
		CrtDraw.glowText(graphics, font, account, contentLeft(MARGIN), below + GAP, tuning.phosphorColor());
		for (PriceLine line : priceLines) {
			CrtDraw.glowText(graphics, font, line.text(), line.x(), line.y(), tuning.phosphorColor());
		}
		for (PriceLine line : advanceLines) {
			CrtDraw.glowText(graphics, font, line.text(), line.x(), line.y(), tuning.phosphorColor());
		}
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}
}
