package io.github.pkeppeler.deepcharter.client.ui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * A small screen that uses every part of the kit, and the example for the screens that build on it. It is test
 * and evidence scaffolding: nothing in the game opens it, and {@link #presses()}, {@link #lastTyped()} and
 * {@link #hookCalls()} exist only so the tests can see what happened.
 */
public final class CrtDemoScreen extends CrtScreen {
	public static final String BUTTON_LABEL = "ACKNOWLEDGE";

	private static final int MARGIN = 24;
	private static final int BUTTON_WIDTH = 110;
	private static final int BUTTON_HEIGHT = 20;
	private static final int FIELD_HEIGHT = 14;
	private static final int GAP = 10;

	private final Typewriter typewriter;
	private final List<Integer> hookCalls = new ArrayList<>();
	private CrtButton acknowledge;
	private CrtTextField field;
	private int presses;
	private String lastTyped = "";

	public CrtDemoScreen() {
		super(Component.translatable("ui.deepcharter.demo.title"));
		typewriter = typewriter(Component.translatable("ui.deepcharter.demo.body"), (index, letter) -> hookCalls.add(index));
	}

	@Override
	protected void layout() {
		int bottom = height - MARGIN;
		acknowledge = addRenderableWidget(new CrtButton(MARGIN, bottom - BUTTON_HEIGHT, BUTTON_WIDTH, BUTTON_HEIGHT,
				Component.literal(BUTTON_LABEL), button -> presses++));
		addRenderableWidget(new CrtButton(MARGIN + BUTTON_WIDTH + GAP, bottom - BUTTON_HEIGHT, BUTTON_WIDTH, BUTTON_HEIGHT,
				Component.translatable("ui.deepcharter.demo.close"), button -> onClose()));
		String typed = field == null ? "" : field.getValue();
		field = addRenderableWidget(new CrtTextField(font, MARGIN + CrtTextField.FRAME,
				bottom - BUTTON_HEIGHT - GAP - FIELD_HEIGHT - CrtTextField.FRAME,
				width - 2 * (MARGIN + CrtTextField.FRAME), FIELD_HEIGHT, Component.translatable("ui.deepcharter.demo.hint")));
		field.setResponder(value -> lastTyped = value);
		field.setValue(typed);
		setInitialFocus(field);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		CrtDraw.header(graphics, font, title.getString(), MARGIN, MARGIN, width - MARGIN);
		drawTypewriter(graphics, typewriter, MARGIN, MARGIN + font.lineHeight + 14, width - 2 * MARGIN);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	/** Times the acknowledge button has been pressed. */
	public int presses() {
		return presses;
	}

	public CrtButton acknowledge() {
		return acknowledge;
	}

	public CrtTextField field() {
		return field;
	}

	/** The field's text as of its last change. */
	public String lastTyped() {
		return lastTyped;
	}

	/** The letter index of every hook call so far, in order. */
	public List<Integer> hookCalls() {
		return hookCalls;
	}

	public Typewriter typewriter() {
		return typewriter;
	}
}
