package io.github.pkeppeler.deepcharter.client.handbook;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * One line of the Notes tab: a Note the player's charter has found.
 *
 * @param id     the Note, which is also its key in the player's read marks
 * @param number the number printed beside the title (7 for N07)
 * @param title  what the list shows
 * @param text   what the Note says when it is opened
 */
public record HandbookNote(Identifier id, int number, Component title, Component text) {
}
