package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.CharterRefusal;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.charter.terminal.ContractState;
import io.github.pkeppeler.deepcharter.charter.terminal.ContractState.Role;
import io.github.pkeppeler.deepcharter.charter.terminal.ContractTerminal;
import io.github.pkeppeler.deepcharter.client.charter.terminal.ContractScreen;
import io.github.pkeppeler.deepcharter.client.terminal.TerminalScreens;
import io.github.pkeppeler.deepcharter.client.ui.CrtText;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.terminal.TerminalView;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.ContractShown;
import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Client GameTest for #72: the real client uses the contract terminal on a dedicated server with a mock player as the other
 * person. The client founds a charter by typing a name, the mock applies and is turned down, applies again and is approved,
 * the client (as Director) leaves, applies to the charter the mock now directs, is approved, and leaves as crew. Refusals show
 * their message on the screen.
 */
public class ContractTerminalClientTest implements FabricClientGameTest {
    private static final String CHARTER = "Riggs and Sons";
    private static final String MOCK_NAME = "MockPilot";
    private static final int GUI_WIDTH = 427;
    private static final int GUI_HEIGHT = 240;
    /** The content margin of ContractScreen. */
    private static final int MARGIN = 24;

    private static final int LONG_NAME = 32;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        aLongCharterNameIsCutAndTheLineKeepsItsEnd(context);
        try (TwoPlayerServer two = TwoPlayerServer.start(context)) {
            UUID mock = two.mock().player().getUUID();
            BlockPos terminal = two.server().computeOnServer(server -> {
                ServerPlayer real = server.getPlayerList().getPlayers().stream()
                        .filter(player -> !player.getUUID().equals(mock))
                        .findFirst()
                        .orElseThrow();
                BlockPos at = real.blockPosition().relative(Direction.EAST, 2);
                real.level().setBlock(at, ContractTerminal.TYPE.block().defaultBlockState(), 3);
                return at;
            });
            UUID real = two.server()
                    .computeOnServer(server -> server.getPlayerList().getPlayers().stream()
                            .map(ServerPlayer::getUUID)
                            .filter(uuid -> !uuid.equals(mock))
                            .findFirst()
                            .orElseThrow());

            ContractScreen screen = open(context, terminal);
            awaitState(context, ContractShown::hasNoCharter);
            require(
                    context.computeOnClient(client -> screen.statusLine().getString())
                            .contains("NO CHARTER"),
                    "a player with no charter is told so");

            refusalsShowTheirMessage(context);
            foundByTypingAName(context);
            Charter charter = two.server()
                    .computeOnServer(
                            server -> Charters.charterOfOrThrow(server, real).orElseThrow());
            require(
                    charter.name().equals(CHARTER) && charter.isDirector(real),
                    "the server has the typed charter, directed by the client, got " + charter);

            anotherPlayerAppliesAndIsDenied(context, two, mock, charter);
            anotherPlayerAppliesAndIsApproved(context, two, mock, real, charter);
            aDirectorLeavesAndAMemberLeaves(context, two, mock, real);
            aFormerMemberSeesTheDormantCharterAndRevivesIt(context, two, mock, real);
        }
    }

    private static ContractScreen open(ClientGameTestContext context, BlockPos terminal) {
        context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(terminal)));
        ClientWait.screen(context, ContractScreen.class);
        return context.computeOnClient(client -> (ContractScreen) client.gui.screen());
    }

    private static void awaitState(ClientGameTestContext context, Predicate<ContractScreen> wanted) {
        ClientWait.until(
                context,
                "the contract screen in the wanted state",
                client -> client.gui.screen() instanceof ContractScreen open && wanted.test(open));
    }

    private static ContractScreen screen(ClientGameTestContext context) {
        return context.computeOnClient(client -> (ContractScreen) client.gui.screen());
    }

    /**
     * #411: a Director of a charter with a 32-character name of wide letters would have a status line wider than the screen. The name is cut
     * and ended in "...", the text after it survives, and a short name is left whole. ("..." and not one ellipsis character, which the terminal font may lack.)
     */
    private static void aLongCharterNameIsCutAndTheLineKeepsItsEnd(ClientGameTestContext context) {
        context.runOnClient(client -> {
            TerminalView view = new TerminalView(
                    BlockPos.ZERO, ContractTerminal.TYPE.id(), true, true, List.of(), Optional.empty());
            ContractScreen screen = (ContractScreen) TerminalScreens.create(view);
            screen.init(GUI_WIDTH, GUI_HEIGHT);
            String name = "W".repeat(LONG_NAME);
            screen.show(
                    new ContractState(Role.DIRECTOR, name, List.of(), 0, List.of(), 0, List.of(), 7, Optional.empty()));
            String line = screen.statusLine().getString();
            require(
                    line.startsWith("DIRECTOR OF W") && line.contains("...") && !line.contains(name),
                    "a 32-letter name is cut and ended in '...', got '" + line + "'");
            require(
                    line.contains(". APPLICATIONS WAITING: 7."),
                    "the text after the name survives the cut, got '" + line + "'");
            require(
                    CrtText.width(client.font, line) <= GUI_WIDTH - 2 * MARGIN,
                    "the cut line fits the content width, '" + line + "' is " + CrtText.width(client.font, line));
            require(screen.statusLine().getString().equals(line), "the second read of the line is the same");
            screen.show(new ContractState(
                    Role.DIRECTOR, "ACME", List.of(), 0, List.of(), 0, List.of(), 7, Optional.empty()));
            require(
                    screen.statusLine().getString().startsWith("DIRECTOR OF ACME. APPLICATIONS WAITING: 7."),
                    "a short name is left whole, got '" + screen.statusLine().getString() + "'");
        });
    }

    private static void type(ClientGameTestContext context, String name) {
        context.runOnClient(client ->
                ContractShown.nameField((ContractScreen) client.gui.screen()).setValue(name));
    }

    private static String refusalShown(ClientGameTestContext context) {
        return context.computeOnClient(client -> ((ContractScreen) client.gui.screen())
                .refusal()
                .map(message -> message.getString())
                .orElse(""));
    }

    /** An empty name is refused by the server, and the screen shows why. */
    private static void refusalsShowTheirMessage(ClientGameTestContext context) {
        type(context, "");
        context.clickScreenButton("FOUND CHARTER");
        awaitState(context, open -> open.refusal().isPresent());
        String expected =
                context.computeOnClient(client -> Component.translatable(CharterRefusal.INVALID_NAME.translationKey())
                        .getString());
        require(
                refusalShown(context).contains(expected),
                "the blank name's refusal reads '" + expected + "', the screen shows '" + refusalShown(context) + "'");
        require(
                context.computeOnClient(client -> ContractShown.hasNoCharter(screen(context))),
                "a refused founding leaves the player on no charter");
    }

    private static void foundByTypingAName(ClientGameTestContext context) {
        type(context, CHARTER);
        context.clickScreenButton("FOUND CHARTER");
        awaitState(context, open -> ContractShown.directs(open, CHARTER));
        require(
                context.computeOnClient(client -> screen(context).refusal().isEmpty()),
                "the Director screen shows no refusal");
        require(
                context.computeOnClient(client -> screen(context).statusLine().getString())
                        .contains("DIRECTOR OF " + CHARTER),
                "the status says who directs what");
    }

    private static void anotherPlayerAppliesAndIsDenied(
            ClientGameTestContext context, TwoPlayerServer two, UUID mock, Charter charter) {
        two.server().runOnServer(server -> {
            require(Charters.apply(server, mock, charter.id()).isEmpty(), "the mock's application should succeed");
        });
        awaitState(context, open -> ContractShown.applicants(open).equals(List.of(MOCK_NAME.toUpperCase(Locale.ROOT))));
        context.clickScreenButton("DENY " + MOCK_NAME.toUpperCase(Locale.ROOT));
        awaitState(context, open -> ContractShown.applicants(open).isEmpty());
        boolean turnedDown = two.server()
                .computeOnServer(
                        server -> Charters.charterOfOrThrow(server, mock).isEmpty()
                                && Charters.findOrThrow(server, charter.id())
                                        .orElseThrow()
                                        .applications()
                                        .isEmpty());
        require(turnedDown, "a denied applicant is on no charter and has no application");
    }

    private static void anotherPlayerAppliesAndIsApproved(
            ClientGameTestContext context, TwoPlayerServer two, UUID mock, UUID real, Charter charter) {
        two.server().runOnServer(server -> Charters.apply(server, mock, charter.id()));
        awaitState(context, open -> ContractShown.applicants(open).equals(List.of(MOCK_NAME.toUpperCase(Locale.ROOT))));
        context.clickScreenButton("APPROVE " + MOCK_NAME.toUpperCase(Locale.ROOT));
        awaitState(context, open -> ContractShown.applicants(open).isEmpty());
        Charter after = two.server()
                .computeOnServer(
                        server -> Charters.charterOfOrThrow(server, mock).orElseThrow());
        require(
                after.id().equals(charter.id()) && !after.isDirector(mock) && after.isDirector(real),
                "the mock is crew of the client's charter");
    }

    /**
     * #168: the mock, now the only Director of the charter the client left, leaves too, and the charter is dormant. The client was crew
     * of it, so it offers REVIVE for it and no APPLY; a dormant charter the client was never in offers nothing. REVIVE makes the client
     * its Director.
     */
    private static void aFormerMemberSeesTheDormantCharterAndRevivesIt(
            ClientGameTestContext context, TwoPlayerServer two, UUID mock, UUID real) {
        String strangers = "Strangers Hold";
        two.server().runOnServer(server -> {
            require(Charters.leave(server, mock).isEmpty(), "the mock Director leaving should succeed");
            require(
                    Charters.found(server, UUID.randomUUID(), strangers).isEmpty(),
                    "the stranger's charter should be founded");
            UUID founder = Charters.findByName(server, strangers)
                    .orElseThrow()
                    .director()
                    .orElseThrow();
            require(Charters.leave(server, founder).isEmpty(), "the stranger's charter should go dormant");
        });
        awaitState(context, open -> ContractShown.hasNoCharter(open) && ContractShown.offersRevive(open, CHARTER));
        require(
                context.computeOnClient(client -> {
                    ContractScreen open = (ContractScreen) client.gui.screen();
                    return !ContractShown.offersRevive(open, strangers)
                            && !ContractShown.offers(open, strangers)
                            && !ContractShown.offers(open, CHARTER);
                }),
                "a dormant charter the player was never in is not offered, and a dormant one is not offered to apply to");

        context.clickScreenButton("REVIVE: " + CHARTER.toUpperCase(Locale.ROOT));
        awaitState(context, open -> ContractShown.directs(open, CHARTER));
        boolean directs = two.server()
                .computeOnServer(server ->
                        Charters.charterOfOrThrow(server, real).orElseThrow().isDirector(real));
        require(directs, "the former member now directs the revived charter on the server");
        context.setScreen(() -> null);
    }

    /** The Director leaves, so the mock takes over; the client applies to the mock's charter, is approved, and leaves as crew. */
    private static void aDirectorLeavesAndAMemberLeaves(
            ClientGameTestContext context, TwoPlayerServer two, UUID mock, UUID real) {
        context.clickScreenButton("LEAVE CHARTER");
        awaitState(context, open -> ContractShown.hasNoCharter(open) && ContractShown.offers(open, CHARTER));

        // Founding a name that is taken is refused with its message.
        type(context, CHARTER.toUpperCase(Locale.ROOT));
        context.clickScreenButton("FOUND CHARTER");
        awaitState(context, open -> open.refusal().isPresent());
        String taken =
                context.computeOnClient(client -> Component.translatable(CharterRefusal.NAME_TAKEN.translationKey())
                        .getString());
        require(
                refusalShown(context).contains(taken),
                "a taken name's refusal reads '" + taken + "', the screen shows '" + refusalShown(context) + "'");

        context.clickScreenButton("APPLY: " + CHARTER.toUpperCase(Locale.ROOT));
        awaitState(context, open -> ContractShown.applied(open, CHARTER));
        two.server().runOnServer(server -> {
            require(Charters.approve(server, mock, real).isEmpty(), "the mock Director's approval should succeed");
        });
        awaitState(context, ContractShown::isCrew);

        context.clickScreenButton("LEAVE CHARTER");
        awaitState(context, ContractShown::hasNoCharter);
        boolean left = two.server()
                .computeOnServer(
                        server -> Charters.charterOfOrThrow(server, real).isEmpty());
        require(left, "the member left the charter on the server");
    }
}
