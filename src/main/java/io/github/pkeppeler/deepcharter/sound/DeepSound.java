package io.github.pkeppeler.deepcharter.sound;

import io.github.pkeppeler.deepcharter.DeepCharter;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

/**
 * Every Deep Charter sound event. The path is also the key in {@code sounds.json} and the id
 * in {@code tools/audio-pack-map.txt}; add a constant, a map line and a sound together
 * ({@code SoundEventsTest} fails if they drift apart). A sound the original game has no counterpart of (the drone beds {@code ambient.*} and the Lamp
 * Hymn {@code hymn.*}, #249) has no map line: the private audio pack leaves it alone ({@code SoundEventsTest} names those prefixes).
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
    POD_HULL_BURNING("pod.hull_burning"),
    POD_LINING_PLACE("pod.lining_place"),
    POD_GLAZE_BURN("pod.glaze_burn"),
    POD_SEEP_HISS("pod.seep_hiss"),
    POD_SIGNAL_CRACKLE("pod.signal_crackle"),
    POD_SIGNAL_LOST("pod.signal_lost"),
    CREATURE_APPROACH("creature.approach"),
    CREATURE_HURT("creature.hurt"),
    CREATURE_DEATH("creature.death"),
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
    ALARM_HULL_CRITICAL("alarm.hull_critical"),
    MUSIC_LAYER_1("music.layer_1"),
    MUSIC_LAYER_2("music.layer_2"),
    MUSIC_TERMINAL("music.terminal"),
    AMBIENT_SURFACE_LOOP("ambient.surface_loop"),
    AMBIENT_SURFACE_GROAN("ambient.surface_groan"),
    AMBIENT_LAYER_1_LOOP("ambient.layer_1_loop"),
    AMBIENT_LAYER_1_CREAK("ambient.layer_1_creak"),
    AMBIENT_LAYER_1_KNOCK("ambient.layer_1_knock"),
    AMBIENT_LAYER_2_LOOP("ambient.layer_2_loop"),
    AMBIENT_LAYER_2_CREAK("ambient.layer_2_creak"),
    AMBIENT_LAYER_2_KNOCK("ambient.layer_2_knock"),
    HYMN_COLONY("hymn.colony"),
    HYMN_LAYER_1("hymn.layer_1"),
    HYMN_LAYER_2("hymn.layer_2");

    private final SoundEvent event;

    DeepSound(String path) {
        this.event = SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path));
    }

    public SoundEvent event() {
        return event;
    }
}
