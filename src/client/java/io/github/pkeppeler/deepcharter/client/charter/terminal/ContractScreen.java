package io.github.pkeppeler.deepcharter.client.charter.terminal;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.charter.CharterTuning;
import io.github.pkeppeler.deepcharter.charter.terminal.ContractActions;
import io.github.pkeppeler.deepcharter.charter.terminal.ContractState;
import io.github.pkeppeler.deepcharter.client.terminal.TerminalViewScreen;
import io.github.pkeppeler.deepcharter.client.ui.CrtButton;
import io.github.pkeppeler.deepcharter.client.ui.CrtDraw;
import io.github.pkeppeler.deepcharter.client.ui.CrtScreen;
import io.github.pkeppeler.deepcharter.client.ui.CrtTextField;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;
import io.github.pkeppeler.deepcharter.client.ui.Typewriter;
import io.github.pkeppeler.deepcharter.terminal.TerminalActionPayload;
import io.github.pkeppeler.deepcharter.terminal.TerminalView;

/**
 * The contract terminal's screen: found a charter, apply to one, answer applications as Director, and leave. What it shows
 * is the last {@link ContractState} the server sent; until the first one arrives it shows only the greeting. It decides nothing:
 * every press is a request, the server checks it, and a refusal comes back in the next state, shown under the status.
 *
 * <p>It implements {@link TerminalViewScreen}, so the server's answer to a press updates it in place and the name being typed
 * survives.
 */
public final class ContractScreen extends CrtScreen implements TerminalViewScreen {
	private static final int MARGIN = 24;
	private static final int ROW_HEIGHT = 16;
	private static final int ROW_PITCH = 20;
	private static final int COLUMN_WIDTH = 170;
	private static final int COLUMN_GAP = 16;
	private static final int CLOSE_WIDTH = 70;
	private static final int ACTION_WIDTH = 120;
	private static final int STATUS_Y = 72;
	private static final int ROWS_TOP = 100;
	private static final int FIELD_HEIGHT = 14;
	private static final int MAX_LABEL_NAME = 18;
	private static final int REFUSAL_COLOR = 0xFFFFB000;

	private final TerminalView view;
	private final Typewriter typewriter;
	private Optional<ContractState> state = Optional.empty();
	private CrtTextField nameField;

	public ContractScreen(TerminalView view) {
		super(Component.translatable("block.deepcharter.contract_terminal"));
		this.view = view;
		this.typewriter = typewriter(Component.translatable("screen.deepcharter.contract.intro"), (index, letter) -> { });
	}

	@Override
	public boolean accepts(TerminalView other) {
		return view.pos().equals(other.pos()) && view.type().equals(other.type());
	}

	/** The server's answer to a press carries no news for this screen: the {@link ContractState} that follows does. */
	@Override
	public void update(TerminalView newer) {
	}

	/** Shows what the server last said about the player. */
	public void show(ContractState newer) {
		state = Optional.of(newer);
		rebuildWidgets();
	}

	public Optional<ContractState> state() {
		return state;
	}

	public Typewriter typewriter() {
		return typewriter;
	}

	/** The field a new charter's name is typed in, or null when the player has a charter. */
	public CrtTextField nameField() {
		return nameField;
	}

	/** The refusal the server last sent, as the screen shows it. */
	public Optional<Component> refusal() {
		return state.flatMap(ContractState::notice).map(key -> Component.translatable("screen.deepcharter.contract.refused", Component.translatable(key)));
	}

