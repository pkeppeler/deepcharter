package io.github.pkeppeler.deepcharter.client.upgrade;

import java.util.Locale;
import java.util.Optional;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

import io.github.pkeppeler.deepcharter.client.charter.ClientCharter;
import io.github.pkeppeler.deepcharter.client.ui.CrtButton;
import io.github.pkeppeler.deepcharter.client.ui.CrtDraw;
import io.github.pkeppeler.deepcharter.client.ui.CrtScreen;
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
 * The online screen of the upgrade terminal. The left column lists the eight tracks with the tier now installed; the right column
 * lists the parts of the chosen track with their prices. A tier above the pod's cap is marked as working at the cap, so the
 * player pays knowing it. The screen decides nothing: the server checks every purchase, and answers with a new
 * {@link UpgradeView}.
 *
 * <p>It asks the server for the parked pod when it opens, because the generic {@link TerminalView} carries none.
 */
public final class UpgradeScreen extends CrtScreen implements TerminalViewScreen {
	private static final int MARGIN = 16;
	private static final int ROW_HEIGHT = 14;
	private static final int ROW_GAP = 2;
	private static final int LIST_TOP = 62;
	private static final int TRACK_WIDTH = 104;
	private static final int TIER_MAX_WIDTH = 250;
	private static final int COLUMN_GAP = 8;
	private static final int CLOSE_WIDTH = 70;

	private TerminalView view;
	private Optional<UpgradeView> upgrade = Optional.empty();
	private ComponentTrack selected = ComponentTrack.values()[0];
	private final Typewriter typewriter;

	public UpgradeScreen(TerminalView view) {
		super(Component.translatable("block.deepcharter.upgrade_terminal"));
		this.view = view;
		this.typewriter = typewriter(Component.translatable("screen.deepcharter.upgrade.intro"), (index, letter) -> { });
		ClientPlayNetworking.send(new TerminalActionPayload(view.pos(), UpgradeTerminal.VIEW, new CompoundTag()));
	}

	@Override
	public boolean accepts(TerminalView other) {
		return view.pos().equals(other.pos()) && view.type().equals(other.type()) && other.repaired();
	}

	@Override
	public void update(TerminalView newer) {
		view = newer;
	}

	/** Shows the pod the server says is parked, if the view is of this screen's terminal. */
	void show(UpgradeView newer) {
		if (newer.pos().equals(view.pos())) {
			upgrade = Optional.of(newer);
			rebuildWidgets();
		}
	}

	public Optional<UpgradeView> upgrade() {
		return upgrade;
	}

	public ComponentTrack selected() {
		return selected;
	}

	public Typewriter typewriter() {
		return typewriter;
	}

	@Override
	protected void layout() {
		int closeY = LIST_TOP + ComponentTrack.values().length * (ROW_HEIGHT + ROW_GAP) + ROW_GAP;
		addRenderableWidget(new CrtButton(MARGIN, closeY, CLOSE_WIDTH, ROW_HEIGHT,
				Component.translatable("screen.deepcharter.terminal.close"), button -> onClose()));
		if (upgrade.isEmpty() || upgrade.get().pod().isEmpty()) {
			return;
		}
		UpgradeView.Pod pod = upgrade.get().pod().get();
		int tierX = MARGIN + TRACK_WIDTH + COLUMN_GAP;
		int tierWidth = Math.min(width - MARGIN - tierX, TIER_MAX_WIDTH);
		for (UpgradeView.Slot slot : pod.slots()) {
			ComponentTrack track = slot.track();
			Component label = Component.translatable("screen.deepcharter.upgrade.track", trackName(track), slot.installed());
			addRenderableWidget(new CrtButton(MARGIN, LIST_TOP + track.ordinal() * (ROW_HEIGHT + ROW_GAP),
					TRACK_WIDTH, ROW_HEIGHT, track == selected ? Component.literal("> ").append(label) : label, pressed -> select(track)));
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
			CrtButton button = addRenderableWidget(new CrtButton(tierX, LIST_TOP + (tier - 1) * (ROW_HEIGHT + ROW_GAP),
					tierWidth, ROW_HEIGHT, label, pressed -> buy(selected, offered)));
			button.active = !here && balance >= price;
		}
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
		CrtTuning tuning = CrtTuning.DEFAULT;
		CrtDraw.glowText(graphics, font, title.getString().toUpperCase(Locale.ROOT), MARGIN, MARGIN, tuning.phosphorColor());
		CrtDraw.border(graphics, MARGIN - 6, MARGIN + font.lineHeight + 4, width - MARGIN + 6, MARGIN + font.lineHeight + 5, tuning.dimColor());
		int below = drawTypewriter(graphics, typewriter, MARGIN, MARGIN + font.lineHeight + 10, width - 2 * MARGIN);
		CrtDraw.glowText(graphics, font, status(), MARGIN, below, tuning.phosphorColor());
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	/** The pod, its tier cap and the account, or why there is no pod. */
	private String status() {
		if (upgrade.isEmpty()) {
			return Component.translatable("screen.deepcharter.upgrade.waiting").getString();
		}
		UpgradeView shown = upgrade.get();
		if (shown.pod().isEmpty()) {
			return Component.translatable(shown.foreignPod() ? "screen.deepcharter.upgrade.foreign_pod" : "screen.deepcharter.upgrade.no_pod").getString();
		}
		String account = ClientCharter.view()
				.map(charter -> Component.translatable("screen.deepcharter.terminal.account", charter.balance()).getString()).orElse("");
		UpgradeView.Pod pod = shown.pod().get();
		Component line = pod.serial().isEmpty()
				? Component.translatable("screen.deepcharter.upgrade.pod_unregistered", pod.cap())
				: Component.translatable("screen.deepcharter.upgrade.pod", pod.serial(), pod.cap());
		return line.getString() + "   " + account;
	}
}
