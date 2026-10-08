package io.github.pkeppeler.deepcharter.sound;

import java.util.Map;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

import io.github.pkeppeler.deepcharter.fuel.FuelPump;
import io.github.pkeppeler.deepcharter.hangar.HangarTerminal;
import io.github.pkeppeler.deepcharter.market.OreProcessor;
import io.github.pkeppeler.deepcharter.repair.RepairStation;
import io.github.pkeppeler.deepcharter.terminal.TerminalEvents;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeTerminal;

/**
 * The sound of a terminal button, played to the player who pressed it and to no one else: the sale and purchase sounds of the
 * actions below, and the error sound of every refusal. An action not listed here makes no sound of its own.
 */
public final class TerminalSounds {
	private static final Map<Identifier, DeepSound> ACTIONS = Map.of(
			OreProcessor.SELL_CARGO, DeepSound.UI_SALE,
			OreProcessor.SELL_INVENTORY, DeepSound.UI_SALE,
			FuelPump.BUY, DeepSound.UI_PURCHASE,
			FuelPump.FILL, DeepSound.UI_PURCHASE,
			UpgradeTerminal.BUY, DeepSound.UI_PURCHASE,
			RepairStation.BUY, DeepSound.UI_PURCHASE,
			HangarTerminal.BUY_MOLE, DeepSound.UI_PURCHASE,
			HangarTerminal.RESTORE_WRECK, DeepSound.UI_PURCHASE);

	private TerminalSounds() {
	}

	static void init() {
		TerminalEvents.ACTED.register((server, type, player, action) -> {
			DeepSound sound = ACTIONS.get(action);
			if (sound != null) {
				playTo(player, sound);
			}
		});
		TerminalEvents.REFUSED.register((player, refusal) -> playTo(player, DeepSound.UI_ERROR));
	}

	private static void playTo(ServerPlayer player, DeepSound sound) {
		Holder<SoundEvent> event = BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound.event());
		player.connection.send(new ClientboundSoundEntityPacket(event, SoundSource.PLAYERS, player, 1f, 1f, player.getRandom().nextLong()));
	}
}
