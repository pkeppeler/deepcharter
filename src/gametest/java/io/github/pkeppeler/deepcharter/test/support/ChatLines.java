package io.github.pkeppeler.deepcharter.test.support;

import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.chat.GuiMessage;

/**
 * The lines the client's chat has received, oldest first. {@code ChatComponent.getRecentChat} is what the player typed, not what the
 * server sent, so this reads the private {@code allMessages} list.
 */
public final class ChatLines {
    private ChatLines() {}

    public static List<String> received(Minecraft client) {
        try {
            Field field = client.gui.hud.getChat().getClass().getDeclaredField("allMessages");
            field.setAccessible(true);
            List<?> messages = (List<?>) field.get(client.gui.hud.getChat());
            return messages.stream()
                    .map(message -> ((GuiMessage) message).content().getString())
                    .toList();
        } catch (ReflectiveOperationException e) {
            throw new LinkageError("the chat has no allMessages list; the test reads it by name", e);
        }
    }
}
