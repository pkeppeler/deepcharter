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
 * The ids of the Tally's look, a skin a resource pack replaces file by file ({@code docs/design/skins.md}): the model and the animations under
 * {@code geckolib/}, the texture and the glowmask of its amber eye under {@code textures/}. The files are written by {@code tools/tally.py}.
 */
public final class TallyAssets {
    /** The id GeckoLib knows the model ({@code geckolib/models/<RESOURCE>.geo.json}) and the animations by. */
    public static final String RESOURCE = "creature/tally";
    /** {@code textures/<TEXTURE>.png}. */
    public static final String TEXTURE = "entity/tally";
    /** {@code textures/<GLOWMASK>.png}: clear except on the eye, a dull amber, drawn at full bright. */
    public static final String GLOWMASK = "entity/tally_glowmask";

    public static final String IDLE = "animation.tally.idle";
    public static final String FLY = "animation.tally.fly";
    /** Gripping a hatch or a pack. */
    public static final String CLAMP = "animation.tally.clamp";
    /** Match CULL_REACH_PX and CULL_HEIGHT_PX in {@code tools/tally.py}, which fails a model that leaves the box. */
    public static final double CULL_REACH_BLOCKS = 1.0;

    public static final double CULL_HEIGHT_BLOCKS = 1.0;

    private TallyAssets() {}

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

    /** Throws, naming the file, when a file or animation is missing: that would otherwise draw a missing-model cube or a frozen bind pose. */
    public static void check(ResourceManager resources) {
        for (Identifier file : new Identifier[] {modelFile(), texture(), glowmask()}) {
            if (resources.getResource(file).isEmpty()) {
                throw new IllegalStateException("The Tally has no file " + file);
            }
        }
        Resource animations = resources
                .getResource(animationFile())
                .orElseThrow(() -> new IllegalStateException("The Tally has no file " + animationFile()));
        JsonObject held;
        try (Reader reader = animations.openAsReader()) {
            held = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("animations");
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + animationFile(), e);
        } catch (JsonParseException | IllegalStateException | ClassCastException e) {
            throw new IllegalStateException(animationFile() + " is not an animation file: " + e.getMessage(), e);
        }
        for (String name : new String[] {IDLE, FLY, CLAMP}) {
            if (held == null || !held.has(name)) {
                throw new IllegalStateException("The Tally has no animation " + name + " in " + animationFile());
            }
        }
    }
}
