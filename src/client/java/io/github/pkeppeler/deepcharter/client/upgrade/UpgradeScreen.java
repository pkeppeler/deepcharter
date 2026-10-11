package io.github.pkeppeler.deepcharter.client.upgrade;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import io.github.pkeppeler.deepcharter.client.charter.ClientCharter;
import io.github.pkeppeler.deepcharter.client.sound.TypewriterSound;
import io.github.pkeppeler.deepcharter.client.theme.PanelLook;
import io.github.pkeppeler.deepcharter.client.ui.CrtButton;
import io.github.pkeppeler.deepcharter.client.ui.CrtDraw;
import io.github.pkeppeler.deepcharter.client.ui.CrtScreen;
import io.github.pkeppeler.deepcharter.client.ui.CrtText;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;
import io.github.pkeppeler.deepcharter.client.ui.Typewriter;
import io.github.pkeppeler.deepcharter.client.terminal.TerminalViewScreen;
import io.github.pkeppeler.deepcharter.terminal.TerminalActionPayload;
import io.github.pkeppeler.deepcharter.terminal.TerminalView;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeTerminal;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeTuning;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeView;

/**
 * The online screen of the upgrade terminal. The left column lists the tracks, closing their rows up to fit the screen, with the tier now installed; the right column
 * lists the parts of the chosen track with their prices. A tier above the pod's cap is marked as working at the cap, so the
 * player pays knowing it. The screen decides nothing: the server checks every purchase, and answers with a new
 * {@link UpgradeView}.
 *
 * <p>It asks the server for the parked pod when it opens, because the generic {@link TerminalView} carries none.
 */
public final class UpgradeScreen extends CrtScreen implements TerminalViewScreen {
	private static final int MARGIN = 16;
	/**
	 * The rows are this far apart. The track list scrolls when the tracks do not fit under the header, so a part track added later never squeezes the rows: only the
	 * tracks that fit are buttons, and the mouse wheel, Page Up and Page Down, or Down and Tab from the last row, move the list.
	 */
	private static final int PITCH = 16;
	/** The plain CRT outlines each button, so its rows need a gap; a panel button has an edge of its own and its rows touch, which gives each label the room. */
	private static final int ROW_GAP = 2;
	private static final int LIST_TOP = 62;
	/** A label's room in its button: {@code buttonPad} and {@link PanelLook#LABEL_MARGIN}, 4 each side. */
	private static final int LABEL_ROOM = 8;
	private static final int TRACK_WIDTH = 104;
	private static final int TIER_MAX_WIDTH = 250;
	private static final int COLUMN_GAP = 6;
	private static final int CLOSE_WIDTH = 70;

	private TerminalView view;
	private ComponentTrack selected = ComponentTrack.values()[0];
	/** The index of the first track shown in the list. */
	private int firstTrack;
	/** Where the scroll hint is drawn, beside Close: set by the last layout. */
	private int hintX;
	private int hintY;
	/** The buttons of the tracks shown, top to bottom, made by the last layout. */
	private final List<CrtButton> trackButtons = new ArrayList<>();
	private final Typewriter typewriter;

