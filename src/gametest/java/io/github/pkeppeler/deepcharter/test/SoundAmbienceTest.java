package io.github.pkeppeler.deepcharter.test;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.attribute.AmbientAdditionsSettings;
import net.minecraft.world.attribute.AmbientSounds;
import net.minecraft.world.attribute.BackgroundMusic;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;

/**
 * Server GameTests for #249: every biome of the surface and of each layer carries its own drone bed in its {@code ambient_sounds}, with
 * the creaks, groans and knocks of that place, and none of them plays vanilla music.
 */
public class SoundAmbienceTest {
    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path);
    }

    private static Identifier name(Holder<SoundEvent> event) {
        return event.value().location();
    }

    /** The biomes of a layer, from the layer's own biome source. */
    private static Set<Identifier> layerBiomes(MinecraftServer server, int layer) {
        BiomeSource source = server.getLevel(LayerChain.dimension(layer))
                .getChunkSource()
                .getGenerator()
                .getBiomeSource();
        Set<Identifier> ids = new TreeSet<>();
        source.possibleBiomes()
                .forEach(biome -> ids.add(biome.unwrapKey().orElseThrow().identifier()));
        return ids;
    }

    /**
     * The biomes of the surface, read from the mod's own {@code data/minecraft/dimension/overworld.json}: the GameTest world replaces the
     * overworld's generator with a flat one, so the level in the test has none of them.
     */
    private static Set<Identifier> surfaceBiomes(GameTestHelper helper) {
        var stream = SoundAmbienceTest.class.getResourceAsStream("/data/minecraft/dimension/overworld.json");
        expect(helper, stream != null, "the mod ships no data/minecraft/dimension/overworld.json");
        Set<Identifier> ids = new TreeSet<>();
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            JsonObject source = JsonParser.parseReader(reader)
                    .getAsJsonObject()
                    .getAsJsonObject("generator")
                    .getAsJsonObject("biome_source");
            for (JsonElement entry : source.getAsJsonArray("biomes")) {
                ids.add(Identifier.parse(entry.getAsJsonObject().get("biome").getAsString()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return ids;
    }

    @GameTest
    public void everyBiomeOfTheSurfaceAndEachLayerHasItsOwnDroneBed(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        int layers = LayerChain.count(server.registryAccess());
        expect(helper, layers == 2, "the chain has %s layers; a drone bed is written for 2", layers);
        check(helper, server, surfaceBiomes(helper), "surface", List.of("groan"));
        for (int layer = 1; layer <= layers; layer++) {
            check(helper, server, layerBiomes(server, layer), "layer_" + layer, List.of("creak", "knock"));
        }
        helper.succeed();
    }

    private static void check(
            GameTestHelper helper,
            MinecraftServer server,
            Set<Identifier> biomes,
            String place,
            List<String> additions) {
        expect(helper, !biomes.isEmpty(), "%s has no biomes", place);
        var registry = server.registryAccess().lookupOrThrow(Registries.BIOME);
        for (Identifier biomeId : biomes) {
            Biome biome = registry.getValue(biomeId);
            expect(helper, biome != null, "the biome %s does not load", biomeId);
            AmbientSounds sounds =
                    biome.getAttributes().applyModifier(EnvironmentAttributes.AMBIENT_SOUNDS, AmbientSounds.EMPTY);
            expect(
                    helper,
                    sounds.loop()
                            .map(SoundAmbienceTest::name)
                            .filter(id("ambient." + place + "_loop")::equals)
                            .isPresent(),
                    "the biome %s has loop %s, wanted deepcharter:ambient.%s_loop",
                    biomeId,
                    sounds.loop().map(SoundAmbienceTest::name),
                    place);
            List<Identifier> heard = new ArrayList<>();
            for (AmbientAdditionsSettings addition : sounds.additions()) {
                heard.add(name(addition.soundEvent()));
                expect(
                        helper,
                        addition.tickChance() > 0 && addition.tickChance() <= 0.005,
                        "%s plays %s too often or never: %s",
                        biomeId,
                        name(addition.soundEvent()),
                        addition.tickChance());
            }
            for (String kind : additions) {
                expect(
                        helper,
                        heard.contains(id("ambient." + place + "_" + kind)),
                        "the biome %s has no %s, it has %s",
                        biomeId,
                        kind,
                        heard);
            }
            expect(
                    helper,
                    sounds.mood().isEmpty(),
                    "the biome %s keeps a mood sound, which is the vanilla cave noise by default",
                    biomeId);
        }
    }

    @GameTest
    public void noSurfaceBiomePlaysAnyMusic(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        var registry = server.registryAccess().lookupOrThrow(Registries.BIOME);
        for (Identifier biomeId : surfaceBiomes(helper)) {
            Biome biome = registry.getValue(biomeId);
            expect(
                    helper,
                    biome.getAttributes().contains(EnvironmentAttributes.BACKGROUND_MUSIC),
                    "the biome %s leaves the overworld's vanilla music on",
                    biomeId);
            BackgroundMusic music = biome.getAttributes()
                    .applyModifier(EnvironmentAttributes.BACKGROUND_MUSIC, BackgroundMusic.OVERWORLD);
            expect(helper, music.equals(BackgroundMusic.EMPTY), "the biome %s plays music: %s", biomeId, music);
        }
        helper.succeed();
    }

    @GameTest
    public void noLayerBiomeOverridesItsLayersMusicWithAVanillaTrack(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        var registry = server.registryAccess().lookupOrThrow(Registries.BIOME);
        for (int layer = 1; layer <= LayerChain.count(server.registryAccess()); layer++) {
            for (Identifier biomeId : layerBiomes(server, layer)) {
                BackgroundMusic music = registry.getValue(biomeId)
                        .getAttributes()
                        .applyModifier(EnvironmentAttributes.BACKGROUND_MUSIC, BackgroundMusic.EMPTY);
                music.defaultMusic()
                        .ifPresent(track -> expect(
                                helper,
                                name(track.sound()).getNamespace().equals(DeepCharter.MOD_ID),
                                "the biome %s plays %s",
                                biomeId,
                                name(track.sound())));
            }
        }
        helper.succeed();
    }

    @FormatMethod
    private static void expect(GameTestHelper helper, boolean condition, @FormatString String format, Object... args) {
        if (!condition) {
            throw helper.assertionException(Component.literal(String.format(format, args)));
        }
    }
}
