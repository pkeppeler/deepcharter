package io.github.pkeppeler.deepcharter.client.texture;

import net.fabricmc.fabric.api.client.model.loading.v1.CustomUnbakedBlockStateModel;

/** Client entry point for the texture system, called from DeepCharterClient: the connected-casing model type (ADR 0037). */
public final class TextureClientInit {
	private TextureClientInit() {
	}

	public static void init() {
		CustomUnbakedBlockStateModel.register(ConnectedBlockModel.TYPE, ConnectedBlockModel.Unbaked.CODEC);
	}
}
