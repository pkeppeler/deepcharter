package io.github.pkeppeler.deepcharter.sound;

import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

import io.github.pkeppeler.deepcharter.DeepCharter;

/**
 * Every Deep Charter sound event. The path is also the key in {@code sounds.json} and the id
 * in {@code tools/audio-pack-map.txt}; add a constant, a map line and a placeholder together
 * ({@code SoundEventsTest} fails if they drift apart).
 */
public enum DeepSound {
	POD_ENGINE_IDLE("pod.engine_idle"),
	POD_ENGINE_DRIVE("pod.engine_drive"),
	POD_ENGINE_DRILL_SIDE("pod.engine_drill_side"),
	POD_ENGINE_DRILL_DOWN("pod.engine_drill_down"),
	POD_ROTOR("pod.rotor"),
	POD_TRANSFORM_LAND("pod.transform_land"),
	POD_TRANSFORM_LAUNCH("pod.transform_launch"),
	POD_CRASH("pod.crash"),
	POD_EXPLOSION("pod.explosion"),
	DRILL_DIG("drill.dig"),
	DRILL_BLOCKED("drill.blocked"),
	DRILL_LAVA("drill.lava"),
	DRILL_DYNAMITE("drill.dynamite"),
	DRILL_PLASTIC("drill.plastic"),
	CARGO_COLLECT("cargo.collect"),
	CARGO_JETTISON("cargo.jettison"),
	CARGO_FULL("cargo.full"),
	FUEL_LOW("fuel.low"),
	FUEL_REFUEL("fuel.refuel"),
	REPAIR_NANOBOTS("repair.nanobots"),
	BREACH_RUMBLE("breach.rumble"),
	BREACH_CROSS("breach.cross"),
	SCANNER_PING("scanner.ping"),
	SCANNER_SWEEP("scanner.sweep"),
	UI_TYPEWRITER("ui.typewriter"),
	UI_HOVER("ui.hover"),
	UI_SELECT("ui.select"),
	UI_CONFIRM("ui.confirm"),
	UI_PURCHASE("ui.purchase"),
	UI_SALE("ui.sale"),
	UI_ERROR("ui.error"),
	UI_REFUSED("ui.refused"),
	TERMINAL_POWER_ON("terminal.power_on"),
	TERMINAL_TELEPORT("terminal.teleport"),
	TRANSMISSION_INCOMING("transmission.incoming"),
	TRANSMISSION_MENACE("transmission.menace"),
	MOTHERSHIP_ARRIVE("mothership.arrive"),
	MOTHERSHIP_IDLE("mothership.idle"),
	MOTHERSHIP_LEAVE("mothership.leave"),
	ALARM_FUEL_CRITICAL("alarm.fuel_critical"),
	ALARM_HULL_CRITICAL("alarm.hull_critical");

	private final String path;
	private final SoundEvent event;

	DeepSound(String path) {
		this.path = path;
		this.event = SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path));
	}

	public String path() {
		return path;
	}

	public SoundEvent event() {
		return event;
	}
}
