package io.github.pkeppeler.deepcharter.test.support;

import java.util.concurrent.CompletableFuture;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.PackRepository;

/** Turns a {@link TestPacks} pack on or off and reloads the client's resources, which is what F3+T does. */
public final class ClientPacks {
	private ClientPacks() {
	}

	/** Selects the pack as the top pack, so its files win, and waits for the reload to finish. */
	public static void enable(ClientGameTestContext context, String pack) {
		change(context, pack, true);
	}

	/** Unselects the pack and waits for the reload to finish. */
	public static void disable(ClientGameTestContext context, String pack) {
		change(context, pack, false);
	}

	private static void change(ClientGameTestContext context, String pack, boolean on) {
		CompletableFuture<Void> reload = context.computeOnClient(client -> {
			PackRepository repository = client.getResourcePackRepository();
			repository.reload();
			String id = repository.getAvailableIds().stream().filter(candidate -> candidate.endsWith(pack)).findFirst()
					.orElseThrow(() -> new AssertionError("No resource pack ending in '" + pack + "' is available: " + repository.getAvailableIds()));
			boolean changed = on ? repository.addPack(id) : repository.removePack(id);
			if (!changed && on != repository.getSelectedIds().contains(id)) {
				throw new AssertionError("Could not " + (on ? "select" : "unselect") + " the resource pack " + id);
			}
			return Minecraft.getInstance().reloadResourcePacks();
		});
		context.waitFor(client -> reload.isDone());
		reload.join();
	}
}
