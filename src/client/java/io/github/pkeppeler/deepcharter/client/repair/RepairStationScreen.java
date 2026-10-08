package io.github.pkeppeler.deepcharter.client.repair;

import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.client.charter.ClientCharter;
import io.github.pkeppeler.deepcharter.client.sound.TypewriterSound;
import io.github.pkeppeler.deepcharter.client.terminal.TerminalViewScreen;
import io.github.pkeppeler.deepcharter.client.ui.CrtButton;
import io.github.pkeppeler.deepcharter.client.ui.CrtDraw;
import io.github.pkeppeler.deepcharter.client.ui.CrtScreen;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;
import io.github.pkeppeler.deepcharter.client.ui.Typewriter;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.repair.RepairRegistry;
import io.github.pkeppeler.deepcharter.repair.RepairStation;
import io.github.pkeppeler.deepcharter.repair.RepairTuning;
import io.github.pkeppeler.deepcharter.terminal.TerminalActionPayload;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.TerminalView;

/**
 * The online repair station: hull repair buttons on the left, the shop on the right. The screen never decides anything: it
 * sends the press and the server checks it, charges the account and answers with a new {@link TerminalView}.
 */
public final class RepairStationScreen extends CrtScreen implements TerminalViewScreen {
	private static final int MARGIN = 24;
	private static final int COLUMN_WIDTH = 190;
	private static final int BUTTON_HEIGHT = 20;
	private static final int GAP = 6;
	private static final int CLOSE_WIDTH = 90;
	private static final int[] REPAIR_STEPS = {10, 25, 50};

	private TerminalView view;
	private final Typewriter typewriter;

	public RepairStationScreen(TerminalView view) {
		super(Component.translatable(TerminalTypes.REPAIR_STATION.block().getDescriptionId()));
		this.view = view;
		this.typewriter = typewriter(Component.translatable("screen.deepcharter.repair.intro"), new TypewriterSound());
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

	@Override
	protected void layout() {
		int top = MARGIN + font.lineHeight + 14 + 3 * font.lineHeight + 3 * GAP;
		int shopX = width - MARGIN - COLUMN_WIDTH;
		int row = 0;
		for (int hp : REPAIR_STEPS) {
			long cost = hp * RepairTuning.DEFAULT.repairCostPerHp();
			CompoundTag args = new CompoundTag();
			args.putInt(RepairStation.HP_KEY, hp);
			addButton(MARGIN, top + row++ * (BUTTON_HEIGHT + GAP), Component.translatable("screen.deepcharter.repair.repair", hp, cost), RepairStation.REPAIR, args);
		}
		addButton(MARGIN, top + row * (BUTTON_HEIGHT + GAP), Component.translatable("screen.deepcharter.repair.total"), RepairStation.REPAIR_TOTAL, new CompoundTag());
		int shopRow = 0;
		for (Consumable consumable : Consumable.values()) {
			CompoundTag args = new CompoundTag();
			args.putString(RepairStation.ITEM_KEY, consumable.itemId().toString());
			String name = new ItemStack(RepairRegistry.item(consumable)).getHoverName().getString().toUpperCase(Locale.ROOT);
			addButton(shopX, top + shopRow++ * (BUTTON_HEIGHT + GAP), Component.translatable("screen.deepcharter.repair.buy", name, consumable.price()),
					RepairStation.BUY, args);
		}
		addRenderableWidget(new CrtButton(MARGIN, height - MARGIN - BUTTON_HEIGHT, CLOSE_WIDTH, BUTTON_HEIGHT,
				Component.translatable("screen.deepcharter.terminal.close"), button -> onClose()));
	}

	private void addButton(int x, int y, Component label, Identifier action, CompoundTag args) {
		addRenderableWidget(new CrtButton(x, y, COLUMN_WIDTH, BUTTON_HEIGHT, label,
				pressed -> ClientPlayNetworking.send(new TerminalActionPayload(view.pos(), action, args))));
	}

	/** The pod the server would repair, as far as the client can tell: the nearest in reach. The server decides. */
	private Optional<PodEntity> nearbyPod() {
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) {
			return Optional.empty();
		}
		double radius = RepairTuning.DEFAULT.parkRadius();
		var centre = Vec3.atCenterOf(view.pos());
		return client.level.getEntitiesOfClass(PodEntity.class, new AABB(view.pos()).inflate(radius)).stream()
				.min(Comparator.comparingDouble(pod -> pod.position().distanceToSqr(centre)));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		CrtTuning tuning = CrtTuning.DEFAULT;
		CrtDraw.glowText(graphics, font, title.getString().toUpperCase(Locale.ROOT), MARGIN, MARGIN, tuning.phosphorColor());
		CrtDraw.border(graphics, MARGIN - 6, MARGIN + font.lineHeight + 4, width - MARGIN + 6, MARGIN + font.lineHeight + 5, tuning.dimColor());
		int below = drawTypewriter(graphics, typewriter, MARGIN, MARGIN + font.lineHeight + 14, width - 2 * MARGIN);
		String account = ClientCharter.view()
				.map(charter -> Component.translatable("screen.deepcharter.terminal.account", charter.balance()).getString()).orElse("");
		CrtDraw.glowText(graphics, font, account, MARGIN, below + GAP, tuning.phosphorColor());
		Component hull = nearbyPod()
				.map(pod -> Component.translatable("screen.deepcharter.repair.hull", Math.round(pod.hull()), Math.round(pod.maxHull())))
				.orElse(Component.translatable("screen.deepcharter.repair.no_pod"));
		CrtDraw.glowText(graphics, font, hull.getString().toUpperCase(Locale.ROOT), MARGIN, below + GAP + font.lineHeight + GAP, tuning.phosphorColor());
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}
}
