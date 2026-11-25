package io.github.pkeppeler.deepcharter.client.creature;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.github.pkeppeler.deepcharter.DeepCharter;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * The ids of the drifter's look, a skin a resource pack replaces file by file ({@code docs/design/skins.md}): the model and the animations
 * under {@code geckolib/}, the texture and the glowmask under {@code textures/}. The files are written by {@code tools/drifter.py}.
 */
public final class DrifterAssets {
    /** The id GeckoLib knows the model ({@code geckolib/models/<RESOURCE>.geo.json}) and the animations by. */
    public static final String RESOURCE = "creature/drifter";
    /** {@code textures/<TEXTURE>.png}: the bladder's skin, a pale translucent green. */
    public static final String TEXTURE = "entity/drifter";
    /** {@code textures/<GLOWMASK>.png}: clear except the core and the veins, the deep's cold light, drawn at full bright. */
    public static final String GLOWMASK = "entity/drifter_glowmask";

    public static final String IDLE = "animation.drifter.idle";
    /** Drawn to a noise: the pulse quickens. */
    public static final String DRAWN = "animation.drifter.drawn";

    private DrifterAssets() {}

    public static Identifier resource() {
        return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, RESOURCE);
    }

    public static Identifier modelFile() {
        return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "geckolib/models/" + RESOURCE + ".geo.json");
    }

    public static Identifier animationFile() {
        return Identifier.fromNamespaceAndPath(
                DeepCharter.MOD_ID, "geckolib/animations/" + RESOURCE + ".animation.json");
    }

    public static Identifier texture() {
        return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "textures/" + TEXTURE + ".png");
    }

    public static Identifier glowmask() {
        return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "textures/" + GLOWMASK + ".png");
    }

    /** Throws, naming the file, when a file or animation is missing: that would otherwise draw a missing-model cube, magenta, or a frozen bind pose. */
    public static void check(ResourceManager resources) {
        for (Identifier file : new Identifier[] {modelFile(), texture(), glowmask()}) {
            if (resources.getResource(file).isEmpty()) {
                throw new IllegalStateException("The drifter has no file " + file);
            }
        }
        Resource animations = resources
                .getResource(animationFile())
                .orElseThrow(() -> new IllegalStateException("The drifter has no file " + animationFile()));
        JsonObject held;
        try (Reader reader = animations.openAsReader()) {
            held = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("animations");
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + animationFile(), e);
        } catch (JsonParseException | IllegalStateException | ClassCastException e) {
            throw new IllegalStateException(animationFile() + " is not an animation file: " + e.getMessage(), e);
        }
        for (String name : new String[] {IDLE, DRAWN}) {
            if (held == null || !held.has(name)) {
                throw new IllegalStateException("The drifter has no animation " + name + " in " + animationFile());
            }
        }
    }
}
