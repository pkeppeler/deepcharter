package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.core.BlockPos;

import io.github.pkeppeler.deepcharter.client.charter.ClientCharter;
import io.github.pkeppeler.deepcharter.test.support.ClientPacks;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.TerminalConceptScene;
import io.github.pkeppeler.deepcharter.test.support.TerminalConceptScene.Scene;
import io.github.pkeppeler.deepcharter.test.support.TerminalConceptScreens;
import io.github.pkeppeler.deepcharter.test.support.TestPacks;

/**
 * Evidence scenario "terminal-concepts" for #246 (docs/design/terminal-concepts.md): the real terminal screens, each in every panel
 * option and every font. A is today's screen with no pack. B to E are the panel test packs, each with the font it is shown in; F to H
 * are the same panel in the other two fonts, on the hangar console only. Stills are named {@code <look>-<screen>}, for example
 * {@code slab-unscii-hangar}. The hangar, the ore processor, the upgrade terminal and the repair station are shot in one world, the
 * contract terminal in another where the player has no charter.
 */
public class TerminalConceptsScenario extends EvidenceScenario {
	private static final int HOLD_FRAMES = 4;
	private static final int TICKS_PER_FRAME = 3;

	/** A panel pack and a font pack to turn on (either may be null), and the screens shot with them. */
	private record Look(String id, String panel, String font, List<String> screens) {
	}

	/** The panel options, each with the font it is shown in on every screen. */
	private static final List<String[]> OPTIONS = List.of(
			new String[] {"slab", TestPacks.TERMINAL_SLAB, "unscii"},
			new String[] {"console", TestPacks.TERMINAL_CONSOLE, "vt323"},
			new String[] {"rack", TestPacks.TERMINAL_RACK, "departure"},
			new String[] {"hatch", TestPacks.TERMINAL_HATCH, "unscii"});
	private static final List<String> FONTS = List.of("unscii", "vt323", "departure");

	@Override
	protected String name() {
		return "terminal-concepts";
	}

	private static String fontPack(String font) {
		return switch (font) {
			case "unscii" -> TestPacks.TERMINAL_FONT_UNSCII;
			case "vt323" -> TestPacks.TERMINAL_FONT_VT323;
			default -> TestPacks.TERMINAL_FONT_DEPARTURE;
		};
	}

	/** The looks in the order they are shot: a panel stays on while its fonts change, so each pack reloads once. {@code otherFonts} adds the hangar in the other two fonts. */
	private static List<Look> looks(List<String> screens, boolean otherFonts) {
		List<Look> looks = new ArrayList<>();
		looks.add(new Look("today", null, null, screens));
		for (String[] option : OPTIONS) {
			looks.add(new Look(option[0] + "-" + option[2], option[1], fontPack(option[2]), screens));
			for (String font : FONTS) {
				if (otherFonts && !font.equals(option[2])) {
					looks.add(new Look(option[0] + "-" + font, option[1], fontPack(font), screens.subList(0, 1)));
				}
			}
		}
		return looks;
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			Scene scene = singleplayer.getServer().computeOnServer(TerminalConceptScene::setUp);
			ClientWait.until(context, "the charter's account", client -> ClientCharter.view().map(charter -> charter.balance() == TerminalConceptScene.ACCOUNT).orElse(false),
					client -> "charter " + ClientCharter.view());
			shootLooks(context, looks(TerminalConceptScreens.SCENE_SCREENS, true), scene, null);
		}
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			BlockPos contract = singleplayer.getServer().computeOnServer(TerminalConceptScene::setUpContract);
			shootLooks(context, looks(List.of(TerminalConceptScreens.CONTRACT), false), null, contract);
		}
	}

	/** Shoots every look in order, changing packs only where the look changes them. */
	private void shootLooks(ClientGameTestContext context, List<Look> looks, Scene scene, BlockPos contract) {
		String panelOn = null;
		String fontOn = null;
		try {
			for (Look look : looks) {
				if (fontOn != null && !fontOn.equals(look.font())) {
					ClientPacks.disable(context, fontOn);
					fontOn = null;
				}
				if (panelOn != null && !panelOn.equals(look.panel())) {
					ClientPacks.disable(context, panelOn);
					panelOn = null;
				}
				if (panelOn == null && look.panel() != null) {
					ClientPacks.enable(context, look.panel());
					panelOn = look.panel();
				}
				if (fontOn == null && look.font() != null) {
					ClientPacks.enable(context, look.font());
					fontOn = look.font();
				}
				for (String screen : look.screens()) {
					TerminalConceptScreens.open(context, screen, scene, contract);
					hold(context);
					screenshot(context, look.id() + "-" + screen);
					TerminalConceptScreens.close(context);
				}
			}
		} finally {
			each(fontOn, pack -> ClientPacks.disable(context, pack));
			each(panelOn, pack -> ClientPacks.disable(context, pack));
		}
	}

	private static void each(String pack, Consumer<String> action) {
		if (pack != null) {
			action.accept(pack);
		}
	}

	private void hold(ClientGameTestContext context) {
		for (int i = 0; i < HOLD_FRAMES; i++) {
			context.waitTicks(TICKS_PER_FRAME);
			frame(context);
		}
	}
}