	public UpgradeScreen(TerminalView view) {
		super(Component.translatable("block.deepcharter.upgrade_terminal"));
		this.view = view;
		this.typewriter = typewriter(Component.translatable("screen.deepcharter.upgrade.intro"), new TypewriterSound());
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

	/** The parked pod as the server sent it with the view. A repaired upgrade terminal carries it; the screen draws "no pod" if it is missing. */
	public Optional<UpgradeView> upgrade() {
		return view.feature(UpgradeView.class);
	}

	@Override
	public Typewriter typewriter() {
		return typewriter;
	}

	/** The y of the first row: the same distance below the top of the content as before the panel. */
	private int listTop() {
		return contentTop(MARGIN) + LIST_TOP - MARGIN;
	}

	@Override
	protected void layout() {
		int left = contentLeft(MARGIN);
		int rowGap = PanelLook.drawn() ? 0 : ROW_GAP;
		int pitch = PITCH;
		int rowHeight = pitch - rowGap;
		int visible = visibleTracks();
		firstTrack = Mth.clamp(firstTrack, 0, Math.max(0, ComponentTrack.values().length - visible));
		trackButtons.clear();
		int trackWidth = Math.max(TRACK_WIDTH, widestTrackLabel() + LABEL_ROOM);
		int tierX = left + trackWidth + COLUMN_GAP;
		// Close sits under the longest list of tiers, so it stays on the screen however many tracks the left column holds.
		int closeY = listTop() + Arrays.stream(ComponentTrack.values()).mapToInt(ComponentTrack::maxTier).max().orElse(0) * pitch + rowGap;
		hintX = tierX + CLOSE_WIDTH + COLUMN_GAP;
		hintY = closeY + (rowHeight - font.lineHeight) / 2;
		addRenderableWidget(new CrtButton(tierX, closeY, CLOSE_WIDTH, rowHeight,
				Component.translatable("screen.deepcharter.terminal.close"), button -> onClose()));
		Optional<UpgradeView.Pod> shownPod = upgrade().flatMap(UpgradeView::pod);
		if (shownPod.isEmpty()) {
			return;
		}
		UpgradeView.Pod pod = shownPod.get();
		int tierWidth = Math.min(contentRight(MARGIN) - tierX, TIER_MAX_WIDTH);
		for (UpgradeView.Slot slot : pod.slots()) {
			ComponentTrack track = slot.track();
			Component label = Component.translatable("screen.deepcharter.upgrade.track", trackName(track), slot.installed());
			if (track.ordinal() < firstTrack || track.ordinal() >= firstTrack + visible) {
				continue;
			}
			CrtButton button = addRenderableWidget(new CrtButton(left, listTop() + (track.ordinal() - firstTrack) * pitch, trackWidth, rowHeight, label, pressed -> select(track)).marked(track == selected));
			trackButtons.add(button);
		}
		UpgradeView.Slot held = slotOf(pod, selected);
		long balance = ClientCharter.view().map(charter -> charter.balance()).orElse(0L);
		for (int tier = 1; tier <= selected.maxTier(); tier++) {
			int offered = tier;
			long price = UpgradeTuning.DEFAULT.price(selected, tier);
			boolean counts = held.effective() > 0;
			boolean here = counts && held.installed() >= tier;
			String capped = tier > pod.cap()
					? Component.translatable("screen.deepcharter.upgrade.capped", pod.cap()).getString() : "";
			Component label = here && held.installed() == tier
					? Component.translatable("screen.deepcharter.upgrade.installed", tier).append(capped)
					: Component.translatable("screen.deepcharter.upgrade.buy", tier, price, capped);
			CrtButton button = addRenderableWidget(new CrtButton(tierX, listTop() + (tier - 1) * pitch,
					tierWidth, rowHeight, label, pressed -> buy(selected, offered)));
			// An unregistered pod (no serial) takes no parts: they would be void.
			button.active = !here && balance >= price && !pod.serial().isEmpty();
		}
	}

	/** How many tracks fit between the header and the bottom of the content, at least one and at most all of them. */
	public int visibleTracks() {
		int space = contentBottom(MARGIN) - listTop() + (PanelLook.drawn() ? 0 : ROW_GAP);
		return Mth.clamp(space / PITCH, 1, ComponentTrack.values().length);
	}

	/** Shows the track list from the track at index {@code first}, kept inside the list, and lays the buttons out again. */
	public void scrollTo(int first) {
		int clamped = Mth.clamp(first, 0, Math.max(0, ComponentTrack.values().length - visibleTracks()));
		if (clamped != firstTrack) {
			firstTrack = clamped;
			rebuildWidgets();
		}
	}

	/** Which tracks are shown, when the list is longer than the screen. */
	private Optional<String> scrollHint() {
		int visible = visibleTracks();
		if (visible >= ComponentTrack.values().length) {
			return Optional.empty();
		}
		return Optional.of(Component.translatable("screen.deepcharter.upgrade.scroll", firstTrack + 1, firstTrack + visible, ComponentTrack.values().length).getString());
	}

	/** The narrator also tells which tracks are shown, so that a player who cannot see the screen knows that there are more. */
	@Override
	public Component getNarrationMessage() {
		Component narration = super.getNarrationMessage();
		return scrollHint().<Component>map(hint -> Component.literal(narration.getString() + ". " + hint)).orElse(narration);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (scrollY != 0) {
			scrollTo(firstTrack - (int) Math.signum(scrollY));
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	/** A button off the screen cannot take focus, so keys scroll the list: Page Up and Page Down a page, Down or Tab from the last row (Up or Shift+Tab from the first) one row. */
	@Override
	public boolean keyPressed(KeyEvent event) {
		int focused = trackButtons.indexOf(getFocused());
		boolean forward = event.isDown() || (event.isCycleFocus() && !event.hasShiftDown());
		boolean backward = event.isUp() || (event.isCycleFocus() && event.hasShiftDown());
		if (focused >= 0 && forward && focused == trackButtons.size() - 1 && firstTrack + trackButtons.size() < ComponentTrack.values().length) {
			scrollTo(firstTrack + 1);
			setFocused(trackButtons.getLast());
			return true;
		}
		if (focused == 0 && backward && firstTrack > 0) {
			scrollTo(firstTrack - 1);
			setFocused(trackButtons.getFirst());
			return true;
		}
		if (event.key() == InputConstants.KEY_PAGEDOWN) {
			scrollTo(firstTrack + visibleTracks());
			return true;
		}
		if (event.key() == InputConstants.KEY_PAGEUP) {
			scrollTo(firstTrack - visibleTracks());
			return true;
		}
		return super.keyPressed(event);
	}

	/** The widest track label in the terminal font, so that its button can leave the label its room however the names are worded. */
	private int widestTrackLabel() {
		return Arrays.stream(ComponentTrack.values())
				.mapToInt(track -> CrtText.width(font, Component.translatable("screen.deepcharter.upgrade.track", trackName(track), track.maxTier()).getString()))
				.max().orElse(0);
	}

	private static String trackName(ComponentTrack track) {
		return Component.translatable("deepcharter.upgrade.track." + track.id()).getString();
	}

	private static UpgradeView.Slot slotOf(UpgradeView.Pod pod, ComponentTrack track) {
		return pod.slots().stream().filter(slot -> slot.track() == track).findFirst()
				.orElseThrow(() -> new IllegalStateException("the view has no slot for " + track));
	}

	private void select(ComponentTrack track) {
		selected = track;
		rebuildWidgets();
	}

	private void buy(ComponentTrack track, int tier) {
		CompoundTag args = new CompoundTag();
		args.putString(UpgradeTerminal.TRACK_KEY, track.id());
		args.putInt(UpgradeTerminal.TIER_KEY, tier);
		ClientPlayNetworking.send(new TerminalActionPayload(view.pos(), UpgradeTerminal.BUY, args));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		CrtTuning tuning = CrtTuning.current();
		CrtDraw.header(graphics, font, title.getString().toUpperCase(Locale.ROOT), contentLeft(MARGIN), contentTop(MARGIN), contentRight(MARGIN));
		int below = drawTypewriter(graphics, typewriter, contentLeft(MARGIN), contentTop(MARGIN) + font.lineHeight + 10, contentWidth(MARGIN));
		CrtDraw.glowText(graphics, font, status(), contentLeft(MARGIN), below, tuning.phosphorColor());
		scrollHint().ifPresent(hint -> CrtDraw.glowText(graphics, font, hint, hintX, hintY, tuning.phosphorColor()));
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	/** The pod, its tier cap and the account, or why there is no pod. */
	private String status() {
		Optional<UpgradeView> shown = upgrade();
		if (shown.isEmpty() || shown.get().pod().isEmpty()) {
			return Component.translatable(shown.isPresent() && shown.get().foreignPod() ? "screen.deepcharter.upgrade.foreign_pod" : "screen.deepcharter.upgrade.no_pod").getString();
		}
		String account = ClientCharter.view()
				.map(charter -> Component.translatable("screen.deepcharter.terminal.account", charter.balance()).getString()).orElse("");
		UpgradeView.Pod pod = shown.get().pod().get();
		Component line = pod.serial().isEmpty()
				? Component.translatable("screen.deepcharter.upgrade.pod_unregistered", pod.cap())
				: Component.translatable("screen.deepcharter.upgrade.pod", pod.serial(), pod.cap());
		return line.getString() + "   " + account;
	}
}
