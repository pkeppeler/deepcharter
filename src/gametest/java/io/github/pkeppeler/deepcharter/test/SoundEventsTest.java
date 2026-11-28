package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.sound.DeepSound;
import io.github.pkeppeler.deepcharter.sound.SoundTuning;
import io.github.pkeppeler.deepcharter.sound.phonograph.Recording;
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
import net.minecraft.resources.Identifier;

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
            "breach.rumble",
            "cave_in.collapse",
            "hymn.chapter",
            "phonograph.gamme_de_la_voix",
            "phonograph.jeune_jouvencelle",
            "phonograph.othello",
            "song.wreck",
            "song.dark",
            "fuel.low",
            "alarm.fuel_critical",
            "continuity.laugh",
            "continuity.needle",
            "continuity.lamp_out",
            "pod.engine_idle",
            "pod.engine_drive",
            "pod.engine_drill_side",
            "pod.engine_drill_down",
            "pod.rotor",
            "drill.dig",
            "drill.blocked",
            "terminal.power_on",
            "terminal.online",
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
                    if (SHIPPED.contains(path) || path.startsWith("ambience.")) {
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

    /**
     * The drone beds, the Lamp Hymn (#249), the cave-in (#1293), the wax cylinders' recordings (#864, made by tools/sounds/first_sounds.py) and the ambience's
     * fragments (#866, tools/sounds/ambience.py) and the two variants of "Au clair de la lune" (#861, {@code song.*}, made by first_sounds.py) and the Continuity Event's laugh, needle and lamp (#1337, {@code continuity.*}) are ours alone: the original game has nothing to map them to.
     * So is the chord of a machine coming online (#1267), the one terminal sound with no original.
     */
    private static boolean hasNoOriginal(DeepSound sound) {
        String path = sound.event().location().getPath();
        return path.startsWith("ambient.")
                || path.startsWith("hymn.")
                || path.startsWith("cave_in.")
                || path.startsWith("phonograph.")
                || path.startsWith("song.")
                || path.startsWith("ambience.")
                || path.equals("terminal.online")
                || path.startsWith("continuity.");
    }

    /**
     * Each recording's file is mono (Minecraft positions only mono sounds), is in the manifest that the audio gate checks, and lasts as long
     * as the game thinks: the length in ticks that {@link Recording} plays for is the file's, rounded up.
     */
    @GameTest
    public void everyRecordingIsMonoAndAsLongAsTheGameCounts(GameTestHelper helper) throws IOException {
        Path root = repoRoot(helper);
        JsonObject manifest;
        try (Reader reader = Files.newBufferedReader(root.resolve("tools/sounds/first_sounds.json"))) {
            manifest = JsonParser.parseReader(reader).getAsJsonObject();
        }
        Set<String> names = new TreeSet<>();
        for (Recording recording : Recording.values()) {
            String name = recording.getSerializedName();
            names.add(name);
            Path file = root.resolve("src/main/resources/assets/deepcharter/sounds/phonograph/" + name + ".ogg");
            byte[] head = Arrays.copyOf(Files.readAllBytes(file), 256);
            int identification = indexOf(head, new byte[] {1, 'v', 'o', 'r', 'b', 'i', 's'});
            if (identification < 0 || head[identification + 11] != 1) {
                throw failure(helper, "%s is not a mono Vorbis file", file);
            }
            JsonObject entry = manifest.getAsJsonObject(name);
            int ticks = (int) Math.ceil(entry.get("duration_seconds").getAsDouble() * 20);
            if (ticks != recording.lengthTicks()) {
                throw failure(
                        helper,
                        "%s lasts %s ticks by the manifest and Recording plays it for %s",
                        name,
                        ticks,
                        recording.lengthTicks());
            }
        }
        // The song (#861) is two more entries of the same manifest, in a folder of their own and no cylinder's: the
        // wreck's, whole, and the dark's.
        for (String name : List.of("wreck", "dark")) {
            Path file = root.resolve("src/main/resources/assets/deepcharter/sounds/song/" + name + ".ogg");
            byte[] head = Arrays.copyOf(Files.readAllBytes(file), 256);
            int identification = indexOf(head, new byte[] {1, 'v', 'o', 'r', 'b', 'i', 's'});
            if (identification < 0 || head[identification + 11] != 1) {
                throw failure(helper, "%s is not a mono Vorbis file", file);
            }
            if (manifest.getAsJsonObject(name) == null
                    || !"song"
                            .equals(manifest.getAsJsonObject(name).get("folder").getAsString())) {
                throw failure(helper, "the manifest has no entry %s in the folder song", name);
            }
        }
        int wreckTicks = (int) Math.ceil(
                manifest.getAsJsonObject("wreck").get("duration_seconds").getAsDouble() * 20);
        if (wreckTicks != SoundTuning.DEFAULT.wreckSongTicks()) {
            throw failure(
                    helper,
                    "the wreck's song lasts %s ticks by the manifest and SoundTuning plays it for %s",
                    wreckTicks,
                    SoundTuning.DEFAULT.wreckSongTicks());
        }
        Set<String> expected = new TreeSet<>(names);
        expected.addAll(List.of("wreck", "dark"));
        if (!manifest.keySet().equals(expected)) {
            throw failure(helper, "the manifest holds %s and Recording and the song %s", manifest.keySet(), expected);
        }
        helper.succeed();
    }

    /**
     * Each fragment of the ambience (#866) is a mono file of its tier, is in the manifest the audio gate checks, and is short; the manifest
     * holds no fragment the game has no sound event for.
     */
    @GameTest
    public void everyAmbienceFragmentIsMonoShortAndInTheManifest(GameTestHelper helper) throws IOException {
        Path root = repoRoot(helper);
        JsonObject manifest;
        try (Reader reader = Files.newBufferedReader(root.resolve("tools/sounds/ambience.json"))) {
            manifest = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("fragments");
        }
        Set<String> declared = new TreeSet<>();
        for (DeepSound sound : DeepSound.values()) {
            String path = sound.event().location().getPath();
            if (!path.startsWith("ambience.") || path.startsWith("ambience.clock.")) {
                continue;
            }
            String[] parts = path.split("\\.", -1);
            // The horn's own pool (#946) is worn like the Old Workings' fragments: ambience.horn.<name> is worn/<name>;
            // the warm-up is worn/<name>.
            String key = parts.length == 3
                    ? (parts[1].equals("horn") ? "worn" : parts[1]) + "/" + parts[2]
                    : "worn/" + parts[1];
            declared.add(key);
            JsonObject entry = manifest.getAsJsonObject(key);
            if (entry == null) {
                throw failure(helper, "%s has no entry %s in tools/sounds/ambience.json", path, key);
            }
            Path file = root.resolve("src/main/resources/assets/deepcharter/sounds/ambience/" + key + ".ogg");
            byte[] head = Arrays.copyOf(Files.readAllBytes(file), 256);
            int identification = indexOf(head, new byte[] {1, 'v', 'o', 'r', 'b', 'i', 's'});
            if (identification < 0 || head[identification + 11] != 1) {
                throw failure(helper, "%s is not a mono Vorbis file", file);
            }
            double seconds = entry.get("duration_seconds").getAsDouble();
            if (seconds > 4.0) {
                throw failure(helper, "%s lasts %s seconds: a fragment is a syllable, not a song", key, seconds);
            }
        }
        if (!manifest.keySet().equals(declared)) {
            throw failure(helper, "the manifest holds %s and DeepSound %s", manifest.keySet(), declared);
        }
        helper.succeed();
    }

    /**
     * The punch clock's words (#938) are cuts of Lambert's talking clock made by tools/sounds/lambert_clock.py: one event per hour but ten
     * (the recording skips it) and the deep fragments' pool, each file mono and in that script's manifest, which credits the source.
     */
    @GameTest
    public void theClockWordsAreMonoCutsOfTheRecordingInItsManifest(GameTestHelper helper) throws IOException {
        Path root = repoRoot(helper);
        JsonObject manifest;
        try (Reader reader = Files.newBufferedReader(root.resolve("tools/sounds/lambert_clock.json"))) {
            manifest = JsonParser.parseReader(reader).getAsJsonObject();
        }
        JsonObject files = manifest.getAsJsonObject("files");
        JsonObject slots = soundsJson(helper);
        Set<String> ids = new TreeSet<>();
        for (int hour = 1; hour <= 12; hour++) {
            if (hour != 10) {
                ids.add("ambience.clock.hour_" + hour);
            }
        }
        ids.add("ambience.clock.fragment");
        Set<String> declared = new TreeSet<>();
        Arrays.stream(DeepSound.values())
                .map(sound -> sound.event().location().getPath())
                .filter(path -> path.startsWith("ambience.clock."))
                .forEach(declared::add);
        if (!declared.equals(ids)) {
            throw failure(helper, "DeepSound has the clock events %s, expected %s", declared, ids);
        }
        Set<String> mapped = mappedIds(helper);
        Set<String> used = new TreeSet<>();
        for (String id : ids) {
            if (mapped.contains(id)) {
                throw failure(helper, "%s is in the pack map, which needs an original file", id);
            }
            for (JsonElement entry : slots.getAsJsonObject(id).getAsJsonArray("sounds")) {
                String name = entry.getAsJsonObject().get("name").getAsString().substring("deepcharter:".length());
                if (!files.has(name)) {
                    throw failure(helper, "%s plays %s, which tools/sounds/lambert_clock.json does not hold", id, name);
                }
                used.add(name);
                Path file = root.resolve("src/main/resources/assets/deepcharter/sounds/" + name + ".ogg");
                byte[] head = Arrays.copyOf(Files.readAllBytes(file), 256);
                int identification = indexOf(head, new byte[] {1, 'v', 'o', 'r', 'b', 'i', 's'});
                if (identification < 0 || head[identification + 11] != 1) {
                    throw failure(helper, "%s is not a mono Vorbis file", file);
                }
            }
        }
        if (!used.equals(files.keySet())) {
            throw failure(helper, "the manifest holds %s and the events play %s", files.keySet(), used);
        }
        helper.succeed();
    }

    private static int indexOf(byte[] bytes, byte[] needle) {
        for (int i = 0; i + needle.length <= bytes.length; i++) {
            if (Arrays.equals(Arrays.copyOfRange(bytes, i, i + needle.length), needle)) {
                return i;
            }
        }
        return -1;
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
}
