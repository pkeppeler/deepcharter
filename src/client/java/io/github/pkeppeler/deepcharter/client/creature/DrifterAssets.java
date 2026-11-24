package io.github.pkeppeler.deepcharter.client.creature;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.creature.DrifterVariant;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * The ids of the drifter's look, one set for each {@link DrifterVariant}, a skin a resource pack replaces file by file
 * ({@code docs/design/skins.md}): the model and the animations under {@code geckolib/}, the texture and the glowmask under {@code textures/}.
 * The files are written by {@code tools/drifter.py}.
 */
public final class DrifterAssets {
    private DrifterAssets() {}

    /** The id GeckoLib knows the model ({@code geckolib/models/<resource>.geo.json}) and the animations of a form by. */
    public static Identifier resource(DrifterVariant variant) {
        return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "creature/drifter_" + variant.id());
    }

    public static Identifier modelFile(DrifterVariant variant) {
        return Identifier.fromNamespaceAndPath(
                DeepCharter.MOD_ID, "geckolib/models/" + resource(variant).getPath() + ".geo.json");
    }

    public static Identifier animationFile(DrifterVariant variant) {
        return Identifier.fromNamespaceAndPath(
                DeepCharter.MOD_ID, "geckolib/animations/" + resource(variant).getPath() + ".animation.json");
    }

    /** {@code textures/entity/drifter_<id>.png}: the skin, translucent where the form is. */
    public static Identifier texture(DrifterVariant variant) {
        return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "textures/entity/drifter_" + variant.id() + ".png");
    }

    /** {@code textures/entity/drifter_<id>_glowmask.png}: clear except the form's light, the deep's cold light, drawn at full bright. */
    public static Identifier glowmask(DrifterVariant variant) {
        return Identifier.fromNamespaceAndPath(
                DeepCharter.MOD_ID, "textures/entity/drifter_" + variant.id() + "_glowmask.png");
    }

    /** Drifting. */
    public static String idle(DrifterVariant variant) {
        return "animation.drifter_" + variant.id() + ".idle";
    }

    /** Drawn to a noise: the motion quickens. */
    public static String drawn(DrifterVariant variant) {
        return "animation.drifter_" + variant.id() + ".drawn";
    }

    /** Throws, naming the file, when a file or animation is missing: that would otherwise draw a missing-model cube, magenta, or a frozen bind pose. */
    public static void check(ResourceManager resources) {
        for (DrifterVariant variant : DrifterVariant.values()) {
            check(resources, variant);
        }
    }

    private static void check(ResourceManager resources, DrifterVariant variant) {
        Identifier animationFile = animationFile(variant);
        for (Identifier file : new Identifier[] {modelFile(variant), texture(variant), glowmask(variant)}) {
            if (resources.getResource(file).isEmpty()) {
                throw new IllegalStateException("The " + variant.id() + " drifter has no file " + file);
            }
        }
        Resource animations = resources
                .getResource(animationFile)
                .orElseThrow(() ->
                        new IllegalStateException("The " + variant.id() + " drifter has no file " + animationFile));
        JsonObject held;
        try (Reader reader = animations.openAsReader()) {
            held = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("animations");
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + animationFile, e);
        } catch (JsonParseException | IllegalStateException | ClassCastException e) {
            throw new IllegalStateException(animationFile + " is not an animation file: " + e.getMessage(), e);
        }
        for (String name : new String[] {idle(variant), drawn(variant)}) {
            if (held == null || !held.has(name)) {
                throw new IllegalStateException(
                        "The " + variant.id() + " drifter has no animation " + name + " in " + animationFile);
            }
        }
    }
}
