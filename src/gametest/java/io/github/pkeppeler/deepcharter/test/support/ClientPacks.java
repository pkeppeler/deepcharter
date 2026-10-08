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

	/**
	 * Selects the pack and reloads, for a pack whose reload must fail. Vanilla then unselects all packs and reloads again, so this
	 * returns once the game has settled again.
	 */
	public static void enableExpectingFailure(ClientGameTestContext context, String pack) {
		String id = context.computeOnClient(client -> {
			PackRepository repository = client.getResourcePackRepository();
			repository.reload();
			String found = repository.getAvailableIds().stream().filter(candidate -> candidate.endsWith(pack)).findFirst().orElseThrow();
			repository.addPack(found);
			Minecraft.getInstance().reloadResourcePacks();
			return found;
		});
		// A failed reload unselects every pack, and the reload's own future never completes, so watch the selection.
		context.waitFor(client -> !client.getResourcePackRepository().getSelectedIds().contains(id));
		context.waitTicks(20);
		context.waitFor(client -> client.gui.overlay() == null);
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
		// The reload overlay fades out after the reload is done; a still taken before that shows the loading logo.
		context.waitFor(client -> reload.isDone() && client.gui.overlay() == null);
		reload.join();
	}
}
