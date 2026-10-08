package io.github.pkeppeler.deepcharter.test.evidence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;

import io.github.pkeppeler.deepcharter.test.ClientTestLog;

/**
 * Base class for PR evidence scenarios. Run with tools/record-evidence.sh, which sets
 * DEEPCHARTER_EVIDENCE to the scenario name and DEEPCHARTER_EVIDENCE_DIR to build/evidence.
 * A plain runClientGameTest skips every scenario.
 *
 * Output (rendered inside the game only, never captured from the desktop):
 *   build/evidence/NAME/frames/frame-0001.png ...   frame sequence, assembled by ffmpeg
 *   build/evidence/NAME/screenshots/*.png            still screenshots
 */
public abstract class EvidenceScenario implements FabricClientGameTest {
	private static final int WIDTH = 800;
	private static final int HEIGHT = 450;

	private int frame;
	private Path framesDir;
	private Path screenshotsDir;

	/** The scenario name: also the output directory name and the argument to record-evidence.sh. */
	protected abstract String name();

	/** Drive the game, calling {@link #frame} and {@link #screenshot} as things happen. */
	protected abstract void run(ClientGameTestContext context);

	@Override
	public final void runTest(ClientGameTestContext context) {
		if (!name().equals(System.getenv("DEEPCHARTER_EVIDENCE"))) {
			return;
		}
		ClientTestLog.start(this);
		String root = System.getenv("DEEPCHARTER_EVIDENCE_DIR");
		if (root == null) {
			throw new IllegalStateException("DEEPCHARTER_EVIDENCE_DIR is not set; run via tools/record-evidence.sh");
		}
		Path out = Path.of(root).toAbsolutePath().resolve(name());
		framesDir = out.resolve("frames");
		screenshotsDir = out.resolve("screenshots");
		try {
			deleteTree(out);
			Files.createDirectories(framesDir);
			Files.createDirectories(screenshotsDir);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		run(context);
		if (frame == 0) {
			throw new AssertionError("Scenario " + name() + " produced no frames");
		}
	}

	/** Add one frame to the recording. */
	protected final void frame(ClientGameTestContext context) {
		frame++;
		shoot(context, String.format("frame-%04d", frame), framesDir);
	}

	/** Take a named still screenshot. */
	protected final void screenshot(ClientGameTestContext context, String screenshotName) {
		shoot(context, screenshotName, screenshotsDir);
	}

	private static void shoot(ClientGameTestContext context, String fileName, Path dir) {
		context.takeScreenshot(TestScreenshotOptions.of(fileName)
				.disableCounterPrefix()
				.withSize(WIDTH, HEIGHT)
				.withDestinationDir(dir));
	}

	private static void deleteTree(Path dir) throws IOException {
		if (!Files.exists(dir)) {
			return;
		}
		try (var paths = Files.walk(dir)) {
			for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(p);
			}
		}
	}
}
