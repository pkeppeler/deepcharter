package io.github.pkeppeler.deepcharter.client.terminal;

import java.util.List;
import java.util.Locale;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import io.github.pkeppeler.deepcharter.client.charter.ClientCharter;
import io.github.pkeppeler.deepcharter.client.sound.TypewriterSound;
import io.github.pkeppeler.deepcharter.client.ui.CrtButton;
import io.github.pkeppeler.deepcharter.client.ui.CrtDraw;
import io.github.pkeppeler.deepcharter.client.ui.CrtScreen;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;
import io.github.pkeppeler.deepcharter.client.ui.Typewriter;
import io.github.pkeppeler.deepcharter.terminal.TerminalActionPayload;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.TerminalView;
import io.github.pkeppeler.deepcharter.terminal.Terminals;

/**
 * The generic terminal screen. Offline (unrepaired), it lists the terminal's parts as buttons: pressing one asks the server to put
 * that part in, and the server answers with the new {@link TerminalView}. Online, it greets the charter and shows its account.
 *
 * <p>A terminal feature replaces the online screen with its own through {@link TerminalScreens#register}. The offline screen is
 * the same for every type. The screen never decides anything: the server checks every press.
 */
public final class TerminalScreen extends CrtScreen implements TerminalViewScreen {
	private static final int MARGIN = 24;
	private static final int BUTTON_WIDTH = 200;
	private static final int BUTTON_HEIGHT = 20;
	private static final int GAP = 6;
	private static final int CLOSE_WIDTH = 90;

	private TerminalView view;
	private final Typewriter typewriter;

	public TerminalScreen(TerminalView view) {
		super(Component.translatable(typeOf(view).block().getDescriptionId()));
		this.view = view;
		this.typewriter = typewriter(intro(view), new TypewriterSound());
	}

	private static TerminalType typeOf(TerminalView view) {
		return TerminalTypes.get(view.type()).orElseThrow(() -> new IllegalStateException("unknown terminal type " + view.type()));
	}

	private Component intro(TerminalView shown) {
		if (shown.repaired()) {
			return Component.translatable("screen.deepcharter.terminal.online");
		}
		if (!shown.unlocked()) {
			TerminalType first = typeOf(shown).prerequisite().orElseThrow();
			return Component.translatable("screen.deepcharter.terminal.offline_locked", Component.translatable(first.block().getDescriptionId()));
		}
		return Component.translatable("screen.deepcharter.terminal.offline");
	}

	/** True when {@code other} is the same terminal in the same mode, so this screen can take it with {@link #update}. */
	@Override
	public boolean accepts(TerminalView other) {
		return view.pos().equals(other.pos()) && view.type().equals(other.type())
				&& view.repaired() == other.repaired() && view.unlocked() == other.unlocked();
	}

	/** Shows a newer view of the same terminal, keeping the typewriter where it is. */
	@Override
	public void update(TerminalView newer) {
		view = newer;
		rebuildWidgets();
	}

	public TerminalView view() {
		return view;
	}

	public boolean online() {
		return view.repaired();
	}

	public Typewriter typewriter() {
		return typewriter;
	}

	@Override
	protected void layout() {
		int closeY = height - MARGIN - BUTTON_HEIGHT;
		addRenderableWidget(new CrtButton(MARGIN, closeY, CLOSE_WIDTH, BUTTON_HEIGHT,
				Component.translatable("screen.deepcharter.terminal.close"), button -> onClose()));
		if (view.repaired()) {
			return;
		}
		List<TerminalView.PartStatus> parts = view.parts();
		int top = closeY - GAP - parts.size() * (BUTTON_HEIGHT + GAP) + GAP;
		for (int i = 0; i < parts.size(); i++) {
			TerminalView.PartStatus part = parts.get(i);
			Component label = Component.translatable(part.inserted() ? "screen.deepcharter.terminal.inserted" : "screen.deepcharter.terminal.insert",
					partName(part));
			CrtButton button = addRenderableWidget(new CrtButton(MARGIN, top + i * (BUTTON_HEIGHT + GAP), BUTTON_WIDTH, BUTTON_HEIGHT, label,
					pressed -> insert(part)));
			button.active = view.unlocked() && !part.inserted();
		}
	}

	private static String partName(TerminalView.PartStatus part) {
		// An id this build does not know (a part from a newer server) still shows, as "?", and the server decides what the button does.
		return BuiltInRegistries.ITEM.getOptional(part.part())
				.map(item -> new ItemStack(item).getHoverName().getString().toUpperCase(Locale.ROOT)).orElse("?");
	}

	private void insert(TerminalView.PartStatus part) {
		CompoundTag args = new CompoundTag();
		args.putString(Terminals.PART_KEY, part.part().toString());
		ClientPlayNetworking.send(new TerminalActionPayload(view.pos(), Terminals.INSERT_PART, args));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		CrtTuning tuning = CrtTuning.DEFAULT;
		CrtDraw.glowText(graphics, font, title.getString().toUpperCase(Locale.ROOT), MARGIN, MARGIN, tuning.phosphorColor());
		CrtDraw.border(graphics, MARGIN - 6, MARGIN + font.lineHeight + 4, width - MARGIN + 6, MARGIN + font.lineHeight + 5, tuning.dimColor());
		int below = drawTypewriter(graphics, typewriter, MARGIN, MARGIN + font.lineHeight + 14, width - 2 * MARGIN);
		if (view.repaired()) {
			String account = ClientCharter.view()
					.map(charter -> Component.translatable("screen.deepcharter.terminal.account", charter.balance()).getString())
					.orElse("");
			CrtDraw.glowText(graphics, font, account, MARGIN, below + GAP, tuning.phosphorColor());
		}
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}
}