	@Override
	protected void layout() {
		String typed = nameField == null ? "" : nameField.getValue();
		nameField = null;
		int closeY = height - MARGIN - ROW_HEIGHT;
		addRenderableWidget(new CrtButton(MARGIN, closeY, CLOSE_WIDTH, ROW_HEIGHT, Component.translatable("screen.deepcharter.terminal.close"), button -> onClose()));
		if (state.isEmpty()) {
			return;
		}
		ContractState shown = state.get();
		int columnWidth = Math.min(COLUMN_WIDTH, (width - 2 * MARGIN - COLUMN_GAP) / 2);
		int rightX = MARGIN + columnWidth + COLUMN_GAP;
		int actionX = MARGIN + CLOSE_WIDTH + 6;
		switch (shown.role()) {
			case NONE -> {
				nameField = addRenderableWidget(new CrtTextField(font, MARGIN + CrtTextField.FRAME, ROWS_TOP + CrtTextField.FRAME,
						columnWidth - 2 * CrtTextField.FRAME, FIELD_HEIGHT, Component.translatable("screen.deepcharter.contract.name_hint")));
				nameField.setMaxLength(CharterTuning.DEFAULT.maxNameLength());
				nameField.setValue(typed);
				addRenderableWidget(new CrtButton(MARGIN, ROWS_TOP + ROW_PITCH + 2, columnWidth, ROW_HEIGHT,
						Component.translatable("screen.deepcharter.contract.found"), button -> send(ContractActions.FOUND, nameField.getValue())));
				rows(shown.charters(), rightX, columnWidth, "screen.deepcharter.contract.apply", ContractActions.APPLY);
			}
			case APPLICANT -> leaveButton(actionX, closeY, "screen.deepcharter.contract.withdraw");
			case CREW -> leaveButton(actionX, closeY, "screen.deepcharter.contract.leave");
			case DIRECTOR -> {
				rows(shown.applicants(), MARGIN, columnWidth, "screen.deepcharter.contract.approve", ContractActions.APPROVE);
				rows(shown.applicants(), rightX, columnWidth, "screen.deepcharter.contract.deny", ContractActions.DENY);
				leaveButton(actionX, closeY, "screen.deepcharter.contract.leave");
			}
		}
	}

	private void leaveButton(int x, int y, String labelKey) {
		addRenderableWidget(new CrtButton(x, y, ACTION_WIDTH, ROW_HEIGHT, Component.translatable(labelKey), button -> send(ContractActions.LEAVE, "")));
	}

	/** One button per name in a column, each sending its action with the full name. */
	private void rows(List<String> names, int x, int columnWidth, String labelKey, Identifier action) {
		for (int i = 0; i < names.size(); i++) {
			String name = names.get(i);
			String shownName = name.length() > MAX_LABEL_NAME ? name.substring(0, MAX_LABEL_NAME) + ".." : name;
			Component label = Component.translatable(labelKey, shownName.toUpperCase(Locale.ROOT));
			addRenderableWidget(new CrtButton(x, ROWS_TOP + i * ROW_PITCH, columnWidth, ROW_HEIGHT, label, button -> send(action, name)));
		}
	}

	private void send(Identifier action, String name) {
		CompoundTag args = new CompoundTag();
		args.putString(ContractActions.NAME_KEY, name);
		ClientPlayNetworking.send(new TerminalActionPayload(view.pos(), action, args));
	}

	private Component status(ContractState shown) {
		return switch (shown.role()) {
			case NONE -> Component.translatable("screen.deepcharter.contract.status.none")
					.append(shown.charterCount() > shown.charters().size()
							? Component.literal(" ").append(Component.translatable("screen.deepcharter.contract.more", shown.charterCount() - shown.charters().size()))
							: Component.empty());
			case APPLICANT -> Component.translatable("screen.deepcharter.contract.status.applicant", shown.charter());
			case CREW -> Component.translatable("screen.deepcharter.contract.status.crew", shown.charter());
			case DIRECTOR -> Component.translatable("screen.deepcharter.contract.status.director", shown.charter(), shown.applicantCount())
					.append(shown.applicantCount() > shown.applicants().size()
							? Component.literal(" ").append(Component.translatable("screen.deepcharter.contract.shown", shown.applicants().size(), shown.applicantCount()))
							: Component.empty());
		};
	}

	/** The status line as the screen draws it, for a test to read. */
	public Component statusLine() {
		return state.map(this::status).orElse(Component.translatable("screen.deepcharter.contract.loading"));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		CrtTuning tuning = CrtTuning.DEFAULT;
		CrtDraw.glowText(graphics, font, title.getString().toUpperCase(Locale.ROOT), MARGIN, MARGIN, tuning.phosphorColor());
		CrtDraw.border(graphics, MARGIN - 6, MARGIN + font.lineHeight + 4, width - MARGIN + 6, MARGIN + font.lineHeight + 5, tuning.dimColor());
		drawTypewriter(graphics, typewriter, MARGIN, MARGIN + font.lineHeight + 14, width - 2 * MARGIN);
		CrtDraw.glowText(graphics, font, statusLine().getString(), MARGIN, STATUS_Y, tuning.phosphorColor());
		refusal().ifPresent(message -> CrtDraw.glowText(graphics, font, message.getString().toUpperCase(Locale.ROOT), MARGIN, STATUS_Y + font.lineHeight + 4, REFUSAL_COLOR));
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}
}
