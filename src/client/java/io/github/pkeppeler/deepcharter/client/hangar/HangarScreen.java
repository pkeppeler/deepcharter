package io.github.pkeppeler.deepcharter.client.hangar;

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
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;
import io.github.pkeppeler.deepcharter.client.ui.Typewriter;
import io.github.pkeppeler.deepcharter.hangar.HangarTerminal;
import io.github.pkeppeler.deepcharter.hangar.HangarTuning;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
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

	private TerminalView view;
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
		int restoreY = closeY - GAP - BUTTON_HEIGHT - GAP;
		int buyY = restoreY - GAP - BUTTON_HEIGHT;
		String catalyst = OreRegistry.stack(tuning.catalyst()).getHoverName().getString().toUpperCase(Locale.ROOT);
		addRenderableWidget(new CrtButton(MARGIN, buyY, BUTTON_WIDTH, BUTTON_HEIGHT,
				Component.translatable("screen.deepcharter.hangar.buy", tuning.refurbishedMole(), tuning.registrationFee()),
				button -> send(HangarTerminal.BUY_MOLE)));
		addRenderableWidget(new CrtButton(MARGIN, restoreY, BUTTON_WIDTH, BUTTON_HEIGHT,
				Component.translatable("screen.deepcharter.hangar.restore", tuning.restoreMoney(), tuning.restoreCatalysts(), catalyst),
				button -> send(HangarTerminal.RESTORE_WRECK)));
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
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}
}
