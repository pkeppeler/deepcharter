package io.github.pkeppeler.deepcharter.client.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * A small screen that uses every part of the kit: typewriter text, two buttons and a text field. It is the
 * kit's test and evidence screen, and the example for the screens that build on it. Nothing in the game opens it.
 */
public final class CrtDemoScreen extends CrtScreen {
	private static final String BUTTON_LABEL = "ACKNOWLEDGE";

	private static final int MARGIN = 24;
	private static final int BUTTON_WIDTH = 110;
	private static final int BUTTON_HEIGHT = 20;
	private static final int FIELD_HEIGHT = 14;
	private static final int GAP = 10;

	private Typewriter typewriter;
	private CrtButton acknowledge;
	private CrtTextField field;
	private int presses;
	private String lastTyped = "";

	public CrtDemoScreen() {
		super(Component.translatable("ui.deepcharter.demo.title"));
	}

	@Override
	protected void init() {
		typewriter = typewriter(Component.translatable("ui.deepcharter.demo.body"), (index, letter) -> { });
		int bottom = height - MARGIN;
		acknowledge = addRenderableWidget(new CrtButton(MARGIN, bottom - BUTTON_HEIGHT, BUTTON_WIDTH, BUTTON_HEIGHT,
				Component.literal(BUTTON_LABEL), () -> presses++));
		addRenderableWidget(new CrtButton(MARGIN + BUTTON_WIDTH + GAP, bottom - BUTTON_HEIGHT, BUTTON_WIDTH, BUTTON_HEIGHT,
				Component.translatable("ui.deepcharter.demo.close"), this::onClose));
		field = addRenderableWidget(new CrtTextField(font, MARGIN + 3, bottom - BUTTON_HEIGHT - GAP - FIELD_HEIGHT - 3,
				width - 2 * MARGIN - 6, FIELD_HEIGHT, Component.translatable("ui.deepcharter.demo.hint")));
		field.setResponder(value -> lastTyped = value);
		setInitialFocus(field);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		CrtDraw.glowText(graphics, font, title.getString(), MARGIN, MARGIN, CrtTuning.DEFAULT.phosphorColor());
		CrtDraw.border(graphics, MARGIN - 6, MARGIN + font.lineHeight + 4, width - MARGIN + 6, MARGIN + font.lineHeight + 5,
				CrtTuning.DEFAULT.dimColor());
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

	public Typewriter typewriter() {
		return typewriter;
	}
}
