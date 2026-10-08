package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookPage;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookPages;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookScreen;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookScreenTuning;
import io.github.pkeppeler.deepcharter.handbook.HandbookChapter;
import io.github.pkeppeler.deepcharter.handbook.HandbookItems;

/**
 * Evidence scenario "m2-handbook-screen" for #66: the handbook opens from the item, then a four-chapter handbook (the first done,
 * the second current, the third next, the fourth classified) is paged from the cover to the end page, then the Notes tab.
 */
public class HandbookScreenScenario extends EvidenceScenario {
	private static final int CHAPTERS = 4;
	private static final int HOLD_FRAMES = 4;
	private static final int FLIP_FRAME_TICKS = 2;

	@Override
	protected String name() {
		return "m2-handbook-screen";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			context.waitFor(client -> handbookSlot(client.player.getInventory()) >= 0);
			context.runOnClient(client -> client.player.getInventory().setSelectedSlot(handbookSlot(client.player.getInventory())));
			context.getInput().pressKey(options -> options.keyUse);
			context.waitForScreen(HandbookScreen.class);
			context.waitTicks(HandbookScreenTuning.DEFAULT.flipTicks());
			hold(context);

			context.setScreen(() -> new HandbookScreen(HandbookPages.of(chapters(), done(1)), id -> id.getPath().equals("test2"), id -> { }, List.of()));
			context.waitForScreen(HandbookScreen.class);
			context.waitTicks(2);
			HandbookScreen screen = context.computeOnClient(client -> (HandbookScreen) client.gui.screen());
			int last = context.computeOnClient(client -> screen.pages().size() - 1);
			for (int page = 1; page <= last; page++) {
				int target = page;
				context.runOnClient(client -> screen.goTo(target));
				context.waitTicks(FLIP_FRAME_TICKS);
				frame(context);
				context.waitTicks(HandbookScreenTuning.DEFAULT.flipTicks());
				hold(context);
				if (page == 3) {
					screenshot(context, "contents");
				}
				if (screen.pages().get(page) instanceof HandbookPage.Chapter chapter) {
					screenshot(context, "chapter-" + chapter.number());
				}
			}
			context.runOnClient(client -> screen.showNotes());
			context.waitTicks(2);
			hold(context);
			screenshot(context, "notes-tab");
			context.setScreen(() -> null);
		}
	}

	private void hold(ClientGameTestContext context) {
		for (int frame = 0; frame < HOLD_FRAMES; frame++) {
			frame(context);
		}
	}

	private static Identifier directive(int chapter, int n) {
		return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "handbook/test/d" + chapter + "_" + n);
	}

	private static Set<Identifier> done(int... chapters) {
		Set<Identifier> done = new java.util.HashSet<>();
		for (int chapter : chapters) {
			done.add(directive(chapter, 1));
			done.add(directive(chapter, 2));
		}
		return done;
	}

	/** Four chapters. The first reuses the id {@code deepcharter:sample}, the fixture chapter of the real-game test. */
	private static Map<Identifier, HandbookChapter> chapters() {
		String[] titles = {"Welcome to the Colony", "Fixing the Terminals", "Driving the Mole", "The Deep"};
		Map<Identifier, HandbookChapter> chapters = new LinkedHashMap<>();
		for (int chapter = 1; chapter <= CHAPTERS; chapter++) {
			Identifier id = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, chapter == 1 ? "sample" : "test" + chapter);
			chapters.put(id, new HandbookChapter(chapter, Component.literal("PLACEHOLDER: " + titles[chapter - 1]),
					List.of(new HandbookChapter.Entry(directive(chapter, 1), Component.literal("PLACEHOLDER: first thing to do")),
							new HandbookChapter.Entry(directive(chapter, 2), Component.literal("PLACEHOLDER: second thing to do")))));
		}
		return chapters;
	}

	private static int handbookSlot(Inventory inventory) {
		for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
			if (HandbookItems.isHandbook(inventory.getItem(slot))) {
				return slot;
			}
		}
		return -1;
	}
}
