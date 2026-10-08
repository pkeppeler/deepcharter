package io.github.pkeppeler.deepcharter.client.theme;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.theme.ThemeData;
import io.github.pkeppeler.deepcharter.theme.ThemeData.Layer;

/**
 * Loads the UI theme with the client's resource packs, so F3+T re-reads it. Each area's file is read from every pack that has one, the
 * mod's own at the bottom, and merged key by key ({@link ThemeData}): a pack names only what it changes. A file that does not parse, or a
 * value that is not valid, fails the reload and names the pack, as a broken model does; vanilla then unselects every resource pack the player had on.
 */
final class UiThemeLoader extends SimplePreparableReloadListener<UiTheme> {
	static final Identifier ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "ui_theme");
	private static final String DIRECTORY = "theme";

	@Override
	protected UiTheme prepare(ResourceManager manager, ProfilerFiller profiler) {
		try {
			return load(manager);
		} catch (RuntimeException e) {
			// Vanilla reports a failed reload without the cause; the log line names the pack and key.
			DeepCharter.LOGGER.error("The UI theme did not load, so the resource reload fails: {}", e.getMessage());
			throw e;
		}
	}

	private static UiTheme load(ResourceManager manager) {
		Map<String, ThemeData> areas = new TreeMap<>();
		for (String area : UiTheme.AREAS) {
			Identifier file = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, DIRECTORY + "/" + area + ".json");
			// Lowest priority first, so the top pack's keys win.
			List<Layer> layers = new ArrayList<>();
			for (Resource resource : manager.getResourceStack(file)) {
				layers.add(new Layer(resource.sourcePackId() + ":" + file, read(resource)));
			}
			if (layers.isEmpty()) {
				throw new IllegalStateException("No pack has " + file + ": the mod ships it, so a pack must have removed it");
			}
			areas.put(area, ThemeData.parse(area, layers));
		}
		return UiTheme.of(areas);
	}

	@Override
	protected void apply(UiTheme theme, ResourceManager manager, ProfilerFiller profiler) {
		UiTheme.install(theme);
	}

	private static String read(Resource resource) {
		try (Reader reader = resource.openAsReader()) {
			return reader.readAllAsString();
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot read " + resource.sourcePackId(), e);
		}
	}
}
