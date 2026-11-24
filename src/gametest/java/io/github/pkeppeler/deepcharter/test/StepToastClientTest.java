package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.client.handbook.StepToast;
import io.github.pkeppeler.deepcharter.handbook.HandbookChapter;
import io.github.pkeppeler.deepcharter.handbook.HandbookChapters;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.DrawnText;
import io.github.pkeppeler.deepcharter.test.support.DrawnText.Drawn;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;

/**
 * Client GameTest for #962: the popup of a finished step shows the step's whole line. Every directive of every shipped chapter is put in
 * a popup and drawn, and all of its text is on the slip, inside its border; so is a line longer than any shipped one, which a step
 * written later may be.
 */
public class StepToastClientTest implements FabricClientGameTest {
    private static final String LONG_LINE =
            "Carry the whole of a very long instruction through the hatch, past the second sump pump and "
                    + "the old tally board, down three ladders to the Company's first fuel depot, and sign for every barrel.";
    private static final String SAMPLE = "sample";

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            int shipped = singleplayer
                    .getServer()
                    .computeOnServer(
                            server -> shippedChapters(server.registryAccess()).size());
            ClientWait.until(
                    context,
                    "the client to know the " + shipped + " shipped chapters",
                    client -> client.getConnection() != null
                            && shippedChapters(client.getConnection().registryAccess())
                                            .size()
                                    == shipped);
            context.runOnClient(client -> {
                List<Component> lines = new ArrayList<>();
                shippedChapters(ClientSession.connection(client).registryAccess()).stream()
                        .flatMap(chapter -> chapter.value().directives().stream())
                        .map(HandbookChapter.Entry::text)
                        .forEach(lines::add);
                require(!lines.isEmpty(), "the client knows the shipped chapters' directives");
                lines.add(Component.literal(LONG_LINE));
                for (Component text : lines) {
                    requireWhole(client, text);
                }
            });
        }
    }

    private static List<Holder.Reference<HandbookChapter>> shippedChapters(HolderLookup.Provider registries) {
        return HandbookChapters.all(registries).stream()
                .filter(chapter -> !chapter.key().identifier().getPath().equals(SAMPLE))
                .toList();
    }

    /** The popup of {@code text} draws every word of it, each line inside the slip's border, with the head rule and "Directive complete". */
    private static void requireWhole(Minecraft client, Component text) {
        StepToast toast = StepToast.of(text);
        List<Drawn> drawn = DrawnText.of(graphics -> toast.draw(graphics, client.font));
        String head = Component.translatable("deepcharter.handbook.quest.toast").getString();
        require(!drawn.isEmpty() && drawn.getFirst().text().equals(head), "the popup is headed '" + head + "'");
        List<String> body = new ArrayList<>();
        for (Drawn line : drawn.subList(1, drawn.size())) {
            body.add(line.text());
            require(
                    line.box().x() >= 1
                            && line.box().right() <= toast.width() - 1
                            && line.box().y() >= 0
                            && line.box().bottom() <= toast.height() - 1,
                    "the popup of '" + text.getString() + "' is " + toast.width() + " x " + toast.height()
                            + ", but line '" + line.text() + "' is at " + line.box());
        }
        require(
                String.join(" ", body).equals(text.getString()),
                "the popup of '" + text.getString() + "' shows all of it, not '" + String.join(" ", body) + "'");
    }
}
