package io.github.pkeppeler.deepcharter.test.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The client classes one of several client JVMs runs side by side in a CI job (#1722,
 * gradle/client-instances.gradle.kts). Gradle writes every instance's slice to {@link #FILE} and starts instance k with
 * {@code -D}{@value #PROPERTY}{@code =k}; the test mixin {@code FabricClientGameTestRunnerMixin} passes Fabric's list
 * of client tests through {@link #keep}. Without the property (every local run, {@code runClientGameTest}, an evidence
 * recording) the list is untouched.
 */
public final class ClientInstances {
    public static final String PROPERTY = "deepcharter.clientInstance";
    static final String FILE = "deepcharter-client-instances.txt";
    private static final Logger LOGGER = LoggerFactory.getLogger("deepcharter-test");

    private ClientInstances() {}

    /** The tests of {@code all} this JVM's instance runs: all of them when it is no instance. */
    public static List<EntrypointContainer<FabricClientGameTest>> keep(
            List<EntrypointContainer<FabricClientGameTest>> all) {
        String instance = System.getProperty(PROPERTY);
        if (instance == null) {
            return all;
        }
        Set<String> mine = slice(instance, read());
        List<EntrypointContainer<FabricClientGameTest>> kept =
                all.stream().filter(test -> mine.contains(test.getDefinition())).toList();
        if (kept.size() != mine.size()) {
            throw new IllegalStateException("client instance " + instance + " should run " + mine
                    + " but Fabric lists only "
                    + kept.stream().map(EntrypointContainer::getDefinition).toList());
        }
        LOGGER.info("Client instance {} runs {} of the {} client classes: {}", instance, kept.size(), all.size(), mine);
        return kept;
    }

    /** The classes of instance {@code instance} in {@code lines} ({@code <instance> <class>} each). */
    static Set<String> slice(String instance, String lines) {
        return lines.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .map(line -> line.split(" ", 2))
                .filter(parts -> parts[0].equals(instance))
                .map(parts -> parts[1])
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String read() {
        @Nullable InputStream in = ClientInstances.class.getClassLoader().getResourceAsStream(FILE);
        if (in == null) {
            throw new IllegalStateException(PROPERTY + " is set but " + FILE
                    + " is missing: run the instances through runClientGameTestInstances (-PclientInstances=N)");
        }
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + FILE, e);
        }
    }
}
