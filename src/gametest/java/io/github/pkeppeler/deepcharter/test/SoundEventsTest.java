package io.github.pkeppeler.deepcharter.test;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
import org.jspecify.annotations.Nullable;

/** Server GameTests for #57: the sound events, the audio pack map and the placeholder sounds.json agree. */
public class SoundEventsTest {
    private static final String MAP = "tools/audio-pack-map.txt";
    /** The sounds that ship as files of ours (#249), the slots of the drone beds, the hymn and the machines; none may fall back to a vanilla event. */
    private static final Set<String> SHIPPED = Set.of(
            "ambient.surface_loop",
            "ambient.surface_groan",
            "ambient.layer_1_loop",
            "ambient.layer_1_creak",
            "ambient.layer_1_knock",
            "ambient.layer_2_loop",
            "ambient.layer_2_creak",
            "ambient.layer_2_knock",
            "hymn.colony",
            "hymn.layer_1",
            "hymn.layer_2",
            "pod.engine_idle",
            "pod.engine_drive",
            "pod.engine_drill_side",
            "pod.engine_drill_down",
            "pod.rotor",
            "drill.dig",
            "drill.blocked",
            "terminal.power_on",
            "ui.typewriter",
            "ui.hover",
            "ui.select",
            "ui.purchase",
            "ui.sale",
            "ui.error",
            "ui.refused",
            "music.terminal",
            "music.layer_1",
            "music.layer_2");

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
                .filter(sound -> !hasNoOriginal(sound))
                .forEach(sound -> declared.add(sound.event().location().getPath()));
        Set<String> onlyDeclared = new TreeSet<>(declared);
        onlyDeclared.removeAll(mapped);
        if (!onlyDeclared.isEmpty()) {
            throw failure(
                    helper,
                    "DeepSound ids missing from %s (a sound with no original is a drone bed or the hymn): %s",
                    MAP,
                    onlyDeclared);
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
        Set<String> placeholders = soundsJson(helper).keySet();
        for (DeepSound sound : DeepSound.values()) {
            String path = sound.event().location().getPath();
            if (!placeholders.contains(path)) {
                throw failure(helper, "sounds.json has no placeholder for %s", path);
            }
        }
        helper.succeed();
    }

    @GameTest
    public void aSoundWithNoOriginalIsNotInTheMapAndAMappedOneIs(GameTestHelper helper) throws IOException {
        Set<String> mapped = mappedIds(helper);
        for (DeepSound sound : DeepSound.values()) {
            String path = sound.event().location().getPath();
            if (hasNoOriginal(sound) == mapped.contains(path)) {
                throw failure(helper, "%s: the map %s it", path, mapped.contains(path) ? "lists" : "does not list");
            }
        }
        helper.succeed();
    }

    @GameTest
    public void everySoundThatIsShippedPointsAtAFileAndNoFileIsLeftUnused(GameTestHelper helper) throws IOException {
        JsonObject slots = soundsJson(helper);
        Set<String> referenced = new TreeSet<>();
        for (DeepSound sound : DeepSound.values()) {
            String path = sound.event().location().getPath();
            for (JsonElement entry : slots.getAsJsonObject(path).getAsJsonArray("sounds")) {
                String name = entry.isJsonObject()
                        ? entry.getAsJsonObject().get("name").getAsString()
                        : entry.getAsString();
                boolean event = entry.isJsonObject()
                        && entry.getAsJsonObject().has("type")
                        && "event".equals(entry.getAsJsonObject().get("type").getAsString());
                if (!name.startsWith(DeepCharter.MOD_ID + ":") || event) {
                    if (SHIPPED.contains(path)) {
                        throw failure(helper, "%s is a shipped sound but plays %s, not a file of ours", path, name);
                    }
                    continue;
                }
                String file = name.substring(DeepCharter.MOD_ID.length() + 1);
                referenced.add(file);
                if (SoundEventsTest.class.getResource("/assets/deepcharter/sounds/" + file + ".ogg") == null) {
                    throw failure(
                            helper,
                            "%s names %s, which is not a shipped file assets/deepcharter/sounds/%s.ogg",
                            path,
                            name,
                            file);
                }
            }
        }
        for (String path : SHIPPED) {
            if (!slots.has(path)) {
                throw failure(helper, "%s is listed as shipped but is not in sounds.json", path);
            }
        }
        Path sounds = repoRoot(helper).resolve("src/main/resources/assets/deepcharter/sounds");
        try (var files = Files.walk(sounds)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".ogg")).toList()) {
                String relative = sounds.relativize(file).toString().replace(".ogg", "");
                if (!referenced.contains(relative)) {
                    throw failure(helper, "the shipped file %s.ogg is named by no sound in sounds.json", relative);
                }
            }
        }
        helper.succeed();
    }

    /** The drone beds and the Lamp Hymn (#249) are ours alone: the original game has nothing to map them to. */
    private static boolean hasNoOriginal(DeepSound sound) {
        String path = sound.event().location().getPath();
        return path.startsWith("ambient.") || path.startsWith("hymn.");
    }

    private static JsonObject soundsJson(GameTestHelper helper) throws IOException {
        var stream = SoundEventsTest.class.getResourceAsStream("/assets/deepcharter/sounds.json");
        if (stream == null) {
            throw failure(helper, "assets/deepcharter/sounds.json is not on the classpath");
        }
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    /** The map is a repo file, not a resource. Game tests run in build/run/gameTest, so walk up to the repo. */
    private static Path repoRoot(GameTestHelper helper) {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isRegularFile(dir.resolve(MAP))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw failure(helper, "cannot find %s above %s", MAP, Path.of("").toAbsolutePath());
        }
        return dir;
    }

    private static Set<String> mappedIds(GameTestHelper helper) throws IOException {
        List<String> lines = Files.readAllLines(repoRoot(helper).resolve(MAP));
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
    private static RuntimeException failure(
            GameTestHelper helper, @FormatString String format, @Nullable Object... args) {
        return helper.assertionException(Component.literal(String.format(format, args)));
    }
}
