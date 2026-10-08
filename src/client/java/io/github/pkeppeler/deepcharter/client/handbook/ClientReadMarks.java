package io.github.pkeppeler.deepcharter.client.handbook;

import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.handbook.HandbookRegistry;
import io.github.pkeppeler.deepcharter.handbook.ReadMarks;

/** The read marks the server synced to this client's player. Never throws on saved data it cannot read: nothing shows as read. */
public final class ClientReadMarks {
	private ClientReadMarks() {
	}

	public static boolean isRead(Identifier entry) {
		return read().contains(entry);
	}

	public static Set<Identifier> read() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			return Set.of();
		}
		return Versioned.readable(player, HandbookRegistry.READ_MARKS).map(ReadMarks::read).orElse(Set.of());
	}
}
