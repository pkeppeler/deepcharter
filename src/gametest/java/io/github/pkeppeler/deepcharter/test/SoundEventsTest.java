package io.github.pkeppeler.deepcharter.test;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import com.google.gson.JsonParser;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.sound.DeepSound;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Server GameTests for #57: the sound events, the audio pack map and the placeholder sounds.json agree. */
public class SoundEventsTest {
    private static final String MAP = "tools/audio-pack-map.txt";

    @GameTest
    public void everyMappedIdIsRegistered(GameTestHelper helper) throws IOException {
        Set<String> ids = mappedIds(helper);
        if (ids.isEmpty()) {
            throw failure(helper, "%s lists no ids", MAP);
        }
        for (String id : ids) {
            Identifier key = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, id);
            if (!BuiltInRegistries.SOUND_EVENT.containsKey(key)) {
                throw failure(helper, "%s maps %s, but no sound event %s is registered", MAP, id, key);
            }
        }
        helper.succeed();
    }

    @GameTest
    public void theMapAndTheSoundEventsListTheSameIds(GameTestHelper helper) throws IOException {
        Set<String> mapped = mappedIds(helper);
        Set<String> declared = new TreeSet<>();
        Arrays.stream(DeepSound.values())
                .forEach(sound -> declared.add(sound.event().location().getPath()));
        Set<String> onlyDeclared = new TreeSet<>(declared);
        onlyDeclared.removeAll(mapped);
        if (!onlyDeclared.isEmpty()) {
            throw failure(helper, "DeepSound ids missing from %s: %s", MAP, onlyDeclared);
        }
        Set<String> onlyMapped = new TreeSet<>(mapped);
        onlyMapped.removeAll(declared);
        if (!onlyMapped.isEmpty()) {
            throw failure(helper, "%s ids missing from DeepSound: %s", MAP, onlyMapped);
        }
        helper.succeed();
    }

    @GameTest
    public void everySoundEventHasAPlaceholderSound(GameTestHelper helper) throws IOException {
        var stream = SoundEventsTest.class.getResourceAsStream("/assets/deepcharter/sounds.json");
        if (stream == null) {
            throw failure(helper, "assets/deepcharter/sounds.json is not on the classpath");
        }
        Set<String> placeholders;
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            placeholders = JsonParser.parseReader(reader).getAsJsonObject().keySet();
        }
        for (DeepSound sound : DeepSound.values()) {
            String path = sound.event().location().getPath();
            if (!placeholders.contains(path)) {
                throw failure(helper, "sounds.json has no placeholder for %s", path);
            }
        }
        helper.succeed();
    }

    /** The map is a repo file, not a resource. Game tests run in build/run/gameTest, so walk up to the repo. */
    private static Set<String> mappedIds(GameTestHelper helper) throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isRegularFile(dir.resolve(MAP))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw failure(helper, "cannot find %s above %s", MAP, Path.of("").toAbsolutePath());
        }
        List<String> lines = Files.readAllLines(dir.resolve(MAP));
        Set<String> ids = new LinkedHashSet<>();
        for (String line : lines) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                ids.add(trimmed.split("\\s+", 2)[0]);
            }
        }
        return ids;
    }

    @FormatMethod
    private static RuntimeException failure(GameTestHelper helper, @FormatString String format, Object... args) {
        return helper.assertionException(Component.literal(String.format(format, args)));
    }
}
