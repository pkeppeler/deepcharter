package io.github.pkeppeler.deepcharter.client.theme;

/**
 * Marks a screen that sits in the machine panel ({@link PanelLook}): a CRT terminal. {@link PanelLook#drawn()} is true only while such a screen is open, so
 * the HUD and every other screen keep the game's own font and look.
 */
public interface PanelScreen {}
