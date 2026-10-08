package io.github.pkeppeler.deepcharter.client.theme;

import net.fabricmc.fabric.api.resource.v1.ResourceLoader;

import net.minecraft.server.packs.PackType;

/** Client entry point for the UI theme, called from DeepCharterClient (ADR 0032). */
public final class ThemeClientInit {
	private ThemeClientInit() {
	}

	public static void init() {
		ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(UiThemeLoader.ID, new UiThemeLoader());
	}
}
