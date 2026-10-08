package io.github.pkeppeler.deepcharter.test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import io.github.pkeppeler.deepcharter.client.handbook.HandbookScreenTuning;
import io.github.pkeppeler.deepcharter.client.theme.BreachLook;
import io.github.pkeppeler.deepcharter.client.theme.CargoLook;
import io.github.pkeppeler.deepcharter.client.theme.HudLook;
import io.github.pkeppeler.deepcharter.client.theme.ScannerLook;
import io.github.pkeppeler.deepcharter.client.theme.TransmissionLook;
import io.github.pkeppeler.deepcharter.client.ui.CrtDemoScreen;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;
import io.github.pkeppeler.deepcharter.test.support.ClientPacks;
import io.github.pkeppeler.deepcharter.test.support.TestPacks;

/**
 * Client GameTest for #226: the UI theme loads with the client's resources, today's look is the default, and a resource pack that names
 * one colour changes that colour on the next reload (which is what F3+T does) and nothing else, on screen as well as in the record.
 */
public class UiThemeClientTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);

		// The default asset is today's look. The values are written out here, not read from the asset, so a changed default fails.
		CrtTuning crt = context.computeOnClient(client -> CrtTuning.current());
		expect("default phosphor", 0xFF7CFC9A, crt.phosphorColor());
		expect("default dim", 0xFF2E7A45, crt.dimColor());
		expect("default background", 0xFF050A06, crt.backgroundColor());
		expect("default scanline", 0x58000000, crt.scanlineColor());
		expect("default glow", 0x307CFC9A, crt.glowColor());
		expect("default letters per second", 40.0, crt.lettersPerSecond());
		HandbookScreenTuning handbook = context.computeOnClient(client -> HandbookScreenTuning.current());
		expect("default paper", 0xFFF1E4C3, handbook.paperColor());
		expect("default flip ticks", 6, handbook.flipTicks());
		expect("default scanner gold", 0xFFFFD21E, context.computeOnClient(client -> ScannerLook.current()).goldOreColor());
		expect("default pod status colour", 0xFFFFFFFF, context.computeOnClient(client -> HudLook.current()).podStatusColor());
		expect("default relay colour", 0xFFFFC857, context.computeOnClient(client -> TransmissionLook.current()).relayColor());
		expect("default fade ticks", 20, context.computeOnClient(client -> BreachLook.current()).fadeTicks());
		expect("default cargo text", 0xFF404040, context.computeOnClient(client -> CargoLook.current()).textColor());
		expect("default screen background pixel", 0x050A06, backgroundPixel(context, "ui-theme-default"));

		// A pack that names six keys of one area.
		ClientPacks.enable(context, TestPacks.AMBER_CRT);
		try {
			CrtTuning amber = context.computeOnClient(client -> CrtTuning.current());
			expect("pack phosphor", 0xFFFFB000, amber.phosphorColor());
			expect("pack background", 0xFF0A0600, amber.backgroundColor());
			expect("pack glow keeps its alpha", 0x30FFB000, amber.glowColor());
			expect("a key the pack leaves out keeps the mod's value", 40.0, amber.lettersPerSecond());
			expect("so does a number", 6, amber.padding());
			expect("so does a colour", 0x58000000, amber.scanlineColor());
			expect("another area is untouched", 0xFFF1E4C3, context.computeOnClient(client -> HandbookScreenTuning.current()).paperColor());
			expect("the screen is drawn with the pack's background", 0x0A0600, backgroundPixel(context, "ui-theme-amber"));
		} finally {
			ClientPacks.disable(context, TestPacks.AMBER_CRT);
		}

		// Off again, back to the default, on screen too.
		expect("phosphor after the pack is off", 0xFF7CFC9A, context.computeOnClient(client -> CrtTuning.current()).phosphorColor());
		expect("screen background pixel after the pack is off", 0x050A06, backgroundPixel(context, "ui-theme-restored"));
	}

	/**
	 * The RGB of a pixel at the right edge, halfway down, on a row that is not a scanline, of the CRT demo screen: the screen
	 * background, which neither the bloom (top and bottom edges) nor the text (top left) reaches.
	 */
	private static int backgroundPixel(ClientGameTestContext context, String shotName) {
		context.setScreen(CrtDemoScreen::new);
		context.waitForScreen(CrtDemoScreen.class);
		context.waitTicks(2);
		int[] gui = context.computeOnClient(client -> new int[] {client.getWindow().getGuiScaledWidth(), client.getWindow().getGuiScaledHeight()});
		Path shot = context.takeScreenshot(shotName);
		context.setScreen(() -> null);
		try {
			BufferedImage image = ImageIO.read(shot.toFile());
			int scale = image.getWidth() / gui[0];
			int guiY = (gui[1] / 2) | 1;
			return image.getRGB(image.getWidth() - 2 * scale, guiY * scale + scale / 2) & 0xFFFFFF;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void expect(String what, Object expected, Object actual) {
		if (!expected.equals(actual)) {
			throw new AssertionError("%s: expected %s, got %s".formatted(what, hex(expected), hex(actual)));
		}
	}

	private static String hex(Object value) {
		return value instanceof Integer number ? "0x%08X".formatted(number) : String.valueOf(value);
	}
}
