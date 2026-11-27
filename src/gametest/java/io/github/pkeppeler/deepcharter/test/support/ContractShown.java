package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.client.charter.terminal.ContractScreen;
import io.github.pkeppeler.deepcharter.client.ui.CrtTextField;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * What the contract screen shows a player: the status line it draws and the buttons and the name field in its widget tree. The server's
 * state is not read, so a test holds the screen to what the player sees. Client thread.
 */
public final class ContractShown {
    private static final String DENY = "DENY ";
    private static final String APPLY = "APPLY: ";
    private static final String REVIVE = "REVIVE: ";

    private ContractShown() {}

    /** True once the server's first state has come: the screen no longer shows only the greeting. */
    public static boolean loaded(ContractScreen screen) {
        return !status(screen)
                .equals(Component.translatable("screen.deepcharter.contract.loading")
                        .getString());
    }

    /** True when the status line says the player has no charter. */
    public static boolean hasNoCharter(ContractScreen screen) {
        return status(screen)
                .startsWith(Component.translatable("screen.deepcharter.contract.status.none")
                        .getString());
    }

    /** True when the status line says the player directs {@code charter}. */
    public static boolean directs(ContractScreen screen, String charter) {
        return status(screen).startsWith("DIRECTOR OF " + charter + ".");
    }

    /** True when the status line says the player's application to {@code charter} is pending. */
    public static boolean applied(ContractScreen screen, String charter) {
        return status(screen)
                .equals(Component.translatable("screen.deepcharter.contract.status.applicant", charter)
                        .getString());
    }

    /** True when the status line says the player is crew. */
    public static boolean isCrew(ContractScreen screen) {
        return status(screen).startsWith("CREW OF ");
    }

    /** True when the Director's list offers {@code charter} to apply to. */
    public static boolean offers(ContractScreen screen, String charter) {
        return labels(screen).contains(APPLY + charter.toUpperCase(Locale.ROOT));
    }

    /** True when the screen has a button to revive {@code charter}. */
    public static boolean offersRevive(ContractScreen screen, String charter) {
        return labels(screen).contains(REVIVE + charter.toUpperCase(Locale.ROOT));
    }

    /** True when the screen has a button labelled exactly {@code label}. */
    public static boolean hasButton(ContractScreen screen, String label) {
        return labels(screen).contains(label);
    }

    /** The applicants on the Director's list, in capitals as the buttons show them. */
    public static List<String> applicants(ContractScreen screen) {
        return labels(screen).stream()
                .filter(label -> label.startsWith(DENY))
                .map(label -> label.substring(DENY.length()))
                .toList();
    }

    /** The field a new charter's name is typed in. */
    public static CrtTextField nameField(ContractScreen screen) {
        return screen.children().stream()
                .filter(CrtTextField.class::isInstance)
                .map(CrtTextField.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the contract screen has no name field: " + status(screen)));
    }

    private static String status(ContractScreen screen) {
        return screen.statusLine().getString();
    }

    private static List<String> labels(ContractScreen screen) {
        return screen.children().stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .map(button -> button.getMessage().getString())
                .toList();
    }
}
