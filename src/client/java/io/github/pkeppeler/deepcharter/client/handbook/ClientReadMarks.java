package io.github.pkeppeler.deepcharter.client.handbook;

import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.handbook.HandbookRegistry;
import io.github.pkeppeler.deepcharter.handbook.ReadMarks;

/** The read marks the server synced to this client's player. Never throws on saved data it cannot read: nothing shows as read. */
public final class ClientReadMarks {
	private static boolean reported;

	private ClientReadMarks() {
	}

	/** Forgets that a problem was reported, so that the next world logs its own. Called when the client leaves a world. */
	static void reset() {
		reported = false;
	}

	public static boolean isRead(Identifier entry) {
		return read().contains(entry);
	}

	public static Set<Identifier> read() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			return Set.of();
		}
		return switch (player.getAttachedOrCreate(HandbookRegistry.READ_MARKS)) {
			case Versioned.Readable<ReadMarks> readable -> readable.value().read();
			case Versioned.Unreadable<ReadMarks> unreadable -> {
				if (!reported) {
					reported = true;
					DeepCharter.LOGGER.error("The handbook shows nothing as read: the read marks have version {}, which this build cannot read",
							unreadable.version());
				}
				yield Set.of();
			}
		};
	}
}
