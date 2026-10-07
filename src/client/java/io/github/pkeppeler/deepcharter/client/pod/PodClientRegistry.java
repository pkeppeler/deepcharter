package io.github.pkeppeler.deepcharter.client.pod;

import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

import io.github.pkeppeler.deepcharter.pod.PodRegistry;

public final class PodClientRegistry {
	private PodClientRegistry() {
	}

	public static void register() {
		EntityRendererRegistry.register(PodRegistry.POD, PodRenderer::new);
	}
}
