package io.github.pkeppeler.deepcharter.test.support;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ColumnPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The three wreck pieces of the salvage prefab concept round (#1214, docs/design/salvage-prefab-concepts.md) and the
 * layout file {@code tools/colony/wrecks.py} writes with them: where each piece's ground is, what stands beside it for
 * scale and where the cameras look. Both are test resources: nothing of the round reaches a player's world.
 */
public final class SalvagePrefabConcepts {
    private static final Identifier LAYOUT =
            Identifier.fromNamespaceAndPath("deepcharter-test", "salvage_prefab_concepts/layout.json");

    private SalvagePrefabConcepts() {}

    /**
     * One wreck. Its piece's Y 0 is the ground's top block: {@link #offset()} is where the piece's own origin lies from
     * that block. The {@code bounds} (piece X and Z) hold the wreck, and the {@code level} box round them is the ground
     * it reshapes: flat under the wreck, blended into the plain over the margin. {@code focus} is the point the cameras
     * look at, {@code sideX} and {@code sideZ} the way from it to the camera and {@code reach} how far along that way
     * the pod and the player stand, at the piece's {@code pod} and {@code player} columns.
     */
    public record Option(
            String name,
            Identifier structure,
            BlockPos offset,
            int blocks,
            int displays,
            ColumnPos boundsFrom,
            ColumnPos boundsTo,
            ColumnPos levelFrom,
            ColumnPos levelTo,
            Vec3 focus,
            double sideX,
            double sideZ,
            double reach,
            ColumnPos pod,
            ColumnPos player) {}

    public static List<Option> read(MinecraftServer server) {
        try (Reader reader =
                server.getResourceManager().getResourceOrThrow(LAYOUT).openAsReader()) {
            List<Option> options = new ArrayList<>();
            for (JsonElement element :
                    JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("options")) {
                options.add(option(element.getAsJsonObject()));
            }
            return options;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Option option(JsonObject json) {
        JsonArray offset = json.getAsJsonArray("offset");
        JsonArray bounds = json.getAsJsonArray("bounds");
        JsonArray level = json.getAsJsonArray("level");
        JsonArray focus = json.getAsJsonArray("focus");
        JsonArray side = json.getAsJsonArray("side");
        JsonArray pod = json.getAsJsonArray("pod");
        JsonArray player = json.getAsJsonArray("player");
        return new Option(
                json.get("name").getAsString(),
                Identifier.parse(json.get("structure").getAsString()),
                new BlockPos(
                        offset.get(0).getAsInt(),
                        offset.get(1).getAsInt(),
                        offset.get(2).getAsInt()),
                json.get("blocks").getAsInt(),
                json.get("displays").getAsInt(),
                new ColumnPos(bounds.get(0).getAsInt(), bounds.get(1).getAsInt()),
                new ColumnPos(bounds.get(2).getAsInt(), bounds.get(3).getAsInt()),
                new ColumnPos(level.get(0).getAsInt(), level.get(1).getAsInt()),
                new ColumnPos(level.get(2).getAsInt(), level.get(3).getAsInt()),
                new Vec3(
                        focus.get(0).getAsDouble(),
                        focus.get(1).getAsDouble(),
                        focus.get(2).getAsDouble()),
                side.get(0).getAsDouble(),
                side.get(1).getAsDouble(),
                json.get("reach").getAsDouble(),
                new ColumnPos(pod.get(0).getAsInt(), pod.get(1).getAsInt()),
                new ColumnPos(player.get(0).getAsInt(), player.get(1).getAsInt()));
    }

    /** The structure file of a piece, as the game reads it from the resource packs. */
    public static CompoundTag file(MinecraftServer server, Option option) {
        Identifier file = Identifier.fromNamespaceAndPath(
                option.structure().getNamespace(),
                "structure/" + option.structure().getPath() + ".nbt");
        try (InputStream stream =
                server.getResourceManager().getResourceOrThrow(file).open()) {
            return NbtIo.readCompressed(stream, NbtAccounter.unlimitedHeap());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The palette of a piece's file, each state parsed as the game parses it. A block the game does not know loads as
     * air without a word, and a property it does not know fails the parse, so this fails on either: every state must
     * come back as the block the file names.
     */
    public static List<BlockState> palette(CompoundTag root, String label) {
        List<BlockState> palette = new ArrayList<>();
        for (Tag tag : root.getListOrEmpty("palette")) {
            BlockState state = BlockState.CODEC
                    .parse(NbtOps.INSTANCE, tag)
                    .getOrThrow(message -> new IllegalStateException(label + " palette: " + message));
            String named = ((CompoundTag) tag).getStringOr("id", "");
            String loaded = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            if (!named.equals(loaded)) {
                throw new IllegalStateException(label + " palette names " + named + ", which loads as " + loaded);
            }
            palette.add(state);
        }
        return palette;
    }
}
